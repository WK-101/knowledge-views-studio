package com.todocompanion.app

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.domain.PastYearReview
import com.todocompanion.app.domain.YearReviewed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Track 3.5 — the Ferriss Past-Year Review: two action lists (more-of / not-to-do), data-adaptive scenes
 * that stay non-empty on a light year, and the three-words theme wiring.
 */
class PastYearReviewTest {

    private val end = LocalDate.of(2026, 9, 4).toEpochDay()
    private val start = end - 364

    private fun recap(
        daysReviewed: Int = 0,
        avgRating: Double = 0.0,
        ratedDays: Int = 0,
        topActivities: List<YearReviewed.TopActivity> = emptyList(),
        habits: List<YearReviewed.HabitConsistency> = emptyList(),
        winsCount: Int = 0,
        emotion: String = "",
        emotionCount: Int = 0,
        highlight: String = "",
        activeDays: Int = 0,
    ) = YearReviewed.Recap(
        startDay = start, endDay = end, periodDays = 365, daysReviewed = daysReviewed,
        avgRating = avgRating, ratedDays = ratedDays, avgMood = 0.0, moodDays = 0,
        ratingTrend = emptyList(), moodTrend = emptyList(), trackedMinutes = topActivities.sumOf { it.minutes },
        topActivities = topActivities, habitConsistency = habits, winsCount = winsCount,
        longestStreakDays = 0, topEmotionWord = emotion, topEmotionCount = emotionCount,
        highlightText = highlight, highlightEpochDay = 0, highlightRating = 0,
        activeDays = activeDays,
    )

    @Test fun richYearProducesBothActionListsAndScenes() {
        val r = recap(
            daysReviewed = 40, avgRating = 3.9, ratedDays = 40,
            topActivities = listOf(YearReviewed.TopActivity("Deep work", null, null, 6000)),
            habits = listOf(YearReviewed.HabitConsistency("Meditate", null, kept = 200, expected = 300)),
            winsCount = 30, emotion = "Grateful", emotionCount = 10, highlight = "Shipped the app",
        )
        val logs = listOf(
            DayLogEntity(epochDay = end - 5, tomorrowObstacle = "Phone distractions", pmReflection = "Focus was hard but the run helped."),
            DayLogEntity(epochDay = end - 20, tomorrowObstacle = "Phone distractions", highlight = "A great run"),
            DayLogEntity(epochDay = end - 30, dayRating = 2, lesson = "Say no to the late meeting"),
        )
        val review = PastYearReview.compute(r, logs)
        assertTrue(review.hasData)
        assertTrue("schedules more of the invested time", review.moreOf.any { it.contains("Deep work") })
        assertTrue("keeps the kept habit", review.moreOf.any { it.contains("Meditate") })
        assertTrue("not-to-do names the recurring obstacle", review.notToDo.any { it.contains("Phone distractions") })
        assertTrue("first scene opens the review", review.scenes.first().emoji == "📖")
        assertTrue("ends on the two-lists scene", review.scenes.last().body.contains("not-to-do"))
    }

    @Test fun lightYearStillYieldsNonEmptyReview() {
        val r = recap(daysReviewed = 3, activeDays = 2)
        val review = PastYearReview.compute(r, emptyList())
        assertTrue("scenes present", review.scenes.isNotEmpty())
        assertTrue("more-of falls back gracefully", review.moreOf.isNotEmpty())
        assertTrue("not-to-do falls back gracefully", review.notToDo.isNotEmpty())
        assertTrue(review.hasData)
    }

    @Test fun emptyYearHasNoData() {
        val review = PastYearReview.compute(recap(), emptyList())
        assertFalse(review.hasData)
        assertTrue(review.scenes.isEmpty())
    }
}
