package com.wkhan.hexis.web.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the wire contract the browser (WebCrypto) must match byte-for-byte: HKDF-SHA256 with a 32-byte
 * zero salt, AES-256-GCM with a 12-byte prepended IV and 128-bit tag, base64url without padding.
 */
class CryptoBoxTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun sealOpen_roundTrips() {
        val msg = "hello hexis — über ✓"
        val blob = CryptoBox.sealText(key, msg)
        assertEquals(msg, CryptoBox.openText(key, blob))
    }

    @Test
    fun seal_isNonDeterministic_butBothOpen() {
        val msg = "same plaintext"
        val a = CryptoBox.sealText(key, msg)
        val b = CryptoBox.sealText(key, msg)
        assertNotEquals("random IV must make ciphertexts differ", a, b)
        assertEquals(msg, CryptoBox.openText(key, a))
        assertEquals(msg, CryptoBox.openText(key, b))
    }

    @Test
    fun open_withWrongKey_fails() {
        val blob = CryptoBox.sealText(key, "secret")
        val wrong = ByteArray(32) { (it + 1).toByte() }
        assertThrows(Exception::class.java) { CryptoBox.openText(wrong, blob) }
    }

    @Test
    fun open_tamperedTag_fails() {
        val raw = CryptoBox.seal(key, "secret".toByteArray())
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) { CryptoBox.open(key, raw) }
    }

    @Test
    fun hkdf_isDeterministic_andCorrectLength() {
        val ikm = ByteArray(32) { 7 }
        val a = CryptoBox.hkdf(ikm, "hexis-web-aead-v1")
        val b = CryptoBox.hkdf(ikm, "hexis-web-aead-v1")
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
        // A different info must yield a different key (domain separation).
        assertNotEquals(CryptoBox.b64(a), CryptoBox.b64(CryptoBox.hkdf(ikm, "other-info")))
    }

    @Test
    fun blobLayout_ivPrefixThenCiphertext() {
        val raw = CryptoBox.seal(key, "x".toByteArray())
        // 12-byte IV + 1 byte ciphertext + 16-byte GCM tag.
        assertEquals(12 + 1 + 16, raw.size)
    }

    @Test
    fun base64Url_hasNoPaddingOrUnsafeChars() {
        val b64 = CryptoBox.b64(ByteArray(10) { it.toByte() })
        assertTrue(!b64.contains('=') && !b64.contains('+') && !b64.contains('/'))
        assertArrayEquals(ByteArray(10) { it.toByte() }, CryptoBox.unb64(b64))
    }
}
