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
    fun strength(habit: HabitEntity, doneDays: Set<Long>, skipDays: Set<Long>, relapseDays: Set<Long>, today: Long, halfLifeDays: Double = 15.0): Int {
        val start = habit.startEpochDay()
        if (today < start) return 0
        val span = (today - start).coerceAtMost(730)      // cap at 2 years; older barely affects the EMA
        val alpha = 1.0 - 0.5.pow(1.0 / halfLifeDays.coerceAtLeast(1.0))
        var expEma = 0.0; var actEma = 0.0
        var d = today - span
        while (d <= today) {
            val (e, a) = sample(habit, d, doneDays, skipDays, relapseDays)
            if (d in skipDays) { /* neutral: don't move either EMA */ } else {
                expEma += alpha * (e - expEma)
                actEma += alpha * (a - actEma)
            }
            d++
        }
        if (expEma <= 1e-6) return 0
        return ((actEma / expEma).coerceIn(0.0, 1.0) * 100).toInt()
    }

    /** One day's (expected, actual) contribution for the strength EMA. */
    private fun sample(habit: HabitEntity, day: Long, doneDays: Set<Long>, skipDays: Set<Long>, relapseDays: Set<Long>): Pair<Double, Double> {
        if (habit.habitType == "break") {
            // Daily abstinence: expect 1 "clean" per day; a relapse scores 0.
            return 1.0 to (if (day in relapseDays) 0.0 else 1.0)
        }
        return when (habit.freqType) {
            FREQ_TIMES_WEEK -> (habit.freqParam.coerceAtLeast(1) / 7.0) to (if (day in doneDays) 1.0 else 0.0)
            FREQ_TIMES_MONTH -> (habit.freqParam.coerceAtLeast(1) / 30.0) to (if (day in doneDays) 1.0 else 0.0)
            else -> if (isExpectedDay(habit, day)) 1.0 to (if (day in doneDays) 1.0 else 0.0) else 0.0 to 0.0
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
