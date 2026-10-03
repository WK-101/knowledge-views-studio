package app.parley.data.vault

import android.content.Context
import android.util.Log
import app.parley.data.history.HistoryCrypto
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
    private val crypto = HistoryCrypto(context, File(context.noBackupFilesDir, KEY_FILE), ALIAS)

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

    companion object {
        private const val TAG = "PrivateCallSeal"
        const val KEY_FILE = "vault_calls.keys"
        private const val ALIAS = "parley_vault_calls_wrap"

        /** [HistoryCrypto]'s row format. */
        private const val CURRENT: Byte = 1
    }
}
