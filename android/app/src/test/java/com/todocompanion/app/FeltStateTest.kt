package com.todocompanion.app

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.domain.FeltState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Track 1.1 — the shared felt-state fold: averages, trends, dominant emotion, and the edge windows. */
class FeltStateTest {

    private fun log(
        day: Long, rating: Int = 0, mood: Int = 0, energy: Int = 0,
        emotion: String = "", reflection: String = "",
    ) = DayLogEntity(
        epochDay = day, dayRating = rating, pmMood = mood, energy = energy,
        emotionLabel = emotion, pmReflection = reflection,
    )

    @Test fun summarizesRatingsMoodEnergyAndEmotion() {
        val logs = listOf(
            log(100, rating = 5, mood = 4, energy = 3, emotion = "Calm"),
            log(101, rating = 4, mood = 4, emotion = "Calm"),
            log(102, rating = 3, energy = 5, emotion = "Tired"),
            log(103, mood = 2),               // mood only
        )
        val s = FeltState.summarize(logs, 100, 106)
        assertEquals(7, s.daysInRange)
        // Ratings: (5+4+3)/3 = 4.0 over 3 rated days.
        assertEquals(3, s.ratedDays)
        assertEquals(4.0, s.avgRating, 1e-9)
        // Mood: (4+4+2)/3 = 3.333 over 3 logged days.
        assertEquals(3, s.moodDays)
        assertEquals(10.0 / 3.0, s.avgMood, 1e-9)
        // Energy: (3+5)/2 = 4.0 over 2 days.
        assertEquals(2, s.energyDays)
        assertEquals(4.0, s.avgEnergy, 1e-9)
        // Trend lists span the whole window, null where nothing was logged.
        assertEquals(7, s.ratingTrend.size)
        assertEquals(5, s.ratingTrend[0])
        assertEquals(null, s.ratingTrend[3])
        assertEquals(null, s.ratingTrend[6])
        // Dominant emotion: Calm (2) beats Tired (1).
        assertEquals("Calm", s.dominantEmotion)
        assertEquals(2, s.dominantEmotionCount)
        assertTrue(s.hasData)
    }

    @Test fun emptyWindowHasNoData() {
        val s = FeltState.summarize(emptyList(), 100, 106)
        assertFalse(s.hasData)
        assertEquals(0, s.ratedDays)
        assertEquals(0.0, s.avgRating, 1e-9)
        assertEquals("", s.dominantEmotion)
        assertEquals(7, s.daysInRange)
    }

    @Test fun reversedWindowIsSafe() {
        val s = FeltState.summarize(listOf(log(100, rating = 5)), 106, 100)
        assertFalse(s.hasData)
        assertEquals(0, s.daysInRange)
        assertTrue(s.ratingTrend.isEmpty())
    }

    @Test fun onlyLogsInsideTheWindowCount() {
        val logs = listOf(log(90, rating = 5), log(100, rating = 3))
        val s = FeltState.summarize(logs, 100, 106)
        assertEquals(1, s.ratedDays)
        assertEquals(3.0, s.avgRating, 1e-9)
    }
}
