package app.parley.data.vault

import android.content.Context
import android.util.Log
import app.parley.common.VaultNumberKeys
import app.parley.common.catching
import app.parley.data.db.PrivateCallEntity
import app.parley.data.history.HistoryCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Seals private calls (number, name, video) with a software key of their own, wrapped by a Keystore key without user
 * authentication, as the call-history archive does ([HistoryCrypto]): opening one is a few microseconds instead of a
 * Keystore operation, so listing two thousand private calls no longer takes seconds after every change, and never
 * competes with call screening for the Keystore.
 *
 * Calls sealed before (with the caller-ID key, still readable) open as they are and are re-sealed with this key the
 * first time they are listed. When this key can't be used, a call is sealed with the caller-ID key as before rather
 * than lost.
 */
internal class PrivateCallSeal(context: Context) {
    private val crypto = HistoryCrypto(context, File(context.noBackupFilesDir, "vault_calls.keys"), ALIAS)

    /** Whether [blob] is sealed with this key (a caller-ID blob starts with its IV length, 12). */
    fun isCurrent(blob: ByteArray): Boolean = blob.size > 13 && blob[0] == CURRENT

    @Suppress("TooGenericExceptionCaught") // Whatever the key's trouble, the call is kept under the caller-ID key.
    fun seal(plain: ByteArray): ByteArray = try {
        crypto.seal(plain)
    } catch (ignored: Exception) {
        Log.w(TAG, "Private call sealed with the caller-ID key for now: ${ignored.javaClass.simpleName}")
        VaultCrypto.sealCallerId(plain)
    }

    fun open(blob: ByteArray): ByteArray = if (isCurrent(blob)) crypto.open(blob) else VaultCrypto.openCallerId(blob)

    /** The private call sealed in [e], or null when it can't be opened now. */
    fun opened(e: PrivateCallEntity): PrivateCall? = runCatching {
        val o = JSONObject(String(open(e.blob)))
        PrivateCall(e.id, e.vaultId, o.optString("n"), o.optString("name"), e.date, e.durationSec, e.type, o.optBoolean("v"),
            o.optString("app").ifBlank { null },
        )
    }.getOrNull()

    @Volatile private var resealing = false

    /**
     * Calls sealed with the caller-ID key before private calls had their own key move to it, once, in the background:
     * each is opened and sealed again, and [write] stores it only if the row is still as it was read.
     */
    fun resealOlder(rows: List<PrivateCallEntity>, scope: CoroutineScope, write: suspend (id: Long, was: ByteArray, sealed: ByteArray) -> Unit) {
        if (resealing || rows.all { isCurrent(it.blob) }) return
        resealing = true
        scope.launch(Dispatchers.IO) {
            try {
                for (e in rows.filter { !isCurrent(it.blob) }) {
                    val plain = catching { VaultCrypto.openCallerId(e.blob) }.getOrNull() ?: continue
                    val sealed = seal(plain)
                    if (isCurrent(sealed)) write(e.id, e.blob, sealed)
                }
            } finally {
                resealing = false
            }
        }
    }

    companion object {
        /**
         * Which call-log numbers may be private (read once per sweep): only those are looked up (a Keystore fingerprint
         * per form), not every call of the month. [entries] are each private contact's numbers with the region they were
         * saved with; [region] is the one lookups read call-log numbers with. Matched on every form caller ID matches
         * on ([VaultNumberKeys.Prefilter]), so a call the full lookup would match always passes. Null when there are no
         * private numbers.
         */
        fun prefilter(entries: List<Pair<List<String>, String?>>, region: String?): ((String) -> Boolean)? {
            val p = VaultNumberKeys.Prefilter(entries, region)
            if (p.isEmpty) return null
            return p::mayMatch
        }

        private const val TAG = "PrivateCallSeal"

        /** The wrapped key's file (no-backup storage, registered in PersistentStores). */
        const val KEY_FILE = "vault_calls.keys"
        private const val ALIAS = "parley_vault_calls_wrap"

        /** [HistoryCrypto]'s row format. */
        private const val CURRENT: Byte = 1
    }
}
