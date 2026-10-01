package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

class RangThroughTest {
    private val now = 1_700_000_000_000L
    private val monNoon = PolicyClock(now, DayOfWeek.MONDAY, 12 * 60)
    private val caller = "+33699999999"
    private val strict = ScreeningSettings(blockNonContacts = true)

    private fun facts(n: String? = caller, contact: Boolean = false) =
        IncomingCallFacts(number = n, hidden = n == null, isContact = contact, countryIso = "FR", ownNumbers = listOf("+33612345678"))

    private fun decide(f: IncomingCallFacts = facts(), rules: List<BlockRule> = emptyList(), s: ScreeningSettings = strict) =
        CallPolicy.decide(f, rules, s, monNoon)

    @Test fun repeat_caller_says_how_often_and_how_fast() {
        val r = decide(facts().copy(blockedAttempts = listOf(now - 150_000)))
        assertEquals(AllowReason.REPEAT, r.allowedBy)
        assertEquals(RangThrough(RangThroughKind.REPEAT_CALLER, calls = 2, minutes = 3), r.rangThrough)
        val three = decide(facts().copy(blockedAttempts = listOf(now - 60_000, now - 170_000)))
        assertEquals(3, three.rangThrough?.calls)
    }

    @Test fun expecting_a_call_only_when_it_made_the_difference() {
        val snooze = strict.copy(snoozeUntil = now + 3_600_000)
        assertEquals(RangThroughKind.EXPECTING, decide(s = snooze).rangThrough?.kind)
        // Nothing would have blocked it: nothing to explain.
        assertNull(decide(s = ScreeningSettings(snoozeUntil = now + 3_600_000)).rangThrough)
        // Hidden numbers too.
        assertEquals(RangThroughKind.EXPECTING, decide(facts(null), s = snooze.copy(blockHidden = true)).rangThrough?.kind)
    }

    @Test fun a_temporary_allow_rule_says_until_when() {
        val until = now + 24 * 3_600_000L
        val rule = BlockRule(id = 3, pattern = caller, type = RuleType.EXACT, kind = RuleKind.ALLOW, note = "Plumber", expiresAt = until)
        val r = decide(rules = listOf(rule))
        assertEquals(RangThrough(RangThroughKind.ALLOW_RULE, name = "Plumber", until = until), r.rangThrough)
        assertNull(decide(rules = listOf(rule), s = ScreeningSettings()).rangThrough)
    }

    @Test fun a_label_allowed_in_off_hours() {
        val offHours = ScreeningSettings(offHours = OffHours(enabled = true, schedule = Schedule(Schedule.ALL_DAYS, 0, 0), allow = OffHoursAllow.FAVOURITES))
        val family = BlockRule(id = 4, pattern = "Family", type = RuleType.LABEL, kind = RuleKind.ALLOW, label = "Family")
        val f = facts(contact = true).copy(contactLabels = setOf("Family"))
        val r = decide(f, listOf(family), offHours)
        assertEquals(Decision.Allow, r.decision)
        assertEquals(RangThrough(RangThroughKind.LABEL, name = "Family"), r.rangThrough)
        // Outside off hours the label changes nothing.
        assertNull(decide(f, listOf(family), ScreeningSettings()).rangThrough)
    }

    @Test fun called_or_talked_recently() {
        val s = strict.copy(allowDialled = true)
        val f = facts().copy(history = listOf(PastCall(now - 86_400_000L, outgoing = true, durationSec = 10)))
        assertEquals(RangThroughKind.DIALLED, decide(f, s = s).rangThrough?.kind)
    }

    @Test fun contacts_and_plain_calls_have_no_line() {
        assertNull(decide(facts(contact = true)).rangThrough)
        assertNull(decide(s = ScreeningSettings()).rangThrough)
        assertNull(decide().rangThrough) // blocked
    }
}
