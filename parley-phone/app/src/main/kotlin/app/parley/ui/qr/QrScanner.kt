package app.parley.ui.qr

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.parley.common.photo.PhotoMath
import app.parley.common.qr.QrImageDecoder
import app.parley.data.ContactPhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Q1: a picture shared to Parley ("Scan QR with Parley"), waiting for the scan screen. */
object QrInbox {
    val image = MutableStateFlow<Uri?>(null)
}

/**
 * Q1: reads QR codes from a picture, off the main thread and with bounded memory: the picture is decoded already
 * reduced (see [ContactPhotoProcessor.decodeBounded]) at a few sizes ([PhotoMath.qrScanSizes]) until codes are
 * found. Parley never uses the camera itself: photos come from the camera app, the photo picker or a share.
 */
object QrScanner {
    sealed interface Outcome {
        data class Found(val texts: List<String>) : Outcome
        data object NothingFound : Outcome
        data object Unreadable : Outcome
    }

    suspend fun scan(context: Context, uri: Uri): Outcome = withContext(Dispatchers.Default) {
        val cr = context.contentResolver
        val size = ContactPhotoProcessor.size(cr, uri)
        val sizes = size?.let { (w, h) -> PhotoMath.qrScanSizes(w, h) } ?: listOf(1600, 800)
        var readAny = false
        for (s in sizes) {
            ensureActive()
            val bitmap = ContactPhotoProcessor.decodeBounded(cr, uri, s) ?: continue
            readAny = true
            val found = try {
                val w = bitmap.width
                val h = bitmap.height
                val px = IntArray(w * h)
                bitmap.getPixels(px, 0, w, 0, 0, w, h)
                QrImageDecoder.decode(px, w, h)
            } catch (_: OutOfMemoryError) {
                emptyList()
            } finally {
                bitmap.recycle()
            }
            if (found.isNotEmpty()) return@withContext Outcome.Found(found.map { it.text })
        }
        if (readAny) Outcome.NothingFound else Outcome.Unreadable
    }

    /** The folder for photos the camera app takes for Parley (cache, shared through the FileProvider). */
    private fun dir(context: Context) = File(context.cacheDir, "qr").apply { mkdirs() }

    /** A new, empty file for the camera app to write to, and its content URI. */
    fun newPhoto(context: Context): Pair<File, Uri> {
        val f = File(dir(context), "photo-${System.currentTimeMillis()}.jpg")
        return f to FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    /** Deletes the camera photos (after decoding, and any left from an interrupted scan). */
    fun clearPhotos(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }
}
