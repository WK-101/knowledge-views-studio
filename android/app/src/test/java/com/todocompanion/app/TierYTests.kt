package com.todocompanion.app

import com.todocompanion.app.domain.Goals
import com.todocompanion.app.domain.Reasoning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier Y — pure-logic coverage for the assistant layer (burnout, per-task fit, seasonality, library). */
class TierYTests {

    // ── Y6 · burnout divergence ───────────────────────────────────────────────────────────────────
    @Test fun burnoutFiresWhenHoursUpAndAdherenceDown() {
        // hours 30 → 40 (+33%); adherence 0.9 → 0.7 (−22%)
        assertTrue(Reasoning.burnoutDiverges(40.0, 30.0, 0.7, 0.9))
    }
    @Test fun burnoutQuietWhenHabitsHold() {
        assertFalse(Reasoning.burnoutDiverges(40.0, 30.0, 0.9, 0.9)) // hours up, habits steady
    }
    @Test fun burnoutQuietWhenHoursFlat() {
        assertFalse(Reasoning.burnoutDiverges(31.0, 30.0, 0.6, 0.9)) // habits down but hours barely up
    }
    @Test fun burnoutNeedsPriorSignal() {
        assertFalse(Reasoning.burnoutDiverges(40.0, 0.0, 0.7, 0.9))  // no prior hours
        assertFalse(Reasoning.burnoutDiverges(40.0, 30.0, 0.7, 0.0)) // no prior adherence
    }

    // ── Y7 · per-task greedy fit ──────────────────────────────────────────────────────────────────
    @Test fun fitCountFillsInOrder() {
        // costs 90,90,30; avail 200 → 90+90=180 fit, +30 would be 210 > 200 → slip
        val (fit, slip) = Reasoning.fitCount(listOf(90, 90, 30), 200)
        assertEquals(2, fit); assertEquals(1, slip)
    }
    @Test fun fitCountAllFit() {
        val (fit, slip) = Reasoning.fitCount(listOf(20, 20, 20), 100)
        assertEquals(3, fit); assertEquals(0, slip)
    }

    // ── Y8 · seasonality ──────────────────────────────────────────────────────────────────────────
    @Test fun heaviestLightestWeekday() {
        // Mon heaviest, Sun lightest
        val wk = doubleArrayOf(500.0, 300.0, 300.0, 300.0, 200.0, 100.0, 50.0)
        val hl = Reasoning.heaviestLightestWeekday(wk)!!
        assertEquals(1, hl.first)  // Monday (ISO 1)
        assertEquals(7, hl.second) // Sunday (ISO 7)
    }
    @Test fun seasonalityNullWhenFlat() {
        assertNull(Reasoning.heaviestLightestWeekday(DoubleArray(7) { 100.0 }))
    }
    @Test fun seasonalityNullWhenEmpty() {
        assertNull(Reasoning.heaviestLightestWeekday(DoubleArray(7)))
    }

    // ── Y5 · goal library ─────────────────────────────────────────────────────────────────────────
    @Test fun goalTemplatesArePresentAndWellFormed() {
        assertTrue(Goals.TEMPLATES.size >= 5)
        Goals.TEMPLATES.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.emoji.isNotBlank())
            assertTrue(it.budgetHours > 0)
        }
    }
}
