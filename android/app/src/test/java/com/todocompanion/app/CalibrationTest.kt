package com.todocompanion.app

import com.todocompanion.app.domain.Calibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Track 1.4 — the one estimate-vs-actual engine: pooled ratio, median factor, and the verdict band. */
class CalibrationTest {

    private fun p(planned: Int, actual: Int) = Calibration.Pair(planned, actual)

    @Test fun overallRatioIsPooled() {
        // Σactual 180 ÷ Σplanned 120 = 1.5.
        val r = Calibration.overallRatio(listOf(p(60, 90), p(60, 90)))!!
        assertEquals(1.5, r, 1e-9)
    }

    @Test fun overallRatioIgnoresPairsMissingASide() {
        val r = Calibration.overallRatio(listOf(p(60, 60), p(30, 0), p(0, 40)))!!
        assertEquals(1.0, r, 1e-9)   // only the 60/60 pair qualifies
    }

    @Test fun overallRatioNullWhenNothingQualifies() {
        assertNull(Calibration.overallRatio(listOf(p(0, 0), p(10, 0))))
    }

    @Test fun medianRatioMatchesTheOldPlanVsActualFactor() {
        // {1.0, 1.5, 2.0} → median 1.5 (same behaviour TimeInsights.planVsActual used to compute inline).
        val r = Calibration.medianRatio(listOf(p(60, 60), p(60, 90), p(60, 120)))!!
        assertEquals(1.5, r, 1e-9)
    }

    @Test fun medianRatioNeedsMinItems() {
        assertNull(Calibration.medianRatio(listOf(p(60, 90), p(60, 120))))   // only 2 < 3
    }

    @Test fun classifyBandsAroundOne() {
        assertEquals(Calibration.Verdict.OVER, Calibration.classify(1.3, 0.10))
        assertEquals(Calibration.Verdict.UNDER, Calibration.classify(0.7, 0.10))
        assertEquals(Calibration.Verdict.ON_POINT, Calibration.classify(1.05, 0.10))
        // A wider band tolerates more slack.
        assertEquals(Calibration.Verdict.ON_POINT, Calibration.classify(1.12, 0.15))
    }

    @Test fun percentOffIsSigned() {
        assertEquals(25, Calibration.percentOff(1.25))
        assertEquals(-20, Calibration.percentOff(0.80))
    }
}
