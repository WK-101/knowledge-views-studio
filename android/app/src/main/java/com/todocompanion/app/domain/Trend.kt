package com.todocompanion.app.domain

import kotlin.math.abs

/**
 * Track 2.2 — drift vs your own baseline. Instead of a lone "only-goes-up" total, a metric is read
 * against the mean of the period before it: is this window rising, easing, or holding level *for you*?
 * A small dead-band keeps ordinary noise reading as "level", so the arrows mean something. It also
 * buckets a set of metrics into "Holding steady" vs "Slipping", honoring each metric's own polarity
 * (for some, more is better; for others, less). Pure and Compose-free; unit-tested as plain Kotlin.
 */
object Trend {

    enum class Direction { RISING, LEVEL, EASING }

    /** Anything within ±10% of the baseline is noise, not a trend. */
    const val DEFAULT_DEAD_BAND = 0.10

    /**
     * One metric's drift. [current] is this window's value, [baseline] the value it's compared against,
     * [delta] their difference and [deltaPct] that as a percentage of the baseline. [hasBaseline] is false
     * when there was no prior period to compare to (then [direction] is always LEVEL).
     */
    data class Result(
        val current: Double,
        val baseline: Double,
        val direction: Direction,
        val delta: Double,
        val deltaPct: Double,
        val hasBaseline: Boolean,
    )

    /** Compare a [current] value to a [baseline], with a dead-band so small moves read as LEVEL. */
    fun analyze(current: Double, baseline: Double, hasBaseline: Boolean, deadBand: Double = DEFAULT_DEAD_BAND): Result {
        val delta = current - baseline
        val deltaPct = when {
            baseline != 0.0 -> delta / abs(baseline) * 100.0
            current == 0.0 -> 0.0
            else -> 100.0
        }
        val dir = when {
            !hasBaseline -> Direction.LEVEL
            abs(delta) <= abs(baseline) * deadBand -> Direction.LEVEL
            delta > 0 -> Direction.RISING
            else -> Direction.EASING
        }
        return Result(current, baseline, dir, delta, deltaPct, hasBaseline)
    }

    /**
     * Split a per-period [series] into the current window (its last [windowSize] values) and a baseline
     * (the mean of everything before it), then [analyze] the two. With no preceding periods the direction
     * is LEVEL and [Result.hasBaseline] is false.
     */
    fun ofSeries(series: List<Double>, windowSize: Int, deadBand: Double = DEFAULT_DEAD_BAND): Result {
        if (series.isEmpty() || windowSize <= 0) return Result(0.0, 0.0, Direction.LEVEL, 0.0, 0.0, false)
        val w = windowSize.coerceAtMost(series.size)
        val current = series.takeLast(w).average()
        val rest = series.dropLast(w)
        return if (rest.isEmpty()) Result(current, current, Direction.LEVEL, 0.0, 0.0, false)
        else analyze(current, rest.average(), true, deadBand)
    }

    /** A named metric with its drift and its polarity ([higherIsBetter] = up is good, e.g. tasks done). */
    data class Metric(val name: String, val result: Result, val higherIsBetter: Boolean = true)

    /** Metrics split into the ones holding steady and the ones slipping (moving the wrong way for them). */
    data class Classification(val steady: List<Metric>, val slipping: List<Metric>)

    /** A metric is "slipping" when it drifts against its own polarity (down when up is good, or vice-versa). */
    fun isSlipping(m: Metric): Boolean = when (m.result.direction) {
        Direction.RISING -> !m.higherIsBetter
        Direction.EASING -> m.higherIsBetter
        Direction.LEVEL -> false
    }

    /** Bucket [metrics] into "Holding steady" and "Slipping", preserving input order within each bucket. */
    fun classify(metrics: List<Metric>): Classification =
        Classification(steady = metrics.filter { !isSlipping(it) }, slipping = metrics.filter { isSlipping(it) })
}
