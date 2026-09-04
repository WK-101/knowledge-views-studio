package com.todocompanion.app

import com.todocompanion.app.domain.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Track 2.2 — drift vs your own baseline, with a dead-band, and the steady/slipping classifier. */
class TrendTest {

    @Test fun deadBandReadsSmallMovesAsLevel() {
        // +5% vs a baseline of 10 is inside the default ±10% dead-band → LEVEL.
        val r = Trend.analyze(current = 10.5, baseline = 10.0, hasBaseline = true)
        assertEquals(Trend.Direction.LEVEL, r.direction)
        assertEquals(5.0, r.deltaPct, 1e-9)
    }

    @Test fun clearRiseAndEase() {
        assertEquals(Trend.Direction.RISING, Trend.analyze(13.0, 10.0, true).direction)
        assertEquals(Trend.Direction.EASING, Trend.analyze(7.0, 10.0, true).direction)
        val rise = Trend.analyze(13.0, 10.0, true)
        assertEquals(3.0, rise.delta, 1e-9)
        assertEquals(30.0, rise.deltaPct, 1e-9)
    }

    @Test fun noBaselineIsAlwaysLevel() {
        val r = Trend.analyze(99.0, 0.0, hasBaseline = false)
        assertEquals(Trend.Direction.LEVEL, r.direction)
        assertFalse(r.hasBaseline)
    }

    @Test fun ofSeriesSplitsCurrentWindowFromBaseline() {
        // Baseline = mean(2,2,2) = 2; current window = mean(4,4) = 4 → rising.
        val r = Trend.ofSeries(listOf(2.0, 2.0, 2.0, 4.0, 4.0), windowSize = 2)
        assertTrue(r.hasBaseline)
        assertEquals(4.0, r.current, 1e-9)
        assertEquals(2.0, r.baseline, 1e-9)
        assertEquals(Trend.Direction.RISING, r.direction)
        assertEquals(100.0, r.deltaPct, 1e-9)
    }

    @Test fun ofSeriesWithNoPrecedingPeriodHasNoBaseline() {
        val r = Trend.ofSeries(listOf(5.0, 6.0), windowSize = 2)
        assertFalse(r.hasBaseline)
        assertEquals(Trend.Direction.LEVEL, r.direction)
    }

    @Test fun classifyHonorsPolarity() {
        val tasksDone = Trend.Metric("tasks done", Trend.analyze(6.0, 10.0, true), higherIsBetter = true)   // down = slipping
        val distractions = Trend.Metric("distractions", Trend.analyze(9.0, 5.0, true), higherIsBetter = false) // up = slipping
        val mood = Trend.Metric("mood", Trend.analyze(4.1, 4.0, true), higherIsBetter = true)                 // level = steady
        val c = Trend.classify(listOf(tasksDone, distractions, mood))
        assertEquals(listOf("mood"), c.steady.map { it.name })
        assertEquals(listOf("tasks done", "distractions"), c.slipping.map { it.name })
    }
}
