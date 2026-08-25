package com.todocompanion.app.data.sync

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest encryption for the files the app writes to a backup/sync folder (G1). AES-256-GCM with a
 * key derived from the user's passphrase via PBKDF2. Entirely on-device — the passphrase never
 * leaves the phone, and an encrypted file is unreadable to the drive it lands on.
 *
 * Wire format (all Base64, marker-prefixed so plaintext files still round-trip):
 *   TCENC1:<base64( salt[16] || iv[12] || ciphertext+tag )>
 */
object Crypto {
    private const val MARKER = "TCENC1:"
    private const val ITER = 120_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun isEncrypted(text: String): Boolean = text.startsWith(MARKER)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITER, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** Encrypt [plaintext] under [passphrase]; a blank passphrase returns the text unchanged. */
    fun encrypt(plaintext: String, passphrase: String): String {
        if (passphrase.isEmpty()) return plaintext
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase.toCharArray(), salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = salt + iv + ct
        return MARKER + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    /**
     * Decrypt if [text] is an encrypted blob; return plaintext unchanged otherwise. Returns null
     * only when the file is encrypted but the passphrase is wrong/missing (so callers can warn).
     */
    fun decrypt(text: String, passphrase: String): String? {
        if (!isEncrypted(text)) return text
        if (passphrase.isEmpty()) return null
        return runCatching {
            val blob = Base64.decode(text.removePrefix(MARKER), Base64.NO_WRAP)
            val salt = blob.copyOfRange(0, SALT_LEN)
            val iv = blob.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
            val ct = blob.copyOfRange(SALT_LEN + IV_LEN, blob.size)
            val key = deriveKey(passphrase.toCharArray(), salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }
}
