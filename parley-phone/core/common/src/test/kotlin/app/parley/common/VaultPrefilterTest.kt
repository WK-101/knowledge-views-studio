package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The private-call sweep's prefilter never skips a call that caller ID would recognise: it matches on the same keys
 * (E.164, the older E.164 form, the last digits), not on the last digits alone.
 */
class VaultPrefilterTest {
    /** What the vault holds for a number saved in [savedRegion] (after re-keying), and the lookup caller ID makes. */
    private fun vaultFinds(saved: String, savedRegion: String, incoming: String, phoneRegion: String): Boolean {
        val table = VaultNumberKeys.storedWithPrevious(listOf(saved), savedRegion).toSet()
        return VaultNumberKeys.lookup(incoming, phoneRegion).any { it in table }
    }

    private fun passes(saved: String, savedRegion: String?, incoming: String, phoneRegion: String): Boolean =
        VaultNumberKeys.Prefilter(listOf(listOf(saved) to savedRegion), phoneRegion).mayMatch(incoming)

    @Test fun an_argentine_mobile_saved_nationally_passes_in_its_international_form() {
        // Its last 9 digits differ (523456789 against 123456789): the old last-digits prefilter dropped this call.
        val saved = "011 15 2345 6789"
        val incoming = "+54 9 11 2345 6789"
        assertTrue(PhoneIdentity.legacyKey(saved) != PhoneIdentity.legacyKey(incoming))
        assertTrue(vaultFinds(saved, "AR", incoming, "AR"))
        assertTrue(passes(saved, "AR", incoming, "AR"))
        // Also for an entry saved before the region was kept (the phone's region was used).
        assertTrue(passes(saved, null, incoming, "AR"))
        // And the other way round: saved internationally, the call logged in the national form.
        assertTrue(passes(incoming, "AR", saved, "AR"))
        assertTrue(passes("0351 15 123 4567", "AR", "+54 9 351 123 4567", "AR"))
    }

    @Test fun trunk_and_mobile_prefix_spellings_pass() {
        val pairs = listOf(
            Triple("GB", "07700 900123", "+44 7700 900123"),
            Triple("FR", "06 12 34 56 78", "+33 6 12 34 56 78"),
            Triple("RU", "8 912 345 67 89", "+7 912 345 67 89"),
            Triple("HU", "06 30 123 4567", "+36 30 123 4567"),
            Triple("MX", "044 55 1234 5678", "+52 1 55 1234 5678"),
            Triple("MX", "+52 1 55 1234 5678", "+52 55 1234 5678"),
            Triple("BR", "0 21 11 91234 5678", "+55 11 91234 5678"),
            Triple("SK", "0912 123 456", "+421 912 123 456"),
            Triple("CI", "01 23 45 6789", "+225 01 23 45 6789"),
            Triple("IT", "06 1234 5678", "+39 06 1234 5678"),
        )
        for ((region, saved, incoming) in pairs) {
            assertTrue("$region $saved / $incoming", passes(saved, region, incoming, region))
            assertTrue("$region $incoming / $saved", passes(incoming, region, saved, region))
        }
    }

    @Test fun whatever_the_vault_would_find_passes() {
        val numbers = listOf(
            "AR" to "011 15 2345 6789", "AR" to "+54 9 11 2345 6789", "AR" to "11 2345 6789", "AR" to "0351 15 123 4567",
            "BR" to "(11) 91234-5678", "MX" to "01 55 1234 5678", "SK" to "0912 123 456", "CI" to "01 23 45 6789",
            "FR" to "06 12 34 56 78", "FR" to "3631", "US" to "555-0123", "GB" to "07700 900123", "DE" to "0151 23456789",
        )
        for ((r1, saved) in numbers) for ((r2, incoming) in numbers) {
            if (vaultFinds(saved, r1, incoming, r2)) assertTrue("$saved ($r1) / $incoming ($r2)", passes(saved, r1, incoming, r2))
        }
    }

    @Test fun other_numbers_are_left_alone() {
        assertFalse(passes("011 15 2345 6789", "AR", "+33 6 12 34 56 78", "AR"))
        assertFalse(passes("06 12 34 56 78", "FR", "06 12 34 56 79", "FR"))
        assertTrue(VaultNumberKeys.Prefilter(emptyList(), "FR").isEmpty)
    }

    @Test fun re_keying_keeps_the_older_form_once() {
        val rows = VaultNumberKeys.storedWithPrevious(listOf("011 15 2345 6789"), "AR")
        assertEquals(
            listOf("e164:+5491123456789", "523456789", "e164:+54111523456789"),
            rows,
        )
        // A number whose form didn't change gets no extra row.
        assertEquals(VaultNumberKeys.storedWithFallback(listOf("06 12 34 56 78"), "FR"), VaultNumberKeys.storedWithPrevious(listOf("06 12 34 56 78"), "FR"))
    }
}
