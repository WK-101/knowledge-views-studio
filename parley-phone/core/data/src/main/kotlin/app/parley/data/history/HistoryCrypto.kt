package app.parley.data.history

import android.security.keystore.KeyPermanentlyInvalidatedException
import app.parley.common.crypto.Aead
import app.parley.common.storage.DurableFiles
import app.parley.data.security.KeystoreSeal
import java.security.UnrecoverableKeyException
import android.content.Context
import app.parley.common.Hex
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Per-row encryption for the call-history archive (envelope encryption).
 *
 * A random 256-bit data key and a 256-bit HMAC key are wrapped with an AES-GCM key that lives in the Android
 * Keystore and never leaves it (no user authentication, like the vault's caller-ID key, so the archive can be
 * kept up to date while the phone is locked). Rows are then sealed in software, which is fast enough for tens
 * of thousands of calls; the wrapped keys sit in no-backup storage.
 */
internal class HistoryCrypto(
    context: Context,
    /** Where the wrapped key is kept (no-backup storage). */
    private val file: File = File(context.noBackupFilesDir, "history.keys"),
    /** The Keystore alias of the wrapping key (other small-record stores use the same envelope under their own). */
    private val alias: String = ALIAS,
) {
    /**
     * The key is gone for good (invalidated, unrecoverable, or its Keystore entry provably missing). [provable] is
     * false when only an [UnrecoverableKeyException] says so: AndroidKeyStore also wraps some passing keystore2 errors
     * (a busy or failing backend) in it, so stores that must not give up their key on a guess treat it as temporary.
     */
    class KeyLostException(cause: Throwable?, val provable: Boolean = true) : Exception("Call-history archive key is no longer available", cause)

    /** The key couldn't be used right now (Keystore busy, not ready, I/O). Nothing is lost: try again later. */
    class KeyUnavailableException(cause: Throwable?) : Exception("Call-history archive key is temporarily unavailable", cause)

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
                    throw if (isPermanent(e)) KeyLostException(e, provable = isProvable(e)) else KeyUnavailableException(e)
                }
            } else {
                ByteArray(64).also { random.nextBytes(it) }.also { k ->
                    // Synced before anything is sealed with it: a key lost to a power cut makes those rows unreadable.
                    DurableFiles.writeOrThrow(file, wrap(k))
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
     * aside ([suffix]) with the old database, never deleted. [deleteKeystoreEntry]: also drop the wrapping key, so a
     * new one is made under the same alias; stores that move to a new alias instead keep the old entry, in case it
     * can still open the file set aside.
     */
    fun reset(suffix: String, deleteKeystoreEntry: Boolean = true) = synchronized(this) {
        keys = null
        if (file.exists() && !DurableFiles.move(file, File(file.parentFile, "${file.name}.$suffix"))) file.delete()
        if (deleteKeystoreEntry) runCatching { keyStore().deleteEntry(alias) }
    }

    /**
     * Drops the unwrapped key from memory (the file and the Keystore entry stay): the next use unwraps it again, one
     * Keystore operation. For stores whose key should not outlive a lock.
     */
    fun forgetKey() = synchronized(this) {
        keys = null
        macs.remove()
    }

    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(Aead.NONCE).also { random.nextBytes(it) }
        return byteArrayOf(VERSION) + iv + Aead.encrypt(keys().first, iv, plain)
    }

    fun open(blob: ByteArray): ByteArray {
        require(blob.size > 13 && blob[0] == VERSION) { "Unknown archive row format" }
        return Aead.decrypt(keys().first, blob, 1, Aead.NONCE, blob, 13, blob.size - 13)
    }

    /** Keyed fingerprint (hex, 128 bits) so rows can be matched and deduplicated without decrypting. */
    fun mac(value: String): String {
        val key = keys().second
        // One initialised Mac per thread and key: a full sync fingerprints every call-log row. doFinal resets it.
        val m = macs.get()?.takeIf { it.first === key }?.second
            ?: Mac.getInstance("HmacSHA256").also { it.init(key); macs.set(key to it) }
        return Hex.encode(m.doFinal(value.toByteArray(Charsets.UTF_8)), MAC_BYTES)
    }

    private val macs = ThreadLocal<Pair<SecretKeySpec, Mac>>()

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    private fun wrappingKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): ByteArray = KeystoreSeal.seal(wrappingKey(), raw)

    private fun unwrap(blob: ByteArray): ByteArray {
        val ks = keyStore()
        val key = ks.getKey(alias, null) as? SecretKey
            // Provably missing only if the Keystore loaded and says the alias isn't there.
            ?: throw if (!ks.containsAlias(alias)) KeyLostException(null) else KeyUnavailableException(null)
        return KeystoreSeal.open(key, blob)
    }

    private companion object {
        /**
         * Errors that retrying can't fix: the Keystore invalidated or can't recover the key, or the key there
         * doesn't open the wrapped data (it was replaced). Everything else (Keystore not ready, I/O) is transient.
         */
        fun isPermanent(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is KeyPermanentlyInvalidatedException || t is UnrecoverableKeyException ||
                    t is AEADBadTagException
                ) {
                    return true
                }
                t = t.cause
            }
            return false
        }

        /** Loss the Keystore states outright: the key was invalidated, or it doesn't open what it wrapped. */
        fun isProvable(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is KeyPermanentlyInvalidatedException || t is AEADBadTagException) return true
                t = t.cause
            }
            return false
        }

        const val STORE = "AndroidKeyStore"
        const val ALIAS = "parley_history_wrap_v1"
        const val VERSION: Byte = 1

        /** Fingerprints keep the first 128 bits of the HMAC. */
        const val MAC_BYTES = 16
    }
}
