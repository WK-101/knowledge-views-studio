package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.TimeEntryEntity

/**
 * Tier U · insight maths for the time-tracker — all pure, so it unit-tests and reuses freely.
 *
 * These are the computations a *unified* store makes possible and single-purpose apps cannot:
 *  · U6  plan-vs-actual reconciliation + an estimate-calibration factor
 *  · U7  cross-type correlation (does tracking X move habit Y?)
 *  · U9  deeper time analytics — split-by-hour, session-duration distribution
 *  · U11 per-tag totals
 *  · U1  which planned time-blocks went untracked (the "did you forget?" set)
 */
object TimeInsights {

    // ── U9 · split-by-hour ─────────────────────────────────────────────────────────────────────
    /** Minutes tracked in each hour-of-day bucket [0..23] for one day window. Splits an interval
     *  across the hours it spans. A running entry is clamped at [now]. */
    fun minutesByHour(entries: List<TimeEntryEntity>, dayStart: Long, dayEnd: Long, now: Long): IntArray {
        val buckets = IntArray(24)
        for (e in entries) {
            val end = e.endMillis ?: now
            var lo = maxOf(e.startMillis, dayStart)
            val hi = minOf(end, dayEnd)
            if (hi <= lo) continue
            while (lo < hi) {
                val hour = (((lo - dayStart) / 3_600_000L).toInt()).coerceIn(0, 23)
                val hourEnd = dayStart + (hour + 1) * 3_600_000L
                val segEnd = minOf(hi, hourEnd)
                buckets[hour] += ((segEnd - lo) / 60_000L).toInt()
                lo = segEnd
            }
        }
        return buckets
    }

    // ── U9 · session-duration distribution ─────────────────────────────────────────────────────
    data class DurationBucket(val label: String, val count: Int, val minutes: Int)

    private val BUCKET_BOUNDS = listOf(0 to 15, 15 to 30, 30 to 60, 60 to 120, 120 to Int.MAX_VALUE)
    private val BUCKET_LABELS = listOf("< 15m", "15–30m", "30–60m", "1–2h", "2h +")

    /** How your completed sessions distribute by length, over the window. Running entries excluded. */
    fun durationDistribution(entries: List<TimeEntryEntity>, winStart: Long, winEnd: Long): List<DurationBucket> {
        val counts = IntArray(5); val mins = IntArray(5)
        for (e in entries) {
            val end = e.endMillis ?: continue
            if (end <= winStart || e.startMillis >= winEnd) continue
            val m = ((end - e.startMillis) / 60_000L).toInt()
            if (m <= 0) continue
            val i = BUCKET_BOUNDS.indexOfFirst { m >= it.first && m < it.second }.let { if (it < 0) 4 else it }
            counts[i]++; mins[i] += m
        }
        return BUCKET_LABELS.mapIndexed { i, l -> DurationBucket(l, counts[i], mins[i]) }
    }

    // ── U11 · per-tag totals ────────────────────────────────────────────────────────────────────
    data class TagTotal(val tag: String, val minutes: Int)

    /** Minutes per tag inside the window (an interval counts toward each of its tags), largest first. */
    fun totalsByTag(entries: List<TimeEntryEntity>, winStart: Long, winEnd: Long, now: Long): List<TagTotal> {
        val acc = HashMap<String, Int>()
        for (e in entries) {
            val m = TimeTracking.minutesInWindow(e.startMillis, e.endMillis, winStart, winEnd, now)
            if (m <= 0) continue
            for (t in e.tagList()) acc[t] = (acc[t] ?: 0) + m
        }
        return acc.map { TagTotal(it.key, it.value) }.sortedByDescending { it.minutes }
    }

    // ── U6 · plan vs actual + calibration ───────────────────────────────────────────────────────
    data class PlanActualItem(val id: String, val label: String, val plannedMin: Int, val actualMin: Int)
    data class PlanActual(val items: List<PlanActualItem>, val plannedMin: Int, val actualMin: Int, val calibration: Double?)

    /**
     * Reconcile a set of planned items (each with an estimate and its tracked actual) into a scorecard.
     * [calibration] is the median actual/planned ratio over items that have both — multiply a future
     * estimate by it to get a self-correcting forecast. Null when there isn't enough signal (< 3 items).
     */
    fun planVsActual(items: List<PlanActualItem>): PlanActual {
        val planned = items.sumOf { it.plannedMin }
        val actual = items.sumOf { it.actualMin }
        val ratios = items.filter { it.plannedMin > 0 && it.actualMin > 0 }.map { it.actualMin.toDouble() / it.plannedMin }
        val cal = if (ratios.size >= 3) median(ratios) else null
        return PlanActual(items, planned, actual, cal)
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    // ── U7 · cross-type correlation ─────────────────────────────────────────────────────────────
    data class Conditional(val rateWith: Double, val rateWithout: Double, val withN: Int, val withoutN: Int) {
        /** Difference in success rate, condition-present minus condition-absent. */
        val lift: Double get() = rateWith - rateWithout
    }

    /**
     * Over [universe] candidate days, how often [success] occurs on days where [condition] holds versus
     * where it doesn't. The engine behind "your reading habit lands 82% on days you track a morning
     * routine, 40% otherwise." Caller decides whether the lift and sample sizes are worth surfacing.
     */
    fun conditionalRate(universe: Collection<Long>, success: Set<Long>, condition: Set<Long>): Conditional {
        var withHit = 0; var withN = 0; var withoutHit = 0; var withoutN = 0
        for (d in universe) {
            val ok = d in success
            if (d in condition) { withN++; if (ok) withHit++ } else { withoutN++; if (ok) withoutHit++ }
        }
        return Conditional(
            rateWith = if (withN == 0) 0.0 else withHit.toDouble() / withN,
            rateWithout = if (withoutN == 0) 0.0 else withoutHit.toDouble() / withoutN,
            withN = withN, withoutN = withoutN,
        )
    }

    // ── U1 · untracked planned blocks ───────────────────────────────────────────────────────────
    data class PlannedBlock(val taskId: String, val label: String, val startMin: Int, val durMin: Int)

    /**
     * Of the day's planned time-blocks, those with little or no tracked time against them — the set the
     * "did you forget to track?" prompt offers to backfill. A block counts as covered when a tracked
     * interval linked to its task, OR overlapping its clock window, accounts for ≥ [coverFraction] of it.
     */
    fun untrackedBlocks(
        blocks: List<PlannedBlock>,
        entries: List<TimeEntryEntity>,
        dayStart: Long,
        now: Long,
        coverFraction: Double = 0.5,
    ): List<PlannedBlock> {
        if (blocks.isEmpty()) return emptyList()
        return blocks.filter { b ->
            val winStart = dayStart + b.startMin * 60_000L
            val winEnd = dayStart + (b.startMin + b.durMin) * 60_000L
            val covered = entries.sumOf { e ->
                if (e.taskId == b.taskId) e.minutes(now)
                else TimeTracking.minutesInWindow(e.startMillis, e.endMillis, winStart, winEnd, now)
            }
            covered < b.durMin * coverFraction
        }
    }

    // ── U5 · timeline-fill: the untracked gaps between what you did log ──────────────────────────
    /** A stretch of the day with no tracked interval over it: [startMillis, endMillis). */
    data class Gap(val startMillis: Long, val endMillis: Long) { val minutes: Int get() = ((endMillis - startMillis) / 60_000L).toInt() }

    /**
     * "Account for my whole day" (U5): the untracked gaps in the day so every part of it can be filled in.
     * We merge overlapping/adjacent tracked intervals (clamped to the window and to [now]) and return the
     * holes between consecutive ones. When [trailingTo] is given (today's `now`), the stretch from the last
     * tracked interval up to that point is also returned — that's the live "you haven't tracked anything
     * since 3pm" gap the previous version dropped, which made untracked time look like it wasn't updating.
     * With trailing on, a single tracked interval is enough to surface a gap (the old code needed two).
     * Leading time before the first entry is still excluded (usually sleep). Gaps under [minGapMin] are noise.
     */
    fun untrackedGaps(entries: List<TimeEntryEntity>, winStart: Long, winEnd: Long, now: Long, minGapMin: Int = 10, trailingTo: Long? = null): List<Gap> {
        val ivals = entries.mapNotNull { e ->
            val s = maxOf(e.startMillis, winStart)
            val en = minOf(e.endMillis ?: now, winEnd)
            if (en > s) s to en else null
        }.sortedBy { it.first }
        if (ivals.isEmpty()) return emptyList()
        // Merge, then read off the holes.
        val merged = ArrayList<Pair<Long, Long>>()
        var curS = ivals[0].first; var curE = ivals[0].second
        for (i in 1 until ivals.size) {
            val (s, e) = ivals[i]
            if (s <= curE) curE = maxOf(curE, e) else { merged.add(curS to curE); curS = s; curE = e }
        }
        merged.add(curS to curE)
        val gaps = ArrayList<Gap>()
        for (i in 1 until merged.size) {
            val gs = merged[i - 1].second; val ge = merged[i].first
            if (ge - gs >= minGapMin * 60_000L) gaps.add(Gap(gs, ge))
        }
        // Live trailing gap: from the last tracked interval up to `trailingTo` (clamped to the window).
        if (trailingTo != null) {
            val gs = merged.last().second
            val ge = minOf(trailingTo, winEnd)
            // A currently-running interval already reaches `now`, so this only fires when nothing is live.
            if (ge - gs >= minGapMin * 60_000L) gaps.add(Gap(gs, ge))
        }
        return gaps
    }
}
