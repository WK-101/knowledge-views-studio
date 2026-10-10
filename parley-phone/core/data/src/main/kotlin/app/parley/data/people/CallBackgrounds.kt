package app.parley.data.people

import app.parley.common.Hex
import app.parley.common.storage.DurableFiles
import java.io.ByteArrayOutputStream
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.parley.common.PhoneIdentity
import app.parley.common.calls.CallerPhoto
import app.parley.common.people.ContactRef
import app.parley.data.ContactsRepository
import app.parley.data.security.RecordSealing
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * Per-contact call-screen backgrounds. The picked image is copied (downscaled) into Parley's private storage,
 * keyed by the contact's lookup key, so it keeps working when the original is deleted and when contact ids change
 * (unlike a stored content URI or contact id). Included in the encrypted backup through [PeopleBackupExtras].
 *
 * A private contact's picture is sealed with the caller-ID key, like its photo (`<hash>.sealed`, opened in memory by
 * the vault photo provider for Parley's own screens); a device contact's is a plain JPEG (`<hash>.jpg`), as its photo
 * in the address book is. One written plain before this rule is sealed by [resealPlain].
 */
class CallBackgrounds(context: Context, private val contacts: ContactsRepository) : RecordSealing.Resealable {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "call_backgrounds")

    /** Bumped on every change so screens showing a background refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun fileFor(lookupKey: String): File = File(dir, sha256(lookupKey) + ".jpg")

    private fun sealedFor(lookupKey: String): File = File(dir, sha256(lookupKey) + SEALED)

    /** Whether [lookupKey] has a picture, sealed or plain. */
    private fun has(lookupKey: String) = sealedFor(lookupKey).isFile || fileFor(lookupKey).isFile

    /**
     * The background of a contact, as a file URI string, or null. The file keeps its name when the picture is
     * replaced, so the URI carries the file's time: image caches keyed by URI (the preview, the call screen) then
     * load the new picture instead of showing the old one. Opening a `file:` URI ignores the query.
     */
    fun forLookupKey(lookupKey: String?): String? {
        if (lookupKey.isNullOrEmpty()) return null
        sealedFor(lookupKey).takeIf { it.isFile }?.let { f ->
            return "content://${app.packageName}.vaultphotos/$SEALED_PATH/${sha256(lookupKey)}/${f.lastModified()}"
        }
        return fileFor(lookupKey).takeIf { it.isFile }
            ?.let { Uri.fromFile(it).buildUpon().appendQueryParameter("v", it.lastModified().toString()).build().toString() }
    }

    /** A sealed picture by its hashed key, opened in memory (for the vault photo provider); null when there is none. */
    fun sealedBytes(hash: String): ByteArray? {
        if (!HASH.matches(hash)) return null
        return runCatching { File(dir, hash + SEALED).takeIf { it.isFile }?.readBytes()?.let(VaultCrypto::openCallerId) }.getOrNull()
    }

    /**
     * Hook for the in-call screen: the background for a caller's number, or null. Blocking (a contacts lookup);
     * call it off the main thread, e.g. inside `TelecomDependencies.callerInfo`.
     */
    fun callBackgroundFor(number: String): String? {
        if (PhoneIdentity.digits(number).length < 3 || !dir.isDirectory) return null
        val info = contacts.lookup(number) ?: return null
        return forLookupKey(info.lookupKey)
    }

    /** How [set] went, so the screen can say what to do next instead of doing nothing. */
    enum class SetResult { OK, UNREADABLE, NOT_A_PICTURE, NOT_SAVED }

    /** Copies [source] into app storage for [lookupKey]. */
    suspend fun set(lookupKey: String, source: Uri): SetResult = withContext(Dispatchers.IO) {
        if (lookupKey.isEmpty()) return@withContext SetResult.NOT_SAVED
        val bytes = try {
            encode(source) ?: return@withContext SetResult.NOT_A_PICTURE
        } catch (e: SecurityException) {
            // The picker's permission to read the picture is gone (for example after Parley was stopped meanwhile).
            Log.w(TAG, "No access to the chosen picture", e)
            return@withContext SetResult.UNREADABLE
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't read the chosen picture", e)
            return@withContext SetResult.UNREADABLE
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "The chosen picture is too large", e)
            return@withContext SetResult.NOT_A_PICTURE
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Couldn't decode the chosen picture", e)
            return@withContext SetResult.NOT_A_PICTURE
        }
        try {
            write(lookupKey, bytes)
            SetResult.OK
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't store the call screen picture", e)
            SetResult.NOT_SAVED
        } catch (e: GeneralSecurityException) {
            // A private contact's picture is never stored plain: nothing is saved while the key can't be used.
            Log.w(TAG, "Couldn't seal the call screen picture", e)
            SetResult.NOT_SAVED
        }
    }

    suspend fun clear(lookupKey: String) = withContext(Dispatchers.IO) {
        fileFor(lookupKey).delete()
        sealedFor(lookupKey).delete()
        _version.value++
    }

    /** Stores [jpeg] for [lookupKey]: sealed for a private contact (throws when it can't be sealed), plain otherwise. */
    internal fun write(lookupKey: String, jpeg: ByteArray) {
        if (ContactRef.isPrivateKey(lookupKey)) {
            DurableFiles.writeOrThrow(sealedFor(lookupKey), sealCallerId(jpeg))
            fileFor(lookupKey).delete()
        } else {
            DurableFiles.writeOrThrow(fileFor(lookupKey), jpeg)
            sealedFor(lookupKey).delete()
        }
        remember(lookupKey)
        _version.value++
    }

    @Suppress("TooGenericExceptionCaught") // Whatever the Keystore throws means the same: not sealed now.
    private fun sealCallerId(jpeg: ByteArray): ByteArray = try {
        VaultCrypto.sealCallerId(jpeg)
    } catch (e: GeneralSecurityException) {
        throw e
    } catch (e: Exception) {
        throw GeneralSecurityException("The caller-ID key can't be used now", e)
    }

    /** Private contacts' pictures written plain by older versions, sealed now; false while one still can't be. */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun resealPlain(): Boolean = withContext(Dispatchers.IO) {
        var left = 0
        for (key in indexedKeys().filter(ContactRef::isPrivateKey)) {
            val plain = fileFor(key).takeIf { it.isFile } ?: continue
            try {
                synchronized(this@CallBackgrounds) { if (plain.isFile) write(key, plain.readBytes()) }
            } catch (e: Exception) {
                Log.w(TAG, "A private call screen picture stays unsealed for now: ${e.javaClass.simpleName}")
                left++
            }
        }
        left == 0
    }

    // ---- Keys. File names are hashes, so an index remembers which lookup key each background belongs to;
    // when a contact's key changes (link, unlink, first sync, move) the background can follow it.

    private val indexFile = File(dir, "index.txt")

    /** Lookup keys that have a background, as far as the index knows. */
    @Synchronized
    fun indexedKeys(): Set<String> = runCatching { indexFile.readLines().filter { it.isNotBlank() && has(it) }.toSet() }.getOrDefault(emptySet())

    @Synchronized
    private fun writeIndex(keys: Set<String>) {
        DurableFiles.writeText(indexFile, keys.sorted().joinToString("\n"))
    }

    /** Records that [lookupKey] has a background (also used to index backgrounds made before the index existed). */
    @Synchronized
    fun remember(lookupKey: String) {
        if (!has(lookupKey)) return
        val keys = indexedKeys()
        if (lookupKey !in keys) writeIndex(keys + lookupKey)
    }

    /** Moves the background (and the photo choice) of [from] to [to]; what [to] already has wins. */
    @Synchronized
    fun move(from: String, to: String) {
        if (from == to) return
        movePhotoChoice(from, to)
        if (!has(from)) return
        if (has(to)) {
            fileFor(from).delete()
            sealedFor(from).delete()
        } else if (fileFor(from).isFile && !ContactRef.isPrivateKey(to)) {
            DurableFiles.move(fileFor(from), fileFor(to))
        } else {
            // Into or out of the private side: sealed or opened on the way (left where it is if that can't be done).
            val bytes = read(from) ?: return
            runCatching { write(to, bytes) }.onFailure { Log.w(TAG, "A call screen picture couldn't follow its contact now") }.getOrNull() ?: return
            fileFor(from).delete()
            sealedFor(from).delete()
        }
        writeIndex(indexedKeys() - from + to)
        _version.value++
    }

    // ---- Photo on the call screen: a contact's Show / Hide over Settings › Calls ([app.parley.common.calls.CallerPhoto]).
    // Kept here with the call-screen picture, under the same Parley key (a private contact's too), so it follows the
    // contact through links, key changes and conversions between device and private.

    private val choicesFile = File(dir, "photo_choices.txt")
    private var choices: Map<String, Boolean>? = null

    @Synchronized
    private fun choices(): Map<String, Boolean> =
        choices ?: CallerPhoto.decode(runCatching { choicesFile.takeIf { it.isFile }?.readText() }.getOrNull()).also { choices = it }

    /** The contact's own choice: true (show), false (hide) or null (follow the setting). */
    fun photoChoice(key: String?): Boolean? = key?.takeIf { it.isNotEmpty() }?.let { choices()[it] }

    @Synchronized
    fun setPhotoChoice(key: String, show: Boolean?) {
        if (key.isEmpty()) return
        val now = choices()
        val next = if (show == null) now - key else now + (key to show)
        if (next == now) return
        writeChoices(next)
    }

    /** Keys with a choice, for the key sweep. */
    fun photoChoiceKeys(): Set<String> = choices().keys

    /** The contact is gone: its choice goes too. */
    fun forgetPhotoChoice(key: String) = setPhotoChoice(key, null)

    @Synchronized
    private fun movePhotoChoice(from: String, to: String) {
        val now = choices()
        val v = now[from] ?: return
        writeChoices(if (to in now) now - from else now - from + (to to v))
    }

    @Synchronized
    private fun writeChoices(next: Map<String, Boolean>) {
        DurableFiles.writeText(choicesFile, CallerPhoto.encode(next))
        choices = next
        _version.value++
    }

    internal fun read(lookupKey: String): ByteArray? = sealedFor(lookupKey).takeIf { it.isFile }?.let { sealedBytes(sha256(lookupKey)) }
        ?: fileFor(lookupKey).takeIf { it.isFile }?.readBytes()

    /** Every stored background by hashed key (the key itself isn't recoverable from the file name). */
    internal fun storedHashes(): Set<String> = dir.listFiles().orEmpty()
        .filter { it.name.endsWith(".jpg") || it.name.endsWith(SEALED) }.map { it.name.substringBefore('.') }.toSet()

    internal fun hashOf(lookupKey: String) = sha256(lookupKey)

    /**
     * Decodes, downsamples to at most [MAX_SIDE] px and re-encodes as JPEG (drops EXIF, including location).
     * Throws [SecurityException] or [IOException] when the picture can't be opened; null when it isn't a picture.
     */
    private fun encode(source: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (app.contentResolver.openInputStream(source) ?: throw IOException("No stream for the picture")).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = app.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }

    private fun sha256(s: String): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    companion object {
        const val MAX_SIDE = 1280
        private const val TAG = "CallBackgrounds"
        private const val SEALED = ".sealed"

        /** The vault photo provider's path for sealed call-screen pictures: `bg/<hash>/<version>`. */
        const val SEALED_PATH = "bg"
        private val HASH = Regex("[0-9a-f]{64}")
    }
}
