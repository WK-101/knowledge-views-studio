package app.parley.data.history

import java.util.Locale
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-row encryption for the call-history archive (envelope encryption).
 *
 * A random 256-bit data key and a 256-bit HMAC key are wrapped with an AES-GCM key that lives in the Android
 * Keystore and never leaves it (no user authentication, like the vault's caller-ID key, so the archive can be
 * kept up to date while the phone is locked). Rows are then sealed in software, which is fast enough for tens
 * of thousands of calls; the wrapped keys sit in no-backup storage.
 */
internal class HistoryCrypto(context: Context) {
    /** The key is gone for good (invalidated, unrecoverable, or its Keystore entry provably missing). */
    class KeyLostException(cause: Throwable?) : Exception("Call-history archive key is no longer available", cause)

    /** The key couldn't be used right now (Keystore busy, not ready, I/O). Nothing is lost: try again later. */
    class KeyUnavailableException(cause: Throwable?) : Exception("Call-history archive key is temporarily unavailable", cause)

    private val file = File(context.noBackupFilesDir, "history.keys")
    private val random = SecureRandom()

    @Volatile private var keys: Pair<SecretKeySpec, SecretKeySpec>? = null

    private fun keys(): Pair<SecretKeySpec, SecretKeySpec> {
        keys?.let { return it }
        synchronized(this) {
            keys?.let { return it }
            val raw = if (file.exists()) {
                try {
                    unwrap(file.readBytes())
                } catch (e: KeyLostException) {
                    throw e
                } catch (e: Exception) {
                    throw if (isPermanent(e)) KeyLostException(e) else KeyUnavailableException(e)
                }
            } else {
                ByteArray(64).also { random.nextBytes(it) }.also { k ->
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeBytes(wrap(k))
                    if (!tmp.renameTo(file)) throw IllegalStateException("Couldn't store the archive key")
                }
            }
            val k = SecretKeySpec(raw, 0, 32, "AES") to SecretKeySpec(raw, 32, 32, "HmacSHA256")
            raw.fill(0)
            keys = k
            return k
        }
    }

    /**
     * Forgets the key (after [KeyLostException]); the next use creates a new one. The wrapped key file is moved
     * aside ([suffix]) with the old database, never deleted.
     */
    fun reset(suffix: String) = synchronized(this) {
        keys = null
        if (file.exists() && !file.renameTo(File(file.parentFile, "${file.name}.$suffix"))) file.delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { random.nextBytes(it) }
        c.init(Cipher.ENCRYPT_MODE, keys().first, GCMParameterSpec(128, iv))
        return byteArrayOf(VERSION) + iv + c.doFinal(plain)
    }

    fun open(blob: ByteArray): ByteArray {
        require(blob.size > 13 && blob[0] == VERSION) { "Unknown archive row format" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keys().first, GCMParameterSpec(128, blob, 1, 12))
        return c.doFinal(blob, 13, blob.size - 13)
    }

    /** Keyed fingerprint (hex, 128 bits) so rows can be matched and deduplicated without decrypting. */
    fun mac(value: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(keys().second)
        return m.doFinal(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    private fun wrappingKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(raw)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val ks = keyStore()
        val key = ks.getKey(ALIAS, null) as? SecretKey
            // Provably missing only if the Keystore loaded and says the alias isn't there.
            ?: throw if (!ks.containsAlias(ALIAS)) KeyLostException(null) else KeyUnavailableException(null)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 1, ivLen))
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    private companion object {
        /**
         * Errors that retrying can't fix: the Keystore invalidated or can't recover the key, or the key there
         * doesn't open the wrapped data (it was replaced). Everything else (Keystore not ready, I/O) is transient.
         */
        fun isPermanent(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is android.security.keystore.KeyPermanentlyInvalidatedException || t is java.security.UnrecoverableKeyException ||
                    t is javax.crypto.AEADBadTagException
                ) {
                    return true
                }
                t = t.cause
            }
            return false
        }

        const val STORE = "AndroidKeyStore"
        const val ALIAS = "parley_history_wrap_v1"
        const val VERSION: Byte = 1
    }
}
