package com.todocompanion.app

import com.todocompanion.app.domain.reminders.ReminderSource
import com.todocompanion.app.domain.reminders.ReminderTiming
import com.todocompanion.app.domain.reminders.UnifiedReminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3 (cross-module unification) — pins the pure normalizers that fold the app's six divergent reminder
 * storage shapes into the single [com.todocompanion.app.domain.reminders.UnifiedReminder] model, so the
 * one-reminder-model contract is proven regardless of how the underlying storage is later unified.
 */
class UnifiedReminderTest {

    @Test fun habit_csvBecomesTimeOfDay_dedupedSortedRangeChecked() {
        val r = UnifiedReminders.fromHabit("h1", "Meditate", "1080, 540, 540, 99999, abc")
        assertEquals("in-range, deduped, sorted", listOf(540, 1080), r.map { it.minuteOfDay })
        assertTrue(r.all { it.source == ReminderSource.HABIT && it.timing == ReminderTiming.TIME_OF_DAY })
        assertEquals("Meditate", r.first().label)
    }

    @Test fun event_csvBecomesBeforeEvent_dedupedSorted() {
        val r = UnifiedReminders.fromEvent("e1", "Standup", "10,1440,10")
        assertEquals(listOf(10, 1440), r.map { it.minutesBefore })
        assertTrue(r.all { it.source == ReminderSource.EVENT && it.timing == ReminderTiming.BEFORE_EVENT })
    }

    @Test fun routine_nullOrOutOfRangeYieldsNothing_elseOneTimeOfDay() {
        assertTrue(UnifiedReminders.fromRoutine("r1", "Wind down", null).isEmpty())
        assertTrue(UnifiedReminders.fromRoutine("r1", "Wind down", 2000).isEmpty())
        assertEquals(1320, UnifiedReminders.fromRoutine("r1", "Wind down", 1320).single().minuteOfDay)
    }

    @Test fun note_primaryPlusExtraBecomeAbsolute_dedupedSorted_positiveOnly() {
        val r = UnifiedReminders.fromNote("n1", "Call mum", 2000L, "3000,2000,0,-5,bad", "FREQ=DAILY")
        assertEquals(listOf(2000L, 3000L), r.map { it.atMillis })
        assertTrue(r.all { it.source == ReminderSource.NOTE && it.timing == ReminderTiming.ABSOLUTE && it.detail == "FREQ=DAILY" })
    }

    @Test fun note_noReminderYieldsNothing() {
        assertTrue(UnifiedReminders.fromNote("n1", "x", null, "", null).isEmpty())
    }

    @Test fun occasion_prepAndKeepInTouch_bothOrNeither() {
        val r = UnifiedReminders.fromOccasion("o1", "Dad's birthday", 3, 30)
        assertEquals(2, r.size)
        val prep = r.first { it.detail == "prep" }
        assertEquals(ReminderTiming.BEFORE_EVENT, prep.timing)
        assertEquals("3 days → minutes-before", 3 * 24 * 60, prep.minutesBefore)
        assertTrue(r.any { it.detail.startsWith("keep-in-touch") && it.timing == ReminderTiming.TIME_OF_DAY })
        assertTrue("zeros produce nothing", UnifiedReminders.fromOccasion("o1", "x", 0, 0).isEmpty())
    }

    @Test fun task_absoluteVsRelative_pickTimingFromType() {
        val abs = UnifiedReminders.fromTask("t1", "Pay rent", "absolute", 9_999L, null)
        assertEquals(ReminderTiming.ABSOLUTE, abs.timing)
        assertEquals(9_999L, abs.atMillis)
        assertNull(abs.minutesBefore)
        val rel = UnifiedReminders.fromTask("t2", "Ping Bob", "relativeToDue", null, 30)
        assertEquals(ReminderTiming.RELATIVE, rel.timing)
        assertEquals(30, rel.minutesBefore)
        assertEquals("relativeToDue", rel.detail)
    }

    @Test fun ordered_mergesAndSortsBySourceThenTime() {
        val merged = UnifiedReminders.ordered(
            UnifiedReminders.fromEvent("e", "E", "20"),
            UnifiedReminders.fromHabit("h", "H", "1080,540"),
        )
        // HABIT (source ordinal 1) sorts before EVENT (2); within HABIT, 540 before 1080.
        assertEquals(listOf(ReminderSource.HABIT, ReminderSource.HABIT, ReminderSource.EVENT), merged.map { it.source })
        assertEquals(listOf(540, 1080, null), merged.map { it.minuteOfDay })
    }
}
