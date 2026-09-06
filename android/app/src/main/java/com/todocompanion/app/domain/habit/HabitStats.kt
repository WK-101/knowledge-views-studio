package com.todocompanion.app.domain.habit

import com.todocompanion.app.data.entity.HabitEntity
import kotlin.math.pow

/** Habit streak / completion / strength maths. Pure, so it's unit-testable. */
object HabitStats {

    // ---- legacy weekly helpers (still used by the widget & simple call sites) ----

    /** Current streak: consecutive completed days ending today (or yesterday if today isn't done). */
    fun streak(doneDays: Set<Long>, today: Long): Int {
        var d = if (today in doneDays) today else today - 1
        var n = 0
        while (d in doneDays) { n++; d-- }
        return n
    }

    /** Completion rate over the last [window] days ending today, as 0..1. */
    fun rate(doneDays: Set<Long>, today: Long, window: Int = 30): Float {
        if (window <= 0) return 0f
        val hits = (0 until window).count { (today - it) in doneDays }
        return hits.toFloat() / window
    }

    /** Parse a "1,3,5" schedule string into day-of-week numbers (1=Mon..7=Sun). Empty = every day. */
    fun parseSchedule(s: String): Set<Int> = s.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()

    private fun dow(epochDay: Long): Int = java.time.LocalDate.ofEpochDay(epochDay).dayOfWeek.value

    fun isScheduled(epochDay: Long, schedule: Set<Int>): Boolean = schedule.isEmpty() || dow(epochDay) in schedule

    /** Schedule-aware streak: consecutive *scheduled* days done, skipping (not breaking on) off days. */
    fun streak(doneDays: Set<Long>, today: Long, schedule: Set<Int>): Int {
        if (schedule.isEmpty()) return streak(doneDays, today)
        var d = today
        if (dow(d) in schedule && d !in doneDays) d--
        var n = 0
        var guard = 0
        while (guard++ < 4000) {
            if (dow(d) in schedule) { if (d in doneDays) { n++; d-- } else break } else d--
        }
        return n
    }

    /** Schedule-aware rate: done scheduled days / scheduled days, over the window. */
    fun rate(doneDays: Set<Long>, today: Long, schedule: Set<Int>, window: Int = 30): Float {
        if (schedule.isEmpty()) return rate(doneDays, today, window)
        var scheduled = 0; var hits = 0
        for (i in 0 until window) { val d = today - i; if (dow(d) in schedule) { scheduled++; if (d in doneDays) hits++ } }
        return if (scheduled == 0) 0f else hits.toFloat() / scheduled
    }

    // ---- Tier I: frequency-aware, skip-aware, strength-scored ----

    const val FREQ_WEEKLY = "weekly"
    const val FREQ_TIMES_WEEK = "times_week"
    const val FREQ_TIMES_MONTH = "times_month"
    const val FREQ_INTERVAL = "interval"

    /** Does a count meet the habit's goal for one day (comparison-aware, for build habits). */
    fun meetsGoal(habit: HabitEntity, count: Int): Boolean {
        val t = habit.targetPerDay.coerceAtLeast(1)
        return if (habit.targetComparison == "atmost") count in 1..t else count >= t
    }

    /** For a *break* habit, a day counts as a relapse when the recorded count exceeds the limit. */
    fun isRelapse(habit: HabitEntity, count: Int): Boolean = habit.habitType == "break" && count > habit.targetPerDay

    /** Whether [epochDay] is an "expected" day for weekday/interval frequencies (times_* = any day). */
    fun isExpectedDay(habit: HabitEntity, epochDay: Long): Boolean {
        if (epochDay < habit.startEpochDay()) return false
        return when (habit.freqType) {
            FREQ_INTERVAL -> {
                val n = habit.freqParam.coerceAtLeast(1)
                ((epochDay - habit.startEpochDay()) % n + n) % n == 0L
            }
            FREQ_TIMES_WEEK, FREQ_TIMES_MONTH -> true   // any day can satisfy a rolling quota
            else -> isScheduled(epochDay, parseSchedule(habit.scheduleDays))
        }
    }

    /** Should the user still act on this habit today? (Not paused, expected, and not already satisfied.) */
    fun dueToday(habit: HabitEntity, today: Long, doneDays: Set<Long>, todayCount: Int): Boolean {
        if (habit.paused || habit.archived) return false
        // A break habit has no positive daily action — success is passive (not exceeding the limit),
        // so it is never "due" to complete. This keeps it out of the due strip, batch check-in and the
        // perfect-day tally, where treating it as due would mislog a relapse or block the celebration.
        if (habit.habitType == "break") return false
        if (today < habit.startEpochDay()) return false
        return when (habit.freqType) {
            FREQ_TIMES_WEEK -> rollingCount(doneDays, today, 7) < habit.freqParam.coerceAtLeast(1)
            FREQ_TIMES_MONTH -> rollingCount(doneDays, today, 30) < habit.freqParam.coerceAtLeast(1)
            else -> isExpectedDay(habit, today) && !meetsGoal(habit, todayCount)
        }
    }

    private fun rollingCount(doneDays: Set<Long>, endDay: Long, window: Int): Int =
        (0 until window).count { (endDay - it) in doneDays }

    private fun rollingSatisfied(doneDays: Set<Long>, day: Long, window: Int, target: Int): Boolean =
        rollingCount(doneDays, day, window) >= target.coerceAtLeast(1)

    /**
     * Current streak, frequency- and skip-aware.
     * - weekly / interval: consecutive *expected* days done, off/skip days skipped over.
     * - times_week / times_month: consecutive days on which the trailing quota window is satisfied.
     * - break: days since the last relapse (i.e. days "clean").
     */
    fun currentStreak(habit: HabitEntity, doneDays: Set<Long>, skipDays: Set<Long>, relapseDays: Set<Long>, today: Long): Int {
        val start = habit.startEpochDay()
        if (habit.habitType == "break") {
            var d = today; var n = 0; var guard = 0
            while (guard++ < 20000 && d >= start) { if (d in relapseDays) break; n++; d-- }
            return n
        }
        return when (habit.freqType) {
            FREQ_TIMES_WEEK -> rollingStreak(doneDays, today, 7, habit.freqParam)
            FREQ_TIMES_MONTH -> rollingStreak(doneDays, today, 30, habit.freqParam)
            else -> {
                var d = today
                // In-progress expected day that isn't done yet shouldn't break the streak.
                if (isExpectedDay(habit, d) && d !in doneDays && d !in skipDays) d--
                var n = 0; var guard = 0
                while (guard++ < 20000 && d >= start) {
                    when {
                        !isExpectedDay(habit, d) || d in skipDays -> d--          // neutral
                        d in doneDays -> { n++; d-- }
                        else -> break
                    }
                }
                n
            }
        }
    }

    // ---- Tier V1: time-since / between-events ----
    /** Whole days since the habit was last done (0 = today). -1 if never done. */
    fun daysSinceLastDone(doneDays: Set<Long>, today: Long): Int {
        val last = doneDays.filter { it <= today }.maxOrNull() ?: return -1
        return (today - last).toInt().coerceAtLeast(0)
    }

    /** Average gap in days between consecutive done days over [window]. Null with fewer than two. */
    fun averageGapDays(doneDays: Set<Long>, today: Long, window: Int = 90): Double? {
        val days = doneDays.filter { it in (today - window + 1)..today }.sorted()
        if (days.size < 2) return null
        return days.zipWithNext { a, b -> (b - a).toDouble() }.average()
    }

    /** Longest gap in days between done days over [window]. 0 if fewer than two done days. */
    fun longestGapDays(doneDays: Set<Long>, today: Long, window: Int = 90): Int {
        val days = doneDays.filter { it in (today - window + 1)..today }.sorted()
        if (days.size < 2) return 0
        return days.zipWithNext { a, b -> (b - a).toInt() }.maxOrNull() ?: 0
    }

    // ---- Tier V2: gradated day grade (partial-credit + over-achievement) ----
    enum class DayGrade { NONE, PARTIAL, MET, EXTRA }

    /** Grade one day's count against the habit's target and optional stretch (extraTarget). For "atmost"
     *  (reduce/quit) habits, staying at/under target is MET; exceeding it is NONE. */
    fun grade(habit: HabitEntity, count: Int): DayGrade {
        val t = habit.targetPerDay.coerceAtLeast(1)
        if (habit.targetComparison == "atmost" || habit.habitType == "break") {
            return if (count <= t) DayGrade.MET else DayGrade.NONE
        }
        val extra = habit.extraTarget
        return when {
            count <= 0 -> DayGrade.NONE
            extra != null && extra > t && count >= extra -> DayGrade.EXTRA
            count >= t -> DayGrade.MET
            else -> DayGrade.PARTIAL
        }
    }

    /** The streak to display: forgiving (U8) when the user opts in, else the strict current streak. */
    fun displayStreak(habit: HabitEntity, doneDays: Set<Long>, skipDays: Set<Long>, relapseDays: Set<Long>, today: Long, forgiving: Boolean): Int =
        if (forgiving) forgivingStreak(habit, doneDays, skipDays, relapseDays, today)
        else currentStreak(habit, doneDays, skipDays, relapseDays, today)

    /** The *best* streak to display — in the SAME unit as [displayStreak], so "current" can never exceed
     *  "best". When forgiving is on we take the longest forgiving run ever (each done-day treated as a
     *  possible run-end); otherwise the strict all-time best. */
    fun displayBestStreak(habit: HabitEntity, doneDays: Set<Long>, skipDays: Set<Long>, relapseDays: Set<Long>, today: Long, forgiving: Boolean): Int {
        val strictBest = bestStreak(habit, doneDays, skipDays, relapseDays, today)
        if (!forgiving || habit.habitType == "break" || habit.freqType == FREQ_TIMES_WEEK || habit.freqType == FREQ_TIMES_MONTH) return strictBest
        val start = habit.startEpochDay()
        var best = strictBest
        doneDays.asSequence().filter { it in start..today }.forEach { end ->
            best = maxOf(best, forgivingStreak(habit, doneDays, skipDays, relapseDays, end))
        }
        // Include the live run so "best" is always ≥ the currently-shown forgiving "current".
        return maxOf(best, forgivingStreak(habit, doneDays, skipDays, relapseDays, today))
    }

    /**
     * Tier U8 — a *forgiving* streak. Like [currentStreak] for weekday/interval habits, but a bounded
     * number of misses is tolerated ([missesPerWeek] per rolling seven expected days) before the streak
     * breaks, so one off day never wipes weeks of momentum. Returns the count of expected days actually
     * done within the still-alive run. Rolling-quota (times_week/month) and break habits already model
     * their own grace, so they defer to [currentStreak] unchanged.
     */
    fun forgivingStreak(habit: HabitEntity, doneDays: Set<Long>, skipDays: Set<Long>, relapseDays: Set<Long>, today: Long, missesPerWeek: Int = 1): Int {
        if (habit.habitType == "break" || habit.freqType == FREQ_TIMES_WEEK || habit.freqType == FREQ_TIMES_MONTH) {
            return currentStreak(habit, doneDays, skipDays, relapseDays, today)
        }
        val start = habit.startEpochDay()
        var d = today
        if (isExpectedDay(habit, d) && d !in doneDays && d !in skipDays) d--   // in-progress day is grace
        var doneCount = 0; var expectedSeen = 0; var misses = 0; var guard = 0
        while (guard++ < 20000 && d >= start) {
            if (!isExpectedDay(habit, d) || d in skipDays) { d--; continue }
            expectedSeen++
            if (d in doneDays) doneCount++
            else {
                misses++
                val allowed = (expectedSeen * missesPerWeek.coerceAtLeast(0)) / 7
                if (misses > allowed) break
            }
            d--
        }
        return doneCount
    }

    private fun rollingStreak(doneDays: Set<Long>, today: Long, window: Int, target: Int): Int {
        var d = today
        if (!rollingSatisfied(doneDays, d, window, target)) d--   // grace for an in-progress day
        var n = 0; var guard = 0
        while (guard++ < 20000 && rollingSatisfied(doneDays, d, window, target)) { n++; d-- }
        return n
    }

    /** Longest streak ever, using the same rule as [currentStreak], scanning from start to today. */
    fun bestStreak(habit: HabitEntity, doneDays: Set<Long>, skipDays: Set<Long>, relapseDays: Set<Long>, today: Long): Int {
        val start = habit.startEpochDay()
        if (today < start) return 0
        var best = 0
        if (habit.habitType == "break") {
            var run = 0
            var d = start
            while (d <= today) { if (d in relapseDays) run = 0 else { run++; best = maxOf(best, run) }; d++ }
            return best
        }
        when (habit.freqType) {
            FREQ_TIMES_WEEK, FREQ_TIMES_MONTH -> {
                val w = if (habit.freqType == FREQ_TIMES_WEEK) 7 else 30
                var run = 0; var d = start
                while (d <= today) { if (rollingSatisfied(doneDays, d, w, habit.freqParam)) { run++; best = maxOf(best, run) } else run = 0; d++ }
            }
            else -> {
                var run = 0; var d = start
                while (d <= today) {
                    when {
                        !isExpectedDay(habit, d) || d in skipDays -> {}
                        d in doneDays -> { run++; best = maxOf(best, run) }
                        else -> run = 0
                    }
                    d++
                }
            }
        }
        return best
    }

    /**
     * Habit strength: a 0–100 consistency score via an exponential moving average of expected vs.
     * actual completion. Skips are neutral (they carry the score). Recent behaviour dominates, so
     * it recovers when you resume and decays when you lapse — the metric every specialist app leads
     * with, and one raw streaks can't express. Frequency-agnostic.
     */
    fun strength(
        habit: HabitEntity, doneDays: Set<Long>, skipDays: Set<Long>, relapseDays: Set<Long>, today: Long,
        halfLifeDays: Double = 15.0, gradedCredit: Map<Long, Double> = emptyMap(),
    ): Int {
        val start = habit.startEpochDay()
        if (today < start) return 0
        val span = (today - start).coerceAtMost(730)      // cap at 2 years; older barely affects the EMA
        val alpha = 1.0 - 0.5.pow(1.0 / halfLifeDays.coerceAtLeast(1.0))
        var expEma = 0.0; var actEma = 0.0
        var d = today - span
        while (d <= today) {
            val (e, a) = sample(habit, d, doneDays, skipDays, relapseDays, gradedCredit)
            if (d in skipDays) { /* neutral: don't move either EMA */ } else {
                expEma += alpha * (e - expEma)
                actEma += alpha * (a - actEma)
            }
            d++
        }
        if (expEma <= 1e-6) return 0
        return ((actEma / expEma).coerceIn(0.0, 1.0) * 100).toInt()
    }

    /**
     * One day's (expected, actual) contribution for the strength EMA. Z8: [gradedCredit] optionally
     * gives a build-habit day that was attempted but fell short a fractional actual (0..1) instead of a
     * flat 0 — but only when the day isn't already a full "done". Empty map = the original binary scoring,
     * so every existing caller is unchanged.
     */
    private fun sample(
        habit: HabitEntity, day: Long, doneDays: Set<Long>, skipDays: Set<Long>, relapseDays: Set<Long>,
        gradedCredit: Map<Long, Double> = emptyMap(),
    ): Pair<Double, Double> {
        if (habit.habitType == "break") {
            // Daily abstinence: expect 1 "clean" per day; a relapse scores 0.
            return 1.0 to (if (day in relapseDays) 0.0 else 1.0)
        }
        fun actual(): Double = if (day in doneDays) 1.0 else gradedCredit[day]?.coerceIn(0.0, 1.0) ?: 0.0
        return when (habit.freqType) {
            FREQ_TIMES_WEEK -> (habit.freqParam.coerceAtLeast(1) / 7.0) to actual()
            FREQ_TIMES_MONTH -> (habit.freqParam.coerceAtLeast(1) / 30.0) to actual()
            else -> if (isExpectedDay(habit, day)) 1.0 to actual() else 0.0 to 0.0
        }
    }

    /** Completion rate 0..1 over [window] days, frequency- and skip-aware (expected days only). */
    fun rate(habit: HabitEntity, doneDays: Set<Long>, skipDays: Set<Long>, today: Long, window: Int = 30): Float {
        var expected = 0.0; var hits = 0.0
        for (i in 0 until window) {
            val d = today - i
            if (d < habit.startEpochDay() || d in skipDays) continue
            when (habit.freqType) {
                FREQ_TIMES_WEEK -> { expected += habit.freqParam.coerceAtLeast(1) / 7.0; if (d in doneDays) hits += 1 }
                FREQ_TIMES_MONTH -> { expected += habit.freqParam.coerceAtLeast(1) / 30.0; if (d in doneDays) hits += 1 }
                else -> if (isExpectedDay(habit, d)) { expected += 1; if (d in doneDays) hits += 1 }
            }
        }
        return if (expected <= 0.0) 0f else (hits / expected).coerceIn(0.0, 1.0).toFloat()
    }

    /** Per-weekday completion rate (Mon..Sun) over the last [window] days — the "which day am I best" chart. */
    fun weekdayRates(doneDays: Set<Long>, skipDays: Set<Long>, today: Long, window: Int = 180): FloatArray {
        val hit = IntArray(7); val opp = IntArray(7)
        for (i in 0 until window) {
            val d = today - i
            if (d in skipDays) continue
            val idx = dow(d) - 1
            opp[idx]++; if (d in doneDays) hit[idx]++
        }
        return FloatArray(7) { if (opp[it] == 0) 0f else hit[it].toFloat() / opp[it] }
    }

    /**
     * B1 — the habits×time intersection (the "unclaimed" cross-module metric): average focused minutes on
     * days this habit was done vs. days it wasn't, over the recent window since the habit began. Both sides
     * carry their day counts so the caller can gate on significance before surfacing "on days you meditate,
     * you focus 42% more". Only days from [sinceDay] onward count, so pre-habit history never dilutes it.
     */
    data class FocusLift(val onAvgMin: Int, val offAvgMin: Int, val onDays: Int, val offDays: Int) {
        /** Relative lift in focused minutes on done-days, as a signed percentage (e.g. +42, -15). */
        val liftPct: Int get() = if (offAvgMin <= 0) 0 else ((onAvgMin - offAvgMin) * 100 / offAvgMin)
    }
    fun focusLift(doneDays: Set<Long>, focusMinByDay: Map<Long, Int>, today: Long, sinceDay: Long, window: Int = 90): FocusLift {
        var onSum = 0; var onN = 0; var offSum = 0; var offN = 0
        val from = maxOf(today - window + 1, sinceDay)
        var d = from
        while (d <= today) {
            val m = focusMinByDay[d] ?: 0
            if (d in doneDays) { onSum += m; onN++ } else { offSum += m; offN++ }
            d++
        }
        return FocusLift(if (onN > 0) onSum / onN else 0, if (offN > 0) offSum / offN else 0, onN, offN)
    }

    /**
     * O2: the typical time-of-day this habit is actually logged — the median stamped completion
     * minute over recent done days. Needs at least [minSamples] stamps to be meaningful; else null.
     */
    fun typicalDoneMinute(checkins: List<com.todocompanion.app.data.entity.HabitCheckinEntity>, minSamples: Int = 4): Int? {
        val mins = checkins.filter { it.status == "done" && it.doneAtMinute != null }.mapNotNull { it.doneAtMinute }.sorted()
        if (mins.size < minSamples) return null
        return mins[mins.size / 2]
    }

    /** Format a minute-of-day (0–1439) as a short local clock label, e.g. "8:10 AM". */
    fun minuteLabel(minute: Int): String {
        val t = java.time.LocalTime.of((minute / 60).coerceIn(0, 23), (minute % 60).coerceIn(0, 59))
        return t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
    }

    /** A short human label for a habit's frequency. */
    fun frequencyLabel(habit: HabitEntity): String = when (habit.freqType) {
        FREQ_TIMES_WEEK -> "${habit.freqParam}× per week"
        FREQ_TIMES_MONTH -> "${habit.freqParam}× per month"
        FREQ_INTERVAL -> if (habit.freqParam <= 1) "Every day" else "Every ${habit.freqParam} days"
        else -> {
            val s = parseSchedule(habit.scheduleDays)
            if (s.isEmpty()) "Every day"
            else s.sorted().joinToString(" ") { java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
        }
    }
}
