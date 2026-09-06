package com.todocompanion.app.domain.habit

import com.todocompanion.app.data.entity.CoreValueEntity
import com.todocompanion.app.data.entity.CravingEventEntity
import com.todocompanion.app.data.entity.ExperimentEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * R35 — the THIRD-WAVE brain. On-device, rules-only heuristics for the three new levers: friction &
 * environment, a just-in-time engine, and causal personal science. No LLM, no network. Pure functions
 * over entities the app already holds.
 */
object ThirdWave {

    // ── TW-A · context-stability score ───────────────────────────────────────────────────────────
    /** How consistent a habit's time-of-day and place are — context stability predicts automaticity.
     *  0 = scattered, 100 = rock-steady. Needs a handful of timed check-ins to mean anything. */
    fun contextStability(habit: HabitEntity, checkins: List<HabitCheckinEntity>): Int? {
        val mine = checkins.filter { it.habitId == habit.id && it.status == "done" }
        val minutes = mine.mapNotNull { it.doneAtMinute }
        if (minutes.size < 5) return null
        val mean = minutes.average()
        val sd = sqrt(minutes.sumOf { (it - mean) * (it - mean) } / minutes.size)
        // A 3-hour (180-min) spread ≈ 0; a 20-min spread ≈ high. Map SD→score.
        val timeScore = (100 - (sd / 1.8)).coerceIn(0.0, 100.0)
        // Place consistency: share of check-ins whose place matches the modal place.
        val places = mine.map { it.ctxPlace.trim().lowercase() }.filter { it.isNotBlank() }
        val placeScore = if (places.size < 3) timeScore else {
            val modal = places.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.value
            modal.toDouble() / places.size * 100
        }
        return ((timeScore + placeScore) / 2).roundToInt()
    }

    // ── TW-B · self-tuning reminder ──────────────────────────────────────────────────────────────
    /** If you reliably complete at a different time than your reminder, suggest moving it. Returns
     *  (suggestedMinute, message) or null. */
    fun reminderDrift(habit: HabitEntity, checkins: List<HabitCheckinEntity>): Pair<Int, String>? {
        val typical = HabitStats.typicalDoneMinute(checkins.filter { it.habitId == habit.id }) ?: return null
        val reminders = habit.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (reminders.isEmpty()) return null
        val nearest = reminders.minByOrNull { abs(it - typical) } ?: return null
        val gap = typical - nearest
        if (abs(gap) < 30) return null
        val dir = if (gap > 0) "later" else "earlier"
        return typical to "You usually do this around ${HabitStats.minuteLabel(typical)} — ${abs(gap)} min $dir than your reminder. Move it to match?"
    }

    // ── TW-B · risk / opportunity nudges (offline JITAI) ─────────────────────────────────────────
    data class Nudge(val habitId: String, val kind: String, val emoji: String, val text: String)

    /** A tiny rules engine: surface the right micro-prompt right now — a risk window for a quit habit,
     *  or an opportunity window for a due build habit. Fully local if-then, no notifications required. */
    fun nudges(habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, cravings: List<CravingEventEntity>, nowMinute: Int, today: Long): List<Nudge> {
        val out = ArrayList<Nudge>()
        val bucket = (nowMinute / 180).coerceIn(0, 7)
        habits.filter { !it.paused && !it.archived }.forEach { h ->
            val mine = checkins.filter { it.habitId == h.id }
            if (h.habitType == "break") {
                // Risk: this 3-hour slot is where most past urges clustered.
                val myUrges = cravings.filter { it.habitId == h.id }
                if (myUrges.size >= 4) {
                    val counts = HabitBuilder.urgeByTimeBucket(myUrges)
                    val peak = counts.indices.maxByOrNull { counts[it] } ?: -1
                    if (peak == bucket && counts[peak] >= 2)
                        out += Nudge(h.id, "risk", "⚡", "Heads up — most of your ${h.name} urges hit right about now. Your plan: ${h.competingResponse.ifBlank { h.woopCoping.ifBlank { "ride it out — it passes." } }}")
                }
            } else {
                val doneDays = mine.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                val due = HabitStats.dueToday(h, today, doneDays, mine.firstOrNull { it.epochDay == today }?.count ?: 0)
                val typical = HabitStats.typicalDoneMinute(mine)
                // Opportunity: it's due, not done, and it's near your usual time — a good window.
                if (due && typical != null && abs(typical - nowMinute) <= 45)
                    out += Nudge(h.id, "opportunity", "✨", "Now's your usual time for ${h.name} — knock it out while the window's open.")
            }
        }
        return out.take(3)
    }

    // ── TW-B · lapse early-warning ───────────────────────────────────────────────────────────────
    /** For a quit habit, learn the day-of-week that most past relapses fell on and warn when today
     *  matches. A self-report-only early-warning signal — no sensors. */
    fun lapseWarning(habit: HabitEntity, checkins: List<HabitCheckinEntity>, today: Long): String? {
        if (habit.habitType != "break") return null
        val relapses = checkins.filter { it.habitId == habit.id && HabitStats.isRelapse(habit, it.count) }.map { it.epochDay }
        if (relapses.size < 3) return null
        val todayDow = java.time.LocalDate.ofEpochDay(today).dayOfWeek
        val byDow = relapses.groupingBy { java.time.LocalDate.ofEpochDay(it).dayOfWeek }.eachCount()
        val worst = byDow.maxByOrNull { it.value } ?: return null
        return if (worst.key == todayDow && worst.value >= 2 && worst.value.toDouble() / relapses.size >= 0.4)
            "${todayDow.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}s are your danger day for ${habit.name} — ${worst.value} of your slips landed here. Pre-arm your plan today."
        else null
    }

    // ── TW-C · n-of-1 experiment analysis ────────────────────────────────────────────────────────
    data class ExperimentResult(val onMean: Double, val offMean: Double, val effect: Double, val nOn: Int, val nOff: Int, val confident: Boolean, val outcomeLabel: String)

    fun analyzeExperiment(exp: ExperimentEntity, habit: HabitEntity, checkins: List<HabitCheckinEntity>, tasks: List<TaskEntity>, today: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), dayLogs: List<com.todocompanion.app.data.entity.DayLogEntity> = emptyList()): ExperimentResult? {
        val end = minOf(exp.endDay(), today)
        val days = (exp.startDay..end).toList()
        if (days.isEmpty()) return null
        // Draw the felt-state outcome from the daily review first, then OTHER habits' tags — never the
        // manipulated habit's own check-in mood, which would circularly inflate the ON-block effect.
        val dlMood = dayLogs.mapNotNull { dl -> (dl.pmMood.takeIf { it > 0 } ?: dl.dayRating.takeIf { it > 0 } ?: dl.amMood.takeIf { it > 0 })?.let { dl.epochDay to it.toDouble() } }.toMap()
        val dlEnergy = dayLogs.filter { it.energy > 0 }.associate { it.epochDay to it.energy.toDouble() }
        val others = checkins.filter { it.habitId != habit.id }
        val moodByDay = others.filter { it.ctxMood > 0 }.groupBy { it.epochDay }.mapValues { e -> e.value.map { it.ctxMood }.average() } + dlMood
        val energyByDay = others.filter { it.ctxEnergy > 0 }.groupBy { it.epochDay }.mapValues { e -> e.value.map { it.ctxEnergy }.average() } + dlEnergy
        val tasksByDay = tasks.filter { it.completed && it.completedAt != null }
            .groupBy { java.time.Instant.ofEpochMilli(it.completedAt!!).atZone(zone).toLocalDate().toEpochDay() }.mapValues { it.value.size.toDouble() }
        val series: Map<Long, Double> = when (exp.outcome) { "mood" -> moodByDay; "energy" -> energyByDay; else -> tasksByDay }
        val onVals = days.filter { exp.onForDay(it) }.mapNotNull { series[it] }
        val offVals = days.filter { !exp.onForDay(it) }.mapNotNull { series[it] }
        if (onVals.isEmpty() || offVals.isEmpty()) return null
        val on = onVals.average(); val off = offVals.average()
        return ExperimentResult(on, off, on - off, onVals.size, offVals.size, onVals.size >= 3 && offVals.size >= 3, exp.outcome)
    }

    // ── TW-C · values-time audit ─────────────────────────────────────────────────────────────────
    data class ValueTime(val value: CoreValueEntity, val minutes: Long)

    /** Minutes of tracked time that flowed to each value, via habits linked to time activities. Stated
     *  vs revealed: what you say matters against where the hours actually went. */
    fun valuesTimeAudit(values: List<CoreValueEntity>, habits: List<HabitEntity>, entries: List<TimeEntryEntity>, sinceDay: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<ValueTime> {
        val sinceMillis = java.time.LocalDate.ofEpochDay(sinceDay).atStartOfDay(zone).toInstant().toEpochMilli()
        fun minutesFor(activityIds: Set<String>): Long = entries
            .filter { it.activityId in activityIds && it.endMillis != null && it.startMillis >= sinceMillis }
            .sumOf { ((it.endMillis!! - it.startMillis) / 60000L).coerceAtLeast(0) }
        return values.map { v ->
            val acts = habits.filter { it.valueId == v.id && it.timeActivityId != null }.mapNotNull { it.timeActivityId }.toSet()
            ValueTime(v, minutesFor(acts))
        }.sortedByDescending { it.minutes }
    }

    // ── TW-C · data-grounded forecaster ──────────────────────────────────────────────────────────
    data class Forecast(val weeks: Int, val low: Int, val mid: Int, val high: Int)

    /** Project automaticity forward from your OWN recent adherence base-rate, with a band from its
     *  variability — a probabilistic forecast, not a single line. */
    fun forecast(habit: HabitEntity, checkins: List<HabitCheckinEntity>, today: Long): List<Forecast>? {
        val mine = checkins.filter { it.habitId == habit.id }
        val done = mine.filter { it.status == "done" && HabitStats.meetsGoal(habit, it.count) }.map { it.epochDay }.toSet()
        if (done.size < 6) return null
        // Adherence over the last 8 weeks of expected days.
        val start = today - 55
        val expected = (start..today).filter { HabitStats.isExpectedDay(habit, it) && it >= habit.startEpochDay() }
        if (expected.size < 8) return null
        val rate = expected.count { it in done }.toDouble() / expected.size
        val perWeek = expected.size.toDouble() / 8.0
        val reps0 = done.size
        fun pctAt(weeks: Int, adh: Double): Int {
            val reps = reps0 + (perWeek * adh.coerceIn(0.0, 1.0) * weeks)
            return ((1.0 - exp(-reps / 21.0)) * 100).roundToInt().coerceIn(0, 99)
        }
        return listOf(4, 12, 26).map { w -> Forecast(w, pctAt(w, rate - 0.2), pctAt(w, rate), pctAt(w, rate + 0.2)) }
    }

    // ── TW-D · compassionate companion ───────────────────────────────────────────────────────────
    data class Companion(val stage: Int, val emoji: String, val label: String, val pct: Int)

    /** A plant that grows from overall consistency — a calm-mode-native visual that replaces numbers and
     *  is never shamed. Stage rises with the average strength across active habits. */
    fun companion(habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, today: Long): Companion {
        val active = habits.filter { !it.archived && !it.paused && it.habitType != "break" }
        if (active.isEmpty()) return Companion(0, "🌱", "Plant a habit to grow your garden", 0)
        val avg = active.map { h ->
            val d = checkins.filter { it.habitId == h.id }
            val done = d.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val skip = d.filter { it.status == "skip" }.map { it.epochDay }.toSet()
            val rel = d.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            HabitStats.strength(h, done, skip, rel, today)
        }.average().roundToInt()
        val (stage, emoji, label) = when {
            avg < 15 -> Triple(0, "🌱", "A seedling — tender days")
            avg < 35 -> Triple(1, "🌿", "Taking root")
            avg < 55 -> Triple(2, "☘️", "Growing steady")
            avg < 75 -> Triple(3, "🪴", "Branching out")
            avg < 90 -> Triple(4, "🌳", "Flourishing")
            else -> Triple(5, "🌳✨", "A mighty tree")
        }
        return Companion(stage, emoji, label, avg)
    }

    // ── TW-F · composite life heatmap ────────────────────────────────────────────────────────────
    /** For each of the last [days] days, the fraction of scheduled habits completed (0..1) — the whole
     *  practice in one grid. */
    fun compositeHeatmap(habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, today: Long, days: Int = 182): Map<Long, Float> {
        val active = habits.filter { !it.archived }
        val doneByHabitDay = checkins.filter { it.status == "done" }.groupBy { it.habitId to it.epochDay }
        val out = LinkedHashMap<Long, Float>()
        for (d in (today - days + 1)..today) {
            var sched = 0; var done = 0
            active.forEach { h ->
                if (h.habitType == "break") return@forEach
                if (d < h.startEpochDay()) return@forEach
                if (HabitStats.isExpectedDay(h, d)) {
                    sched++
                    val c = doneByHabitDay[h.id to d]?.firstOrNull()
                    if (c != null && HabitStats.meetsGoal(h, c.count)) done++
                }
            }
            out[d] = if (sched == 0) -1f else done.toFloat() / sched
        }
        return out
    }

    /** "On this day, N years ago" — a past day (same month/day) with at least one completion. */
    fun onThisDay(checkins: List<HabitCheckinEntity>, today: Long): Pair<Int, Int>? {
        val td = java.time.LocalDate.ofEpochDay(today)
        for (y in 1..10) {
            val past = td.minusYears(y.toLong())
            val pd = past.toEpochDay()
            val n = checkins.count { it.epochDay == pd && it.status == "done" }
            if (n > 0) return y to n
        }
        return null
    }
}
