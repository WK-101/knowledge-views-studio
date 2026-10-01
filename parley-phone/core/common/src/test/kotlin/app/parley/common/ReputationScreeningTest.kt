package app.parley.common

import app.parley.common.spam.RepReason
import app.parley.common.spam.RepSignal
import app.parley.common.spam.Reputation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/** I2: personal reputation in the screening precedence (a tag by default, a soft silence when asked). */
class ReputationScreeningTest {
    private val clock = PolicyClock(1_700_000_000_000L, DayOfWeek.MONDAY, 12 * 60)
    private val n = "+33612345601"
    private val sales = Reputation(
        60, listOf(RepReason(RepSignal.SHORT_RINGS, 2, 30), RepReason(RepSignal.NEVER_ANSWERED, 3, 20), RepReason(RepSignal.NO_VOICEMAIL, 3, 10)),
    )
    private val mild = Reputation(30, listOf(RepReason(RepSignal.NEVER_ANSWERED, 2, 20), RepReason(RepSignal.NO_VOICEMAIL, 2, 10)))

    private fun facts(rep: Reputation? = sales, contact: Boolean = false) =
        IncomingCallFacts(number = n, hidden = false, isContact = contact, countryIso = "FR", reputation = rep)

    private fun decide(f: IncomingCallFacts, s: ScreeningSettings = ScreeningSettings(), rules: List<BlockRule> = emptyList()) =
        CallPolicy.decide(f, rules, s, clock)

    @Test fun tag_only_by_default() {
        val r = decide(facts())
        assertEquals(Decision.Allow, r.decision)
        assertEquals(sales, r.reputation)
        assertTrue(r.trace.any { it.check == "Your calls" && it.result == "looks like a sales line, tag only" })
    }

    @Test fun silences_when_the_rule_is_on() {
        val r = decide(facts(), ScreeningSettings(silenceSalesLines = true))
        val b = r.decision as Decision.Block
        assertEquals(BlockAction.SILENCE, b.action)
        assertEquals(BlockReason.PERSONAL_REPUTATION, b.reason)
        assertNotNull(r.reputation)
        assertTrue(r.trace.any { it.check == "Your calls" && it.mark == TraceMark.MATCH })
    }

    @Test fun learn_from_calls_off_means_nothing() {
        val r = decide(facts(), ScreeningSettings(learnFromCalls = false, silenceSalesLines = true))
        assertEquals(Decision.Allow, r.decision)
        assertNull(r.reputation)
    }

    @Test fun below_the_threshold_nothing_happens() {
        val r = decide(facts(mild), ScreeningSettings(silenceSalesLines = true))
        assertEquals(Decision.Allow, r.decision)
        assertNull(r.reputation)
    }

    @Test fun contacts_win() {
        val r = decide(facts(contact = true), ScreeningSettings(silenceSalesLines = true))
        assertEquals(AllowReason.CONTACT, r.allowedBy)
        assertNull(r.reputation)
    }

    @Test fun allow_rules_win_and_drop_the_tag() {
        val allow = BlockRule(id = 2, pattern = n, type = RuleType.EXACT, kind = RuleKind.ALLOW)
        val r = decide(facts(), ScreeningSettings(silenceSalesLines = true), listOf(allow))
        assertEquals(AllowReason.RULE, r.allowedBy)
        assertNull(r.reputation)
    }

    @Test fun expecting_a_call_wins() {
        val r = decide(facts(), ScreeningSettings(silenceSalesLines = true, snoozeUntil = clock.millis + 60_000))
        assertEquals(AllowReason.SNOOZE, r.allowedBy)
        assertNull(r.reputation)
    }

    @Test fun emergency_numbers_are_never_tagged() {
        val r = decide(facts().copy(isEmergency = true), ScreeningSettings(silenceSalesLines = true))
        assertEquals(AllowReason.EMERGENCY, r.allowedBy)
        assertNull(r.reputation)
    }

    @Test fun block_rules_and_lists_come_first() {
        val block = BlockRule(id = 1, pattern = n, type = RuleType.EXACT)
        assertEquals(BlockReason.RULE, (decide(facts(), ScreeningSettings(silenceSalesLines = true), listOf(block)).decision as Decision.Block).reason)
        val hit = ListHit("p", "FTC", "Robocall", 90, ListMode.BLOCK, 50)
        val listed = decide(facts().copy(listHits = listOf(hit)), ScreeningSettings(silenceSalesLines = true))
        assertEquals(BlockReason.LIST, (listed.decision as Decision.Block).reason)
    }

    @Test fun a_repeat_caller_still_rings() {
        val f = facts().copy(history = listOf(PastCall(clock.millis - 90_000, outgoing = false, durationSec = 0)))
        val r = decide(f, ScreeningSettings(silenceSalesLines = true))
        assertEquals(AllowReason.REPEAT, r.allowedBy)
        // P1: it says why it rang.
        assertEquals(RangThroughKind.REPEAT_CALLER, r.rangThrough?.kind)
    }

    @Test fun soft_reason() {
        assertTrue(BlockReason.PERSONAL_REPUTATION.soft)
    }
}
