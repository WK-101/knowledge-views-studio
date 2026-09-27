package app.parley.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultKeysFallbackTest {
    @Test fun stored_rows_include_a_last_digits_fallback() {
        val rows = VaultNumberKeys.storedWithFallback(listOf("06 12 34 56 78"), "FR")
        assertTrue(VaultNumberKeys.E164_PREFIX + "+33612345678" in rows)
        assertTrue(PhoneNumbers.matchKey("0612345678") in rows)
        // A call read under another region still finds a row through the non-exact lookup, never the exact one.
        val roaming = VaultNumberKeys.lookup("06 12 34 56 78", "DE")
        assertTrue(roaming.any { it in rows })
        assertFalse(VaultNumberKeys.lookup("06 12 34 56 78", "DE", exact = true).any { it in rows })
    }
}
