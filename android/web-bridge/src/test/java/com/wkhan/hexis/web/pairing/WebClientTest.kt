package com.wkhan.hexis.web.pairing

import com.wkhan.hexis.web.crypto.CryptoBox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Context-free client logic: expiry windows and the AEAD key derivation the server relies on. */
class WebClientTest {

    private fun client(expiresAt: Long = 0L) = WebClient(
        id = "abc",
        name = "Laptop",
        keyB64 = CryptoBox.b64(ByteArray(32) { it.toByte() }),
        expiresAt = expiresAt,
    )

    @Test fun neverExpires_whenExpiresAtZero() {
        assertFalse(client(expiresAt = 0L).expired(Long.MAX_VALUE))
    }

    @Test fun expired_onlyAfterTheDeadline() {
        val c = client(expiresAt = 1_000L)
        assertFalse(c.expired(999L))
        assertTrue(c.expired(1_000L))
        assertTrue(c.expired(2_000L))
    }

    @Test fun aeadKey_matchesCryptoBoxHkdf_andIs32Bytes() {
        val c = client()
        val expected = CryptoBox.hkdf(CryptoBox.unb64(c.keyB64), WebClient.AEAD_INFO)
        assertArrayEquals(expected, c.aeadKey())
        assertTrue(c.aeadKey().size == 32)
    }
}
