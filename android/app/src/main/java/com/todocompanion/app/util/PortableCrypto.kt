package com.todocompanion.app.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Wave O — a device-independent, passphrase-encrypted backup (CypherLeaf's portable-backup idea). Kairo's
 * at-rest SQLCipher key is bound to this device's KeyStore, so a raw encrypted DB can't be restored on a
 * new phone; this wraps the plaintext JSON backup in a self-describing, versioned envelope encrypted
 * from a passphrase alone — so it restores anywhere, with no account and no network.
 *
 * PBKDF2-HMAC-SHA256 (210k iterations, OWASP-current) derives a 256-bit key from the passphrase + a
 * random 16-byte salt; AES-GCM (random 12-byte IV, 128-bit tag) encrypts. The GCM tag authenticates, so
 * a wrong passphrase or a tampered/corrupt file decrypts to null rather than garbage. Pure JVM crypto +
 * java.util.Base64, so it unit-tests without Android.
 */
object PortableCrypto {
    private const val MAGIC = "kairo-encrypted-backup"
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    @Serializable
    private data class Envelope(
        val magic: String, val version: Int, val kdf: String, val iterations: Int,
        val salt: String, val iv: String, val ciphertext: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val b64: Base64.Encoder = Base64.getEncoder()
    private val unb64: Base64.Decoder = Base64.getDecoder()

    /** Encrypt [plaintext] under [passphrase]; returns the JSON envelope string. */
    fun encrypt(plaintext: String, passphrase: CharArray): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = deriveKey(passphrase, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return json.encodeToString(
            Envelope.serializer(),
            Envelope(MAGIC, VERSION, "PBKDF2WithHmacSHA256", ITERATIONS, b64.encodeToString(salt), b64.encodeToString(iv), b64.encodeToString(ct)),
        )
    }

    /** Decrypt an envelope produced by [encrypt]. Returns null on a wrong passphrase, tamper, or a
     *  non-Kairo/unsupported blob — never throws for those. */
    fun decrypt(blob: String, passphrase: CharArray): String? {
        val env = runCatching { json.decodeFromString(Envelope.serializer(), blob) }.getOrNull() ?: return null
        if (env.magic != MAGIC || env.version > VERSION) return null
        return runCatching {
            val key = deriveKey(passphrase, unb64.decode(env.salt), env.iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, unb64.decode(env.iv)))
            String(cipher.doFinal(unb64.decode(env.ciphertext)), Charsets.UTF_8)
        }.getOrNull()
    }

    /** True if [blob] looks like one of our envelopes (used to pick the decrypt path on import). */
    fun isEnvelope(blob: String): Boolean =
        runCatching { json.decodeFromString(Envelope.serializer(), blob).magic == MAGIC }.getOrDefault(false)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
