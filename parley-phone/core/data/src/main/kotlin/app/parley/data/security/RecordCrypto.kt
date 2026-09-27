package app.parley.data.security

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import app.parley.data.history.HistoryCrypto

/**
 * The small-records key: seals short personal texts and payloads at rest (pinned notes, call notes, screened callers'
 * names, the undo journal, time-machine snapshots) with the call-history archive's envelope: AES-GCM under a random
 * key wrapped by a Keystore key without user authentication, so the call screen and background work can write them
 * while the phone is locked.
 *
 * Values written before sealing existed are plain; they stay readable ([openText], [openBytes]) until
 * [app.parley.data.security.RecordSealing] re-seals them. If sealing fails (the Keystore is unavailable) a value is
 * stored plain rather than lost, and re-sealed later.
 */
class RecordCrypto private constructor(context: Context) {
    private val crypto = HistoryCrypto(context, File(context.noBackupFilesDir, "records.keys"), ALIAS)

    /** [text] sealed (as text), or [text] itself when empty or when sealing isn't possible right now. */
    fun sealText(text: String?): String? {
        if (text.isNullOrEmpty() || isSealed(text)) return text
        return try {
            TEXT_PREFIX + Base64.encodeToString(seal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (ignored: Exception) {
            // Never lose the value: it stays plain and is sealed on a later run.
            Log.w(TAG, "Kept a record unsealed for now: ${ignored.javaClass.simpleName}")
            text
        }
    }

    /** The plain text of [stored]; plain values pass through. Null when the sealed value can't be opened. */
    fun openText(stored: String?): String? {
        if (stored == null || !isSealed(stored)) return stored
        return try {
            String(crypto.open(Base64.decode(stored.substring(TEXT_PREFIX.length), Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (ignored: Exception) {
            Log.w(TAG, "A sealed record couldn't be opened: ${ignored.javaClass.simpleName}")
            null
        }
    }

    fun isSealed(text: String?): Boolean = text != null && text.startsWith(TEXT_PREFIX)

    /** [plain] sealed, marked so [openBytes] tells it from older plain payloads; [plain] itself if sealing fails. */
    fun sealBytes(plain: ByteArray): ByteArray = try {
        BYTES_MAGIC + seal(plain)
    } catch (ignored: Exception) {
        Log.w(TAG, "Kept a payload unsealed for now: ${ignored.javaClass.simpleName}")
        plain
    }

    /** The plain bytes of [stored]; older plain payloads pass through. Throws when a sealed payload can't be opened. */
    fun openBytes(stored: ByteArray): ByteArray = if (isSealed(stored)) crypto.open(stored.copyOfRange(BYTES_MAGIC.size, stored.size)) else stored

    fun isSealed(stored: ByteArray): Boolean = stored.size > BYTES_MAGIC.size && BYTES_MAGIC.indices.all { stored[it] == BYTES_MAGIC[it] }

    private fun seal(plain: ByteArray): ByteArray = try {
        crypto.seal(plain)
    } catch (e: HistoryCrypto.KeyLostException) {
        // The key is gone: older sealed records stay as they are (unreadable), new ones go under a fresh key.
        crypto.reset("lost-${System.currentTimeMillis()}")
        Log.w(TAG, "Record key replaced", e)
        crypto.seal(plain)
    }

    companion object {
        private const val TAG = "RecordCrypto"
        private const val ALIAS = "parley_records_wrap_v1"

        /** Marks a sealed text value; a control character no typed note starts with. */
        const val TEXT_PREFIX = "\u0001rs1:"
        private val BYTES_MAGIC = byteArrayOf(0x50, 0x52, 0x53, 0x01) // "PRS" 1

        @Volatile private var instance: RecordCrypto? = null

        fun get(context: Context): RecordCrypto = instance ?: synchronized(this) {
            instance ?: RecordCrypto(context.applicationContext).also { instance = it }
        }
    }
}
