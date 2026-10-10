package app.parley.data.vault

import android.content.Context
import android.util.Log
import app.parley.common.Hex
import app.parley.data.history.HistoryCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The private contacts' list rows as the last listing opened them, kept between runs so a cold start lists the vault
 * with one Keystore operation instead of one per contact (each caller-ID copy opens with an AndroidKeyStore key; with
 * hundreds of private contacts that held the Contacts list back for seconds).
 *
 * One file in no-backup storage, sealed as a whole with a software key of its own wrapped by a Keystore key without
 * user authentication: the same protection as the caller-ID copies it is made from, which also open without an unlock
 * (see docs/SECURITY_MODEL.md). It holds only what a list row needs ([CallerIdCopy.summaryPart]), never the caller
 * card's note or the sealed details. Each row is checked against a digest of the caller-ID copy now in the database,
 * so a changed or new entry is opened as before and a deleted one is ignored: what is kept can be stale, never shown
 * stale. The unwrapped key is dropped whenever opened details are forgotten (the app lock, the screen off, "Lock
 * private contacts"), so it lives no longer than they do.
 */
internal class PrivateSummaryCache(context: Context) {
    private val file = File(context.noBackupFilesDir, "vault_summaries")
    private val keyFile = File(context.noBackupFilesDir, "vault_summaries.keys")

    @Volatile private var crypto = HistoryCrypto(context, keyFile, ALIAS)
    private val appContext = context.applicationContext

    /** One kept row: the digest of the caller-ID copy it came from, and that copy cut down to a row's fields. */
    class Entry(val digest: String, val part: JSONObject)

    /** What the file held, or what was last written: compared before writing, so an unchanged listing writes nothing. */
    @Volatile private var kept: Map<Long, String>? = null

    /** The kept rows by entry; empty when there are none or they can't be opened (each entry is then opened itself). */
    @Synchronized
    fun load(): Map<Long, Entry> {
        if (!file.isFile) {
            kept = emptyMap()
            return emptyMap()
        }
        return try {
            val o = JSONObject(String(crypto.open(file.readBytes())))
            val arr = o.optJSONArray(J_ROWS) ?: JSONArray()
            val out = HashMap<Long, Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                out[r.getLong(J_ID)] = Entry(r.getString(J_DIGEST), r.getJSONObject(J_PART))
            }
            kept = out.mapValues { it.value.digest }
            out
        } catch (e: HistoryCrypto.KeyUnavailableException) {
            // Kept as it is: the Keystore may answer next time.
            Log.w(TAG, "Kept private rows can't be opened now: ${e.cause?.javaClass?.simpleName}")
            emptyMap()
        } catch (ignored: Exception) {
            // Its key is gone or the file is damaged: it is only a copy, so it goes, with its key.
            Log.w(TAG, "Kept private rows dropped: ${ignored.javaClass.simpleName}")
            drop()
            emptyMap()
        }
    }

    /** Keeps [rows] (entry → its row) unless they are what is kept already. Never written unsealed. */
    @Synchronized
    fun save(rows: Map<Long, Entry>) {
        if (kept == rows.mapValues { it.value.digest }) return
        val arr = JSONArray()
        rows.forEach { (id, e) -> arr.put(JSONObject().put(J_ID, id).put(J_DIGEST, e.digest).put(J_PART, e.part)) }
        try {
            val sealed = crypto.seal(JSONObject().put(J_ROWS, arr).toString().toByteArray())
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(sealed)
            if (tmp.renameTo(file)) kept = rows.mapValues { it.value.digest }
        } catch (ignored: Exception) {
            // Not kept this time: the next cold start opens every entry, as before.
            Log.w(TAG, "Private rows not kept: ${ignored.javaClass.simpleName}")
        }
    }

    /** Drops the unwrapped key from memory (the app lock, the screen off, "Lock private contacts"). */
    fun forgetKey() = crypto.forgetKey()

    /** Removes the file and its wrapped key (a new key is made on the next save). */
    @Synchronized
    fun drop() {
        file.delete()
        keyFile.delete()
        kept = null
        crypto = HistoryCrypto(appContext, keyFile, ALIAS)
    }

    companion object {
        private const val TAG = "PrivateSummaryCache"

        private const val ALIAS = "parley_vault_summaries_wrap"
        private const val J_ROWS = "rows"
        private const val J_ID = "i"
        private const val J_DIGEST = "d"
        private const val J_PART = "p"

        /** A caller-ID copy's digest: any change to the sealed copy (a new IV at every seal) changes it. */
        fun digest(blob: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(blob), DIGEST_BYTES)

        private const val DIGEST_BYTES = 16
    }
}
