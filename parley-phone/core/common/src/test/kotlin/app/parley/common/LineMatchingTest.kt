package app.parley.common

import app.parley.common.blocking.PersonalReputation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineMatchingTest {
    /** A French and a Spanish mobile that share their last 9 digits. */
    private val fr = "+33612345678"

    private val es = "+34612345678"

    @Test fun different_countries_sharing_last_9_digits_do_not_match() {
        assertEquals(PhoneNumbers.matchKey(fr), PhoneNumbers.matchKey(es)) // the old key collided
        assertFalse(PhoneNumbers.same(fr, es, "FR"))
        assertFalse(PhoneNumbers.same("06 12 34 56 78", es, "FR"))
        assertNotEquals(PhoneNumbers.lineKey(fr, "FR"), PhoneNumbers.lineKey(es, "FR"))
        assertNotEquals(PhoneNumbers.lineKey("0612345678", "FR"), PhoneNumbers.lineKey(es, "FR"))
        val mine = PhoneNumbers.LineSet(listOf(fr), "FR")
        assertFalse(es in mine)
        assertFalse("0034 612 34 56 78" in mine)
    }

    @Test fun national_and_international_forms_of_one_number_match() {
        for (form in listOf("06 12 34 56 78", "0612345678", "+33 6 12 34 56 78", "0033612345678", "33612345678")) {
            assertTrue(form, PhoneNumbers.same(form, fr, "FR"))
            assertEquals(form, fr, PhoneNumbers.lineKey(form, "FR"))
            assertTrue(form, form in PhoneNumbers.LineSet(listOf("06 12 34 56 78"), "FR"))
        }
        // The SIM country decides how a national number is read.
        assertEquals("+34612345678", PhoneNumbers.lineKey("612 34 56 78", "ES"))
        assertEquals("+14155550123", PhoneNumbers.lineKey("(415) 555-0123", "US"))
    }

    @Test fun fallback_key_only_without_e164() {
        assertEquals("~d112", PhoneNumbers.lineKey("112", "DE"))
        assertEquals("", PhoneNumbers.lineKey("", "DE"))
        // A national number with no known country has no E.164 form: last digits, never equal to an E.164 key.
        assertTrue(PhoneNumbers.lineKey("0612345678", null).startsWith("~"))
        // LineSet follows same(): a probe without E.164 falls back to the last digits.
        assertTrue("0612345678" in PhoneNumbers.LineSet(listOf(fr), null))
        assertFalse("0612345679" in PhoneNumbers.LineSet(listOf(fr), null))
        assertFalse("12" in PhoneNumbers.LineSet(listOf("112"), "DE"))
        assertTrue("112" in PhoneNumbers.LineSet(listOf("112"), "DE"))
        assertTrue(PhoneNumbers.LineSet(emptyList(), "FR").isEmpty)
    }

    @Test fun vault_keys_are_e164_with_suffix_only_as_fallback() {
        assertEquals(listOf("e164:+33612345678"), VaultNumberKeys.stored("06 12 34 56 78", "FR"))
        assertEquals(listOf("e164:+33612345678"), VaultNumberKeys.stored("+33 6 12 34 56 78", "DE"))
        // A foreign caller with the same last 9 digits is looked up by its own E.164 first.
        assertEquals("e164:+34612345678", VaultNumberKeys.lookup(es, "FR").first())
        assertTrue(VaultNumberKeys.stored(fr, "FR").none { it in VaultNumberKeys.lookup(es, "FR", exact = true) })
        // Short codes can't be E.164: the digits are the key.
        assertEquals(listOf("112"), VaultNumberKeys.stored("112", "DE"))
        assertEquals(listOf("112"), VaultNumberKeys.lookup("112", "DE"))
        // Exact lookups (private-name provider) never use the last digits.
        assertEquals(listOf("e164:+33612345678"), VaultNumberKeys.lookup("0612345678", "FR", exact = true))
        assertEquals(emptyList<String>(), VaultNumberKeys.lookup("112", "DE", exact = true))
        assertEquals(listOf("e164:+33612345678"), VaultNumberKeys.storedAll(listOf("0612345678", "+33612345678"), "FR"))
    }

    @Test fun reputation_groups_by_line() {
        val h = 3_600_000L
        val now = 100 * h
        fun call(n: String, t: Long) = CallEntry(t, n, null, CallType.REJECTED, t, 0, null, false, false)
        // One rejection each from two different countries sharing the last 9 digits: neither reaches the threshold.
        val calls = listOf(call(fr, now - 5 * h), call(es, now - 4 * h))
        assertTrue(PersonalReputation.suggestions(calls, now, countryOf = { "FR" }) { false }.isEmpty())
        // National and international forms of one number are one caller.
        val same = listOf(call("0612345678", now - 5 * h), call(fr, now - 4 * h))
        assertEquals(1, PersonalReputation.suggestions(same, now, countryOf = { "FR" }) { false }.size)
    }
}
