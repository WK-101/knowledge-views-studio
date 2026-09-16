package com.cairn.reader.domain.transcript

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the on-device speech model files (Whisper GGML weights) the [SpeechToTextEngine] needs:
 * lists the offered models, reports what's downloaded and how much space it uses, downloads a model
 * with progress, and deletes it. The weights are open-source (whisper.cpp GGML) and stored under the
 * app's private files dir; nothing about the audio ever leaves the device once a model is present.
 */
@Singleton
class WhisperModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    data class Model(val id: String, val label: String, val url: String, val approxMb: Int)

    /** The open whisper.cpp GGML weights, smallest first. Quantized (q5_1) to keep the download lean. */
    val catalog: List<Model> = listOf(
        Model("tiny.en", "Tiny · English", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin", 32),
        Model("base.en", "Base · English", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin", 60),
        Model("small", "Small · multilingual", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190),
    )

    private fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }
    fun file(id: String): File = File(modelsDir(), "whisper-$id.bin")

    fun isInstalled(id: String): Boolean = file(id).let { it.exists() && it.length() > 0 }
    fun anyInstalled(): Boolean = catalog.any { isInstalled(it.id) }
    fun installedBytes(): Long = modelsDir().listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(id: String): Boolean = file(id).let { if (it.exists()) it.delete() else false }

    /** Stream a model to disk with progress (0f..1f). Writes to a .part file, then renames on success
     *  so a cancelled/failed download never leaves a half file that looks installed. */
    suspend fun download(id: String, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val model = catalog.firstOrNull { it.id == id } ?: return@withContext false
        val target = file(id)
        val part = File(target.parentFile, target.name + ".part")
        try {
            val req = Request.Builder().url(model.url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                val total = body.contentLength().takeIf { it > 0 } ?: (model.approxMb * 1_000_000L)
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } >= 0) {
                            output.write(buf, 0, read)
                            done += read
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (part.length() <= 0) { part.delete(); return@withContext false }
            part.renameTo(target)
        } catch (_: Exception) {
            part.delete()
            false
        }
    }
}
