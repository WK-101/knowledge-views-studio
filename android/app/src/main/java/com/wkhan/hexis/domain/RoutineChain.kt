package com.wkhan.hexis.domain

import com.wkhan.hexis.data.entity.HabitCheckinEntity
import com.wkhan.hexis.data.entity.HabitEntity
import com.wkhan.hexis.domain.habit.HabitStats
import java.util.UUID

/**
 * G2 — reframe a Routine as a **habit chain**. A routine is a press-play ritual; a habit chain is the
 * same ordered steps re-expressed as anchored habits (habit stacking: "after I do the previous, I do
 * this"), so each step earns its own streak, strength and place in the daily habit list — and the whole
 * chain has an adherence you can read at a glance. Purely on-device; no new table (habits already exist).
 */
object RoutineChain {
    data class Result(val routine: Routine, val newHabits: List<HabitEntity>)

    /** Build a chain of anchored habits from [routine]'s steps. Each new habit's `anchorHabitId` points at
     *  the previous step's habit; the first carries the routine as its cue. A step already linked to a real
     *  habit is reused as the anchor (never duplicated). The returned routine has every step linked to its
     *  habit, so running the routine ticks the whole chain. */
    fun build(routine: Routine, existingHabits: List<HabitEntity>, nowMillis: Long = System.currentTimeMillis()): Result {
        val sched = routine.days.filter { it in 1..7 }.distinct().sorted().joinToString(",")
        val category = routine.name.trim().ifBlank { "Routine" }
        val newHabits = ArrayList<HabitEntity>()
        val stepToHabit = HashMap<String, String>()
        var prev: String? = null
        routine.steps.forEachIndexed { idx, s ->
            if (s.title.isBlank()) return@forEachIndexed
            val linked = s.linkedHabitId?.takeIf { id -> existingHabits.any { it.id == id } }
            if (linked != null) { stepToHabit[s.id] = linked; prev = linked; return@forEachIndexed }
            val timed = s.kind == StepKind.TIMER && (s.durationSec ?: 0) > 0
            val id = UUID.randomUUID().toString()
            newHabits += HabitEntity(
                id = id,
                name = s.title.trim(),
                emoji = s.emoji.ifBlank { null },
                createdAt = nowMillis,
                unit = if (timed) "min" else null,
                targetPerDay = if (timed) ((s.durationSec ?: 60) / 60).coerceAtLeast(1) else 1,
                scheduleDays = sched,
                category = category,
                anchorHabitId = prev,
                cueContext = if (idx == 0 && routine.name.isNotBlank()) "During ${routine.name.trim()}" else "",
                description = s.note.trim(),
            )
            stepToHabit[s.id] = id
            prev = id
        }
        val updatedSteps = routine.steps.map { s ->
            val hid = stepToHabit[s.id]
            if (hid != null && s.linkedHabitId == null) s.copy(linkedHabitId = hid) else s
        }
        return Result(routine.copy(steps = updatedSteps), newHabits)
    }

    /** How many steps [build] would still need to create — 0 once the whole chain already exists, so the
     *  UI can say "chain built" instead of offering to duplicate it. */
    fun uncreatedSteps(routine: Routine, existingHabits: List<HabitEntity>): Int =
        routine.steps.count { s ->
            s.title.isNotBlank() && s.linkedHabitId?.let { id -> existingHabits.any { it.id == id } } != true
        }

    /** The habits that make up a routine's chain: the ones its steps link to, plus any live habit filed
     *  under the routine's name (so the chain stays visible after edits). Ordered by step order first. */
    fun habitsFor(routine: Routine, allHabits: List<HabitEntity>): List<HabitEntity> {
        val stepOrder = routine.steps.mapNotNull { it.linkedHabitId }
        val cat = routine.name.trim()
        val byId = allHabits.associateBy { it.id }
        val linked = stepOrder.mapNotNull { byId[it] }.filter { !it.archived && !it.trashed }
        val byCategory = if (cat.isBlank()) emptyList() else allHabits.filter {
            it.category.trim() == cat && !it.archived && !it.trashed && it.id !in stepOrder
        }
        return (linked + byCategory).distinctBy { it.id }
    }

    data class ChainStat(val size: Int, val adherencePct: Int, val streak: Int, val weekdayRates: FloatArray)

    /** Whole-chain adherence over [window] days: the average of each link's schedule/skip-aware rate; a
     *  weekday row (Mon..Sun) averaged across links; and a whole-chain streak — consecutive scheduled days,
     *  back from today, on which EVERY link was done (an in-progress today is never counted as a miss). */
    fun chainStat(habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, today: Long, window: Int = 30): ChainStat {
        if (habits.isEmpty()) return ChainStat(0, 0, 0, FloatArray(7))
        val sets = habits.map { h -> h to HabitStats.daySets(h, checkins.filter { it.habitId == h.id }) }
        val adherence = (sets.map { (h, ds) -> HabitStats.rate(h, ds.done, ds.skip, today, window) }.average() * 100).toInt()
        val wd = FloatArray(7)
        sets.forEach { (_, ds) ->
            val r = HabitStats.weekdayRates(ds.done, ds.skip, today)
            for (i in 0..6) wd[i] += r.getOrElse(i) { 0f }
        }
        for (i in 0..6) wd[i] = wd[i] / sets.size
        val h0 = sets.first().first
        var streak = 0
        var d = today
        var scanned = 0
        while (scanned < 400) {
            if (HabitStats.isExpectedDay(h0, d)) {
                val allDone = sets.all { (_, ds) -> d in ds.done }
                if (allDone) streak++
                else if (d != today) break   // a past expected day missed → the chain broke
                // today not-yet-all-done is left uncounted, not treated as a break
            }
            d -= 1; scanned++
        }
        return ChainStat(habits.size, adherence.coerceIn(0, 100), streak, wd)
    }
}
