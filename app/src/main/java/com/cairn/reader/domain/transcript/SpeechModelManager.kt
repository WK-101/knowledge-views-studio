package com.cairn.reader.domain.transcript

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the open-source Vosk speech models the on-device engine needs: lists the offered models,
 * reports what's installed and how much space it uses, downloads + unzips a model with progress, and
 * deletes it. Models are open (CC-BY / Apache) and stored under the app's private files dir; once a
 * model is present, transcription never touches the network. A `.ok` marker is written only after a
 * clean extract so a cancelled download never looks installed.
 */
@Singleton
class SpeechModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    appClient: OkHttpClient,
) {
    // The shared client caps every call at 45s; a ~40 MB model over cellular blows past that, so the
    // download uses a variant with no overall call timeout (a generous read timeout still guards a
    // truly stalled connection). Without this a slow download is aborted and the model never installs.
    private val client: OkHttpClient = appClient.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    data class Model(val id: String, val label: String, val url: String, val approxMb: Int, val lang: String)

    /** A curated set of Vosk "small" models — light enough to download on a phone (~40 MB each). */
    val catalog: List<Model> = listOf(
        Model("en-us", "English (US)", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40, "en"),
        Model("en-in", "English (India)", "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip", 36, "en"),
        Model("es", "Spanish", "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 39, "es"),
        Model("fr", "French", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 41, "fr"),
        Model("de", "German", "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 45, "de"),
        Model("hi", "Hindi", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 42, "hi"),
    )

    private fun root(): File = File(context.filesDir, "models/vosk").apply { mkdirs() }
    fun dir(id: String): File = File(root(), id)
    private fun marker(id: String): File = File(dir(id), ".ok")

    fun isInstalled(id: String): Boolean = marker(id).exists()
    fun anyInstalled(): Boolean = catalog.any { isInstalled(it.id) }
    fun installedBytes(): Long = root().walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** The Vosk model directory (the folder that actually holds am/, conf/, graph/…) for [id], or
     *  null if not installed. Vosk zips nest everything under one top folder, which we point at. */
    fun modelPath(id: String): String? {
        if (!isInstalled(id)) return null
        val d = dir(id)
        val nested = d.listFiles()?.firstOrNull { it.isDirectory && File(it, "conf").exists() }
        return (nested ?: d).absolutePath
    }

    /** The first installed model's path, preferring one whose language matches [preferLang]. */
    fun firstInstalledPath(preferLang: String? = null): String? {
        val ordered = if (preferLang != null) catalog.sortedByDescending { it.lang == preferLang } else catalog
        return ordered.firstOrNull { isInstalled(it.id) }?.let { modelPath(it.id) }
    }

    fun delete(id: String): Boolean = dir(id).deleteRecursively()

    /** Download the model zip and extract it into [dir]. [onProgress] covers the download (0f..0.9f)
     *  then extraction (…1f). Returns true on success. */
    suspend fun download(id: String, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val model = catalog.firstOrNull { it.id == id } ?: return@withContext false
        val target = dir(id)
        target.deleteRecursively(); target.mkdirs()
        val zip = File(target, "model.zip")
        try {
            val req = Request.Builder().url(model.url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                val total = body.contentLength().takeIf { it > 0 } ?: (model.approxMb * 1_000_000L)
                body.byteStream().use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var read: Int; var done = 0L
                        while (input.read(buf).also { read = it } >= 0) {
                            out.write(buf, 0, read); done += read
                            onProgress((done.toFloat() / total * 0.9f).coerceIn(0f, 0.9f))
                        }
                    }
                }
            }
            unzip(zip, target)
            zip.delete()
            // Sanity-check the extract really contains a Vosk model before marking it ready.
            val ok = target.walkTopDown().any { it.isDirectory && it.name == "conf" }
            if (!ok) { target.deleteRecursively(); return@withContext false }
            marker(id).writeText("ok")
            onProgress(1f)
            true
        } catch (_: Exception) {
            target.deleteRecursively()
            false
        }
    }

    private fun unzip(zip: File, into: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val outFile = File(into, entry.name)
                // Guard against zip-slip: keep every entry inside the target dir.
                if (!outFile.canonicalPath.startsWith(into.canonicalPath + File.separator)) { entry = zin.nextEntry; continue }
                if (entry.isDirectory) outFile.mkdirs()
                else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
}
