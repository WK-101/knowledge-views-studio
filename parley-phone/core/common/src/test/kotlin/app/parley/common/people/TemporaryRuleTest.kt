package app.parley.common.people

import app.parley.common.cases.CaseFile
import app.parley.common.cases.CaseMode
import app.parley.common.cases.CaseStatus
import app.parley.common.people.TemporaryRule.CaseNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryRuleTest {
    private val day = TemporaryChoice.DAY_MS
    private val now = 100 * day

    @Test fun a_fixed_date_never_moves() {
        assertEquals(now + day, TemporaryRule.follow(now + day, TemporaryRule.FIXED, lastContact = now, case = CaseNow.OPEN, now = now))
    }

    @Test fun each_call_moves_a_last_call_date_on_but_never_back() {
        val seven = TemporaryRule.of(TemporaryClock.LAST_CALL, 7)
        val saved = now - 5 * day
        val stored = saved + 7 * day
        // A call today: seven days from now.
        assertEquals(now + 7 * day, TemporaryRule.follow(stored, seven, lastContact = now, case = CaseNow.UNKNOWN, now = now))
        // No call yet, or one from before it was saved: the date stays.
        assertEquals(stored, TemporaryRule.follow(stored, seven, lastContact = null, case = CaseNow.UNKNOWN, now = now))
        assertEquals(stored, TemporaryRule.follow(stored, seven, lastContact = saved - day, case = CaseNow.UNKNOWN, now = now))
        // "Keep 7 more days" set a later date than the last call gives: kept.
        val extended = now + 20 * day
        assertEquals(extended, TemporaryRule.follow(extended, seven, lastContact = now, case = CaseNow.UNKNOWN, now = now))
    }

    @Test fun a_quiet_contact_becomes_due_on_its_own() {
        val seven = TemporaryRule.of(TemporaryClock.LAST_CALL, 7)
        val lastCall = now - 8 * day
        val at = TemporaryRule.follow(lastCall + 7 * day, seven, lastContact = lastCall, case = CaseNow.UNKNOWN, now = now)
        assertTrue(at <= now)
        assertEquals(setOf("plumber"), TemporaryDue.due(mapOf("plumber" to at), now))
    }

    @Test fun a_case_holds_the_date_while_open_or_unreadable_and_lets_go_when_resolved() {
        val rule = TemporaryRule.of(TemporaryClock.CASE_CLOSED, 0)
        val stored = TemporaryRule.firstExpiry(TemporaryClock.CASE_CLOSED, 0, now - 3 * day)
        val held = now + TemporaryRule.CASE_HOLD_DAYS * day
        assertEquals(held, TemporaryRule.follow(stored, rule, null, CaseNow.OPEN, now))
        // Nothing is decided on case files that can't be read.
        assertEquals(held, TemporaryRule.follow(stored, rule, null, CaseNow.UNKNOWN, now))
        // Resolved: the held date stands, so it becomes due and is asked about as usual.
        assertEquals(stored, TemporaryRule.follow(stored, rule, null, CaseNow.CLOSED, now))
        assertTrue(TemporaryRule.follow(stored, rule, null, CaseNow.CLOSED, now) <= now)
    }

    @Test fun where_a_case_stands() {
        val open = CaseFile("c1", "Northwind", listOf("+441234"))
        assertEquals(CaseNow.OPEN, TemporaryRule.caseNow(open))
        assertEquals(CaseNow.OPEN, TemporaryRule.caseNow(open.copy(status = CaseStatus.WAITING)))
        assertEquals(CaseNow.CLOSED, TemporaryRule.caseNow(open.copy(status = CaseStatus.RESOLVED)))
        assertEquals(CaseNow.CLOSED, TemporaryRule.caseNow(open.copy(mode = CaseMode.OFF)))
        // Gone (deleted, or never kept for their number): the job is over too.
        assertEquals(CaseNow.CLOSED, TemporaryRule.caseNow(null))
        assertTrue(TemporaryRule.offersCase(open))
        assertFalse(TemporaryRule.offersCase(open.copy(status = CaseStatus.RESOLVED)))
        assertFalse(TemporaryRule.offersCase(null))
    }

    @Test fun picks_map_to_stored_rules_and_back() {
        assertEquals(TemporaryRule.FIXED, TemporaryRule.of(TemporaryClock.FIXED, 7))
        assertEquals(TemporaryRule(slideDays = 14), TemporaryRule.of(TemporaryClock.LAST_CALL, 14))
        assertEquals(TemporaryRule(untilCase = true), TemporaryRule.of(TemporaryClock.CASE_CLOSED, 7))
        TemporaryClock.entries.forEach { assertEquals(it, TemporaryRule.of(it, 7).clock) }
        assertEquals(now + 7 * day, TemporaryChoice(days = 7, clock = TemporaryClock.LAST_CALL).expiresAt(now))
        assertEquals(now + 30 * day, ExpiryChange.After(30, TemporaryClock.LAST_CALL).expiresAt(now))
        assertEquals(TemporaryRule(slideDays = 30), ExpiryChange.After(30, TemporaryClock.LAST_CALL).rule)
    }

    @Test fun stored_values_are_read_leniently() {
        assertEquals(TemporaryRule.FIXED, TemporaryRule.stored(null, false))
        assertEquals(TemporaryRule.FIXED, TemporaryRule.stored(0, false))
        assertEquals(TemporaryRule.FIXED, TemporaryRule.stored(-3, false))
        assertEquals(TemporaryRule.FIXED, TemporaryRule.stored(TemporaryChoice.MAX_DAYS + 1, false))
        assertEquals(TemporaryRule(slideDays = 7), TemporaryRule.stored(7, false))
        assertEquals(TemporaryClock.CASE_CLOSED, TemporaryRule.stored(7, true).clock)
    }

    @Test fun the_header_chip_says_when_it_waits_for_a_case() {
        assertEquals(listOf(VariantChip.Temporary(5, untilCase = true)), ContactVariants(ContactStorage.DEVICE, 5, untilCase = true).chips)
    }
}
