package app.parley.data.memory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.parley.common.Hex
import app.parley.data.security.RecordCrypto
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The number-memory index's keys: an HMAC-SHA256 key of its own in the Android Keystore for the numbers (like the
 * vault's number fingerprints, but a separate key, so the two indexes can't be joined), and the small-records key
 * ([RecordCrypto]) for the hints. No authentication: the index is read while a call rings on a locked phone.
 */
class KeystoreMemoryKeys(context: Context) : NumberMemoryIndex.Keys {
    private val crypto = RecordCrypto.get(context)
    private val ks: KeyStore by lazy { KeyStore.getInstance(STORE).apply { load(null) } }
    private var mac: Mac? = null

    @Synchronized
    override fun key(input: String): String {
        val m = mac ?: Mac.getInstance("HmacSHA256").also { it.init(secret()); mac = it }
        return Hex.encode(m.doFinal(input.toByteArray(Charsets.UTF_8)))
    }

    private fun secret(): SecretKey = (ks.getKey(ALIAS, null) as? SecretKey)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE).run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }

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

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "parley_number_memory_v1"
    }
}
