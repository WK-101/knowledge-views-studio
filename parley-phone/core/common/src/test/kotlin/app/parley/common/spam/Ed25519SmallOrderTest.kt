package app.parley.common.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small-order keys and R values are refused, so no single signature passes for every message. */
class Ed25519SmallOrderTest {
    /** The identity point (x = 0, y = 1), encoded. */
    private val identity = ByteArray(32).also { it[0] = 1 }

    @Test fun the_identity_key_with_an_identity_r_verifies_nothing() {
        val sig = identity + ByteArray(32)
        for (m in listOf("a", "anything at all", "")) {
            assertFalse(Ed25519.verifyPure(identity, m.toByteArray(), sig))
            assertFalse(Ed25519.verify(identity, m.toByteArray(), sig))
        }
    }

    @Test fun small_order_points_are_recognised() {
        assertTrue(Ed25519.smallOrder(identity))
        // The point of order 2: x = 0, y = -1.
        val minusOne = java.math.BigInteger.ONE.shiftLeft(255).subtract(java.math.BigInteger.valueOf(20))
        val le = minusOne.toByteArray().reversedArray().copyOf(32)
        assertTrue(Ed25519.smallOrder(le))
        val pub = Ed25519.publicKey(ByteArray(32) { it.toByte() })
        assertFalse(Ed25519.smallOrder(pub))
    }

    @Test fun real_signatures_still_verify_and_fingerprints_are_128_bits() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        val pub = Ed25519.publicKey(secret)
        val sig = Ed25519.sign(secret, "hello".toByteArray())
        assertTrue(Ed25519.verify(pub, "hello".toByteArray(), sig))
        assertTrue(Ed25519.verifyPure(pub, "hello".toByteArray(), sig))
        assertEquals(32, Ed25519.fingerprint(pub).replace(" ", "").length)
    }
}
