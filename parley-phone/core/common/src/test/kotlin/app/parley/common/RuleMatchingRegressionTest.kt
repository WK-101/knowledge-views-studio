package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bugs found in other blockers (Call Blocker, SpamBlocker) that Parley must never have. */
class RuleMatchingRegressionTest {

    private fun rule(p: String, t: RuleType) = BlockRule(pattern = p, type = t)

    // Call Blocker: "+52 1…" never matched.
    @Test fun mexico_legacy_mobile_one_matches_exact_rules_in_every_form() {
        val forms = listOf("+52 1 55 1234 5678", "+525512345678", "55 1234 5678", "044 55 1234 5678", "5215512345678", "01 55 1234 5678")
        for (ruleForm in forms) for (incoming in forms) {
            assertTrue("$ruleForm vs $incoming", CallPolicy.ruleMatches(rule(ruleForm, RuleType.EXACT), incoming, "MX"))
        }
        assertFalse(CallPolicy.ruleMatches(rule("+52 1 55 1234 5678", RuleType.EXACT), "+525512345679", "MX"))
    }

    @Test fun mexico_prefix_rules_in_every_form() {
        val incoming = listOf("+52 1 444 123 4567", "+524441234567", "444 123 4567", "045 444 123 4567")
        for (p in listOf("+52 1 444", "+52444", "444", "044 444", "01 444")) for (n in incoming) {
            assertTrue("$p vs $n", CallPolicy.ruleMatches(rule(p, RuleType.PREFIX), n, "MX"))
        }
        assertFalse(CallPolicy.ruleMatches(rule("+52 1 444", RuleType.PREFIX), "+525512345678", "MX"))
    }

    @Test fun mexico_e164_canonical() {
        assertEquals("+525512345678", PhoneNumbers.toE164("+52 1 55 1234 5678", "MX"))
        assertEquals("+525512345678", PhoneNumbers.toE164("044 55 1234 5678", "MX"))
        assertEquals("+525512345678", PhoneNumbers.toE164("5512345678", "MX"))
        assertEquals("+525512345678", PhoneNumbers.toE164("+52 1 55 1234 5678", "US"))
    }

    // Call Blocker: stored entries were never normalised.
    @Test fun rule_saved_with_spaces_and_brackets() {
        val checked = RuleTools.check(" (06) 99-99.99 99 ", RuleType.EXACT, "FR")
        assertNull(checked.error)
        assertEquals("+33699999999", checked.pattern)
        assertTrue(CallPolicy.ruleMatches(rule(checked.pattern, RuleType.EXACT), "06 99 99 99 99", "FR"))
        val prefix = RuleTools.check("(0162)", RuleType.PREFIX, "FR")
        assertEquals("0162", prefix.pattern)
        assertTrue(CallPolicy.ruleMatches(rule(prefix.pattern, RuleType.PREFIX), "+33 1 62 00 00 00", "FR"))
        // Unnormalised stored rules still match.
        assertTrue(CallPolicy.ruleMatches(rule(" (01) 62 ", RuleType.PREFIX), "0162000000", "FR"))
    }

    // Call Blocker: user-typed _ and % acted as SQL wildcards.
    @Test fun underscore_and_percent_are_literal() {
        assertFalse(CallPolicy.ruleMatches(rule("0800_%", RuleType.PREFIX), "0800123456", "FR"))
        assertFalse(CallPolicy.ruleMatches(rule("%", RuleType.WILDCARD), "0800123456", "FR"))
        assertFalse(CallPolicy.ruleMatches(rule("06_9999999", RuleType.EXACT), "0619999999", "FR"))
        assertNotNull(RuleTools.check("0800_%", RuleType.PREFIX, "FR").error)
        assertNotNull(RuleTools.check("%", RuleType.WILDCARD, "FR").error)
    }

    @Test fun forwarded_a_and_b_numbers() {
        val n = "+33612345678&+33699999999"
        assertTrue(CallPolicy.ruleMatches(rule("+33699999999", RuleType.EXACT), n, "FR"))
        assertTrue(CallPolicy.ruleMatches(rule("0612", RuleType.PREFIX), n, "FR"))
        assertFalse(CallPolicy.ruleMatches(rule("0700", RuleType.PREFIX), n, "FR"))
        // The digits must never be glued together into one long number.
        assertFalse(CallPolicy.ruleMatches(rule("+3361234567833", RuleType.PREFIX), n, "FR"))
        assertEquals(listOf("+33612345678", "+33699999999"), PhoneNumbers.forwardedParts(n))
    }

    // SpamBlocker: stripping every leading zero merged "00" with the trunk "0".
    @Test fun double_zero_is_not_the_trunk_zero() {
        val uk = rule("0044 20", RuleType.PREFIX)
        assertTrue(CallPolicy.ruleMatches(uk, "+44 20 7946 0000", "FR"))
        assertTrue(CallPolicy.ruleMatches(uk, "0044 20 7946 0000", "FR"))
        // French national 04 42… is not a UK number.
        assertFalse(CallPolicy.ruleMatches(uk, "04 42 00 00 00", "FR"))
        assertFalse(CallPolicy.ruleMatches(rule("044", RuleType.PREFIX), "0044 20 7946 0000", "FR"))
        assertEquals("+4420", RuleTools.check("0044 20", RuleType.PREFIX, "FR").pattern)
    }

    @Test fun foreign_country_code_warning_and_preview() {
        val c = RuleTools.check("+44 20", RuleType.PREFIX, "FR")
        assertTrue(c.warnings.any { it.contains("+44") })
        val preview = RuleTools.preview(rule("+52 444", RuleType.PREFIX), "MX")
        assertEquals(listOf("+52444…", "444…", "0444…", "00 52444…"), preview)
        val national = RuleTools.preview(rule("0162", RuleType.PREFIX), "FR")
        assertTrue(national.toString(), national.contains("+33 162…"))
    }

    @Test fun wildcard_needs_pattern_type() {
        assertNotNull(RuleTools.check("0800*", RuleType.PREFIX, "FR").error)
        assertNull(RuleTools.check("0800*", RuleType.WILDCARD, "FR").error)
        assertEquals("+4420*", RuleTools.check("0044 20*", RuleType.WILDCARD, "FR").pattern)
    }
}
