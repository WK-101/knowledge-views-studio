package app.parley.common.security

import org.junit.Assert.assertEquals
import org.junit.Test

class CertDigestTest {
    @Test fun a_sha256_shows_as_colon_separated_pairs() {
        val hex = "ab".repeat(31) + "0f"
        val shown = CertDigest.shown(hex)
        assertEquals(32, shown.split(":").size)
        assertEquals("AB:AB", shown.take(5))
        assertEquals("0F", shown.takeLast(2))
    }

    @Test fun anything_else_shows_as_it_is() {
        assertEquals("not hex", CertDigest.shown("not hex"))
        assertEquals("abcd", CertDigest.shown("abcd"))
    }
}
