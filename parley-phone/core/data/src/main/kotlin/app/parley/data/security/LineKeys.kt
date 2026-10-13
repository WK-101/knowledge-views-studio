package app.parley.data.security

import android.content.Context
import app.parley.common.catching
import app.parley.data.history.HistoryCrypto
import java.io.File

/**
 * Keyed fingerprints of line keys ([app.parley.common.PhoneIdentity.key] and its older forms) for Parley's own tables:
 * call notes and remembered SIMs are found by the fingerprint, never by the number, and the number itself is kept
 * sealed beside it ([RecordCrypto]) for the screens, exports and backups that show it. So a copy of the database
 * alone doesn't say which numbers it holds notes or SIM choices for (a private contact's among them).
 *
 * The key is random per install, wrapped by a Keystore key without user authentication (a SIM is looked up while a
 * locked phone places a call), the call-history archive's envelope ([HistoryCrypto]). Fingerprints are stable for the
 * life of the key; backups carry the plain line keys inside their encryption and are keyed again on restore, so they
 * move between phones. Lookups pass every key form a line may be stored under through [forms] (each form keyed the
 * same way), so rows stored under an older form still match.
 *
 * When the key can't be used right now, a value is stored plain as before and keyed later by [RecordSealing]; if the
 * Keystore says outright that the key is gone, a new one is made and [RecordSealing] keys every row again from its
 * sealed copy.
 */
class LineKeys private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val crypto = HistoryCrypto(appContext, File(appContext.noBackupFilesDir, "lines.keys"), ALIAS)

    /**
     * [key]'s stored form, or null when the key can't be used now (the caller then keeps [key] as it is, and the next
     * [RecordSealing] run keys it). An empty key (a withheld number) and one already keyed are returned unchanged.
     */
    fun keyed(key: String): String? {
        if (key.isEmpty() || isKeyed(key)) return key
        val made = catching { crypto.mac(key) }
        made.getOrNull()?.let { return PREFIX + it }
        val lost = made.exceptionOrNull() as? HistoryCrypto.KeyLostException
        if (lost?.provable == true) return renewed(key)
        RecordSealing.markPending(appContext)
        return null
    }

    /** [keys] and their keyed forms: what a lookup matches, whether a row is keyed yet or not. */
    fun forms(keys: Collection<String>): List<String> = (keys + keys.mapNotNull(::keyed)).distinct()

    /** The key is gone for good: a new one from now on, and every keyed row is keyed again from its sealed copy. */
    @Synchronized
    private fun renewed(key: String): String? {
        // Another thread may have replaced it meanwhile.
        catching { crypto.mac(key) }.getOrNull()?.let { return PREFIX + it }
        crypto.reset("lost-${System.currentTimeMillis()}")
        RecordSealing.markPending(appContext)
        return catching { PREFIX + crypto.mac(key) }.getOrNull()
    }

    companion object {
        /** Marks a keyed value; no line key starts with it ("+", "~" or digits). */
        const val PREFIX = "#"

        /** The wrapped key's file (no-backup storage, registered in PersistentStores). */
        const val KEY_FILE = "lines.keys"
        private const val ALIAS = "parley_lines_wrap"

        fun isKeyed(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

        @Volatile private var instance: LineKeys? = null

        fun get(context: Context): LineKeys = instance ?: synchronized(this) {
            instance ?: LineKeys(context).also { instance = it }
        }

        /** A separate instance that reads its key afresh, as after a process restart or on another phone (tests). */
        @androidx.annotation.VisibleForTesting
        internal fun fresh(context: Context): LineKeys = LineKeys(context)
    }
}
