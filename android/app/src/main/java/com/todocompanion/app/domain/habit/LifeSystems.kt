package com.todocompanion.app.domain.habit

import com.todocompanion.app.data.entity.CravingEventEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * R34 — the LIFE-SYSTEMS brain. On-device, rules-only heuristics turning the owned, cross-module ledger
 * (habits + their context tags + tasks) into private insight: a correlation engine, a what-if forward
 * simulator, the permanent identity ledger, an integrity review, urge analytics and a chronotype coach.
 * No LLM, no network — every function is pure over entities the app already holds.
 */
object LifeSystems {

    // ── LS8 · on-device correlation engine ───────────────────────────────────────────────────────
    data class Correlation(
        val habit: HabitEntity, val signal: String, val delta: Double,
        val onValue: Double, val offValue: Double, val nOn: Int, val nOff: Int,
    ) { val positive get() = delta >= 0 }

    /** Daily signals the engine can read: same-day average mood & energy captured at check-in, tasks
     *  completed that day, and focus minutes. */
    private fun dailySignals(checkins: List<HabitCheckinEntity>, tasks: List<TaskEntity>, zone: java.time.ZoneId): Triple<Map<Long, Double>, Map<Long, Double>, Map<Long, Double>> {
        val moodByDay = checkins.filter { it.ctxMood > 0 }.groupBy { it.epochDay }.mapValues { e -> e.value.map { it.ctxMood }.average() }
        val energyByDay = checkins.filter { it.ctxEnergy > 0 }.groupBy { it.epochDay }.mapValues { e -> e.value.map { it.ctxEnergy }.average() }
        val tasksByDay = tasks.filter { it.completed && it.completedAt != null }
            .groupBy { java.time.Instant.ofEpochMilli(it.completedAt!!).atZone(zone).toLocalDate().toEpochDay() }
            .mapValues { it.value.size.toDouble() }
        return Triple(moodByDay, energyByDay, tasksByDay)
    }

    /**
     * For each habit, compare a daily signal on the days it was DONE vs the days it was expected-but-not.
     * Reported only past a minimum sample on each side, and only when the gap is meaningful — the
     * min-sample guard is what keeps it honest rather than spurious.
     */
    fun correlations(
        habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, tasks: List<TaskEntity>,
        today: Long, minSample: Int = 5, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): List<Correlation> {
        val (mood, energy, tasksByDay) = dailySignals(checkins, tasks, zone)
        val signals = listOf("mood" to mood, "energy" to energy, "tasks done" to tasksByDay)
        val out = ArrayList<Correlation>()
        habits.filter { !it.archived }.forEach { h ->
            val mine = checkins.filter { it.habitId == h.id }
            val done = mine.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            if (done.size < minSample) return@forEach
            signals.forEach { (name, series) ->
                // Only correlate against signals the habit doesn't itself produce (a mood-tagged habit
                // shouldn't "predict" mood from its own tag) — compare next-day too would need lag; keep
                // same-day but exclude the habit's own contribution isn't needed for tasks/focus.
                val onVals = series.filterKeys { it in done }.values
                val offVals = series.filterKeys { it !in done && it <= today }.values
                if (onVals.size >= minSample && offVals.size >= minSample) {
                    val on = onVals.average(); val off = offVals.average()
                    val delta = on - off
                    if (abs(delta) >= 0.2) out += Correlation(h, name, delta, on, off, onVals.size, offVals.size)
                }
            }
        }
        return out.sortedByDescending { abs(it.delta) }
    }

    /** LS8b · keystone — the habit whose done-days most lift the other signals, across correlations. */
    fun keystone(correlations: List<Correlation>): HabitEntity? =
        correlations.filter { it.positive }.groupBy { it.habit }
            .mapValues { e -> e.value.sumOf { it.delta } }
            .maxByOrNull { it.value }?.key

    // ── LS9 · what-if forward simulator ──────────────────────────────────────────────────────────
    data class Projection(val adherenceLabel: String, val adherence: Double, val weeks: List<Pair<Int, Int>>)

    /** Project automaticity forward under a few adherence scenarios, using the same Lally curve as the
     *  meter (1 − e^(−reps/21)). Each scenario returns (weekOffset, pct) points. */
    fun whatIf(habit: HabitEntity, doneDays: Set<Long>, today: Long): List<Projection> {
        val reps0 = doneDays.size
        val perWeek = expectedPerWeek(habit)
        val scenarios = listOf("If you keep it up" to 0.9, "A typical week" to 0.6, "If it slips" to 0.3)
        return scenarios.map { (label, adh) ->
            val pts = listOf(0, 4, 8, 12, 26).map { wk ->
                val reps = reps0 + (perWeek * adh * wk).roundToInt()
                wk to ((1.0 - exp(-reps / 21.0)) * 100).roundToInt().coerceIn(0, 99)
            }
            Projection(label, adh, pts)
        }
    }

    private fun expectedPerWeek(h: HabitEntity): Double = when (h.freqType) {
        HabitStats.FREQ_TIMES_WEEK -> h.freqParam.coerceAtLeast(1).toDouble()
        HabitStats.FREQ_TIMES_MONTH -> h.freqParam.coerceAtLeast(1) / 4.3
        "interval" -> 7.0 / h.freqParam.coerceAtLeast(1)
        else -> { // weekly: count scheduled weekdays, or 7 when every day
            val days = h.scheduleDays.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (days.isEmpty()) 7.0 else days.size.toDouble()
        }
    }

    // ── LS3 · permanent identity ledger ──────────────────────────────────────────────────────────
    data class IdentityTally(val identity: String, val votes: Int, val sinceDay: Long?, val habitNames: List<String>)

    /** Every completion of a habit that carries an identity is a "vote" for that person. Tallies votes
     *  across the whole (permanent) history, grouped by identity statement. */
    fun identityLedger(habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>): List<IdentityTally> {
        val withIdentity = habits.filter { it.identity.isNotBlank() }
        return withIdentity.groupBy { it.identity.trim() }.map { (ident, hs) ->
            val ids = hs.map { it.id }.toSet()
            val votes = checkins.filter { it.habitId in ids && it.status == "done" }
            val since = votes.minOfOrNull { it.epochDay }
            IdentityTally(ident, votes.count { c -> hs.first { it.id == c.habitId }.let { HabitStats.meetsGoal(it, c.count) } }, since, hs.map { it.name })
        }.sortedByDescending { it.votes }
    }

    // ── LS10 · urge analytics ────────────────────────────────────────────────────────────────────
    data class UrgeStats(
        val total: Int, val surfedRate: Float, val byBucket: IntArray, val topTriggers: List<Pair<String, Int>>,
        val haltCounts: Map<String, Int>, val avgDurationSec: Int, val medianDurationSec: Int,
    )

    fun urgeStats(cravings: List<CravingEventEntity>): UrgeStats {
        val n = cravings.size
        val surfed = cravings.count { it.surfed }
        val bucket = HabitBuilder.urgeByTimeBucket(cravings)
        val triggers = cravings.filter { it.trigger.isNotBlank() }.groupingBy { it.trigger.trim().lowercase() }.eachCount()
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        val halt = LinkedHashMap<String, Int>()
        listOf("hungry", "angry", "lonely", "tired").forEach { flag ->
            val c = cravings.count { it.halt.split(",").map { s -> s.trim() }.contains(flag) }
            if (c > 0) halt[flag] = c
        }
        val durs = cravings.map { it.durationSec }.filter { it > 0 }.sorted()
        val avg = if (durs.isEmpty()) 0 else durs.average().roundToInt()
        val med = if (durs.isEmpty()) 0 else durs[durs.size / 2]
        return UrgeStats(n, if (n == 0) 0f else surfed.toFloat() / n, bucket, triggers, halt, avg, med)
    }

    // ── LS · chronotype-fit coach ────────────────────────────────────────────────────────────────
    /** If a habit's usual/intended time falls in the user's low-energy window for their chronotype,
     *  return a short nudge with a better slot — else null. chronotype: 1 = morning lark, 2 = night owl. */
    fun chronotypeNudge(habit: HabitEntity, typicalMinute: Int?, chronotype: Int): String? {
        if (chronotype == 0) return null
        val m = habit.cueTime ?: typicalMinute ?: return null
        return when (chronotype) {
            1 -> if (m >= 1080) "You're a morning lark, but this sits in the evening (${HabitStats.minuteLabel(m)}) — your low-energy window. Try moving it before noon." else null
            2 -> if (m < 540) "You're a night owl, but this is scheduled early (${HabitStats.minuteLabel(m)}) — against your grain. An afternoon or evening slot may stick better." else null
            else -> null
        }
    }

    // ── LS6 · integrity review (weekly / annual) ─────────────────────────────────────────────────
    data class ReviewValueLine(val name: String, val emoji: String?, val actions: Int)
    data class Review(
        val kind: String, val label: String, val startDay: Long, val endDay: Long,
        val completions: Int, val activeHabits: Int, val bestStreakName: String?, val bestStreak: Int,
        val keystoneName: String?, val values: List<ReviewValueLine>, val automaticityGainName: String?, val automaticityGain: Int,
    )

    fun review(
        kind: String, label: String, startDay: Long, endDay: Long,
        habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, tasks: List<TaskEntity>,
        values: List<com.todocompanion.app.data.entity.CoreValueEntity>,
    ): Review {
        val inRange = { d: Long -> d in startDay..endDay }
        val doneInRange = checkins.filter { it.status == "done" && inRange(it.epochDay) }
        val completions = doneInRange.count { c -> habits.firstOrNull { it.id == c.habitId }?.let { HabitStats.meetsGoal(it, c.count) } ?: false }
        val active = doneInRange.map { it.habitId }.distinct().size
        // Best streak among active habits over the whole history (a review celebrates the peak).
        var bestName: String? = null; var best = 0
        habits.forEach { h ->
            val d = checkins.filter { it.habitId == h.id }
            val done = d.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val skip = d.filter { it.status == "skip" }.map { it.epochDay }.toSet()
            val relapse = d.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            val bs = HabitStats.bestStreak(h, done, skip, relapse, endDay)
            if (bs > best) { best = bs; bestName = h.name }
        }
        val corr = correlations(habits, checkins, tasks, endDay)
        val keystone = keystone(corr)?.name
        val valueLines = values.map { v ->
            val ids = habits.filter { it.valueId == v.id }.map { it.id }.toSet()
            ReviewValueLine(v.name, v.emoji, doneInRange.count { it.habitId in ids })
        }.sortedByDescending { it.actions }
        // Automaticity gain: habit that added the most reps in-range (proxy: done-in-range count).
        val gains = habits.map { h -> h to doneInRange.count { it.habitId == h.id } }.maxByOrNull { it.second }
        return Review(kind, label, startDay, endDay, completions, active, bestName, best, keystone, valueLines, gains?.first?.name, gains?.second ?: 0)
    }

    // ── LS · buddy digest (export / import) ──────────────────────────────────────────────────────
    @Serializable
    data class BuddyDigest(
        val name: String, val exportedAt: Long,
        val habits: List<BuddyHabit>,
    )
    @Serializable
    data class BuddyHabit(val name: String, val emoji: String?, val streak: Int, val strength: Int, val automaticity: Int)

    /** Build the compact, shareable digest of this device's own progress — no ids, no notes, just the
     *  streaks a buddy would cheer. */
    fun buildDigest(name: String, habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, today: Long, forgiving: Boolean): BuddyDigest {
        val hs = habits.filter { !it.archived && !it.paused }.map { h ->
            val d = checkins.filter { it.habitId == h.id }
            val done = d.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val skip = d.filter { it.status == "skip" }.map { it.epochDay }.toSet()
            val relapse = d.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            BuddyHabit(
                h.name, h.emoji,
                HabitStats.displayStreak(h, done, skip, relapse, today, forgiving),
                HabitStats.strength(h, done, skip, relapse, today),
                HabitBuilder.automaticity(done).pct,
            )
        }
        return BuddyDigest(name, System.currentTimeMillis(), hs)
    }
}
