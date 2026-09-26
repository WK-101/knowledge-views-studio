package com.wkhan.hexis.domain.task

import com.wkhan.hexis.data.entity.TaskEntity
import com.wkhan.hexis.domain.recurrence.Recurrence
import java.time.ZoneId

/**
 * The pure decision behind advancing a *repeating* task to its next occurrence.
 *
 * Extracted from AppViewModel (`toggleComplete` and `skipOccurrence`) so the date-bundle math — due, start
 * and deadline all shift by the SAME delta, keeping the task's shape (the start→due lead time and the hard
 * deadline both move to the next occurrence) — the "recurrence ended?" guard, and the subtask-reset policy
 * can be unit-tested on the JVM without a ViewModel, a database, or the Android framework. Both VM call
 * sites had their own copy of the shift; this is the single source of truth for it.
 *
 * This decides *what* to persist, never *how*: the VM keeps every side effect (persisting the roll, logging
 * the occurrence, rescheduling reminders by [Roll.delta], the completion sound, the Undo event, and — when
 * the recurrence has ended — marking the task done).
 */
object RecurringRollForward {

    /** The rolled-forward task, the ms [delta] every date moved by, and the subtask-reset mode to apply. */
    data class Roll(val next: TaskEntity, val delta: Long, val subtaskReset: String)

    /**
     * Completing [t] rolls it forward. Returns the roll, or null when it must NOT roll: the task is already
     * completed (a toggle *off*), has no recurrence rule or no due date, or its recurrence has ended
     * (until-date reached / count exhausted) — in which case the caller completes it normally.
     */
    fun onComplete(t: TaskEntity, zone: ZoneId, nowMillis: Long): Roll? {
        if (t.completed) return null
        val (next, delta) = advanceDates(t, zone, nowMillis, resetCompletion = true) ?: return null
        return Roll(next, delta, Recurrence.parse(t.rrule)?.subtaskReset ?: "all")
    }

    /**
     * Skip (MLO "skip"): advance [t] to the next occurrence without logging a completion, resetting
     * subtasks, or emitting Undo — and regardless of the completed flag. Returns the rolled task and the
     * ms delta, or null when there is no rule / no due date, or the recurrence has ended. The caller
     * distinguishes "ended" (rule + due present) to mark the task done, exactly as before.
     */
    fun onSkip(t: TaskEntity, zone: ZoneId, nowMillis: Long): Pair<TaskEntity, Long>? =
        advanceDates(t, zone, nowMillis, resetCompletion = false)

    /**
     * The completed children to reset for a [subtaskReset] mode, given the task's live [children]:
     * - "keep"    → none;
     * - "allDone" → all of them, but only once every child is done (a partial set stays as-is);
     * - "all"     → every completed child (the default).
     */
    fun subtasksToReset(subtaskReset: String, children: List<TaskEntity>): List<TaskEntity> {
        val done = children.filter { it.completed }
        return when (subtaskReset) {
            "keep" -> emptyList()
            "allDone" -> if (children.isNotEmpty() && done.size == children.size) done else emptyList()
            else -> done // "all"
        }
    }

    /** The shared shift. [resetCompletion] clears completed/completedAt on the copy (true when completing
     *  rolls a task forward; false for a bare skip that leaves those flags untouched). */
    private fun advanceDates(
        t: TaskEntity, zone: ZoneId, nowMillis: Long, resetCompletion: Boolean,
    ): Pair<TaskEntity, Long>? {
        if (t.rrule.isNullOrBlank() || t.dueDate == null) return null
        val (nextDue, newRule) = Recurrence.advance(t.rrule!!, t.dueDate!!, zone, nowMillis)
        if (nextDue == null) return null
        val delta = nextDue - t.dueDate!!
        val next = t.copy(
            dueDate = nextDue,
            startDate = t.startDate?.plus(delta),
            deadlineDate = t.deadlineDate?.plus(delta),
            rrule = newRule,
            completed = if (resetCompletion) false else t.completed,
            completedAt = if (resetCompletion) null else t.completedAt,
        )
        return next to delta
    }
}
