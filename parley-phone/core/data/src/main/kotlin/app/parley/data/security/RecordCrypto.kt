package app.parley.data.security

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import app.parley.data.history.HistoryCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The small-records key: seals short personal texts and payloads at rest (pinned notes, call notes, screened callers'
 * names, the undo journal, time-machine snapshots) with the call-history archive's envelope: AES-GCM under a random
 * key wrapped by a Keystore key without user authentication, so the call screen and background work can write them
 * while the phone is locked.
 *
 * Values written before sealing existed are plain; they stay readable ([openText], [openBytes]) until
 * [app.parley.data.security.RecordSealing] re-seals them. If sealing fails (the Keystore is unavailable) a value is
 * stored plain rather than lost, and re-sealed later.
 *
 * The key is replaced only when the Keystore says outright that it is gone (invalidated, or its entry missing), never
 * on an ambiguous error. Even then nothing is deleted: the old wrapped key file and its Keystore entry stay, reads
 * still try them, and the user is told ([resetAt]).
 */
class RecordCrypto private constructor(private val context: Context) {
    private val keyFile = File(context.noBackupFilesDir, "records.keys")
    private val dir = keyFile.parentFile!!
    private val prefs = context.getSharedPreferences("record_crypto", Context.MODE_PRIVATE)

    @Volatile private var crypto = HistoryCrypto(context, keyFile, alias(prefs.getInt(K_GENERATION, 1)))

    /** Keys set aside by earlier resets: kept so a value they sealed still opens if their Keystore entry works. */
    private class Retired(val crypto: HistoryCrypto) {
        @Volatile var gone = false
    }

    @Volatile private var retired: List<Retired> = loadRetired()

    private val _resetAt = MutableStateFlow(if (prefs.getBoolean(K_RESET_SEEN, true)) 0L else prefs.getLong(K_RESET_AT, 0L))

    /** When the key was last replaced and the user hasn't acknowledged it yet (0: nothing to tell). */
    val resetAt: StateFlow<Long> = _resetAt.asStateFlow()

    fun acknowledgeReset() {
        prefs.edit().putBoolean(K_RESET_SEEN, true).apply()
        _resetAt.value = 0L
    }

    /** A value that is sealed but can't be opened right now (the key is unavailable, or gone). */
    class UnreadableException(cause: Throwable?) : Exception("A sealed record can't be opened", cause)

    /** [text] sealed (as text), or [text] itself when empty or when sealing isn't possible right now. */
    fun sealText(text: String?): String? {
        if (text.isNullOrEmpty() || isSealed(text)) return text
        return try {
            TEXT_PREFIX + Base64.encodeToString(seal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (ignored: Exception) {
            // Never lose the value: it stays plain and is sealed on a later run.
            Log.w(TAG, "Kept a record unsealed for now: ${ignored.javaClass.simpleName}")
            RecordSealing.markPending(context)
            text
        }
    }

    /**
     * The plain text of [stored]; plain values pass through. Throws [UnreadableException] when a sealed value can't be
     * opened, so a caller that writes back can tell "no value" from "a value it can't see right now".
     */
    fun openTextOrThrow(stored: String?): String? {
        if (stored == null || !isSealed(stored)) return stored
        return try {
            String(open(Base64.decode(stored.substring(TEXT_PREFIX.length), Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (ignored: Exception) {
            // Any failure (key unavailable or gone, damaged value): the caller keeps the stored value as it is.
            throw UnreadableException(ignored)
        }
    }

    /** For display: the plain text of [stored], or null when the sealed value can't be opened right now. */
    fun openText(stored: String?): String? = try {
        openTextOrThrow(stored)
    } catch (ignored: UnreadableException) {
        Log.w(TAG, "A sealed record couldn't be opened: ${ignored.cause?.javaClass?.simpleName}")
        null
    }

    /** Whether [stored] is a sealed value that can't be opened now (it must be kept as it is, never overwritten). */
    fun isUnreadable(stored: String?): Boolean = isSealed(stored) && try {
        openTextOrThrow(stored)
        false
    } catch (_: UnreadableException) {
        true
    }

    fun isSealed(text: String?): Boolean = text != null && text.startsWith(TEXT_PREFIX)

    /** [plain] sealed, marked so [openBytes] tells it from older plain payloads; [plain] itself if sealing fails. */
    fun sealBytes(plain: ByteArray): ByteArray = try {
        BYTES_MAGIC + seal(plain)
    } catch (ignored: Exception) {
        Log.w(TAG, "Kept a payload unsealed for now: ${ignored.javaClass.simpleName}")
        RecordSealing.markPending(context)
        plain
    }

    /** The plain bytes of [stored]; older plain payloads pass through. Throws when a sealed payload can't be opened. */
    fun openBytes(stored: ByteArray): ByteArray = if (isSealed(stored)) open(stored.copyOfRange(BYTES_MAGIC.size, stored.size)) else stored

    fun isSealed(stored: ByteArray): Boolean = stored.size > BYTES_MAGIC.size && BYTES_MAGIC.indices.all { stored[it] == BYTES_MAGIC[it] }

    /** Opens with the current key, then with keys set aside by earlier resets (a value sealed before one). */
    private fun open(blob: ByteArray): ByteArray {
        val first = try {
            return crypto.open(blob)
        } catch (e: HistoryCrypto.KeyUnavailableException) {
            // Can't tell which key sealed it until the current one works again.
            throw e
        } catch (ignored: Exception) {
            ignored
        }
        for (r in retired) {
            if (r.gone) continue
            try {
                return r.crypto.open(blob)
            } catch (e: HistoryCrypto.KeyLostException) {
                if (e.provable) r.gone = true
            } catch (_: Exception) {
                // Not this key's value, or not usable right now.
            }
        }
        throw first
    }

    private fun seal(plain: ByteArray): ByteArray {
        val current = crypto
        return try {
            current.seal(plain)
        } catch (e: HistoryCrypto.KeyLostException) {
            // Only a loss the Keystore states outright replaces the key; anything ambiguous keeps it (the value is
            // stored plain for now and sealed on a later run).
            if (!e.provable) throw HistoryCrypto.KeyUnavailableException(e)
            replaceKey(current, e)
            crypto.seal(plain)
        }
    }

    /**
     * The key is gone for good: new values go under a new key (a new Keystore alias, so an invalidated entry is never
     * reused). Older sealed values stay as they are; the old wrapped key and its Keystore entry are kept, not deleted.
     */
    @Synchronized
    private fun replaceKey(failed: HistoryCrypto, e: Exception) {
        // Another write already replaced it.
        if (crypto !== failed) return
        Log.w(TAG, "Record key lost; a new one is used from now on, older records are kept as they are", e)
        val gen = prefs.getInt(K_GENERATION, 1)
        val suffix = "lost-${System.currentTimeMillis()}"
        crypto.reset(suffix, deleteKeystoreEntry = false)
        val aside = File(dir, "${keyFile.name}.$suffix")
        val kept = prefs.getStringSet(K_RETIRED, emptySet()).orEmpty().toMutableSet()
        if (aside.exists()) kept += "$suffix|${alias(gen)}"
        val now = System.currentTimeMillis()
        prefs.edit()
            .putInt(K_GENERATION, gen + 1)
            .putStringSet(K_RETIRED, kept)
            .putLong(K_RESET_AT, now)
            .putBoolean(K_RESET_SEEN, false)
            .commit()
        crypto = HistoryCrypto(context, keyFile, alias(gen + 1))
        retired = loadRetired()
        _resetAt.value = now
    }

    private fun loadRetired(): List<Retired> = prefs.getStringSet(K_RETIRED, emptySet()).orEmpty().mapNotNull { entry ->
        val suffix = entry.substringBefore('|')
        val alias = entry.substringAfter('|', "")
        val file = File(dir, "${keyFile.name}.$suffix")
        if (alias.isEmpty() || !file.exists()) null else Retired(HistoryCrypto(context, file, alias))
    }

    companion object {
        private const val TAG = "RecordCrypto"
        private const val ALIAS = "parley_records_wrap_v1"
        private const val K_GENERATION = "generation"
        private const val K_RETIRED = "retired"
        private const val K_RESET_AT = "resetAt"
        private const val K_RESET_SEEN = "resetSeen"

        /** The first key keeps the original alias; a replacement gets a new one. */
        private fun alias(gen: Int) = if (gen <= 1) ALIAS else "${ALIAS}_g$gen"

        /** Marks a sealed text value; a control character no typed note starts with. */
        const val TEXT_PREFIX = "\u0001rs1:"
        private val BYTES_MAGIC = byteArrayOf(0x50, 0x52, 0x53, 0x01) // "PRS" 1

        @Volatile private var instance: RecordCrypto? = null

        fun get(context: Context): RecordCrypto = instance ?: synchronized(this) {
            instance ?: RecordCrypto(context.applicationContext).also { instance = it }
        }

        /** A separate instance that reads its key state afresh, as after a process restart (tests). */
        @androidx.annotation.VisibleForTesting
        internal fun fresh(context: Context): RecordCrypto = RecordCrypto(context.applicationContext)
    }
}
