package com.cairn.reader.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (e.g. the WebDAV password) at rest with an AES-256-GCM key held in the
 * Android Keystore — the key material never leaves the secure hardware/keystore and is not part of
 * any backup. Values are stored as "enc1:" + base64(iv‖ciphertext). Reads transparently accept a
 * legacy plaintext value (no prefix) and it is re-encrypted the next time it is written, so the
 * change is backward-compatible with existing installs.
 */
object SecretStore {
    private const val ALIAS = "cairn_secret_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val PREFIX = "enc1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Encrypt [plain] into an "enc1:" token; on any keystore failure, returns the input unchanged
     *  (better a stored-plaintext fallback than losing the user's saved credential). */
    fun encrypt(plain: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ct
        PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }.getOrDefault(plain)

    /** Decrypt an "enc1:" token; a legacy plaintext value (no prefix) is returned as-is. */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_LEN)
            val ct = packed.copyOfRange(IV_LEN, packed.size)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
