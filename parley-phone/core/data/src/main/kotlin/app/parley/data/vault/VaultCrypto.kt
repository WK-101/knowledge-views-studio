package app.parley.data.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Vault keys live in the Android Keystore and never leave it.
 * - caller-ID key: no user auth, so incoming calls can show vault names on the lock screen;
 * - detail key: requires the user to have unlocked (biometric/device credential) in the last 5 min
 *   when the device supports it;
 * - HMAC key: fingerprints phone numbers so lookups don't need decryption.
 */
object VaultCrypto {
    private const val STORE = "AndroidKeyStore"
    private const val CALLER_KEY = "parley_vault_callerid_v1"
    private const val DETAIL_KEY = "parley_vault_detail_v1"
    private const val HMAC_KEY = "parley_vault_hmac_v1"
    private const val AUTH_SECONDS = 300

    class LockedException : Exception("Unlock needed")

    /** The key was invalidated (screen lock removed or reset); data sealed with it can't be read any more. */
    class KeyLostException : Exception("Vault key invalidated")

    private val ks: KeyStore by lazy { KeyStore.getInstance(STORE).apply { load(null) } }

    private fun aesKey(alias: String, requireAuth: Boolean): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        fun spec(auth: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (auth) {
                    setUserAuthenticationRequired(true)
                    // Enrolling a new fingerprint must not destroy private contacts.
                    setInvalidatedByBiometricEnrollment(false)
                    if (Build.VERSION.SDK_INT >= 30) {
                        setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
                    }
                }
            }
            .build()
        return try {
            gen.init(spec(requireAuth))
            gen.generateKey()
        } catch (_: Exception) {
            // No secure lock screen: fall back to a key without user authentication.
            gen.init(spec(false))
            gen.generateKey()
        }
    }

    private val random = SecureRandom()

    private fun encrypt(alias: String, auth: Boolean, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            c.init(Cipher.ENCRYPT_MODE, aesKey(alias, auth))
        } catch (e: UserNotAuthenticatedException) {
            throw LockedException()
        } catch (e: KeyPermanentlyInvalidatedException) {
            // New data can simply go under a fresh key.
            ks.deleteEntry(alias)
            c.init(Cipher.ENCRYPT_MODE, aesKey(alias, auth))
        }
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    private fun decrypt(alias: String, auth: Boolean, blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            c.init(Cipher.DECRYPT_MODE, aesKey(alias, auth), GCMParameterSpec(128, blob, 1, ivLen))
        } catch (e: UserNotAuthenticatedException) {
            throw LockedException()
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyLostException()
        } catch (e: InvalidKeyException) {
            // A key created before this blob (e.g. regenerated after invalidation) can't open it.
            throw KeyLostException()
        }
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    fun sealCallerId(plain: ByteArray) = encrypt(CALLER_KEY, false, plain)
    fun openCallerId(blob: ByteArray) = decrypt(CALLER_KEY, false, blob)
    fun sealDetail(plain: ByteArray) = encrypt(DETAIL_KEY, true, plain)

    /** Throws [LockedException] when a fresh unlock is needed, [KeyLostException] when the key is gone for good. */
    fun openDetail(blob: ByteArray) = decrypt(DETAIL_KEY, true, blob)

    /** Whether the detail key currently needs a fresh unlock. */
    fun detailNeedsUnlock(): Boolean = try {
        Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, aesKey(DETAIL_KEY, true))
        false
    } catch (_: UserNotAuthenticatedException) {
        true
    } catch (_: KeyPermanentlyInvalidatedException) {
        ks.deleteEntry(DETAIL_KEY)
        false
    } catch (_: Exception) {
        false
    }

    fun hmac(value: String): String {
        val key = (ks.getKey(HMAC_KEY, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE).run {
            init(KeyGenParameterSpec.Builder(HMAC_KEY, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
}
