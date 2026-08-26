package com.todocompanion.app.domain

/**
 * Tier X · the reasoning layer — pure maths that fuse signals only a unified store holds together.
 * Every function here is deterministic and side-effect-free, so it unit-tests directly and the
 * ViewModel just feeds it real data. None of these is expressible by a single-purpose app:
 *
 *  · X2  keystone      — which habit's presence predicts a more productive day (habits × tasks)
 *  · X3  honest capacity — your real median focus-hours, not an assumed eight (tracked time)
 *  · X4  peak window    — the hours you actually get deep work done (tracked time × hour-of-day)
 *  · X5  forecast       — an honest end-of-day finish line (estimates × your calibration × hours left)
 *  · X6  rhythm fit     — the weekdays a "daily" habit is really kept on (habit rhythm)
 */
object Reasoning {

    // ── X2 · keystone habit ───────────────────────────────────────────────────────────────────────
    data class Keystone(val avgWith: Double, val avgWithout: Double, val withN: Int, val withoutN: Int) {
        /** Relative lift in the day-metric when the condition holds, e.g. +0.38 = 38% more. */
        val lift: Double get() = if (avgWithout <= 0.0) 0.0 else (avgWith - avgWithout) / avgWithout
    }

    /**
     * Over [universe] candidate days, the average of [metricPerDay] (e.g. tasks completed) on days
     * where [condition] holds (the habit was kept) versus days it didn't. Missing days count as 0.
     */
    fun keystone(universe: Collection<Long>, metricPerDay: Map<Long, Int>, condition: Set<Long>): Keystone {
        var withSum = 0; var withN = 0; var withoutSum = 0; var withoutN = 0
        for (d in universe) {
            val v = metricPerDay[d] ?: 0
            if (d in condition) { withN++; withSum += v } else { withoutN++; withoutSum += v }
        }
        return Keystone(
            avgWith = if (withN == 0) 0.0 else withSum.toDouble() / withN,
            avgWithout = if (withoutN == 0) 0.0 else withoutSum.toDouble() / withoutN,
            withN = withN, withoutN = withoutN,
        )
    }

    // ── X3 · honest capacity ────────────────────────────────────────────────────────────────────
    /** Median of the per-day tracked minutes, counting only days that had any tracking. Null when
     *  there are fewer than [minDays] such days — not enough signal to trust as a capacity figure. */
    fun medianDailyFocusMinutes(minutesByDay: Map<Long, Int>, minDays: Int = 5): Int? {
        val xs = minutesByDay.values.filter { it > 0 }.sorted()
        if (xs.size < minDays) return null
        val n = xs.size
        return if (n % 2 == 1) xs[n / 2] else (xs[n / 2 - 1] + xs[n / 2]) / 2
    }

    // ── X4 · peak focus window ────────────────────────────────────────────────────────────────────
    data class PeakWindow(val startHour: Int, val endHour: Int, val minutes: Int)

    /** The contiguous [len]-hour window (over an hour-of-day histogram) with the most tracked minutes.
     *  Null when the histogram is essentially empty (< 30 total minutes of signal). */
    fun peakWindow(byHour: IntArray, len: Int = 2): PeakWindow? {
        if (byHour.size != 24 || byHour.sum() < 30) return null
        val w = len.coerceIn(1, 24)
        var best = -1; var bestSum = -1
        for (start in 0..(24 - w)) {
            var s = 0
            for (h in start until start + w) s += byHour[h]
            if (s > bestSum) { bestSum = s; best = start }
        }
        if (best < 0 || bestSum <= 0) return null
        return PeakWindow(best, best + w, bestSum)
    }

    // ── X5 · end-of-day forecast ──────────────────────────────────────────────────────────────────
    data class Forecast(
        val willFinish: Int,   // how many of the remaining tasks fit the time left, at your real pace
        val willSlip: Int,     // how many won't
        val neededMin: Int,    // calibrated minutes the remaining work really needs
        val availMin: Int,     // minutes left in the working day
        val calibrated: Boolean,
    ) { val total get() = willFinish + willSlip }

    /**
     * Given each remaining task's [estimatesMin] (in ranked order — most important first), your
     * [calibration] factor (median actual/planned ratio, or null if unknown), and the [availMin]
     * minutes left today, greedily fit tasks into the remaining time and report how many finish vs slip.
     */
    fun forecast(estimatesMin: List<Int>, calibration: Double?, availMin: Int): Forecast {
        val factor = (calibration ?: 1.0).coerceIn(0.25, 4.0)
        var used = 0; var finish = 0; var slip = 0; var needed = 0
        for (e in estimatesMin) {
            val cost = Math.round(e * factor).toInt().coerceAtLeast(1)
            needed += cost
            if (used + cost <= availMin) { used += cost; finish++ } else slip++
        }
        return Forecast(finish, slip, needed, availMin.coerceAtLeast(0), calibration != null)
    }

    // ── X6 · rhythm-matched weekdays ──────────────────────────────────────────────────────────────
    /**
     * The set of ISO weekdays (1=Mon..7=Sun) a habit is really kept on, when its completions clearly
     * concentrate on a subset — the signal that a "daily" schedule should become specific weekdays.
     * Returns null unless there's enough history ([minCompletions]) AND the pattern is genuinely a
     * subset: the busiest weekdays covering ≥ [dominance] of completions form a set of 1..6 days, and
     * every excluded weekday is essentially unused (≤ 5% of completions each).
     */
    fun rhythmWeekdays(doneWeekdayCounts: IntArray, minCompletions: Int = 8, dominance: Double = 0.85): Set<Int>? {
        if (doneWeekdayCounts.size != 7) return null
        val total = doneWeekdayCounts.sum()
        if (total < minCompletions) return null
        // weekday indices 0..6 → ISO 1..7, ranked by count desc
        val ranked = (0..6).sortedByDescending { doneWeekdayCounts[it] }
        val chosen = LinkedHashSet<Int>()
        var acc = 0
        for (i in ranked) {
            if (doneWeekdayCounts[i] == 0) break
            chosen += i + 1
            acc += doneWeekdayCounts[i]
            if (acc.toDouble() / total >= dominance) break
        }
        if (chosen.isEmpty() || chosen.size >= 7) return null
        // every excluded weekday must be essentially unused
        val excludedOk = (0..6).all { (it + 1) in chosen || doneWeekdayCounts[it] <= total * 0.05 }
        return if (excludedOk) chosen.toSortedSet() else null
    }

    // ── Y6 · burnout divergence — hours climbing while habit adherence falls ──────────────────────
    /** True when tracked hours rose by ≥ [hoursUp] fraction week-over-week AND habit adherence fell by
     *  ≥ [rateDown] fraction — the earliest honest shape of over-work. Both prior figures must be > 0. */
    fun burnoutDiverges(
        hoursThisWk: Double, hoursPrevWk: Double, rateThisWk: Double, ratePrevWk: Double,
        hoursUp: Double = 0.25, rateDown: Double = 0.15,
    ): Boolean {
        if (hoursPrevWk <= 0.0 || ratePrevWk <= 0.0) return false
        val hUp = (hoursThisWk - hoursPrevWk) / hoursPrevWk
        val rDown = (ratePrevWk - rateThisWk) / ratePrevWk
        return hUp >= hoursUp && rDown >= rateDown
    }

    // ── Y7 · greedy fit on already-costed minutes (per-task calibrated forecast) ──────────────────
    /** Fit costs (in ranked order) into [availMin]; returns (fit, slip). Costs are pre-calibrated. */
    fun fitCount(costsMin: List<Int>, availMin: Int): Pair<Int, Int> {
        var used = 0; var finish = 0; var slip = 0
        for (c in costsMin) { if (used + c <= availMin) { used += c; finish++ } else slip++ }
        return finish to slip
    }

    // ── Y8 · seasonality — the heaviest and lightest weekday ──────────────────────────────────────
    /** Given a 7-slot weekday total (index 0=Mon..6=Sun), the (heaviest, lightest) ISO weekdays (1..7),
     *  or null when there's no signal or the spread between them is under [minShare] of the total. */
    fun heaviestLightestWeekday(byWeekday: DoubleArray, minShare: Double = 0.05): Pair<Int, Int>? {
        if (byWeekday.size != 7) return null
        val total = byWeekday.sum()
        if (total <= 0.0) return null
        val hi = (0..6).maxByOrNull { byWeekday[it] } ?: return null
        val lo = (0..6).minByOrNull { byWeekday[it] } ?: return null
        if (hi == lo) return null
        if ((byWeekday[hi] - byWeekday[lo]) / total < minShare) return null
        return (hi + 1) to (lo + 1)
    }
}
