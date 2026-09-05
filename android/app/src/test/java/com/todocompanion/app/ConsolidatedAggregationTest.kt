package com.todocompanion.app

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.OmegaContext
import com.todocompanion.app.domain.PeriodRecap
import com.todocompanion.app.domain.ReviewRollup
import com.todocompanion.app.domain.WeeklyDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Track 1 (consolidation) — proves the recap and the weekly digest now DERIVE the same period aggregation the
 * Day-review roll-up folds, so the three surfaces cannot disagree. The WeeklyDigest case is a true parity check:
 * the retained raw-fold path ([WeeklyDigest.compute]) and the Rollup-derived path ([WeeklyDigest.fromRollups])
 * must produce byte-identical [WeeklyDigest.Digest] output for the same fixture.
 */
class ConsolidatedAggregationTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 8, 20).toEpochDay()
    private val now = dayStart(today + 1)

    private fun dayStart(day: Long) = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun at(day: Long, hour: Int) = dayStart(day) + hour * 3_600_000L

    private fun habit(id: String) = HabitEntity(id = id, name = "Meditate", createdAt = dayStart(today - 100))
    private fun ck(day: Long) = HabitCheckinEntity(habitId = "h1", epochDay = day, count = 1, status = "done")
    private fun task(id: String, completedDay: Long?) = TaskEntity(
        id = id, listId = "l", title = id, createdAt = now, updatedAt = now,
        completedAt = completedDay?.let { at(it, 12) },
    )

    // ── A shared fixture spanning this week (today-6..today) and the week before (today-13..today-7). ──
    private val habits = listOf(habit("h1"))
    private val checkins = listOf(ck(today), ck(today - 1), ck(today - 8)) // 2 this week, 1 last week
    private val tasks = listOf(task("t1", today), task("t2", today - 9), task("open", null)) // 1 + 1
    private val focus = listOf(
        FocusSessionEntity(id = "fc1", epochDay = today, startMillis = at(today, 8), minutes = 60),      // this week
        FocusSessionEntity(id = "fc2", epochDay = today - 10, startMillis = at(today - 10, 8), minutes = 30), // last week
    )
    private val activities = listOf(TimeActivityEntity(id = "act1", name = "Reading", createdAt = now))
    private val timeEntries = listOf(
        TimeEntryEntity(id = "e1", activityId = "act1", startMillis = at(today, 9), endMillis = at(today, 9) + 45 * 60_000L),      // 45m this week
        TimeEntryEntity(id = "e2", activityId = "act1", startMillis = at(today - 8, 9), endMillis = at(today - 8, 9) + 30 * 60_000L), // 30m last week
    )

    private fun weekRollups(): Pair<ReviewRollup.Rollup, ReviewRollup.Rollup> {
        val cur = ReviewRollup.compute(
            today - 6, today, emptyList(), emptyList(), habits, checkins, timeEntries, activities,
            zone, now, tasks = tasks, focusSessions = focus,
        )
        val prev = ReviewRollup.compute(
            today - 13, today - 7, emptyList(), emptyList(), habits, checkins, timeEntries, activities,
            zone, now, tasks = tasks, focusSessions = focus,
        )
        return cur to prev
    }

    // ── WeeklyDigest: the raw fold and the Rollup-derived builder must agree exactly. ──
    @Test fun weeklyDigestRawFoldEqualsRollupDerived() {
        val (cur, prev) = weekRollups()
        val viaRollups = WeeklyDigest.fromRollups(cur, prev, habits, checkins, tasks, focus, 55, today)
        val viaCompute = WeeklyDigest.compute(
            habits, checkins, tasks, focus, 55, today, zone,
            timeThisWeek = cur.trackedMinutes, timeLastWeek = prev.trackedMinutes,
        )
        assertEquals(viaCompute, viaRollups)

        // And the headline numbers are what the old raw fold reported.
        val ci = viaRollups.metrics.first { it.label == "Check-ins" }
        assertEquals("2", ci.value); assertEquals(1, ci.delta)
        val done = viaRollups.metrics.first { it.label == "Tasks done" }
        assertEquals("1", done.value); assertEquals(0, done.delta)
        val foc = viaRollups.metrics.first { it.label == "Focus" }
        assertEquals("60m", foc.value); assertEquals(30, foc.delta)
        val time = viaRollups.metrics.first { it.label == "Time" }
        assertEquals("45m", time.value); assertEquals(15, time.delta)
        assertTrue(viaRollups.hasData)
    }

    // ── PeriodRecap: the Rollup-derived recap reports the same counts + ▲/▼ deltas vs the prior window. ──
    @Test fun periodRecapDerivesCountsAndDeltas() {
        val ctx = OmegaContext(
            tasks = tasks, habits = habits, checkins = checkins, focus = focus,
            timeEntries = timeEntries, activities = activities, zone = zone, today = today, now = now,
        )
        val dayLogs = listOf(DayLogEntity(epochDay = today, dayRating = 5, pmMood = 4))
        val recap = PeriodRecap.compute(today - 6, today, "This week", ctx, dayLogs)

        assertTrue(recap.hasData)
        val t = recap.lines.first { it.label == "Tasks done" }; assertEquals("1", t.value); assertEquals(0, t.delta)
        val c = recap.lines.first { it.label == "Habit check-ins" }; assertEquals("2", c.value); assertEquals(1, c.delta)
        val f = recap.lines.first { it.label == "Focus" }; assertEquals("1h", f.value); assertEquals(30, f.delta)
        val tr = recap.lines.first { it.label == "Tracked" }; assertEquals("45m", tr.value); assertEquals(15, tr.delta)
        // Felt lines derive from the roll-up's shared FeltState summary.
        assertEquals("5.0★", recap.lines.first { it.label == "Avg day rating" }.value)
        assertEquals("4.0", recap.lines.first { it.label == "Avg mood" }.value)
        assertTrue(recap.narrative.contains("finished 1 task"))
    }
}
