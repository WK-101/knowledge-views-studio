package com.todocompanion.app

import com.todocompanion.app.util.PortableCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave O — passphrase-encrypted portable backup: round-trip, wrong-passphrase, tamper, envelope check. */
class PortableCryptoTest {

    @Test fun roundTrips() {
        val plain = """{"notes":[{"t":"hi"}],"n":42}"""
        val blob = PortableCrypto.encrypt(plain, "correct horse".toCharArray())
        assertNotEquals(plain, blob)                                  // it's actually encrypted
        assertFalse(blob.contains("hi"))                              // plaintext not visible
        assertEquals(plain, PortableCrypto.decrypt(blob, "correct horse".toCharArray()))
    }

    @Test fun wrongPassphraseReturnsNull() {
        val blob = PortableCrypto.encrypt("secret", "right-pass".toCharArray())
        assertNull(PortableCrypto.decrypt(blob, "wrong-pass".toCharArray()))
    }

    @Test fun tamperedBlobReturnsNull() {
        val blob = PortableCrypto.encrypt("secret data here", "pass1234".toCharArray())
        // Flip a character inside the base64 ciphertext field → GCM tag check fails → null, not garbage.
        val tampered = blob.replace("\"ciphertext\":\"", "\"ciphertext\":\"A")
        assertNull(PortableCrypto.decrypt(tampered, "pass1234".toCharArray()))
    }

    @Test fun envelopeDetection() {
        val blob = PortableCrypto.encrypt("x", "passphrase".toCharArray())
        assertTrue(PortableCrypto.isEnvelope(blob))
        assertFalse(PortableCrypto.isEnvelope("{\"just\":\"some json\"}"))
        assertFalse(PortableCrypto.isEnvelope("not json at all"))
    }

    @Test fun saltMakesTwoEncryptionsDiffer() {
        val a = PortableCrypto.encrypt("same", "pw12345".toCharArray())
        val b = PortableCrypto.encrypt("same", "pw12345".toCharArray())
        assertNotEquals(a, b)                                         // random salt + IV per encryption
        assertEquals("same", PortableCrypto.decrypt(a, "pw12345".toCharArray()))
        assertEquals("same", PortableCrypto.decrypt(b, "pw12345".toCharArray()))
    }
}
