package app.parley.data.memory

import android.content.Context
import android.util.Log
import app.parley.data.history.HistoryCrypto
import app.parley.data.security.RecordCrypto
import java.io.File
import java.security.KeyStore

/**
 * The number-memory index's keys: an HMAC key of its own for the numbers (separate from the vault's number
 * fingerprints, so the two indexes can't be joined), a random software key wrapped by a Keystore key without
 * authentication ([HistoryCrypto], as the call-history archive keeps its own), and the small-records key
 * ([RecordCrypto]) for the hints. No authentication: the index is read while a call rings on a locked phone.
 *
 * A rebuild hashes every remembered number. Versions before 5.4 did it with an HMAC key inside the Keystore, one
 * Keystore operation per number (about 25,000 a day with a large call history); in software it is microseconds. The
 * index made with that key no longer matches ([NumberMemoryIndex.matchesKey]) and is rebuilt once; the old Keystore
 * key is deleted after that.
 */
class KeystoreMemoryKeys(context: Context) : NumberMemoryIndex.Keys {
    private val crypto = RecordCrypto.get(context)
    private val hashing = HistoryCrypto(context, File(context.noBackupFilesDir, "memory.keys"), ALIAS)

    override fun key(input: String): String = hashing.mac(input)

    override fun seal(plain: ByteArray): ByteArray {
        val sealed = crypto.sealBytes(plain)
        // sealBytes keeps a value plain when it can't seal; the index would rather have no row than a plain one.
        check(crypto.isSealed(sealed)) { "The records key can't seal right now" }
        return sealed
    }

    override fun open(sealed: ByteArray): ByteArray {
        check(crypto.isSealed(sealed)) { "Not a sealed hint" }
        return crypto.openBytes(sealed)
    }

    /** Deletes the Keystore HMAC key of versions before 5.4, once the index no longer needs it (nothing if it's gone). */
    fun retireOldKey() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(OLD_ALIAS)) ks.deleteEntry(OLD_ALIAS)
        } catch (ignored: Exception) {
            // Tried again after the next rebuild.
            Log.w("NumberMemory", "Old number-memory key kept for now: ${ignored.javaClass.simpleName}")
        }
    }

    companion object {
        /** The wrapped key (no-backup storage, registered in PersistentStores). */
        const val KEY_FILE = "memory.keys"
        private const val ALIAS = "parley_number_memory_wrap"
        private const val OLD_ALIAS = "parley_number_memory_v1"
    }
}
