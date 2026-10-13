package app.parley.data.vault

import android.content.Context
import android.util.Log
import app.parley.common.VaultNumberKeys
import app.parley.common.catching
import app.parley.data.db.PrivateCallEntity
import app.parley.data.history.HistoryCrypto
import app.parley.data.security.LineKeys
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
 * A call's time, length and direction are sealed with it; the row keeps only its entry and a keyed fingerprint of
 * "entry|time|direction" ([dedupeKey]) that stops the same call being stored twice, so a copy of the database alone
 * doesn't say when anyone called a private contact. Rows are ordered after opening.
 *
 * Calls sealed before (with the caller-ID key, still readable), or stored with their time beside the seal, open as they
 * are and are sealed again with this key, time included, the first time they are listed. When this key can't be used,
 * a call is sealed with the caller-ID key and its time kept beside it, as before, rather than lost.
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
    fun opened(e: PrivateCallEntity): PrivateCall? = catching {
        val o = JSONObject(String(open(e.blob)))
        PrivateCall(e.id, e.vaultId, o.optString("n"), o.optString("name"), o.optLong(K_DATE, e.date), o.optLong(K_DURATION, e.durationSec),
            o.optInt(K_TYPE, e.type), o.optBoolean("v"), o.optString("app").ifBlank { null },
        )
    }.getOrNull()

    /** [e]'s time, length and direction: sealed in it, or beside it for a row not sealed whole yet (its columns when it can't be opened). */
    fun facts(e: PrivateCallEntity): SealedCall {
        val o = catching { JSONObject(String(open(e.blob))) }.getOrNull()
        return SealedCall(
            e.blob, o?.optLong(K_DATE, e.date) ?: e.date, o?.optLong(K_DURATION, e.durationSec) ?: e.durationSec, o?.optInt(K_TYPE, e.type) ?: e.type,
        )
    }

    /** The keyed fingerprint of one call ([PrivateCallEntity.dedupeKey]), or null when this key can't be used now. */
    fun dedupeKey(vaultId: Long, date: Long, type: Int): String? =
        catching { LineKeys.PREFIX + crypto.mac(PrivateCallEntity.dedupeKey(vaultId, date, type)) }.getOrNull()

    /** Whether [e] is sealed whole: with this key, and with nothing but its entry and fingerprint beside it. */
    fun isSealedWhole(e: PrivateCallEntity): Boolean = isCurrent(e.blob) && LineKeys.isKeyed(e.dedupeKey)

    /**
     * [plain] (a call's sealed JSON) as a row of entry [vaultId]: sealed whole when this key works, otherwise sealed with
     * the caller-ID key and the time kept beside it as before.
     */
    fun row(vaultId: Long, plain: ByteArray, date: Long, durationSec: Long, type: Int): PrivateCallEntity {
        val key = dedupeKey(vaultId, date, type)
        val blob = seal(plain)
        return if (key != null && isCurrent(blob)) {
            PrivateCallEntity(vaultId = vaultId, blob = blob, date = 0, durationSec = 0, type = 0, dedupeKey = key)
        } else {
            val plainKey = PrivateCallEntity.dedupeKey(vaultId, date, type)
            PrivateCallEntity(vaultId = vaultId, blob = blob, date = date, durationSec = durationSec, type = type, dedupeKey = plainKey)
        }
    }

    /** [row] for a call already sealed ([blob], from a kept copy): sealed whole again when this key works. */
    fun row(vaultId: Long, c: SealedCall): PrivateCallEntity {
        val plain = catching { open(c.blob) }.getOrNull()
        return if (plain != null) {
            row(vaultId, withFacts(plain, c.date, c.durationSec, c.type), c.date, c.durationSec, c.type)
        } else {
            // Can't be opened now: kept exactly as it was, its time beside it.
            PrivateCallEntity(vaultId = vaultId, blob = c.blob, date = c.date, durationSec = c.durationSec, type = c.type, dedupeKey = null)
        }
    }

    @Volatile private var resealing = false

    /**
     * Calls not sealed whole yet (sealed with the caller-ID key before private calls had their own, or stored with their
     * time beside the seal) are sealed whole, once, in the background: each is opened and sealed again with its time,
     * and [write] stores it only if the row is still as it was read.
     */
    fun resealOlder(
        rows: List<PrivateCallEntity>, scope: CoroutineScope, write: suspend (id: Long, was: ByteArray, sealed: ByteArray, dedupeKey: String) -> Unit,
    ) {
        if (resealing || rows.all(::isSealedWhole)) return
        resealing = true
        scope.launch(Dispatchers.IO) {
            try {
                for (e in rows.filterNot(::isSealedWhole)) {
                    val plain = catching { open(e.blob) }.getOrNull() ?: continue
                    val f = facts(e)
                    val key = dedupeKey(e.vaultId, f.date, f.type) ?: continue
                    val sealed = catching { crypto.seal(withFacts(plain, f.date, f.durationSec, f.type)) }.getOrNull() ?: continue
                    write(e.id, e.blob, sealed, key)
                }
            } finally {
                resealing = false
            }
        }
    }

    companion object {
        private const val K_DATE = "d"
        private const val K_DURATION = "s"
        private const val K_TYPE = "t"

        /** A call's sealed JSON with its time, length and direction in it. */
        fun withFacts(plain: ByteArray, date: Long, durationSec: Long, type: Int): ByteArray =
            JSONObject(String(plain)).put(K_DATE, date).put(K_DURATION, durationSec).put(K_TYPE, type).toString().toByteArray()

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
