package com.todocompanion.app.domain.habit

import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * R33 — the "builder" brain: on-device, rules-only heuristics that turn tracked history into active
 * coaching. No LLM, no network. Everything here is a pure function over a habit + its check-ins.
 *
 *   F15 automaticity meter · F9 never-miss-twice · F10 ramp-up · F12 quit economics · F17 insights coach.
 */
object HabitBuilder {

    // ── F15 · 66-day automaticity (Lally curve) ──────────────────────────────────────────────────
    data class Automaticity(val reps: Int, val pct: Int, val stage: String)

    /** Automaticity rises asymptotically with repetitions (Lally 2010: ~66 days to plateau). We model
     *  it as 1 − e^(−reps/21), which reaches ~95% around 63 done days — distinct from a fragile streak. */
    fun automaticity(doneDays: Set<Long>): Automaticity {
        val reps = doneDays.size
        val pct = ((1.0 - exp(-reps / 21.0)) * 100).roundToInt().coerceIn(0, 99)
        val stage = when {
            reps == 0 -> "Not started"
            pct < 40 -> "Getting started"
            pct < 80 -> "Settling in"
            pct < 95 -> "Nearly automatic"
            else -> "Automatic"
        }
        return Automaticity(reps, pct, stage)
    }

    // ── F9 · never miss twice ────────────────────────────────────────────────────────────────────
    data class MissStatus(val missedLast: Boolean, val atRiskToday: Boolean, val lastMissedDay: Long?)

    /** Did we miss the previous expected day, and is today the second at-risk day? "Missing once is an
     *  accident; missing twice is the start of a new habit" (Clear). */
    fun missStatus(habit: HabitEntity, doneDays: Set<Long>, skipDays: Set<Long>, today: Long, todayDone: Boolean): MissStatus {
        if (habit.habitType == "break" || habit.paused) return MissStatus(false, false, null)
        // Most recent expected day strictly before today.
        var d = today - 1; var guard = 0; var prevExpected: Long? = null
        while (guard++ < 90) { if (d < habit.startEpochDay()) break; if (HabitStats.isExpectedDay(habit, d)) { prevExpected = d; break }; d-- }
        val missedLast = prevExpected != null && prevExpected !in doneDays && prevExpected !in skipDays
        val expectedToday = HabitStats.isExpectedDay(habit, today) && today >= habit.startEpochDay()
        return MissStatus(missedLast, missedLast && expectedToday && !todayDone, prevExpected?.takeIf { missedLast })
    }

    // ── F10 · two-minute ramp-up ─────────────────────────────────────────────────────────────────
    /** If the habit is ramping and consistency has held over the last step window, the new (higher) target
     *  it should move to — else null. Caller persists the bump. */
    fun rampNextTarget(habit: HabitEntity, doneDays: Set<Long>, today: Long): Int? {
        val goal = habit.rampFinalTarget ?: return null
        if (habit.targetPerDay >= goal) return null
        val since = if (habit.rampLastStepDay > 0) habit.rampLastStepDay else habit.startEpochDay()
        if (today - since < habit.rampStepDays) return null
        // Held it? At least 70% of the expected days in the window were done.
        val expected = ((today - habit.rampStepDays + 1)..today).filter { HabitStats.isExpectedDay(habit, it) && it >= habit.startEpochDay() }
        if (expected.isEmpty()) return null
        val held = expected.count { it in doneDays }.toDouble() / expected.size
        if (held < 0.7) return null
        return (habit.targetPerDay + habit.rampAddPerStep.coerceAtLeast(1)).coerceAtMost(goal)
    }

    // ── F12 · quit economics ─────────────────────────────────────────────────────────────────────
    data class QuitStats(val cleanDays: Int, val cleanStartMillis: Long, val moneySaved: Double, val minutesSaved: Int, val lastSlipDay: Long?)

    /** Clean-time (reset by any relapse day), plus money & time saved — computed from the per-day avoided
     *  amount (the break habit's targetPerDay in full-quit mode) × the per-unit cost/time the user set. */
    fun quitStats(habit: HabitEntity, checkins: List<HabitCheckinEntity>, today: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): QuitStats {
        val relapses = checkins.filter { it.habitId == habit.id && HabitStats.isRelapse(habit, it.count) }.map { it.epochDay }
        val lastSlip = relapses.maxOrNull()
        val anchorDay = habit.quitSinceMillis?.let { LocalDate.ofEpochDay(java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay()).toEpochDay() }
            ?: habit.startEpochDay(zone)
        val startDay = maxOf(anchorDay, (lastSlip?.plus(1)) ?: anchorDay)
        val cleanDays = (today - startDay + 1).toInt().coerceAtLeast(0)
        val avoidedPerDay = habit.targetPerDay.coerceAtLeast(1)
        val money = cleanDays.toDouble() * avoidedPerDay * (habit.moneyPerUnit ?: 0.0)
        val minutes = cleanDays * avoidedPerDay * habit.minutesPerUnit
        val startMillis = LocalDate.ofEpochDay(startDay).atStartOfDay(zone).toInstant().toEpochMilli()
        return QuitStats(cleanDays, startMillis, money, minutes, lastSlip)
    }

    // ── F17 · heuristic insights coach ───────────────────────────────────────────────────────────
    /** Up to a few actionable, plain-language nudges from this habit's own history. Guarded so nothing
     *  fires until there's enough signal. */
    fun coachTips(habit: HabitEntity, checkins: List<HabitCheckinEntity>, today: Long): List<String> {
        if (habit.habitType == "break") return breakTips(habit, checkins, today)
        val mine = checkins.filter { it.habitId == habit.id }
        val done = mine.filter { it.status == "done" && HabitStats.meetsGoal(habit, it.count) }.map { it.epochDay }.toSet()
        val skip = mine.filter { it.status == "skip" }.map { it.epochDay }.toSet()
        val out = ArrayList<String>()
        if (done.size < 8) { // early-stage encouragement instead of stats
            if (done.isNotEmpty()) out += "Keep showing up — ${done.size} done. Automaticity builds around day 66."
            return out
        }
        // Weak weekday.
        val rates = HabitStats.weekdayRates(done, skip, today)
        val minIdx = rates.indices.minByOrNull { rates[it] }
        val maxIdx = rates.indices.maxByOrNull { rates[it] }
        if (minIdx != null && maxIdx != null && rates[maxIdx] - rates[minIdx] > 0.25f && rates[maxIdx] > 0f) {
            val dow = DayOfWeek.of(minIdx + 1).getDisplayName(TextStyle.FULL, Locale.getDefault())
            out += "${dow}s are your soft spot — plan a tiny version, or move it earlier."
        }
        // Typical time.
        HabitStats.typicalDoneMinute(mine)?.let { m -> out += "You usually do this around ${HabitStats.minuteLabel(m)} — set the reminder there." }
        // Slipping recently.
        val r30 = HabitStats.rate(habit, done, skip, today, 30)
        if (r30 in 0.01f..0.5f) out += "Only ${(r30 * 100).roundToInt()}% the last month. Halve the goal for a week to rebuild the streak."
        // Long gap.
        val gap = HabitStats.daysSinceLastDone(done, today)
        if (gap in 2..14) out += "It's been $gap days. Do the two-minute version today — showing up is the win."
        return out.take(3)
    }

    private fun breakTips(habit: HabitEntity, checkins: List<HabitCheckinEntity>, today: Long): List<String> {
        val out = ArrayList<String>()
        val q = quitStats(habit, checkins, today)
        if (q.cleanDays >= 1) out += "${q.cleanDays} clean day${if (q.cleanDays == 1) "" else "s"} — the counter you won't want to reset."
        if (q.lastSlipDay != null) {
            val ago = (today - q.lastSlipDay).toInt()
            if (ago in 1..3) out += "A slip isn't a relapse. You're already $ago day${if (ago == 1) "" else "s"} back on track."
        }
        return out
    }

    // ── F13 · urge trigger heatmap (time-of-day buckets) ─────────────────────────────────────────
    /** Craving counts bucketed into 8 three-hour slots (0=midnight..3, 7=21..24) for a trigger heatmap. */
    fun urgeByTimeBucket(cravings: List<com.todocompanion.app.data.entity.CravingEventEntity>): IntArray {
        val b = IntArray(8)
        cravings.forEach { b[(it.minuteOfDay / 180).coerceIn(0, 7)]++ }
        return b
    }
}
