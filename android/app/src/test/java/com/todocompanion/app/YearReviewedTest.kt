package com.todocompanion.app

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.YearReviewed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Wave 3 (feature B) — the fully-local "Year, Reviewed": aggregation over a fabricated year (rating/mood
 * averages, tracked time + top activity, habit consistency, wins, longest review streak, most-common
 * emotion word, and the standout highlight), plus the empty-window case.
 */
class YearReviewedTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = System.currentTimeMillis()
    private val start = 1_000L
    private val end = 1_039L // an inclusive 40-day window

    private fun dayMillis(epochDay: Long, hour: Int) =
        LocalDate.ofEpochDay(epochDay).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun log(
        day: Long, rating: Int = 0, mood: Int = 0, highlight: String = "",
        good1: String = "", good2: String = "", emotion: String = "",
    ) = DayLogEntity(
        epochDay = day, dayRating = rating, pmMood = mood, highlight = highlight,
        good1 = good1, good2 = good2, emotionLabel = emotion,
    )

    private val logs = listOf(
        // Run A — 3 consecutive reviewed days.
        log(1000, rating = 4, mood = 4, good1 = "Walked", good2 = "Read"),
        log(1001, rating = 4, mood = 3),
        log(1002, rating = 4),
        // Emotion-only days (NOT reviewed — only emotionLabel is set).
        log(1005, emotion = "Calm"),
        log(1006, emotion = "Calm"),
        log(1007, emotion = "Calm"),
        log(1008, emotion = "Tired"),
        // Run B — 5 consecutive reviewed days (the longest run).
        log(1010, rating = 5, mood = 5, good1 = "Shipped"),
        log(1011, rating = 5, mood = 5),
        log(1012, rating = 5, highlight = "Shipped the release"),
        log(1013, rating = 3),
        log(1014, rating = 3),
        // A lone reviewed day.
        log(1020, rating = 2),
    )

    private val habit = HabitEntity(id = "h1", name = "Meditate", createdAt = dayMillis(500, 0))
    private val checkins = (1000L..1019L).map { HabitCheckinEntity(habitId = "h1", epochDay = it, count = 1, status = "done") }

    private val act1 = TimeActivityEntity(id = "a1", name = "Deep work", createdAt = dayMillis(500, 0))
    private val act2 = TimeActivityEntity(id = "a2", name = "Exercise", createdAt = dayMillis(500, 0))
    private val entries = listOf(
        TimeEntryEntity(id = "e1", activityId = "a1", startMillis = dayMillis(1000, 9), endMillis = dayMillis(1000, 11)), // 120m
        TimeEntryEntity(id = "e2", activityId = "a1", startMillis = dayMillis(1001, 9), endMillis = dayMillis(1001, 10)), // 60m
        TimeEntryEntity(id = "e3", activityId = "a2", startMillis = dayMillis(1002, 7), endMillis = dayMillis(1002, 7) + 30 * 60_000L), // 30m
    )

    private fun compute() = YearReviewed.compute(start, end, logs, listOf(habit), checkins, entries, listOf(act1, act2), zone, now)

    @Test fun aggregatesTheYear() {
        val r = compute()
        assertEquals(40, r.periodDays)
        // 9 days have a "reviewed" field set (emotion-only days don't count).
        assertEquals(9, r.daysReviewed)
        assertEquals(9, r.ratedDays)
        assertEquals(35.0 / 9.0, r.avgRating, 0.001)
        assertEquals(4, r.moodDays)
        assertEquals(17.0 / 4.0, r.avgMood, 0.001)
        assertTrue(r.hasData)
    }

    @Test fun longestStreakIsTheLongestConsecutiveRun() {
        // Run B (1010–1014) is 5 days; run A (1000–1002) is only 3.
        assertEquals(5, compute().longestStreakDays)
    }

    @Test fun countsWinsAndTracksTime() {
        val r = compute()
        assertEquals(3, r.winsCount) // Walked + Read + Shipped
        assertEquals(210, r.trackedMinutes)
        assertEquals(3, r.trackedHours)
        assertEquals("Deep work", r.topActivities.first().name)
        assertEquals(180, r.topActivities.first().minutes)
    }

    @Test fun habitConsistencyOverTheWindow() {
        val h = compute().habitConsistency.single()
        assertEquals("Meditate", h.name)
        assertEquals(20, h.kept)
        assertEquals(40, h.expected)
        assertEquals(50, h.pct)
    }

    @Test fun mostCommonEmotionAndStandoutHighlight() {
        val r = compute()
        assertEquals("Calm", r.topEmotionWord)
        assertEquals(3, r.topEmotionCount)
        // The highlight comes from the highest-rated day that has one (1012, rating 5).
        assertEquals("Shipped the release", r.highlightText)
        assertEquals(1012L, r.highlightEpochDay)
        assertEquals(5, r.highlightRating)
    }

    @Test fun trendsSpanTheWindow() {
        val r = compute()
        assertTrue(r.ratingTrend.isNotEmpty())
        assertTrue("some month has a rating average", r.ratingTrend.any { it != null })
        assertTrue(r.moodTrend.any { it != null })
    }

    @Test fun emptyWindowHasNothing() {
        val r = YearReviewed.compute(start, end, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), zone, now)
        assertFalse(r.hasData)
        assertEquals(0, r.daysReviewed)
        assertEquals(0, r.trackedMinutes)
        assertEquals(0, r.longestStreakDays)
        assertEquals("", r.topEmotionWord)
        assertEquals("", r.highlightText)
        assertTrue(r.topActivities.isEmpty())
        assertTrue(r.habitConsistency.isEmpty())
    }

    @Test fun reversedWindowIsSafe() {
        val r = YearReviewed.compute(1_050, 1_000, logs, listOf(habit), checkins, entries, listOf(act1, act2), zone, now)
        assertFalse(r.hasData)
        assertEquals(0, r.periodDays)
    }
}
