package com.cairn.reader.domain.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.cairn.reader.data.net.HttpFetcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an image or media file and either saves it to the device's shared collections
 * (Photos / Downloads via MediaStore, no permission needed on Android 10+) or stages it for the
 * system share sheet through the app's FileProvider. All local — the file is fetched once and
 * written straight to the chosen destination.
 */
@Singleton
class MediaSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fetcher: HttpFetcher,
) {
    /** On Android 10+ we can write to the shared Photos/Downloads without any storage permission. */
    val canSaveDirectly: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Save an image into the shared Pictures/Cairn collection. Android 10+ only. */
    suspend fun saveImageToGallery(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!canSaveDirectly) return@withContext Result.failure(UnsupportedOperationException("Use Share on this Android version"))
        val (bytes, contentType) = fetcher.fetchBytes(url, maxBytes = 20L * 1024 * 1024)
            ?: return@withContext Result.failure(IOException("Couldn't download image"))
        val ext = extensionFor(contentType, url, "jpg")
        val mime = contentType?.substringBefore(';')?.takeIf { it.startsWith("image/") } ?: "image/$ext"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName("cairn", ext))
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cairn")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext Result.failure(IOException("Couldn't create gallery entry"))
        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("No output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext Result.failure(it)
        }
        Result.success(Unit)
    }

    /** Save an arbitrary media file (audio/video) into the shared Downloads/Cairn. Android 10+ only. */
    suspend fun saveMediaToDownloads(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!canSaveDirectly) return@withContext Result.failure(UnsupportedOperationException("Use Share on this Android version"))
        val (bytes, contentType) = fetcher.fetchBytes(url, maxBytes = 200L * 1024 * 1024)
            ?: return@withContext Result.failure(IOException("Couldn't download media"))
        val ext = extensionFor(contentType, url, "bin")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName("cairn", ext))
            put(MediaStore.Downloads.MIME_TYPE, contentType?.substringBefore(';') ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Cairn")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext Result.failure(IOException("Couldn't create download entry"))
        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("No output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext Result.failure(it)
        }
        Result.success(Unit)
    }

    /** Download to a private cache file and return a shareable content:// URI for the share sheet. */
    suspend fun stageForShare(url: String): Result<Pair<Uri, String>> = withContext(Dispatchers.IO) {
        val (bytes, contentType) = fetcher.fetchBytes(url, maxBytes = 200L * 1024 * 1024)
            ?: return@withContext Result.failure(IOException("Couldn't download this file"))
        val ext = extensionFor(contentType, url, "bin")
        val dir = File(context.cacheDir, "media").apply { mkdirs() }
        val file = File(dir, fileName("cairn", ext))
        runCatching { file.writeBytes(bytes) }.getOrElse { return@withContext Result.failure(it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        Result.success(uri to (contentType?.substringBefore(';') ?: "application/octet-stream"))
    }

    private fun fileName(prefix: String, ext: String) = "$prefix-${System.currentTimeMillis()}.$ext"

    private fun extensionFor(contentType: String?, url: String, fallback: String): String {
        contentType?.substringAfter('/')?.substringBefore(';')?.trim()?.let {
            when (it) {
                "jpeg" -> return "jpg"
                "svg+xml" -> return "svg"
                "mpeg" -> return "mp3"
                "quicktime" -> return "mov"
                else -> if (it.isNotBlank() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() }) return it
            }
        }
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (fromUrl.isNotBlank() && fromUrl.length <= 5) fromUrl else fallback
    }
}
