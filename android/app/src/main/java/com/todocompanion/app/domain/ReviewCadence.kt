package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import java.time.LocalDate

/**
 * Phase F — the cadence mechanics of the Daily Review, as pure, testable Kotlin (no Android, no Compose,
 * mirroring HabitStats / ReviewRollup / DailyQuestions). Two independent concerns live here:
 *
 *  1. The **smart evening nudge**'s adaptive time: when the user opts to "adapt the reminder to when I
 *     usually close my day", the fixed evening hour is replaced by the median of their recent close times,
 *     clamped to a sane evening window. When they don't opt in, the caller keeps the fixed time untouched.
 *
 *  2. The **streak recovery** ("never miss twice"): the review streak is computed from reviewed days with
 *     a settings-side overlay of a small number of "repair" days. A single missed day (exactly yesterday)
 *     can be repaired with one grace token; a multi-day gap can not. Repairs are always a deliberate opt-in
 *     tap — never auto-consumed — and capped per period, so the recovery stays honest.
 */
object ReviewCadence {

    // ── 1 · adaptive evening reminder ────────────────────────────────────────────────────────────────

    /** The evening window (minute-of-day) an adaptive reminder is clamped into: 17:00–23:00. */
    const val WINDOW_START_MIN = 17 * 60
    const val WINDOW_END_MIN = 23 * 60

    /** How many recent reviewed days feed the adaptive time, and the minimum needed before we adapt. */
    const val SAMPLE_DAYS = 14
    const val MIN_SAMPLES = 3

    /**
     * The minute-of-day an adaptive evening reminder should fire at, given the [closeMinutes] the user
     * usually closes their day (minute-of-day of each recent close). Returns the median of the samples
     * clamped to [windowStart]..[windowEnd]; falls back to [fallbackMinuteOfDay] (unclamped — the user's
     * own fixed time) when there aren't yet [minSamples] samples to learn from. Pure.
     */
    fun adaptiveReminderMinuteOfDay(
        closeMinutes: List<Int>,
        fallbackMinuteOfDay: Int,
        windowStart: Int = WINDOW_START_MIN,
        windowEnd: Int = WINDOW_END_MIN,
        minSamples: Int = MIN_SAMPLES,
    ): Int {
        val valid = closeMinutes.filter { it in 0..1439 }
        if (valid.size < minSamples) return fallbackMinuteOfDay
        val sorted = valid.sorted()
        val n = sorted.size
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        val lo = minOf(windowStart, windowEnd)
        val hi = maxOf(windowStart, windowEnd)
        return median.coerceIn(lo, hi)
    }

    // ── 2 · review streak + single-miss recovery ─────────────────────────────────────────────────────

    /** How many streak repairs a user gets per calendar month. Small on purpose: recovery, not a loophole. */
    const val STREAK_REPAIR_CAP = 2

    /**
     * The state of the review streak once the settings-side repair overlay is applied.
     *
     * @param streak            the current streak length (reviewed days plus repaired days).
     * @param brokenBySingleGap the streak was broken by *exactly one* missed day (yesterday), so a repair
     *                          would bridge it — a multi-day gap sets this false.
     * @param repairableDay     the epoch day a repair would cover (yesterday), or null when a repair isn't
     *                          available (no single gap, or no tokens left). Never auto-applied.
     * @param tokensAvailable   remaining repair tokens for the current period.
     */
    data class StreakState(
        val streak: Int,
        val brokenBySingleGap: Boolean,
        val repairableDay: Long?,
        val tokensAvailable: Int,
    )

    /** Does this day's log count as "reviewed / closed"? Matches the Day Review's own reviewed-day test. */
    fun isReviewed(log: DayLogEntity): Boolean =
        log.pmReflection.isNotBlank() || log.dayRating > 0 || log.amIntention.isNotBlank() ||
            log.highlight.isNotBlank() || log.gratitude.isNotBlank() || log.lesson.isNotBlank() ||
            log.tomorrowFocus.isNotBlank()

    /**
     * Compute the review streak with the repair overlay. [repairedDays] are the epoch days the user chose
     * to repair (stored in settings, never fabricated in the DB); they count as covered alongside
     * [reviewedDays]. A single-gap repair is offered only when yesterday is the sole missing day between
     * today and an existing streak — i.e. yesterday is uncovered but the day before it is covered — and
     * [tokensAvailable] > 0. Pure; the tap that consumes a token lives in the ViewModel.
     */
    fun computeStreak(
        reviewedDays: Set<Long>,
        repairedDays: Set<Long>,
        today: Long,
        tokensAvailable: Int,
    ): StreakState {
        val covered = if (repairedDays.isEmpty()) reviewedDays else reviewedDays + repairedDays
        var d0 = if (today in covered) today else today - 1
        var streak = 0
        while (d0 in covered) { streak++; d0-- }
        val yesterday = today - 1
        // A single gap: yesterday is missing, but the day before it was covered (a real streak that a
        // single miss just broke). Two consecutive misses is a genuine break — never repairable.
        val singleGap = yesterday !in covered && (today - 2) in covered
        val repairable = singleGap && tokensAvailable > 0
        return StreakState(
            streak = streak,
            brokenBySingleGap = singleGap,
            repairableDay = if (repairable) yesterday else null,
            tokensAvailable = tokensAvailable.coerceAtLeast(0),
        )
    }

    /** "YYYY-MM" for an epoch day — the period a monthly token allowance is scoped to. */
    fun periodKey(epochDay: Long): String {
        val d = LocalDate.ofEpochDay(epochDay)
        return "%04d-%02d".format(d.year, d.monthValue)
    }

    /**
     * Effective repair tokens for [currentPeriod]: the stored count while still in the same period, or a
     * fresh [cap] when the month has rolled over since the stored count was written. Pure — a monthly
     * refill without a background writer; the ViewModel persists the new period when a token is consumed.
     */
    fun tokensForPeriod(storedTokens: Int, storedPeriod: String, currentPeriod: String, cap: Int = STREAK_REPAIR_CAP): Int =
        if (storedPeriod != currentPeriod) cap else storedTokens.coerceIn(0, cap)
}
