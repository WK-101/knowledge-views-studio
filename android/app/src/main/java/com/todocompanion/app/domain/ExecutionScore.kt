package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.TaskEntity
import java.time.Instant
import java.time.ZoneId

/**
 * Track 2.1 — the Weekly Execution Score, framed as a *lead* measure (how much of what you planned you
 * actually did), benchmarked against the 85% target that keeps a plan honest without demanding
 * perfection. It reuses the very inputs [ReviewRollup] already folds: the week's planned commitments are
 * the tasks due / scheduled inside the window plus the habit check-ins that were expected
 * ([ReviewRollup.HabitConsistency] already carries kept-vs-expected), and "done" is those same tasks
 * completed plus the habits kept. Pure and Compose-free so it unit-tests as plain Kotlin, mirroring
 * ExecutionScore's sibling folds (Calibration / ReviewRollup / FeltState).
 */
object ExecutionScore {

    /** The benchmark a week's execution is measured against — a lead measure, not a demand for 100%. */
    const val BENCHMARK_PCT = 85

    /** Above the benchmark, this many points still reads as "on track" before it counts as clearly above. */
    const val ON_TRACK_BAND = 5

    /** Where the week's execution sits relative to the [BENCHMARK_PCT] benchmark. */
    enum class Verdict { BELOW, ON_TRACK, ABOVE }

    /**
     * The week's execution. [plannedTasks]/[doneTasks] are the task side, [expectedHabits]/[keptHabits]
     * the habit side; [planned]/[completed] pool them, and [pct] is completed ÷ planned as a whole
     * percentage (0 when nothing was planned). [verdict] compares [pct] to [benchmark].
     */
    data class Score(
        val plannedTasks: Int,
        val doneTasks: Int,
        val expectedHabits: Int,
        val keptHabits: Int,
        val benchmark: Int = BENCHMARK_PCT,
    ) {
        val planned: Int get() = plannedTasks + expectedHabits
        val completed: Int get() = doneTasks + keptHabits
        /** Integer percentage of what you planned that got done (floor, matching HabitConsistency.pct). */
        val pct: Int get() = if (planned <= 0) 0 else (completed * 100) / planned
        val hasData: Boolean get() = planned > 0
        val verdict: Verdict
            get() = when {
                pct < benchmark -> Verdict.BELOW
                pct <= benchmark + ON_TRACK_BAND -> Verdict.ON_TRACK
                else -> Verdict.ABOVE
            }
    }

    /**
     * Count planned vs done tasks over the inclusive epoch-day window. A task is a live commitment in the
     * window when it isn't trashed or abandoned ("won't do" was deliberately released, so it never drags
     * the score) and its due date OR its scheduled start falls inside the window. "Done" is a planned task
     * that's completed. Returns planned to done.
     */
    fun taskCommitments(tasks: List<TaskEntity>, startDay: Long, endDay: Long, zone: ZoneId): Pair<Int, Int> {
        if (endDay < startDay) return 0 to 0
        var planned = 0
        var done = 0
        for (t in tasks) {
            if (t.trashed || t.abandoned) continue
            val dueDay = t.dueDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
            val startD = t.startDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
            val inWindow = (dueDay != null && dueDay in startDay..endDay) || (startD != null && startD in startDay..endDay)
            if (!inWindow) continue
            planned++
            if (t.completed) done++
        }
        return planned to done
    }

    /**
     * Build the [Score] straight from a computed [ReviewRollup.Rollup] plus the task list. The habit side
     * pools every scheduled habit's kept-vs-expected the rollup already carries; the task side is derived
     * over the rollup's own window. This is the one entry point the UI uses.
     */
    fun fromRollup(rollup: ReviewRollup.Rollup, tasks: List<TaskEntity>, zone: ZoneId): Score {
        val (planned, done) = taskCommitments(tasks, rollup.startDay, rollup.endDay, zone)
        val expected = rollup.habitConsistency.sumOf { it.expected }
        val kept = rollup.habitConsistency.sumOf { it.kept }
        return Score(planned, done, expected, kept)
    }
}
