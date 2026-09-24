package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPolicyTest {
    private fun facts(n: String?, contact: Boolean = false, hidden: Boolean = false) =
        IncomingCallFacts(number = n, hidden = hidden, isContact = contact, countryIso = "FR", ownNumbers = listOf("+33612345678"))

    @Test fun contacts_always_allowed() {
        val s = ScreeningSettings(blockNonContacts = true)
        assertEquals(Decision.Allow, CallPolicy.evaluate(facts("+33700000000", contact = true), emptyList(), s))
    }

    @Test fun hidden_numbers() {
        assertEquals(Decision.Allow, CallPolicy.evaluate(facts(null, hidden = true), emptyList(), ScreeningSettings()))
        val d = CallPolicy.evaluate(facts(null, hidden = true), emptyList(), ScreeningSettings(blockHidden = true))
        assertTrue(d is Decision.Block && d.reason == BlockReason.HIDDEN)
    }

    @Test fun prefix_rule_matches_international_and_national() {
        val rule = BlockRule(pattern = "+33162", type = RuleType.PREFIX)
        assertTrue(CallPolicy.ruleMatches(rule, "0162123456", "FR"))
        assertTrue(CallPolicy.ruleMatches(rule, "+33 1 62 12 34 56", "FR"))
        val national = BlockRule(pattern = "0162", type = RuleType.PREFIX)
        assertTrue(CallPolicy.ruleMatches(national, "+33162123456", "FR"))
        assertFalse(CallPolicy.ruleMatches(national, "+33163123456", "FR"))
    }

    @Test fun wildcard_rule() {
        val rule = BlockRule(pattern = "+1 855*", type = RuleType.WILDCARD)
        assertTrue(CallPolicy.ruleMatches(rule, "+18551234567", "US"))
        assertTrue(CallPolicy.ruleMatches(rule, "(855) 123-4567", "US"))
        assertFalse(CallPolicy.ruleMatches(rule, "+18661234567", "US"))
        val q = BlockRule(pattern = "0800??????", type = RuleType.WILDCARD)
        assertTrue(CallPolicy.ruleMatches(q, "0800123456", "FR"))
    }

    @Test fun exact_rule_uses_normalisation() {
        val rule = BlockRule(pattern = "06 99 99 99 99", type = RuleType.EXACT, action = BlockAction.SILENCE)
        val d = CallPolicy.evaluate(facts("+33699999999"), listOf(rule), ScreeningSettings())
        assertTrue(d is Decision.Block && d.action == BlockAction.SILENCE)
    }

    @Test fun disabled_rule_ignored() {
        val rule = BlockRule(pattern = "+33699999999", type = RuleType.EXACT, enabled = false)
        assertEquals(Decision.Allow, CallPolicy.evaluate(facts("+33699999999"), listOf(rule), ScreeningSettings()))
    }

    @Test fun neighbour_spoofing() {
        val d = CallPolicy.evaluate(facts("+33612349999"), emptyList(), ScreeningSettings(blockNeighbourSpoofing = true))
        assertTrue(d is Decision.Block && d.reason == BlockReason.NEIGHBOUR_SPOOF)
    }

    @Test fun emergency_never_blocked() {
        val f = facts("112").copy(isEmergency = true)
        assertEquals(Decision.Allow, CallPolicy.evaluate(f, listOf(BlockRule(pattern = "*", type = RuleType.WILDCARD)), ScreeningSettings(blockNonContacts = true)))
    }
}
