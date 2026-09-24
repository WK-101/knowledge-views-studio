package app.parley.data.people

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.parley.common.PhoneNumbers
import app.parley.data.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Per-contact call-screen backgrounds. The picked image is copied (downscaled) into Parley's private storage,
 * keyed by the contact's lookup key, so it keeps working when the original is deleted and when contact ids change
 * (unlike a stored content URI or contact id). Included in the encrypted backup through [PeopleBackupExtras].
 */
class CallBackgrounds(context: Context, private val contacts: ContactsRepository) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "call_backgrounds")

    /** Bumped on every change so screens showing a background refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun fileFor(lookupKey: String): File = File(dir, sha256(lookupKey) + ".jpg")

    /** The background of a contact, as a file URI string, or null. */
    fun forLookupKey(lookupKey: String?): String? =
        lookupKey?.takeIf { it.isNotEmpty() }?.let(::fileFor)?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }

    /**
     * Hook for the in-call screen: the background for a caller's number, or null. Blocking (a contacts lookup);
     * call it off the main thread, e.g. inside `TelecomDependencies.callerInfo`.
     */
    fun callBackgroundFor(number: String): String? {
        if (PhoneNumbers.digits(number).length < 3 || !dir.isDirectory) return null
        val info = contacts.lookup(number) ?: return null
        return forLookupKey(info.lookupKey)
    }

    /** Copies [source] into app storage for [lookupKey]; returns false if it isn't a readable image. */
    suspend fun set(lookupKey: String, source: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching { encode(source) }.getOrNull() ?: return@withContext false
        write(lookupKey, bytes)
        true
    }

    suspend fun clear(lookupKey: String) = withContext(Dispatchers.IO) {
        fileFor(lookupKey).delete()
        _version.value++
    }

    internal fun write(lookupKey: String, jpeg: ByteArray) {
        dir.mkdirs()
        val target = fileFor(lookupKey)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(jpeg)
        tmp.renameTo(target)
        remember(lookupKey)
        _version.value++
    }

    // ---- Keys (F8). File names are hashes, so an index remembers which lookup key each background belongs to;
    // when a contact's key changes (link, unlink, first sync, move) the background can follow it.

    private val indexFile = File(dir, "index.txt")

    /** Lookup keys that have a background, as far as the index knows. */
    @Synchronized
    fun indexedKeys(): Set<String> = runCatching { indexFile.readLines().filter { it.isNotBlank() && fileFor(it).isFile }.toSet() }.getOrDefault(emptySet())

    @Synchronized
    private fun writeIndex(keys: Set<String>) {
        dir.mkdirs()
        val tmp = File(dir, "index.txt.tmp")
        tmp.writeText(keys.sorted().joinToString("\n"))
        tmp.renameTo(indexFile)
    }

    /** Records that [lookupKey] has a background (also used to index backgrounds made before the index existed). */
    @Synchronized
    fun remember(lookupKey: String) {
        if (!fileFor(lookupKey).isFile) return
        val keys = indexedKeys()
        if (lookupKey !in keys) writeIndex(keys + lookupKey)
    }

    /** Moves the background of [from] to [to]; an existing background of [to] wins. */
    @Synchronized
    fun move(from: String, to: String) {
        if (from == to) return
        val src = fileFor(from)
        if (!src.isFile) return
        val dst = fileFor(to)
        if (dst.isFile) src.delete() else src.renameTo(dst)
        writeIndex(indexedKeys() - from + to)
        _version.value++
    }

    internal fun read(lookupKey: String): ByteArray? = fileFor(lookupKey).takeIf { it.isFile }?.readBytes()

    /** Every stored background by hashed key (the key itself isn't recoverable from the file name). */
    internal fun storedHashes(): Set<String> = dir.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }.map { it.name.removeSuffix(".jpg") }.toSet()

    internal fun hashOf(lookupKey: String) = sha256(lookupKey)

    /** Decodes, downsamples to at most [MAX_SIDE] px and re-encodes as JPEG (drops EXIF, including location). */
    private fun encode(source: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = app.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_SIDE = 1280
    }
}
