package com.todocompanion.app

import com.todocompanion.app.domain.ReviewInsights
import com.todocompanion.app.domain.Trend
import com.todocompanion.app.domain.WeekChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Track 2.3 — the "what changed this week" ranked movers + factor-effects. */
class WeekChangesTest {

    private fun metric(name: String, cur: Double, base: Double, higherIsBetter: Boolean = true) =
        Trend.Metric(name, Trend.analyze(cur, base, hasBaseline = true), higherIsBetter)

    @Test fun ranksBiggestMoversAndSkipsLevelOnes() {
        val metrics = listOf(
            metric("focus hours", cur = 6.0, base = 3.0),   // +100%
            metric("tasks done", cur = 8.0, base = 10.0),   // -20%
            metric("mood", cur = 4.05, base = 4.0),         // within dead-band → LEVEL, skipped
        )
        val out = WeekChanges.compute(metrics, emptyList())
        assertEquals(2, out.size)
        // Biggest absolute drift first.
        assertTrue(out[0].text.startsWith("Your focus hours is up 100%"))
        assertTrue(out[1].text.startsWith("Your tasks done is down 20%"))
    }

    @Test fun appendsFactorEffectsAfterMovers() {
        val metrics = listOf(metric("focus hours", cur = 6.0, base = 3.0))
        val insights = listOf(
            ReviewInsights.Insight(ReviewInsights.Kind.ACTIVITY, "You rate days higher when you track deep work.", 0.7, 12),
            ReviewInsights.Insight(ReviewInsights.Kind.HABITS, "Best days keep more habits.", 0.5, 10),
            ReviewInsights.Insight(ReviewInsights.Kind.MORNING, "Third one — should be dropped.", 0.4, 9),
        )
        val out = WeekChanges.compute(metrics, insights, maxFactors = 2)
        assertEquals(3, out.size) // 1 mover + 2 factors (third insight trimmed)
        assertTrue(out[0].text.startsWith("Your focus hours"))
        assertEquals("You rate days higher when you track deep work.", out[1].text)
        assertEquals("Best days keep more habits.", out[2].text)
    }

    @Test fun emptyInputsProduceNothing() {
        assertTrue(WeekChanges.compute(emptyList(), emptyList()).isEmpty())
    }
}
