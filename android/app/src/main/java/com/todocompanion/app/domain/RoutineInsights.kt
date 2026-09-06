package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure, on-device analytics over a routine's run history — the moat features single-purpose runners
 * can't build, because they don't hold the day's felt-state on the same store:
 *   • adherence + streak (Tier 2.3),
 *   • the drop-off step and the best time of day,
 *   • keystone detection (moat #3) — how the days you run this routine differ from the days you don't,
 *   • "on this day" memory + a year summary (moat #7).
 * Everything is derived from RoutineRun rows + DayLogEntity felt-state. No network, no new storage.
 */
object RoutineInsights {

    data class Stat(
        val routineId: String,
        val runs30: Int,            // distinct days run in the last 30
        val adherencePct: Int,      // runs30 / 30
        val currentStreak: Int,     // consecutive days up to today with a run
        val bestStreak: Int,
        val bestHour: Int?,         // modal start hour (0–23), null if unknown
        val dropOffStepTitle: String?,   // the step most often left undone
        val keystoneDelta: Double,  // avg metric on run-days minus other-days (0 = not enough data)
        val keystoneMetric: String, // "day rating" | "energy" | ""
        val totalRuns: Int,
        val window: Int = 30,       // the days the routine has existed, capped at 30 — the adherence denominator
    )

    private fun dayOf(millis: Long, zone: ZoneId) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun forRoutine(
        r: Routine,
        allRuns: List<RoutineRun>,
        dayLogs: List<DayLogEntity>,
        today: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Stat {
        val runs = allRuns.filter { it.routineId == r.id }
        val runDays = runs.map { it.epochDay }.toSortedSet()
        val runs30 = runDays.count { it in (today - 29)..today }

        // Streaks over the set of run-days. The current streak is forgiving of "today not run yet": we
        // count back from today if it's been run, else from yesterday — so a live streak still shows in
        // the morning before the day's run, rather than reading 0 exactly when encouragement matters.
        var cur = 0
        run { var d = if (runDays.contains(today)) today else today - 1; while (runDays.contains(d)) { cur++; d-- } }
        var best = 0; var chain = 0; var prev: Long? = null
        for (d in runDays) { chain = if (prev != null && d == prev!! + 1) chain + 1 else 1; best = maxOf(best, chain); prev = d }

        val bestHour = runs.map { Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).hour }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        // Drop-off: among *full* finished runs, the step most often missing. Lite runs are excluded —
        // they legitimately omit non-essential steps, so counting them would wrongly flag a skipped
        // essential-day step as "the one you skip most".
        val stepTitle = r.steps.associate { it.id to it.title }
        val missCount = HashMap<String, Int>()
        runs.filter { it.finished && !it.lite }.forEach { run ->
            // Scope to the steps that existed at run time (completed ∪ skipped), so a step added *after*
            // a run isn't spuriously counted "missing" on it. A finished run's missing steps = its skips.
            val done = run.completedStepIds.toSet()
            (done + run.skippedStepIds).forEach { id -> if (id !in done) missCount[id] = (missCount[id] ?: 0) + 1 }
        }
        val dropOff = missCount.entries.filter { it.value > 0 }.maxByOrNull { it.value }?.key?.let { stepTitle[it] }

        // Keystone: compare a felt metric on run-days vs other-days (needs ≥3 of each with the metric).
        val runSet = runDays
        fun avgOn(pred: (DayLogEntity) -> Boolean, value: (DayLogEntity) -> Int): Pair<Double, Int> {
            val vals = dayLogs.filter { pred(it) && value(it) > 0 }.map { value(it) }
            return (if (vals.isEmpty()) 0.0 else vals.average()) to vals.size
        }
        var kDelta = 0.0; var kMetric = ""
        for ((label, extractor) in listOf<Pair<String, (DayLogEntity) -> Int>>("day rating" to { it.dayRating }, "energy" to { it.energy })) {
            val (onRun, nOn) = avgOn({ it.epochDay in runSet }, extractor)
            val (offRun, nOff) = avgOn({ it.epochDay !in runSet }, extractor)
            if (nOn >= 3 && nOff >= 3) { kDelta = onRun - offRun; kMetric = label; break }
        }

        // Adherence is over the days the routine has actually existed (capped at the 30-day window), so a
        // fresh routine run every day reads ~100%, not "7%" against a fixed 30-day denominator.
        val createdDay = if (r.createdAt > 0) dayOf(r.createdAt, zone).toEpochDay() else today - 29
        val window = (today - createdDay + 1).coerceIn(1L, 30L).toInt()
        return Stat(
            routineId = r.id, runs30 = runs30, adherencePct = (runs30 * 100 / window),
            currentStreak = cur, bestStreak = best, bestHour = bestHour,
            dropOffStepTitle = dropOff, keystoneDelta = kDelta, keystoneMetric = kMetric,
            totalRuns = runs.size, window = window,
        )
    }

    data class OnThisDay(val yearsAgo: Int, val routineName: String, val emoji: String)

    /** Runs on this calendar day (same month + day) in a previous year. */
    fun onThisDay(routines: List<Routine>, runs: List<RoutineRun>, today: Long, zone: ZoneId = ZoneId.systemDefault()): List<OnThisDay> {
        val t = LocalDate.ofEpochDay(today)
        val nameOf = routines.associate { it.id to (it.name to it.emoji) }
        return runs.mapNotNull { run ->
            val d = LocalDate.ofEpochDay(run.epochDay)
            if (d.year < t.year && d.monthValue == t.monthValue && d.dayOfMonth == t.dayOfMonth) {
                val n = nameOf[run.routineId] ?: return@mapNotNull null
                OnThisDay(t.year - d.year, n.first, n.second)
            } else null
        }.distinctBy { it.yearsAgo to it.routineName }.sortedBy { it.yearsAgo }
    }

    data class YearSummary(val totalRuns: Int, val totalMinutes: Int, val topRoutineName: String?, val topRoutineRuns: Int, val bestStreak: Int)

    fun yearSummary(routines: List<Routine>, runs: List<RoutineRun>, year: Int): YearSummary {
        val inYear = runs.filter { LocalDate.ofEpochDay(it.epochDay).year == year }
        val byRoutine = inYear.groupingBy { it.routineId }.eachCount().maxByOrNull { it.value }
        val topName = byRoutine?.let { entry -> routines.firstOrNull { it.id == entry.key }?.name }
        // Best streak across all routines this year (any routine counts toward a "kept a ritual" day).
        val days = inYear.map { it.epochDay }.toSortedSet()
        var best = 0; var chain = 0; var prev: Long? = null
        for (d in days) { chain = if (prev != null && d == prev!! + 1) chain + 1 else 1; best = maxOf(best, chain); prev = d }
        return YearSummary(inYear.size, inYear.sumOf { it.totalSec } / 60, topName, byRoutine?.value ?: 0, best)
    }
}
