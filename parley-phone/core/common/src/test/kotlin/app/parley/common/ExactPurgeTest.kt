package app.parley.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactPurgeTest {
    @Test fun exact_match_needs_the_same_line() {
        assertTrue(PhoneNumbers.sameExact("+33 6 12 34 56 78", "06 12 34 56 78", "FR"))
        // Same last 9 digits, other country: `same` may say yes; a purge must not.
        assertFalse(PhoneNumbers.sameExact("+44 6 12 34 56 78", "+33 6 12 34 56 78", "FR"))
        assertFalse(PhoneNumbers.sameExact("1234", "01234", null))
        assertTrue(PhoneNumbers.sameExact("1234", "1234", null))
        assertFalse(PhoneNumbers.sameExact("", "", null))
    }

    @Test fun delete_by_number_never_reaches_a_last_digits_collision() {
        // A call-log row with no E.164 form (no country to read it with) against another country's number with the
        // same last 9 digits: the loose match joins them, the one deletions use does not.
        val row = "0612345678"
        val other = "+44 612345678"
        assertTrue(PhoneNumbers.same(row, other, null))
        assertFalse(PhoneNumbers.sameExact(row, other, null))
        // The same line in another format still matches.
        assertTrue(PhoneNumbers.sameExact("06 12 34 56 78", row, null))
        assertTrue(PhoneNumbers.sameExact("+33612345678", "06 12 34 56 78", "FR"))
    }
}
