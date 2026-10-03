package com.wkhan.hexis.web.crypto

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The app-layer end-to-end crypto shared by the phone (here) and the browser (WebCrypto). Deliberately
 * uses only primitives the browser's `SubtleCrypto` supports natively — **HKDF-SHA256** + **AES-256-GCM**
 * — so the web client needs no WASM/JS crypto library.
 *
 * Wire format of an encrypted blob: `base64url( iv(12 bytes) || AES-256-GCM-ciphertext-with-128-bit-tag )`.
 * This makes every request/response confidential + authenticated **even over plain HTTP**: only a holder
 * of the pairing key can produce a blob whose GCM tag verifies, so a valid blob IS proof of authorization.
 *
 * KDF parameters (must match the browser exactly): HKDF-SHA256, salt = 32 zero bytes, caller-supplied
 * `info`, output 32 bytes.
 */
object CryptoBox {

    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_LEN = 32
    private const val HASH_LEN = 32

    /** RFC 5869 HKDF-SHA256 with a 32-byte zero salt (so it matches WebCrypto's explicit zero salt). */
    fun hkdf(ikm: ByteArray, info: String, length: Int = KEY_LEN): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // extract
        mac.init(SecretKeySpec(ByteArray(HASH_LEN), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // expand
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** Encrypt [plaintext] with [key] (32 bytes) → `iv || ciphertext+tag`. */
    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Decrypt `iv || ciphertext+tag`; throws if the tag doesn't verify (wrong key / tampered / forged). */
    fun open(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "blob too short" }
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    fun sealText(key: ByteArray, plaintext: String): String = b64(seal(key, plaintext.toByteArray(Charsets.UTF_8)))
    fun openText(key: ByteArray, blob: String): String = String(open(key, unb64(blob)), Charsets.UTF_8)
}
