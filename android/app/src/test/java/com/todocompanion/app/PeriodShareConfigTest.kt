package com.todocompanion.app

import com.todocompanion.app.domain.HabitDetail
import com.todocompanion.app.domain.PeriodShareConfig
import com.todocompanion.app.domain.PeriodShareConfigs
import com.todocompanion.app.domain.ShareStyle
import com.todocompanion.app.domain.TimeDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The period-spanning SHARE config: its defaults (a shared roll-up ≈ the on-screen roll-up), its
 * round-trip through the single settings-JSON value it persists under, and the [ShareStyle] round-trip.
 */
class PeriodShareConfigTest {

    @Test fun defaultsAreOnExceptThemes() {
        val c = PeriodShareConfig()
        assertTrue(c.feltTrend)
        assertTrue(c.executionScore)
        assertTrue(c.wins)
        assertTrue(c.goals)
        assertTrue(c.tasks)
        assertTrue(c.footerTagline)
        // Themes are off until the user opts in.
        assertTrue(!c.themes)
        // The tri-states default to a compact summary.
        assertEquals(HabitDetail.COUNT, c.habits)
        assertEquals(TimeDetail.TOTAL, c.time)
        // Personal is the default style.
        assertEquals(ShareStyle.PERSONAL, c.style)
    }

    @Test fun blankParsesToDefaults() {
        assertEquals(PeriodShareConfig(), PeriodShareConfigs.parse(""))
        assertEquals(PeriodShareConfig(), PeriodShareConfigs.parse("   "))
        // Malformed JSON also falls back to the defaults, so a corrupt value never breaks sharing.
        assertEquals(PeriodShareConfig(), PeriodShareConfigs.parse("{ not json"))
    }

    @Test fun roundTripsEveryField() {
        val c = PeriodShareConfig(
            feltTrend = false,
            executionScore = false,
            wins = false,
            habits = HabitDetail.DETAILED,
            time = TimeDetail.OFF,
            goals = false,
            themes = true,
            tasks = false,
            footerTagline = false,
            style = ShareStyle.PROFESSIONAL,
        )
        val json = PeriodShareConfigs.encode(c)
        assertTrue(json.isNotBlank())
        assertEquals(c, PeriodShareConfigs.parse(json))
    }

    @Test fun styleRoundTripsBothValues() {
        for (s in ShareStyle.entries) {
            val back = PeriodShareConfigs.parse(PeriodShareConfigs.encode(PeriodShareConfig(style = s)))
            assertEquals(s, back.style)
        }
    }

    @Test fun triStateEnumsRoundTripByName() {
        for (h in HabitDetail.entries) for (t in TimeDetail.entries) {
            val back = PeriodShareConfigs.parse(PeriodShareConfigs.encode(PeriodShareConfig(habits = h, time = t)))
            assertEquals(h, back.habits)
            assertEquals(t, back.time)
        }
    }
}
