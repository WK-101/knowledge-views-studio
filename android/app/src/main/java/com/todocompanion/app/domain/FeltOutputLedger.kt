package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.domain.done.Accomplishment
import com.todocompanion.app.domain.done.DoneKind
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Track 3.1 — the felt × output ledger. A pure, Compose-free fold that joins the achievement feed
 * (per-day output: task-like finishes, focus minutes, wins) with the felt state the day logs carry
 * (day-rating) across an inclusive epoch-day window, and surfaces honest, strictly *descriptive*
 * findings about how doing more related — or didn't relate — to how the days felt.
 *
 * The point is candour, not a productivity scold: it can just as easily say "your best-rated days
 * weren't your busiest" as the reverse. Every finding is gated to a minimum qualifying sample and a
 * minimum effect size, carries the sample it rests on and a strength/confidence signal, and the whole
 * card ships the non-causal disclaimer. Nothing leaves the device; everything is derived from data the
 * Record already holds. Kept Compose-free so it unit-tests as plain Kotlin, mirroring ReviewInsights.
 */
object FeltOutputLedger {

    /** Below this many days that have BOTH a rating and known output we don't mine day-level patterns. */
    private const val MIN_PAIRED_DAYS = 8

    /** Each side of a high-vs-rest split needs at least this many members to be worth comparing. */
    private const val MIN_GROUP = 3

    /** A rating gap (1–5 scale) below this isn't a real separation worth reporting. */
    private const val MIN_RATING_GAP = 0.3

    /** A weekly finding needs at least this many qualifying weeks to split high-output vs the rest. */
    private const val MIN_WEEKS = 4

    /** A coarse, honest confidence band derived from [Finding.strength], for a plain-language label. */
    enum class Confidence(val label: String) { SLIGHT("slight"), MODERATE("moderate"), STRONG("strong") }

    /** One descriptive finding. [strength] (0..1) blends effect size and sample; never a causal claim. */
    data class Finding(val text: String, val strength: Double, val sampleSize: Int) {
        val confidence: Confidence
            get() = when {
                strength >= 0.60 -> Confidence.STRONG
                strength >= 0.35 -> Confidence.MODERATE
                else -> Confidence.SLIGHT
            }
    }

    /**
     * The ledger for a window. [correlation] is Pearson r between daily output and daily rating over the
     * days that had both (null when there is too little, or no variance, to report honestly).
     */
    data class Ledger(
        val startDay: Long,
        val endDay: Long,
        val pairedDays: Int,          // days with both a rating and a known output count
        val avgRating: Double,        // over rated days in range
        val avgOutputPerDay: Double,  // task-like finishes per day, over all days in range
        val correlation: Double?,     // r(output, rating) over paired days
        val findings: List<Finding>,
        val disclaimer: String = "These are descriptive patterns, not cause and effect.",
    ) {
        /** True when there is enough joined signal to render the card at all. */
        val hasData: Boolean get() = pairedDays >= MIN_PAIRED_DAYS && (findings.isNotEmpty() || correlation != null)
    }

    /** Per-day output the ledger reasons about. [tasks] is task-like finishes; the rest ride along. */
    data class DayOutput(val tasks: Int, val focusMin: Int, val wins: Int)

    /** The output for each day in [startDay]..[endDay] (0 for days with nothing finished). */
    fun outputByDay(startDay: Long, endDay: Long, feed: List<Accomplishment>): Map<Long, DayOutput> {
        val byDay = feed.filter { it.epochDay in startDay..endDay }.groupBy { it.epochDay }
        return (startDay..endDay).associateWith { d ->
            val items = byDay[d].orEmpty()
            DayOutput(
                tasks = items.count { it.isTaskLike },
                focusMin = items.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin },
                wins = items.count { it.isWin },
            )
        }
    }

    /**
     * Join the achievement [feed] with the felt [dayLogs] over the inclusive window [startDay]..[endDay]
     * and mine descriptive findings. Both inputs are plain data the caller already holds (day logs must
     * be workspace-scoped). Returns an empty-ish ledger when there is too little joined history.
     */
    fun compute(startDay: Long, endDay: Long, feed: List<Accomplishment>, dayLogs: List<DayLogEntity>): Ledger {
        if (endDay < startDay) return Ledger(startDay, endDay, 0, 0.0, 0.0, null, emptyList())
        val output = outputByDay(startDay, endDay, feed)
        val logByDay = dayLogs.filter { it.epochDay in startDay..endDay }.associateBy { it.epochDay }

        val ratedDays = (startDay..endDay).filter { (logByDay[it]?.dayRating ?: 0) in 1..5 }
        val ratingByDay = ratedDays.associateWith { logByDay[it]!!.dayRating.toDouble() }
        val avgRating = if (ratedDays.isEmpty()) 0.0 else ratedDays.map { ratingByDay.getValue(it) }.average()
        val avgOutput = if (output.isEmpty()) 0.0 else output.values.map { it.tasks.toDouble() }.average()

        val paired = ratedDays  // every rated day has an output count (0 when nothing was finished)
        if (paired.size < MIN_PAIRED_DAYS) {
            return Ledger(startDay, endDay, paired.size, avgRating, avgOutput, null, emptyList())
        }

        val xs = paired.map { output.getValue(it).tasks.toDouble() }
        val ys = paired.map { ratingByDay.getValue(it) }
        val r = pearson(xs, ys)

        val findings = mutableListOf<Finding>()
        correlationFinding(r, paired.size)?.let { findings += it }
        bestRatedDaysBusyness(paired, ratingByDay, output)?.let { findings += it }
        highOutputWeeks(paired, ratingByDay, output)?.let { findings += it }
        focusOnBestDays(paired, ratingByDay, output)?.let { findings += it }

        return Ledger(
            startDay = startDay, endDay = endDay, pairedDays = paired.size,
            avgRating = avgRating, avgOutputPerDay = avgOutput, correlation = r,
            findings = findings.sortedWith(compareByDescending<Finding> { it.strength }.thenBy { it.text }),
        )
    }

    // ── Finding: the overall correlation, put in plain words ──
    private fun correlationFinding(r: Double?, n: Int): Finding? {
        if (r == null) return null
        if (abs(r) < 0.15) {
            return Finding("Across ${n} rated days, how much you finished barely tracked with how the day felt — the two moved almost independently.", strengthFromR(abs(r), n).coerceAtLeast(0.2), n)
        }
        val dir = if (r > 0) "higher" else "lower"
        val band = when {
            abs(r) >= 0.5 -> "a clear"
            abs(r) >= 0.3 -> "a modest"
            else -> "a faint"
        }
        return Finding("There's $band tendency for busier days to feel $dir (r = ${twoDp(r)} over $n rated days).", strengthFromR(abs(r), n), n)
    }

    // ── Finding: were your best-rated days actually your busiest? ──
    private fun bestRatedDaysBusyness(
        paired: List<Long>, ratingByDay: Map<Long, Double>, output: Map<Long, DayOutput>,
    ): Finding? {
        val sorted = paired.sortedByDescending { ratingByDay.getValue(it) }
        val n = ceil(sorted.size / 3.0).toInt().coerceIn(1, sorted.size - 1)
        val best = sorted.take(n)
        val rest = sorted.drop(n)
        if (best.size < MIN_GROUP || rest.size < MIN_GROUP) return null
        val bestRating = best.map { ratingByDay.getValue(it) }.average()
        val restRating = rest.map { ratingByDay.getValue(it) }.average()
        if (bestRating - restRating < MIN_RATING_GAP) return null
        val bestOut = best.map { output.getValue(it).tasks.toDouble() }.average()
        val restOut = rest.map { output.getValue(it).tasks.toDouble() }.average()
        val gap = bestOut - restOut
        val text = if (gap <= 0.3) {
            "Your best-rated days weren't your busiest — they averaged ${oneDp(bestOut)} finished vs ${oneDp(restOut)} on the rest."
        } else {
            "Your best-rated days were also your busiest — ${oneDp(bestOut)} finished vs ${oneDp(restOut)} on the rest."
        }
        return Finding(text, strengthOf(abs(gap), 3.0, best.size + rest.size), best.size + rest.size)
    }

    // ── Finding: how did your highest-output weeks feel? ──
    private fun highOutputWeeks(
        paired: List<Long>, ratingByDay: Map<Long, Double>, output: Map<Long, DayOutput>,
    ): Finding? {
        data class Wk(val outputSum: Int, val avgRating: Double)
        val byWeek = paired.groupBy { weekKey(it) }
            .mapValues { (_, days) ->
                Wk(days.sumOf { output.getValue(it).tasks }, days.map { ratingByDay.getValue(it) }.average())
            }
            .filterValues { it.avgRating > 0 }
        if (byWeek.size < MIN_WEEKS) return null
        val weeks = byWeek.values.sortedByDescending { it.outputSum }
        val topN = ceil(weeks.size / 3.0).toInt().coerceIn(1, weeks.size - 1)
        val top = weeks.take(topN)
        val rest = weeks.drop(topN)
        if (top.isEmpty() || rest.isEmpty()) return null
        val topRating = top.map { it.avgRating }.average()
        val restRating = rest.map { it.avgRating }.average()
        val gap = topRating - restRating
        if (abs(gap) < MIN_RATING_GAP) return null
        val dir = if (gap > 0) "higher" else "lower"
        val text = "Your highest-output weeks averaged ${oneDp(abs(gap))}★ $dir than your quieter weeks (${oneDp(topRating)} vs ${oneDp(restRating)})."
        return Finding(text, strengthOf(abs(gap), 1.5, byWeek.size), byWeek.size)
    }

    // ── Finding: did focus-heavy days feel different? ──
    private fun focusOnBestDays(
        paired: List<Long>, ratingByDay: Map<Long, Double>, output: Map<Long, DayOutput>,
    ): Finding? {
        val withFocus = paired.filter { output.getValue(it).focusMin > 0 }
        val without = paired.filter { output.getValue(it).focusMin == 0 }
        if (withFocus.size < MIN_GROUP || without.size < MIN_GROUP) return null
        val a = withFocus.map { ratingByDay.getValue(it) }.average()
        val b = without.map { ratingByDay.getValue(it) }.average()
        val gap = a - b
        if (abs(gap) < MIN_RATING_GAP) return null
        val dir = if (gap > 0) "higher" else "lower"
        return Finding("Days you ran a focus session rate $dir on average (${oneDp(a)} vs ${oneDp(b)}).", strengthOf(abs(gap), 1.5, withFocus.size + without.size), withFocus.size + without.size)
    }

    // ── Shared helpers ──

    /** Pearson r, or null when either side has no variance (a flat line has no correlation to report). */
    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < 3) return null
        val n = xs.size
        val mx = xs.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        if (sxx <= 0.0 || syy <= 0.0) return null
        return (sxy / sqrt(sxx * syy)).coerceIn(-1.0, 1.0)
    }

    /** ISO week key ("2026-W36") so weeks group stably across a year boundary. */
    private fun weekKey(day: Long): String {
        val d = LocalDate.ofEpochDay(day)
        return "${d.get(IsoFields.WEEK_BASED_YEAR)}-W${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
    }

    private fun strengthOf(gap: Double, scale: Double, n: Int): Double {
        val effect = (gap / scale).coerceIn(0.0, 1.0)
        val conf = (n.toDouble() / (MIN_PAIRED_DAYS * 2)).coerceIn(0.0, 1.0)
        return (effect * 0.65 + conf * 0.35).coerceIn(0.0, 1.0)
    }

    private fun strengthFromR(absR: Double, n: Int): Double {
        val conf = (n.toDouble() / (MIN_PAIRED_DAYS * 2)).coerceIn(0.0, 1.0)
        return (absR.coerceIn(0.0, 1.0) * 0.65 + conf * 0.35).coerceIn(0.0, 1.0)
    }

    private fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun twoDp(v: Double): String = String.format(Locale.US, "%.2f", v)
}
