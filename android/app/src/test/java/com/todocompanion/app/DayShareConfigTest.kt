package com.todocompanion.app

import com.todocompanion.app.domain.DayShareConfig
import com.todocompanion.app.domain.DayShareConfigs
import com.todocompanion.app.domain.HabitDetail
import com.todocompanion.app.domain.TaskDetail
import com.todocompanion.app.domain.TimeDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redesigned daily-review SHARE config: its defaults (a card ≈ today's, just cleaner) and its
 * round-trip through the single settings-JSON value it persists under.
 */
class DayShareConfigTest {

    @Test fun defaultsMatchTodaysCard() {
        val c = DayShareConfig()
        // Felt state, wins and a line of reflection are on by default.
        assertTrue(c.rating)
        assertTrue(c.moodEnergyEmotion)
        assertTrue(c.wins)
        assertTrue(c.reflection)
        assertTrue(c.footerTagline)
        // Tasks/habits/time show a compact count by default.
        assertEquals(TaskDetail.COUNT, c.tasks)
        assertEquals(HabitDetail.COUNT, c.habits)
        assertEquals(TimeDetail.TOTAL, c.time)
        // The richer / new sections are off until the user opts in.
        assertTrue(!c.highlight)
        assertTrue(!c.gratitude)
        assertTrue(!c.lesson)
        assertTrue(!c.themes)
        assertTrue(!c.dailyQuestions)
        assertTrue(!c.alignment)
        assertTrue(!c.tomorrowFocus)
        assertTrue(!c.woop)
        assertTrue(!c.pattern)
    }

    @Test fun blankParsesToDefaults() {
        assertEquals(DayShareConfig(), DayShareConfigs.parse(""))
        assertEquals(DayShareConfig(), DayShareConfigs.parse("   "))
        // Malformed JSON also falls back to the defaults, so a corrupt value never breaks sharing.
        assertEquals(DayShareConfig(), DayShareConfigs.parse("{ not json"))
    }

    @Test fun roundTripsEveryField() {
        val c = DayShareConfig(
            rating = false,
            moodEnergyEmotion = false,
            wins = false,
            highlight = true,
            gratitude = true,
            lesson = true,
            reflection = false,
            themes = true,
            tasks = TaskDetail.FULL,
            habits = HabitDetail.DETAILED,
            time = TimeDetail.OFF,
            dailyQuestions = true,
            alignment = true,
            tomorrowFocus = true,
            woop = true,
            pattern = true,
            footerTagline = false,
        )
        val json = DayShareConfigs.encode(c)
        assertTrue(json.isNotBlank())
        assertEquals(c, DayShareConfigs.parse(json))
    }

    @Test fun triStateEnumsRoundTripByName() {
        // Each tri-state's three values survive a round-trip.
        for (t in TaskDetail.entries) for (h in HabitDetail.entries) for (tm in TimeDetail.entries) {
            val c = DayShareConfig(tasks = t, habits = h, time = tm)
            val back = DayShareConfigs.parse(DayShareConfigs.encode(c))
            assertEquals(t, back.tasks)
            assertEquals(h, back.habits)
            assertEquals(tm, back.time)
        }
    }
}
