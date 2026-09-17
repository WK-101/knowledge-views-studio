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
 *   TCENC2:<base64( salt[16] || iv[12] || ciphertext+tag )>   ← current, PBKDF2 210k iterations
 *   TCENC1:<base64( salt[16] || iv[12] || ciphertext+tag )>   ← legacy, 120k iterations (still read)
 *
 * The iteration count is encoded by the marker, not stored in the blob, so an old file always
 * decrypts under the count it was written with. New files are always written as TCENC2, raising the
 * PBKDF2 work factor to OWASP's current PBKDF2-HMAC-SHA256 guidance. A previously-exported file is
 * transparently upgraded to v2 the next time the app re-writes it (no user action, no re-key).
 */
object Crypto {
    private const val MARKER_V2 = "TCENC2:"
    private const val MARKER_V1 = "TCENC1:"   // legacy, decrypt-only
    private const val ITER_V2 = 210_000
    private const val ITER_V1 = 120_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun isEncrypted(text: String): Boolean = text.startsWith(MARKER_V2) || text.startsWith(MARKER_V1)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** Encrypt [plaintext] under [passphrase]; a blank passphrase returns the text unchanged.
     *  Always writes the current (v2) format. */
    fun encrypt(plaintext: String, passphrase: String): String {
        if (passphrase.isEmpty()) return plaintext
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase.toCharArray(), salt, ITER_V2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = salt + iv + ct
        return MARKER_V2 + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    /**
     * Decrypt if [text] is an encrypted blob; return plaintext unchanged otherwise. Returns null
     * only when the file is encrypted but the passphrase is wrong/missing (so callers can warn).
     * Reads both v2 (210k) and legacy v1 (120k) blobs, picking the iteration count by marker.
     */
    fun decrypt(text: String, passphrase: String): String? {
        val iterations: Int
        val marker: String
        when {
            text.startsWith(MARKER_V2) -> { iterations = ITER_V2; marker = MARKER_V2 }
            text.startsWith(MARKER_V1) -> { iterations = ITER_V1; marker = MARKER_V1 }
            else -> return text
        }
        if (passphrase.isEmpty()) return null
        return runCatching {
            val blob = Base64.decode(text.removePrefix(marker), Base64.NO_WRAP)
            val salt = blob.copyOfRange(0, SALT_LEN)
            val iv = blob.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
            val ct = blob.copyOfRange(SALT_LEN + IV_LEN, blob.size)
            val key = deriveKey(passphrase.toCharArray(), salt, iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }
}
