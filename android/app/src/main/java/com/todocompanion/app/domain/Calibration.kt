package com.todocompanion.app.domain

/**
 * Track 1 (Unify) — the one estimate-vs-actual engine. Three surfaces asked the same question in three
 * slightly different ways: Statistics' "Estimation calibration" (total actual ÷ total estimate), The
 * Record's "Estimate vs. actual" honesty ledger (also total-based), and Momentum's "Planned vs actual"
 * calibration factor (the median of per-task ratios, which also feeds the self-correcting forecast).
 *
 * This centralises the maths — the total ratio, the median-of-ratios factor, and the over/under/on-point
 * verdict — while each surface keeps its own wording. Pure and Compose-free; unit-tested as plain Kotlin.
 */
object Calibration {

    /** Momentum needs at least this many two-sided items before its median factor is trustworthy. */
    const val MIN_ITEMS_FOR_FACTOR = 3

    /** One planned/actual pair in minutes. Only pairs with both > 0 count toward a ratio. */
    data class Pair(val plannedMin: Int, val actualMin: Int)

    /** Where a ratio sits relative to a tolerance band around 1.0 (perfectly calibrated). */
    enum class Verdict { OVER, ON_POINT, UNDER }

    /**
     * The pooled ratio: Σ actual ÷ Σ planned over every pair with a positive estimate and a positive
     * actual. Null when nothing qualifies. This is what Statistics and The Record report.
     */
    fun overallRatio(pairs: List<Pair>): Double? {
        val usable = pairs.filter { it.plannedMin > 0 && it.actualMin > 0 }
        if (usable.isEmpty()) return null
        val est = usable.sumOf { it.plannedMin }
        if (est <= 0) return null
        return usable.sumOf { it.actualMin }.toDouble() / est
    }

    /**
     * The median of the per-item actual ÷ planned ratios, over pairs with both > 0 — a robust factor you
     * can multiply a future estimate by to self-correct. Null below [MIN_ITEMS_FOR_FACTOR] qualifying
     * pairs. This is what Momentum's forecast uses.
     */
    fun medianRatio(pairs: List<Pair>, minItems: Int = MIN_ITEMS_FOR_FACTOR): Double? {
        val ratios = pairs.filter { it.plannedMin > 0 && it.actualMin > 0 }
            .map { it.actualMin.toDouble() / it.plannedMin }
        if (ratios.size < minItems) return null
        return median(ratios)
    }

    /** Classify a ratio against a symmetric [tolerance] band (default ±10%) around 1.0. */
    fun classify(ratio: Double, tolerance: Double = 0.10): Verdict = when {
        ratio > 1.0 + tolerance -> Verdict.OVER
        ratio < 1.0 - tolerance -> Verdict.UNDER
        else -> Verdict.ON_POINT
    }

    /** Signed percentage a ratio runs over (positive) or under (negative) a perfect estimate. */
    fun percentOff(ratio: Double): Int = Math.round((ratio - 1.0) * 100).toInt()

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
