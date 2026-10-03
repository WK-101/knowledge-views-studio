package app.parley.ui.common

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.FileProvider
import app.parley.common.photo.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A picture Parley shows that can be saved or shared ("Save" and "Share" in every image viewer and QR dialog).
 * [read] gives the bytes to hand out, exactly as Parley keeps them; the format comes from those bytes
 * ([ImageFiles.detect]). [name] is the file's name before its extension ("Ana Lima"). [private]: a private contact's
 * picture, handed out only after the private contacts' unlock and never in discreet mode.
 */
class ExportableImage(
    val name: String,
    val kind: Kind,
    val private: Boolean,
    val read: suspend (Context) -> ByteArray?,
) {
    enum class Kind {
        /** The photo as picked, kept whole by Parley ([app.parley.data.people.OriginalPhotos]). */
        ORIGINAL,

        /** No original is kept (a photo another app set, or from before 4.3): the largest copy Android has. */
        ANDROID_COPY,

        /** A private contact's photo without a kept original: the copy sealed for caller ID, the only one there is. */
        PRIVATE_COPY,

        /** A contact's call-screen picture, as Parley keeps it for the call screen. */
        CALL_PICTURE,

        /** A picture Parley made (a QR code), as a lossless PNG. */
        GENERATED,
    }
}

/**
 * The one helper behind Save and Share for pictures. Save writes through the Storage Access Framework (the system's
 * "Save to" screen), so Parley needs no storage permission; Share hands a file of Parley's cache to the chosen app
 * through its FileProvider with a read grant for that one share.
 *
 * Parley's cache isn't sealed, so a shared copy is short-lived: a private contact's is deleted as soon as the share
 * screen returns and whenever Parley locks ([forgetPrivate]); copies whose time is up go before the next share, every
 * copy at the next start, and by the daily upkeep ([sweep]).
 */
object ImageExport {
    internal const val DIR = "image_share"

    /** A copy for any picture but a private contact's: the receiving app has this long to read it. */
    internal const val STALE_MS = 60 * 60 * 1000L

    /** A private contact's decrypted copy: deleted when the share returns; this is only for a share that never did. */
    internal const val PRIVATE_STALE_MS = 10 * 60 * 1000L

    private const val PRIVATE_PREFIX = "p"
    private const val PLAIN_PREFIX = "s"

    /** Largest picture read for handing out (Parley keeps originals up to 20 MB). */
    private const val MAX_BYTES = 64L shl 20

    private const val TAG = "ImageExport"

    private val FOLDER = Regex("[ps][0-9]+-[0-9]+")

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    private fun authority(context: Context) = context.packageName + ".files"

    /** The file name for [image] in [format] ("Ana Lima.jpg"); [fallback] when the name is empty. */
    fun fileName(image: ExportableImage, format: ImageFiles.Format, fallback: String) = ImageFiles.fileName(image.name, format, fallback)

    /** Writes [bytes] to [target] (a document the user just created through "Save to"). */
    suspend fun save(context: Context, target: Uri, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            // "wt": a document the provider already had (a name chosen again) is replaced, never partly overwritten.
            val out = runCatching { context.contentResolver.openOutputStream(target, "wt") }.getOrNull()
                ?: context.contentResolver.openOutputStream(target, "w")
                ?: return@withContext false
            out.use { it.write(bytes) }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't save the picture", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to the chosen place", e)
            false
        }
    }

    /**
     * Writes [bytes] as [fileName] into a folder of its own in the cache (so the receiving app sees the real name) and
     * returns its content URI. Copies older than their time are cleared first.
     */
    suspend fun shareFile(context: Context, bytes: ByteArray, fileName: String, private: Boolean): Uri = withContext(Dispatchers.IO) {
        FileProvider.getUriForFile(context, authority(context), writeShareCopy(context, bytes, fileName, private))
    }

    /** The file behind [shareFile]: `image_share/<p|s><time>-<n>/<fileName>`. */
    internal fun writeShareCopy(context: Context, bytes: ByteArray, fileName: String, private: Boolean, now: Long = System.currentTimeMillis()): File {
        sweep(context, now)
        val folder = File(dir(context), (if (private) PRIVATE_PREFIX else PLAIN_PREFIX) + now + "-" + System.nanoTime()).apply { mkdirs() }
        return File(folder, fileName).apply { writeBytes(bytes) }
    }

    /** The share screen for [uri], with a read grant for the app the user picks. */
    fun shareIntent(uri: Uri, mime: String, title: String?): Intent {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The ClipData carries the grant through the chooser to the app chosen (and gives the chooser its preview).
        send.clipData = ClipData.newRawUri(null, uri)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Deletes the shared copy behind [uri] and its folder (anything that isn't one is left alone). */
    fun forget(context: Context, uri: Uri?) {
        if (uri == null || uri.authority != authority(context) || uri.pathSegments.firstOrNull() != DIR) return
        val segments = uri.pathSegments
        // Only a folder [writeShareCopy] made ("p1700000000000-123"), never "..".
        if (segments.size != 3 || !FOLDER.matches(segments[1])) return
        runCatching {
            val folder = File(dir(context), segments[1])
            if (folder.parentFile == dir(context)) folder.deleteRecursively()
        }
    }

    /**
     * Clears shared copies whose time is up ([STALE_MS], a private contact's after [PRIVATE_STALE_MS]); [all] clears
     * every one (at start: no share from before is still being read).
     */
    fun sweep(context: Context, now: Long = System.currentTimeMillis(), all: Boolean = false) {
        runCatching {
            dir(context).listFiles()?.forEach { f ->
                val age = now - f.lastModified()
                val limit = if (f.name.startsWith(PRIVATE_PREFIX)) PRIVATE_STALE_MS else STALE_MS
                if (all || age > limit) f.deleteRecursively()
            }
        }
    }

    /** Deletes every private contact's shared copy at once (Parley locked: what was opened for the session goes). */
    fun forgetPrivate(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { if (it.name.startsWith(PRIVATE_PREFIX)) it.deleteRecursively() } }
    }

    // ---- Reading what is handed out

    /** The bytes behind [uri] (a content or file URI), at most [MAX_BYTES]; null when they can't be read. */
    suspend fun readUri(context: Context, uri: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use(::readBounded) }
            .onFailure { Log.w(TAG, "Couldn't read the picture", it) }.getOrNull()
    }

    /**
     * A phone contact's photo as Android keeps it: its display photo (the largest, PHOTO_FILE_ID) when it has one,
     * else [photoUri] (the thumbnail).
     */
    suspend fun readAndroidPhoto(context: Context, contactId: Long?, photoUri: String): ByteArray? = withContext(Dispatchers.IO) {
        val large = contactId?.takeIf { it > 0 }?.let { id ->
            runCatching {
                val contact = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
                ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, contact, true)?.use(::readBounded)
            }.getOrNull()
        }
        large ?: readUri(context, photoUri)
    }

    /** [bitmap] as a lossless PNG (a QR code's every module stays sharp). */
    suspend fun png(bitmap: Bitmap): ByteArray = withContext(Dispatchers.Default) {
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}
