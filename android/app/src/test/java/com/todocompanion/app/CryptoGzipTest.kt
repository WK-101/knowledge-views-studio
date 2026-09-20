package com.todocompanion.app

import com.todocompanion.app.data.sync.Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D4 — the sync/backup file crypto now gzips the plaintext before AES-GCM (TCENC4). Robolectric because
 * [Crypto] uses android.util.Base64. Validates the round-trip is lossless, that a wrong passphrase is
 * detectable (not silently corrupt), that the blank-passphrase path stays plain, and that gzip actually
 * shrinks a large repetitive payload (the whole point).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CryptoGzipTest {

    @Test fun gzipRoundTripsLossless() {
        // A realistic, highly-repetitive backup-style JSON — exactly what gzip should crush.
        val plain = buildString {
            append("""{"tasks":[""")
            append((1..500).joinToString(",") { """{"id":"task-$it","title":"Do the thing number $it","done":false,"note":""}""" })
            append("]}")
        }
        val blob = Crypto.encrypt(plain, "correct horse battery staple")
        assertTrue("writes the current TCENC4 marker", blob.startsWith("TCENC4:"))
        assertTrue("Crypto recognises it as encrypted", Crypto.isEncrypted(blob))
        assertNotEquals("actually transformed", plain, blob)
        assertFalse("plaintext not visible in the blob", blob.contains("Do the thing number 250"))
        // The encrypted+base64 blob should be far smaller than the raw JSON thanks to gzip.
        assertTrue("gzip shrinks the payload", blob.length < plain.length / 2)
        assertEquals("decrypts back to the exact original", plain, Crypto.decrypt(blob, "correct horse battery staple"))
    }

    @Test fun wrongPassphraseReturnsNull() {
        val blob = Crypto.encrypt("""{"secret":"value"}""", "right-key")
        assertNull("a wrong passphrase yields null, never garbage", Crypto.decrypt(blob, "wrong-key"))
    }

    @Test fun blankPassphraseStaysPlain() {
        val plain = """{"a":1}"""
        assertEquals("no passphrase → unchanged plain JSON (still importable by any tool)", plain, Crypto.encrypt(plain, ""))
        assertFalse("plain JSON is not flagged encrypted", Crypto.isEncrypted(plain))
        assertEquals("passing plain text through decrypt returns it unchanged", plain, Crypto.decrypt(plain, "anything"))
    }
}
