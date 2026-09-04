package com.todocompanion.app

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.ReviewInsights
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Wave 2 (feature 5) — the on-device cross-stream insights engine: a best-vs-rest finding fires only
 * with enough rated days and a real gap, and the day-of-week finding surfaces a clearly-best weekday.
 */
class ReviewInsightsTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = System.currentTimeMillis()

    private fun dayMillis(epochDay: Long, hour: Int) =
        LocalDate.ofEpochDay(epochDay).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun log(day: Long, rating: Int = 0, mood: Int = 0, amIntention: String = "") =
        DayLogEntity(epochDay = day, dayRating = rating, pmMood = mood, amIntention = amIntention)

    // An every-day build habit that started well before the test window.
    private fun habit(id: String) = HabitEntity(id = id, name = "Meditate", createdAt = dayMillis(50, 0))
    private fun checkin(id: String, day: Long) = HabitCheckinEntity(habitId = id, epochDay = day, count = 1, status = "done")

    // ── 1. Best-vs-rest habits: fires with enough data, suppressed below the sample threshold ──
    @Test fun bestVsRestHabitsFiresWithEnoughDataAndIsSuppressedBelowThreshold() {
        // 3 clearly-best days (rating 5, habit kept) + 6 rest days (rating 2, habit missed) = 9 rated days.
        val best = listOf(200L, 201L, 202L)
        val rest = (203L..208L).toList()
        val logs = best.map { log(it, rating = 5) } + rest.map { log(it, rating = 2) }
        val checkins = best.map { checkin("h1", it) } // best days keep the habit; rest days don't

        val full = ReviewInsights.compute(
            200, 208, logs, emptyList(), listOf(habit("h1")), checkins,
            emptyList(), emptyList(), zone, now,
        )
        // 9 rated days (≥ MIN_RATED_DAYS) with a real habit gap → a HABITS pattern fires.
        assertTrue("expected a HABITS pattern", full.any { it.kind == ReviewInsights.Kind.HABITS })
        // Every surfaced pattern carries a non-empty sentence and a sample size.
        assertTrue(full.all { it.text.isNotBlank() && it.sampleSize > 0 })

        // The same data trimmed to 5 rated days (200..204) falls below MIN_RATED_DAYS → suppressed.
        val trimmed = ReviewInsights.compute(
            200, 204, logs.filter { it.epochDay in 200..204 }, emptyList(), listOf(habit("h1")),
            checkins.filter { it.epochDay in 200..204 }, emptyList(), emptyList(), zone, now,
        )
        assertTrue("below the rated-day threshold → no HABITS pattern", trimmed.none { it.kind == ReviewInsights.Kind.HABITS })
    }

    // ── 2. Day-of-week finding surfaces a clearly-best weekday ──
    @Test fun dayOfWeekFindingFiresForAClearlyBestWeekday() {
        val start = 200L
        // Four weeks: every Monday is rated 5, every other day 3.
        val logs = (0 until 28).map { off ->
            val d = start + off
            val isMonday = LocalDate.ofEpochDay(d).dayOfWeek == DayOfWeek.MONDAY
            log(d, rating = if (isMonday) 5 else 3)
        }
        val insights = ReviewInsights.compute(
            start, start + 27, logs, emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), zone, now,
        )
        val dow = insights.filter { it.kind == ReviewInsights.Kind.DAY_OF_WEEK }
        assertTrue("a day-of-week pattern is surfaced", dow.isNotEmpty())
        assertTrue("the best weekday is flagged as highest-rated", dow.any { it.text.contains("highest-rated") })
    }
}
