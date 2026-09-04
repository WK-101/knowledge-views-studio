package com.todocompanion.app.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Track 2.3 — "What changed this week". Rather than make the user scan a wall of tiles, this assembles
 * the story: the biggest movers vs their own baseline (via [Trend]) plus one or two descriptive
 * factor-effects from [ReviewInsights] ("your highest-rated days were the ones you tracked deep work").
 * Each line is a finished, plain-language sentence. Pure and Compose-free; unit-tested as plain Kotlin.
 */
object WeekChanges {

    /** One line of the change story. [magnitude] is only for the caller's reference / debugging. */
    data class Change(val text: String, val magnitude: Double)

    /**
     * Build the ranked change list: the [maxMovers] metrics whose drift is largest (by absolute percent,
     * skipping any that are LEVEL or have no baseline), each as a sentence, followed by up to [maxFactors]
     * descriptive factor-effects taken from [insights] (strongest first, as the caller already sorts them).
     */
    fun compute(
        metrics: List<Trend.Metric>,
        insights: List<ReviewInsights.Insight> = emptyList(),
        maxMovers: Int = 3,
        maxFactors: Int = 2,
    ): List<Change> {
        val movers = metrics
            .filter { it.result.hasBaseline && it.result.direction != Trend.Direction.LEVEL }
            .sortedByDescending { abs(it.result.deltaPct) }
            .take(maxMovers)
            .map { m ->
                val up = m.result.direction == Trend.Direction.RISING
                val pct = abs(m.result.deltaPct).roundToInt()
                Change("Your ${m.name} is ${if (up) "up" else "down"} $pct% vs your recent baseline.", abs(m.result.deltaPct))
            }
        val factors = insights.take(maxFactors).map { Change(it.text, it.strength) }
        return movers + factors
    }
}
