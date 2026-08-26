package com.todocompanion.app

import com.todocompanion.app.data.entity.ActivityEntity
import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.TimeTracking
import com.todocompanion.app.domain.WeeklyDigest
import com.todocompanion.app.domain.nlp.SmartCapture
import com.todocompanion.app.domain.task.TaskReliability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** R5 — coverage for the cross-module engines added in Tiers P–R. */

private val UTC = ZoneId.of("UTC")
private const val DAY = 86_400_000L

private fun rtask(id: String, rrule: String? = null) =
    TaskEntity(id = id, listId = "l", title = id, createdAt = 0, updatedAt = 0, rrule = rrule)

private fun completed(taskId: String, at: Long) =
    ActivityEntity(id = "a-$taskId-$at", taskId = taskId, type = "completed", at = at)

private fun habit(id: String) = HabitEntity(id = id, name = id, createdAt = 0)

private fun ck(habitId: String, epochDay: Long, status: String = "done", count: Int = 1) =
    HabitCheckinEntity(habitId = habitId, epochDay = epochDay, count = count, status = status)

private fun focus(epochDay: Long, minutes: Int) =
    FocusSessionEntity(id = "f-$epochDay-$minutes", epochDay = epochDay, startMillis = 0, minutes = minutes)

class SmartCaptureTest {
    @Test fun everyMorningIsHabit() {
        assertEquals(SmartCapture.Kind.HABIT, SmartCapture.classify("meditate 10 min every morning").kind)
    }
    @Test fun dailyIsHabit() {
        assertEquals(SmartCapture.Kind.HABIT, SmartCapture.classify("read 20 pages daily").kind)
    }
    @Test fun timesPerWeekIsHabit() {
        assertEquals(SmartCapture.Kind.HABIT, SmartCapture.classify("gym 3x a week").kind)
    }
    @Test fun weekdaysIsHabit() {
        assertEquals(SmartCapture.Kind.HABIT, SmartCapture.classify("stand-up on weekdays").kind)
    }
    @Test fun deadlineIsTask() {
        assertEquals(SmartCapture.Kind.TASK, SmartCapture.classify("call dentist tomorrow 3pm").kind)
    }
    @Test fun plainPhraseFallsBackToTask() {
        assertEquals(SmartCapture.Kind.TASK, SmartCapture.classify("email Sam the deck").kind)
    }
    @Test fun blankIsTaskAndSafe() {
        assertEquals(SmartCapture.Kind.TASK, SmartCapture.classify("   ").kind)
    }
}

class TaskReliabilityTest {
    private val now = LocalDate.of(2026, 8, 1).atStartOfDay(UTC).toInstant().toEpochMilli()

    @Test fun periodDaysForCommonRules() {
        assertEquals(1.0, TaskReliability.periodDays("FREQ=DAILY;INT=1")!!, 0.001)
        assertEquals(7.0, TaskReliability.periodDays("FREQ=WEEKLY;INT=1")!!, 0.001)
    }

    @Test fun nonRecurringHasNoScore() {
        assertNull(TaskReliability.score(rtask("t"), emptyList(), now))
    }

    @Test fun weeklyScoreIsKeptOverExpected() {
        // Weekly period = 7d, window = 70d, expected = 10 occurrences.
        val t = rtask("t", "FREQ=WEEKLY;INT=1")
        // 5 completions inside the window ⇒ 50%.
        val acts = (0 until 5).map { completed("t", now - it * 7 * DAY) }
        val r = TaskReliability.score(t, acts, now)!!
        assertEquals(10, r.expected)
        assertEquals(5, r.kept)
        assertEquals(50, r.score)
    }

    @Test fun streakCountsOnRhythmRun() {
        val t = rtask("t", "FREQ=WEEKLY;INT=1")
        val acts = (0 until 3).map { completed("t", now - it * 7 * DAY) }
        assertEquals(3, TaskReliability.streak(t, acts, now))
    }

    @Test fun streakBreaksWhenLastCompletionIsStale() {
        val t = rtask("t", "FREQ=WEEKLY;INT=1")
        // Last completion 4 weeks ago (> 2 periods) ⇒ run already broken.
        val acts = listOf(completed("t", now - 28 * DAY))
        assertEquals(0, TaskReliability.streak(t, acts, now))
    }

    @Test fun streakForgivesOneSlip() {
        val t = rtask("t", "FREQ=WEEKLY;INT=1")
        // now, -7d, then a 2-week gap (one slip, within grace), then -35d.
        val acts = listOf(
            completed("t", now),
            completed("t", now - 7 * DAY),
            completed("t", now - 21 * DAY),
        )
        assertEquals(3, TaskReliability.streak(t, acts, now))
    }
}

class WeeklyDigestTest {
    private val today = LocalDate.of(2026, 8, 20).toEpochDay()
    private fun dayMillis(epochDay: Long) =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(UTC).toInstant().toEpochMilli()

    @Test fun emptyStoreReportsNoData() {
        val d = WeeklyDigest.compute(emptyList(), emptyList(), emptyList(), emptyList(), 0, today, UTC)
        assertTrue(!d.hasData)
        assertTrue(d.headline.contains("blank page"))
    }

    @Test fun deltasComparedToPriorWeek() {
        val h = habit("h1")
        val checkins = listOf(
            // this week: 3 done
            ck("h1", today), ck("h1", today - 1), ck("h1", today - 2),
            // last week: 1 done
            ck("h1", today - 8),
        )
        val tasks = listOf(
            rtask("t1").copy(completedAt = dayMillis(today)),
            rtask("t2").copy(completedAt = dayMillis(today - 1)),
            rtask("t3").copy(completedAt = dayMillis(today - 9)), // last week
        )
        val focusSessions = listOf(focus(today, 60), focus(today - 10, 30))
        val d = WeeklyDigest.compute(listOf(h), checkins, tasks, focusSessions, 55, today, UTC)

        val ci = d.metrics.first { it.label == "Check-ins" }
        assertEquals("3", ci.value); assertEquals(2, ci.delta)
        val done = d.metrics.first { it.label == "Tasks done" }
        assertEquals("2", done.value); assertEquals(1, done.delta)
        val foc = d.metrics.first { it.label == "Focus" }
        assertEquals("60m", foc.value); assertEquals(30, foc.delta)
        assertTrue(d.hasData)
    }
}

class TimeTrackingTest {
    private fun entry(act: String, start: Long, end: Long?) =
        TimeEntryEntity(id = "e-$act-$start", activityId = act, startMillis = start, endMillis = end)

    @Test fun fullEntryInsideWindowCountsFully() {
        // 90 minutes fully inside the window.
        assertEquals(90, TimeTracking.minutesInWindow(10 * DAY, 10 * DAY + 90 * 60_000L, 10 * DAY, 11 * DAY, 11 * DAY))
    }

    @Test fun entryIsClampedToWindow() {
        // Starts 30 min before the window; only the in-window part counts.
        val winStart = 10 * DAY
        val start = winStart - 30 * 60_000L
        val end = winStart + 30 * 60_000L
        assertEquals(30, TimeTracking.minutesInWindow(start, end, winStart, 11 * DAY, 11 * DAY))
    }

    @Test fun runningEntryUsesNow() {
        val winStart = 10 * DAY
        val now = winStart + 45 * 60_000L
        assertEquals(45, TimeTracking.minutesInWindow(winStart, null, winStart, 11 * DAY, now))
    }

    @Test fun totalsAreSortedAndZerosDropped() {
        val winStart = 10 * DAY; val winEnd = 11 * DAY
        val entries = listOf(
            entry("a", winStart, winStart + 20 * 60_000L),
            entry("b", winStart, winStart + 60 * 60_000L),
            entry("c", winEnd + DAY, winEnd + DAY + 60_000L), // outside window ⇒ 0, dropped
        )
        val totals = TimeTracking.totalsByActivity(entries, winStart, winEnd, winEnd)
        assertEquals(2, totals.size)
        assertEquals("b", totals[0].activityId) // 60 min first
        assertEquals(60, totals[0].minutes)
        assertEquals("a", totals[1].activityId)
    }
}
