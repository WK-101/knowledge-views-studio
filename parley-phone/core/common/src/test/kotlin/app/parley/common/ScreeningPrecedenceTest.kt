package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class ScreeningPrecedenceTest {
    private val monNoon = PolicyClock(1_700_000_000_000L, DayOfWeek.MONDAY, 12 * 60)
    private val spam = "+33699999999"

    private fun facts(n: String? = spam, contact: Boolean = false) =
        IncomingCallFacts(number = n, hidden = n == null, isContact = contact, countryIso = "FR", ownNumbers = listOf("+33612345678"))

    private fun decide(f: IncomingCallFacts, rules: List<BlockRule> = emptyList(), s: ScreeningSettings = ScreeningSettings(), clock: PolicyClock = monNoon) =
        CallPolicy.decide(f, rules, s, clock)

    private val blockAll = BlockRule(id = 1, pattern = "+33*", type = RuleType.WILDCARD, note = "All French")
    private val allowThis = BlockRule(id = 2, pattern = spam, type = RuleType.EXACT, kind = RuleKind.ALLOW)

    @Test fun precedence_emergency_beats_everything() {
        val r = decide(facts().copy(inEmergencyWindow = true), listOf(blockAll), ScreeningSettings(blockNonContacts = true))
        assertEquals(Decision.Allow, r.decision)
        assertEquals(AllowReason.EMERGENCY, r.allowedBy)
    }

    @Test fun precedence_contact_beats_block_rule() {
        val r = decide(facts(contact = true), listOf(blockAll))
        assertEquals(AllowReason.CONTACT, r.allowedBy)
    }

    @Test fun precedence_allow_rule_beats_block_rule_regardless_of_order() {
        assertEquals(AllowReason.RULE, decide(facts(), listOf(blockAll, allowThis)).allowedBy)
        assertEquals(AllowReason.RULE, decide(facts(), listOf(allowThis, blockAll)).allowedBy)
    }

    @Test fun precedence_block_rule_beats_list() {
        val hit = ListHit("p", "FTC", "Robocall", 90, ListMode.BLOCK, 50)
        val r = decide(facts().copy(listHits = listOf(hit)), listOf(blockAll))
        assertEquals(BlockReason.RULE, (r.decision as Decision.Block).reason)
    }

    @Test fun precedence_list_beats_default_toggles() {
        val hit = ListHit("p", "FTC", "Robocall", 90, ListMode.BLOCK, 50, action = BlockAction.SILENCE)
        val r = decide(facts().copy(listHits = listOf(hit)), s = ScreeningSettings(blockNonContacts = true))
        val b = r.decision as Decision.Block
        assertEquals(BlockReason.LIST, b.reason)
        assertEquals(BlockAction.SILENCE, b.action)
        assertEquals(VerdictKind.REPORTED, r.verdict?.kind)
    }

    @Test fun list_warn_mode_rings_with_verdict() {
        val hit = ListHit("p", "FTC", "Robocall", 90, ListMode.WARN)
        val r = decide(facts().copy(listHits = listOf(hit)), s = ScreeningSettings(likelySpamRingtone = "tone://spam"))
        assertEquals(Decision.Allow, r.decision)
        assertEquals(VerdictKind.LIKELY_SPAM, r.verdict?.kind)
        assertEquals("tone://spam", r.ringtone)
    }

    @Test fun list_below_threshold_only_warns() {
        val hit = ListHit("p", "FTC", null, 40, ListMode.BLOCK, 50)
        assertEquals(Decision.Allow, decide(facts().copy(listHits = listOf(hit))).decision)
    }

    @Test fun allow_rule_beats_list_and_non_contacts() {
        val hit = ListHit("p", "FTC", null, 99, ListMode.BLOCK, 50)
        val r = decide(facts().copy(listHits = listOf(hit)), listOf(allowThis), ScreeningSettings(blockNonContacts = true))
        assertEquals(AllowReason.RULE, r.allowedBy)
    }

    @Test fun expired_temporary_allow_is_ignored() {
        val temp = allowThis.copy(expiresAt = monNoon.millis - 1)
        val r = decide(facts(), listOf(temp), ScreeningSettings(blockNonContacts = true))
        assertTrue(r.blocked)
        val live = allowThis.copy(expiresAt = monNoon.millis + 1000)
        assertEquals(AllowReason.RULE, decide(facts(), listOf(live), ScreeningSettings(blockNonContacts = true)).allowedBy)
    }

    // ---- Repeat callers ----

    @Test fun repeat_caller_overrides_soft_reason_only() {
        val f = facts().copy(blockedAttempts = listOf(monNoon.millis - 60_000))
        assertEquals(AllowReason.REPEAT, decide(f, s = ScreeningSettings(blockNonContacts = true)).allowedBy)
        // Never an explicit rule.
        assertTrue(decide(f, listOf(blockAll), ScreeningSettings(blockNonContacts = true)).blocked)
    }

    @Test fun instant_redial_does_not_count_as_repeat() {
        val f = facts().copy(blockedAttempts = listOf(monNoon.millis - 5_000))
        val r = decide(f, s = ScreeningSettings(blockNonContacts = true, repeatMinIntervalSeconds = 20))
        assertTrue(r.blocked)
        assertTrue(r.trace.any { it.check == "Repeat caller" && it.result.contains("too fast") })
    }

    @Test fun repeat_outside_window_does_not_count() {
        val f = facts().copy(blockedAttempts = listOf(monNoon.millis - 10 * 60_000))
        assertTrue(decide(f, s = ScreeningSettings(blockNonContacts = true, repeatWindowMinutes = 3)).blocked)
    }

    @Test fun repeat_disabled() {
        val f = facts().copy(blockedAttempts = listOf(monNoon.millis - 60_000))
        assertTrue(decide(f, s = ScreeningSettings(blockNonContacts = true, repeatCallers = false)).blocked)
    }

    // ---- Snooze ----

    @Test fun snooze_lets_unknown_and_hidden_through_until_it_expires() {
        val s = ScreeningSettings(blockNonContacts = true, blockHidden = true, snoozeUntil = monNoon.millis + 60_000)
        assertEquals(AllowReason.SNOOZE, decide(facts(), s = s).allowedBy)
        assertEquals(AllowReason.SNOOZE, decide(facts(null), s = s).allowedBy)
        val expired = s.copy(snoozeUntil = monNoon.millis - 1)
        assertTrue(decide(facts(), s = expired).blocked)
        assertTrue(decide(facts(null), s = expired).blocked)
    }

    @Test fun snooze_beats_block_rules_but_not_the_system_list() {
        // Snooze sits with the allow rules, so it beats block rules too (you asked to let unknown callers ring).
        val s = ScreeningSettings(snoozeUntil = monNoon.millis + 60_000)
        assertEquals(AllowReason.SNOOZE, decide(facts(), listOf(blockAll), s).allowedBy)
        // But not the system block list.
        assertTrue(decide(facts().copy(inSystemBlockList = true), s = s).blocked)
    }

    // ---- Dialled / answered ----

    @Test fun dialled_and_answered_allow() {
        val s = ScreeningSettings(blockNonContacts = true, allowDialled = true, allowAnswered = true, answeredMinSeconds = 30)
        val dialled = facts().copy(history = listOf(PastCall(monNoon.millis - 86_400_000L, outgoing = true, durationSec = 0)))
        assertEquals(AllowReason.DIALLED, decide(dialled, s = s).allowedBy)
        val talked = facts().copy(history = listOf(PastCall(monNoon.millis - 86_400_000L, outgoing = false, durationSec = 45)))
        assertEquals(AllowReason.ANSWERED, decide(talked, s = s).allowedBy)
        val short = facts().copy(history = listOf(PastCall(monNoon.millis - 86_400_000L, outgoing = false, durationSec = 5)))
        assertTrue(decide(short, s = s).blocked)
        val old = facts().copy(history = listOf(PastCall(monNoon.millis - 40 * 86_400_000L, outgoing = true, durationSec = 0)))
        assertTrue(decide(old, s = s.copy(dialledDays = 30)).blocked)
    }

    // ---- Contacts fail open (regression: four apps got this wrong) ----

    @Test fun contacts_lookup_failure_fails_open_and_is_marked() {
        val f = facts().copy(isContact = true, contactLookupFailed = true)
        val r = decide(f, listOf(blockAll), ScreeningSettings(blockNonContacts = true))
        assertEquals(Decision.Allow, r.decision)
        assertTrue(r.failedOpen)
        assertEquals(TraceMark.FAILED_OPEN, r.trace.first().mark)
    }

    // ---- Labels, off hours, schedules ----

    @Test fun label_block_rule_blocks_contacts_in_label() {
        val rule = BlockRule(id = 9, pattern = "7", type = RuleType.LABEL, label = "Spam")
        val f = facts(contact = true).copy(contactLabels = setOf(7L))
        assertTrue(decide(f, listOf(rule)).blocked)
        assertFalse(decide(facts(contact = true).copy(contactLabels = setOf(8L)), listOf(rule)).blocked)
        // Label lookup failed: fail open.
        assertFalse(decide(f.copy(labelLookupFailed = true), listOf(rule)).blocked)
    }

    @Test fun off_hours_only_label_rings_across_midnight() {
        val oh = OffHours(enabled = true, schedule = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60), allow = OffHoursAllow.LABEL, labelId = 3, labelTitle = "Family")
        val s = ScreeningSettings(offHours = oh)
        val night = PolicyClock(0, DayOfWeek.TUESDAY, 2 * 60)
        val family = facts(contact = true).copy(contactLabels = setOf(3L))
        val colleague = facts(contact = true).copy(contactLabels = setOf(4L))
        assertFalse(decide(family, s = s, clock = night).blocked)
        val r = decide(colleague, s = s, clock = night)
        assertEquals(BlockReason.OFF_HOURS, (r.decision as Decision.Block).reason)
        assertTrue(decide(facts(), s = s, clock = night).blocked)
        assertFalse(decide(colleague, s = s, clock = monNoon).blocked)
    }

    @Test fun rule_schedule_is_respected() {
        val weekdayNights = blockAll.copy(schedule = Schedule(Schedule.WEEKDAYS, 20 * 60, 8 * 60))
        assertFalse(decide(facts(), listOf(weekdayNights), clock = monNoon).blocked)
        assertTrue(decide(facts(), listOf(weekdayNights), clock = PolicyClock(0, DayOfWeek.FRIDAY, 23 * 60)).blocked)
        // Saturday 01:00 belongs to Friday night's window.
        assertTrue(decide(facts(), listOf(weekdayNights), clock = PolicyClock(0, DayOfWeek.SATURDAY, 60)).blocked)
        // Sunday night is not a weekday window, and Monday 01:00 belongs to Sunday.
        assertFalse(decide(facts(), listOf(weekdayNights), clock = PolicyClock(0, DayOfWeek.MONDAY, 60)).blocked)
    }

    @Test fun toggle_schedule() {
        val s = ScreeningSettings(blockNonContacts = true, nonContactsSchedule = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60))
        assertFalse(decide(facts(), s = s, clock = monNoon).blocked)
        assertTrue(decide(facts(), s = s, clock = PolicyClock(0, DayOfWeek.MONDAY, 23 * 60)).blocked)
    }

    // ---- SIM ----

    @Test fun sim_rule_only_applies_when_sim_known_and_equal() {
        val work = blockAll.copy(simId = "sim-work")
        val screeningPath = decide(facts(), listOf(work))
        assertFalse(screeningPath.blocked)
        assertTrue(screeningPath.trace.any { it.mark == TraceMark.SKIPPED })
        assertTrue(decide(facts().copy(simId = "sim-work"), listOf(work)).blocked)
        assertFalse(decide(facts().copy(simId = "sim-home"), listOf(work)).blocked)
    }

    // ---- Name, region, line type, invalid ----

    @Test fun caller_name_region_and_line_type_rules() {
        val name = BlockRule(pattern = "survey", type = RuleType.CALLER_NAME)
        assertTrue(decide(facts().copy(callerName = "NATIONAL SURVEY INC"), listOf(name)).blocked)
        assertFalse(decide(facts().copy(callerName = null), listOf(name)).blocked)
        val region = BlockRule(pattern = "GB,IE", type = RuleType.REGION)
        assertTrue(decide(facts().copy(region = "IE"), listOf(region)).blocked)
        val notMine = BlockRule(pattern = "", type = RuleType.NOT_MY_REGION)
        assertTrue(decide(facts().copy(region = "US"), listOf(notMine)).blocked)
        assertFalse(decide(facts().copy(region = "FR"), listOf(notMine)).blocked)
        val voip = BlockRule(pattern = "VOIP,PREMIUM_RATE", type = RuleType.LINE_TYPE)
        assertTrue(decide(facts().copy(lineType = LineType.VOIP), listOf(voip)).blocked)
        assertFalse(decide(facts().copy(lineType = LineType.MOBILE), listOf(voip)).blocked)
    }

    @Test fun invalid_numbers_default_to_silence() {
        val s = ScreeningSettings(blockInvalid = true)
        val r = decide(facts().copy(validity = NumberValidity.INVALID), s = s)
        val b = r.decision as Decision.Block
        assertEquals(BlockReason.INVALID_NUMBER, b.reason)
        assertEquals(BlockAction.SILENCE, b.action)
        assertFalse(decide(facts().copy(validity = NumberValidity.UNKNOWN), s = s).blocked)
    }

    // ---- Trace ----

    @Test fun trace_reads_like_the_spec() {
        val rule = BlockRule(id = 5, pattern = "+33699", type = RuleType.PREFIX, note = "Telemarketing", action = BlockAction.SILENCE, hitCount = 6)
        val r = decide(facts(), listOf(rule))
        val text = r.trace.joinToString(" → ") { "${it.check}: ${it.result}" }
        assertEquals("Contact?: no → Allow rules: none → Block rule: Telemarketing → Decision: Silence", text)
        assertEquals("Blocked by rule 'Telemarketing' · 7 calls", r.verdict?.text)
        assertEquals(rule, r.rule)
    }

    @Test fun hidden_callers_are_never_matched_by_number_rules() {
        val r = decide(facts(null), listOf(blockAll))
        assertEquals(Decision.Allow, r.decision)
        assertNull(r.rule)
    }
}
