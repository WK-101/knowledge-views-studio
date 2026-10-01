package app.parley.common

import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.ExpectedWindow
import app.parley.common.spam.RepReason
import app.parley.common.spam.RepSignal
import app.parley.common.spam.Reputation
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

    // H1: an automatic expected-call window comes after rules, lists and the sales-line silence, and only lets an
    // unknown caller past "who may ring" toggles.
    private val now = 1_790_000_000_000L
    private val clock = PolicyClock.of(now)
    private val note = ExpectedWindow(now - 1, now + 3_600_000, ExpectedSource.NOTE, "note:i1")

    private fun decide(f: IncomingCallFacts, rules: List<BlockRule>, s: ScreeningSettings) = CallPolicy.decide(f, rules, s, clock)

    @Test fun an_expected_window_lets_unknown_callers_past_who_may_ring_toggles() {
        val withNote = ScreeningSettings(blockNonContacts = true, expected = listOf(note))
        assertEquals(AllowReason.SNOOZE, decide(facts("+33699999999"), emptyList(), withNote).allowedBy)
        val offHours = ScreeningSettings(offHours = OffHours(enabled = true, schedule = Schedule(Schedule.ALL_DAYS, 0, 0)), expected = listOf(note))
        assertEquals(AllowReason.SNOOZE, decide(facts("+33699999999"), emptyList(), offHours).allowedBy)
        val hidden = ScreeningSettings(blockHidden = true, expected = listOf(note))
        assertEquals(AllowReason.SNOOZE, decide(facts(null, hidden = true), emptyList(), hidden).allowedBy)
    }

    @Test fun an_expected_window_never_overrides_a_rule_a_range_rule_or_a_list() {
        val s = ScreeningSettings(blockNonContacts = true, expected = listOf(note))
        val rule = BlockRule(pattern = "+33699999999", type = RuleType.EXACT)
        val r = decide(facts("+33699999999"), listOf(rule), s)
        assertTrue(r.decision is Decision.Block && (r.decision as Decision.Block).reason == BlockReason.RULE)
        // "Block this range" (a prefix rule), whether it silences or rejects.
        val range = BlockRule(pattern = "+336999", type = RuleType.PREFIX, action = BlockAction.SILENCE)
        assertTrue(decide(facts("+33699999999"), listOf(range), s).blocked)
        // A spam list that blocks.
        val hit = ListHit("p", "Pack", "Scam", score = 90, mode = ListMode.BLOCK)
        val listed = decide(facts("+33699999999").copy(listHits = listOf(hit)), emptyList(), s)
        assertTrue(listed.decision is Decision.Block && (listed.decision as Decision.Block).reason == BlockReason.LIST)
        // The sales-line silence.
        val sales = Reputation(80, listOf(RepReason(RepSignal.SHORT_RINGS, 3, 40), RepReason(RepSignal.NEVER_ANSWERED, 3, 40)))
        val rep = decide(facts("+33699999999").copy(reputation = sales), emptyList(), s.copy(silenceSalesLines = true))
        assertTrue(rep.decision is Decision.Block && (rep.decision as Decision.Block).reason == BlockReason.PERSONAL_REPUTATION)
        // Scam signals stay too.
        val spoof = decide(facts("+33612349999"), emptyList(), ScreeningSettings(blockNeighbourSpoofing = true, expected = listOf(note)))
        assertTrue(spoof.blocked)
    }

    @Test fun expecting_a_call_by_hand_keeps_its_place_before_rules_and_lists() {
        val s = ScreeningSettings(snoozeUntil = now + 60_000)
        val rule = BlockRule(pattern = "+33699999999", type = RuleType.EXACT)
        assertEquals(AllowReason.SNOOZE, decide(facts("+33699999999"), listOf(rule), s).allowedBy)
        val hit = ListHit("p", "Pack", "Scam", score = 90, mode = ListMode.BLOCK)
        assertEquals(AllowReason.SNOOZE, decide(facts("+33699999999").copy(listHits = listOf(hit)), emptyList(), s).allowedBy)
    }

    @Test fun a_window_for_one_line_lets_only_that_line_ring() {
        val callNote = note.copy(key = "call:n3", number = "+33611111111")
        val s = ScreeningSettings(blockNonContacts = true, expected = listOf(callNote))
        assertEquals(AllowReason.SNOOZE, decide(facts("06 11 11 11 11"), emptyList(), s).allowedBy)
        assertTrue(decide(facts("+33699999999"), emptyList(), s).blocked)
        assertTrue(decide(facts(null, hidden = true), emptyList(), s.copy(blockHidden = true)).blocked)
    }
}
