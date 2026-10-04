package app.parley.common

import app.parley.common.history.NumberKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneIdentityTest {

    /** One row per pair of numbers: which rules call them one line. */
    private data class Row(val a: String, val b: String, val region: String?, val same: Boolean, val exact: Boolean, val sameKey: Boolean)

    private val table = listOf(
        // Same French mobile written three ways: one line under every rule.
        Row("+33 6 12 34 56 78", "06 12 34 56 78", "FR", same = true, exact = true, sameKey = true),
        Row("0033612345678", "+33612345678", "FR", same = true, exact = true, sameKey = true),
        // Two countries sharing the last 9 digits: never the same line when both have an E.164 form.
        Row("+33612345678", "+34612345678", "FR", same = false, exact = false, sameKey = false),
        Row("+14155550123", "+44 4155550123", null, same = false, exact = false, sameKey = false),
        // Short codes compare by every digit.
        Row("112", "112", "DE", same = true, exact = true, sameKey = true),
        Row("112", "1112", "DE", same = false, exact = false, sameKey = false),
        // Without a region, a national number has no E.164 form: it matches the international one loosely (same
        // line by its last digits), never exactly, and has a different stored key.
        Row("+33612345678", "0612345678", null, same = true, exact = false, sameKey = false),
        // Legacy Mexican mobile form folds into the current one.
        Row("+52 1 55 1234 5678", "+52 55 1234 5678", null, same = true, exact = true, sameKey = true),
    )

    @Test fun equivalence_table() {
        for (r in table) {
            assertEquals("same $r", r.same, PhoneIdentity.same(r.a, r.b, r.region))
            assertEquals("same is symmetric $r", r.same, PhoneIdentity.same(r.b, r.a, r.region))
            assertEquals("sameExact $r", r.exact, PhoneIdentity.sameExact(r.a, r.b, r.region))
            assertEquals("key $r", r.sameKey, PhoneIdentity.key(r.a, r.region) == PhoneIdentity.key(r.b, r.region))
            // The containers agree with same().
            assertEquals("LineSet $r", r.same, r.b in PhoneIdentity.LineSet(listOf(r.a), r.region))
            val map = PhoneIdentity.LineMap<String>(r.region).apply { putIfAbsent(r.a, "A") }
            assertEquals("LineMap $r", r.same, map[r.b] == "A")
            // Equal stored keys always mean the same line.
            if (r.sameKey) assertTrue("key implies same $r", r.same)
            // Exact is never looser than same.
            if (r.exact) assertTrue("exact implies same $r", r.same)
        }
    }

    @Test fun key_forms() {
        assertEquals("+33612345678", PhoneIdentity.key("06 12 34 56 78", "FR"))
        assertEquals("~d112", PhoneIdentity.key("112", "FR"))
        assertEquals("", PhoneIdentity.key("", "FR"))
        assertEquals("", PhoneIdentity.key(null, "FR"))
        assertTrue(PhoneIdentity.key("0612345678", null).startsWith("~"))
    }

    @Test fun exact_key_matches_history_keys() {
        assertEquals("+33612345678", PhoneIdentity.exactKey("06 12 34 56 78", "FR"))
        assertEquals("#*100#", PhoneIdentity.exactKey("*100#", "IN"))
        assertNull(PhoneIdentity.exactKey(" ", "FR"))
        assertEquals(NumberKeys.HIDDEN, NumberKeys.of("", "FR"))
        assertEquals(PhoneIdentity.exactKey("0612345678", "FR"), NumberKeys.of("0612345678", "FR"))
    }

    @Test fun portable_key_needs_seven_digits() {
        assertEquals("612345678", PhoneIdentity.portableKey("+33 6 12 34 56 78"))
        assertEquals(PhoneIdentity.portableKey("06 12 34 56 78"), PhoneIdentity.portableKey("+33612345678"))
        assertNull(PhoneIdentity.portableKey("112"))
        assertNull(PhoneIdentity.portableKey(null))
    }

    @Test fun legacy_keys_stay_readable() {
        val n = "06 12 34 56 78"
        assertEquals(listOf("+33612345678", "612345678"), PhoneIdentity.lookupKeys(n, "FR"))
        assertTrue(PhoneIdentity.isLegacyKey("612345678"))
        assertFalse(PhoneIdentity.isLegacyKey("+33612345678"))
        assertFalse(PhoneIdentity.isLegacyKey("~d112"))
        assertFalse(PhoneIdentity.isLegacyKey(""))
        assertTrue(PhoneIdentity.matchesStored("612345678", n, "FR"))
        assertTrue(PhoneIdentity.matchesStored("+33612345678", n, "FR"))
        assertFalse(PhoneIdentity.matchesStored("+34612345678", n, "FR"))
        // A short number's key and legacy key are one lookup.
        assertEquals(listOf("~d112", "112"), PhoneIdentity.lookupKeys("112", "FR"))
    }

    @Test fun call_row_key_ignores_format_and_milliseconds() {
        assertEquals(PhoneIdentity.callRowKey("+33612345678", 1_700_000_000_123), PhoneIdentity.callRowKey("06 12 34 56 78", 1_700_000_000_999))
        assertEquals(NumberKeys.dedupe("0612345678", 5_000), PhoneIdentity.callRowKey("0612345678", 5_000))
    }

    @Test fun key_set_reads_stored_keys_as_lines() {
        val set = PhoneIdentity.KeySet(listOf("+33612345678", PhoneIdentity.key("0700 000", null), "511122233"), "FR")
        assertTrue("06 12 34 56 78" in set)
        assertTrue("0033 6 12 34 56 78" in set)
        assertFalse("+34612345678" in set)
        // A fallback key ("~d…"/"~k…") holds letters: it matches by the digits, never as a dialled word.
        assertTrue("0700000" in set)
        // An old last-digits key still finds its number.
        assertTrue("+44 511 122 233" in set)
        assertFalse(null in set)
        // A fallback key of a long number: "~k" plus its last digits.
        val long = PhoneIdentity.KeySet(listOf(PhoneIdentity.key("612345678", null)), null)
        assertTrue("612345678" in long)
        assertTrue("+33612345678" in long)
        assertFalse("+33612345679" in long)
    }

    @Test fun line_map_first_value_wins() {
        val m = PhoneIdentity.LineMap<Int>("FR")
        m.putIfAbsent("06 12 34 56 78", 1)
        m.putIfAbsent("+33612345678", 2)
        assertEquals(1, m["0033612345678"])
        assertNull(m["+34612345678"])
        assertNull(m[""])
        assertFalse(m.isEmpty)
    }

    @Test fun known_set_keeps_contacts_saved_in_another_countrys_national_format() {
        // A German mobile saved nationally on a phone set to France reads as a French E.164 form.
        val contacts = listOf("0171 1234567", "06 12 34 56 78")
        assertFalse("+491711234567" in PhoneIdentity.LineSet(contacts, "FR"))
        val known = PhoneIdentity.KnownSet(contacts, "FR")
        assertTrue("+491711234567" in known)
        assertTrue("+33 6 12 34 56 78" in known)
        assertTrue("0612345678" in known)
        assertFalse("+33699999999" in known)
        assertFalse("112" in known)
    }

    @Test fun sameLineAnyRegionReadsANationalNumberInTheOtherOnesCountry() {
        // Saved in Germany, compared while roaming in France.
        assertTrue(PhoneIdentity.sameLineAnyRegion("030 1234567", "+49 30 1234567", "FR"))
        assertTrue(PhoneIdentity.sameLineAnyRegion("+49 30 1234567", "030 1234567", "FR"))
        assertTrue(PhoneIdentity.sameLineAnyRegion("030 1234567", "030-123 4567", "FR"))
        // Another line, a national number of another country, or nothing at all: never the same.
        assertFalse(PhoneIdentity.sameLineAnyRegion("030 1234568", "+49 30 1234567", "FR"))
        assertFalse(PhoneIdentity.sameLineAnyRegion("01 23 45 67 89", "+49 30 1234567", "FR"))
        assertFalse(PhoneIdentity.sameLineAnyRegion("", "+49 30 1234567", "DE"))
        assertFalse(PhoneIdentity.sameLineAnyRegion(null, null, "DE"))
    }
}
