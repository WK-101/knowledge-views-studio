package app.parley.common.crypto

import java.security.GeneralSecurityException
import java.security.Key
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a 96-bit nonce and a 128-bit tag: the one authenticated cipher behind every sealed format in
 * Parley. Each format keeps its own layout (magic, version byte, where the nonce goes) and its own key domain; only
 * the cipher calls live here, so there is one place to review them.
 */
object Aead {
    const val NONCE = 12
    const val TAG = 16

    /** The ciphertext and tag of [plain] under [key] at [nonce]. */
    fun encrypt(key: Key, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, nonce, 0, nonce.size, aad).doFinal(plain)

    fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray = encrypt(aes(key), nonce, plain, aad)

    /**
     * Opens [len] bytes of [data] from [off] (ciphertext and tag), the nonce being [nonceLen] bytes of [nonceSrc] from
     * [nonceOff]. Throws [GeneralSecurityException] when the key, nonce, data or [aad] don't match.
     */
    fun decrypt(
        key: Key,
        nonceSrc: ByteArray,
        nonceOff: Int,
        nonceLen: Int,
        data: ByteArray,
        off: Int,
        len: Int,
        aad: ByteArray? = null,
    ): ByteArray = cipher(Cipher.DECRYPT_MODE, key, nonceSrc, nonceOff, nonceLen, aad).doFinal(data, off, len)

    fun decrypt(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray? = null): ByteArray =
        decrypt(aes(key), nonce, 0, nonce.size, data, 0, data.size, aad)

    /** A fresh random nonce followed by [encrypt]'s output. */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        return nonce + encrypt(key, nonce, plain, aad)
    }

    /** Opens [seal]'s output; null when it is too short or doesn't authenticate. */
    fun openOrNull(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray? {
        if (sealed.size < NONCE + TAG) return null
        return try {
            decrypt(aes(key), sealed, 0, NONCE, sealed, NONCE, sealed.size - NONCE, aad)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    fun aes(key: ByteArray): SecretKeySpec = SecretKeySpec(key, "AES")

    private fun cipher(mode: Int, key: Key, nonce: ByteArray, off: Int, len: Int, aad: ByteArray?): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG * 8, nonce, off, len))
            if (aad != null) updateAAD(aad)
        }
}

/** HKDF-SHA256 (RFC 5869) with one block of output: a 32-byte key. */
object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray(Charsets.UTF_8)); mac.update(1)
        return mac.doFinal().also { prk.fill(0) }
    }
}
