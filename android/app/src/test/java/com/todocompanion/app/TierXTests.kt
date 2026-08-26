package com.todocompanion.app

import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.Goal
import com.todocompanion.app.domain.Goals
import com.todocompanion.app.domain.Reasoning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier X — pure-logic coverage for the reasoning layer and Unified Goals. */
class TierXTests {

    // ── X1 · Goals ────────────────────────────────────────────────────────────────────────────────
    @Test fun goalsRoundTrip() {
        val list = listOf(
            Goal(id = "1", name = "Write the book", emoji = "📖", listId = "L1", habitId = "H1", activityId = "A1", budgetMinutes = 6000, targetEpochDay = 20000),
            Goal(id = "2", name = "Get fit", habitId = "H2"),
        )
        val back = Goals.parse(Goals.encode(list))
        assertEquals(2, back.size)
        assertEquals("Write the book", back[0].name)
        assertEquals(6000, back[0].budgetMinutes)
        assertEquals("🎯", back[1].emoji) // default kept
        assertEquals("1", Goals.byId(back, "1")?.id)
    }

    @Test fun goalArmsDetected() {
        assertTrue(Goal("1", "x", listId = "L").hasTasks)
        assertTrue(Goal("1", "x", habitId = "H").hasHabit)
        assertTrue(!Goal("1", "x", activityId = "A", budgetMinutes = 0).hasBudget) // budget needs minutes
        assertTrue(Goal("1", "x", activityId = "A", budgetMinutes = 60).hasBudget)
    }

    @Test fun goalsParseBlankAndGarbage() {
        assertTrue(Goals.parse("").isEmpty())
        assertTrue(Goals.parse("nonsense").isEmpty())
    }

    // ── X2 · keystone ─────────────────────────────────────────────────────────────────────────────
    @Test fun keystoneLift() {
        val universe = (1L..10L).toList()
        val metric = mapOf(1L to 5, 2L to 4, 3L to 5, 4L to 1, 5L to 0, 6L to 5, 7L to 4, 8L to 1, 9L to 0, 10L to 1)
        val condition = setOf(1L, 2L, 3L, 6L, 7L)   // avg 4.6
        val k = Reasoning.keystone(universe, metric, condition) // without: {1,0,1,0,1} avg 0.6
        assertEquals(4.6, k.avgWith, 1e-9)
        assertEquals(0.6, k.avgWithout, 1e-9)
        assertTrue(k.lift > 5.0)
        assertEquals(5, k.withN); assertEquals(5, k.withoutN)
    }

    @Test fun keystoneNoConditionDays() {
        val k = Reasoning.keystone(listOf(1L, 2L), mapOf(1L to 3), emptySet())
        assertEquals(0.0, k.avgWith, 1e-9)   // no condition days
        assertEquals(1.5, k.avgWithout, 1e-9) // (3 + 0) / 2
        assertEquals(0, k.withN)
    }

    // ── X3 · honest capacity ──────────────────────────────────────────────────────────────────────
    @Test fun medianFocusMinutes() {
        // days with tracking: 60, 120, 90, 30, 200 → sorted 30,60,90,120,200 → median 90
        val m = mapOf(1L to 60, 2L to 120, 3L to 90, 4L to 30, 5L to 200, 6L to 0)
        assertEquals(90, Reasoning.medianDailyFocusMinutes(m))
    }
    @Test fun medianFocusTooLittleSignal() {
        assertNull(Reasoning.medianDailyFocusMinutes(mapOf(1L to 60, 2L to 90)))
    }

    // ── X4 · peak window ──────────────────────────────────────────────────────────────────────────
    @Test fun peakWindowFindsBusiestSpan() {
        val byHour = IntArray(24)
        byHour[9] = 40; byHour[10] = 55; byHour[14] = 30; byHour[15] = 20
        val w = Reasoning.peakWindow(byHour, 2)!!
        assertEquals(9, w.startHour); assertEquals(11, w.endHour); assertEquals(95, w.minutes)
    }
    @Test fun peakWindowNullWhenEmpty() {
        assertNull(Reasoning.peakWindow(IntArray(24), 2))
    }

    // ── X5 · forecast ─────────────────────────────────────────────────────────────────────────────
    @Test fun forecastFitsAndSlips() {
        // estimates 60,60,60; calibration 1.5 → each costs 90; avail 200 → 2 fit (180), 1 slips
        val f = Reasoning.forecast(listOf(60, 60, 60), 1.5, 200)
        assertEquals(2, f.willFinish); assertEquals(1, f.willSlip)
        assertEquals(270, f.neededMin); assertTrue(f.calibrated)
    }
    @Test fun forecastNoCalibrationUsesRaw() {
        val f = Reasoning.forecast(listOf(30, 30), null, 45)
        assertEquals(1, f.willFinish); assertEquals(1, f.willSlip)
        assertTrue(!f.calibrated)
    }

    // ── X6 · rhythm weekdays ──────────────────────────────────────────────────────────────────────
    @Test fun rhythmDetectsWeekdaySubset() {
        // Mon/Wed/Fri heavy, weekend/Tue/Thu essentially empty
        val counts = intArrayOf(10, 0, 9, 0, 8, 0, 0) // Mon..Sun
        val wd = Reasoning.rhythmWeekdays(counts)
        assertEquals(setOf(1, 3, 5), wd)
    }
    @Test fun rhythmNullWhenSpreadEvenly() {
        val counts = intArrayOf(4, 4, 4, 4, 4, 4, 4)
        assertNull(Reasoning.rhythmWeekdays(counts))
    }
    @Test fun rhythmNullWhenTooFewCompletions() {
        assertNull(Reasoning.rhythmWeekdays(intArrayOf(2, 0, 2, 0, 1, 0, 0)))
    }

    // ── settings round-trip ───────────────────────────────────────────────────────────────────────
    @Test fun tierXSettingsRoundTrip() {
        val s = AppSettings(goalsJson = Goals.encode(listOf(Goal("1", "G"))), honestCapacity = true)
        val back = AppSettings.fromMap(s.toMap())
        assertEquals(1, Goals.parse(back.goalsJson).size)
        assertTrue(back.honestCapacity)
    }
    @Test fun tierXSettingsDefaults() {
        val back = AppSettings.fromMap(AppSettings().toMap())
        assertEquals("", back.goalsJson)
        assertTrue(!back.honestCapacity)
    }
}
