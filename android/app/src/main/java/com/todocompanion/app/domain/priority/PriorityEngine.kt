package com.todocompanion.app.domain.priority

import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.TaskEntity
import kotlin.math.min

/**
 * MyLifeOrganized-style computed priority. Pure Kotlin (no Android) so it is unit-testable.
 *
 * The "Do Next" list is every actionable, non-gated leaf task ranked by [score] descending.
 * Score blends importance (with ancestor inheritance), urgency, and due-date proximity;
 * gating removes tasks that are not-yet-started, blocked by a dependency, or whose contexts
 * are all unavailable.
 */
object PriorityEngine {

    private const val DAY_MS = 86_400_000.0
    private const val DEFAULT_LEAD_DAYS = 7.0

    data class Ranked(val task: TaskEntity, val score: Double)

    /** Tasks blocked by an incomplete predecessor (respecting AND/OR mode). */
    fun computeBlocked(
        deps: List<DependencyEntity>,
        tasksById: Map<String, TaskEntity>,
    ): Set<String> {
        if (deps.isEmpty()) return emptySet()
        val byTask = deps.groupBy { it.taskId }
        val blocked = mutableSetOf<String>()
        for ((taskId, list) in byTask) {
            val incomplete = list.map { tasksById[it.dependsOnTaskId]?.completed == false }
            val mode = list.firstOrNull()?.mode ?: "AND"
            val isBlocked = if (mode.equals("OR", ignoreCase = true)) {
                incomplete.all { it }      // OR: blocked only while every predecessor is incomplete
            } else {
                incomplete.any { it }      // AND: blocked while any predecessor is incomplete
            }
            if (isBlocked) blocked.add(taskId)
        }
        return blocked
    }

    /** Due-date proximity multiplier: 1.0 when far away, ramping up as due nears, >2 when overdue. */
    fun dueProximity(task: TaskEntity, now: Long): Double {
        val due = task.dueDate ?: return 1.0
        val leadDays = (task.leadTimeMin?.let { it / 1440.0 }) ?: DEFAULT_LEAD_DAYS
        val daysToDue = (due - now) / DAY_MS
        return when {
            daysToDue <= 0 -> 2.0 + min(-daysToDue, 7.0) * 0.1   // overdue: 2.0 .. 2.7
            daysToDue >= leadDays -> 1.0
            else -> 1.0 + (1.0 - daysToDue / leadDays)           // ramps 1 -> 2 as due approaches
        }
    }

    private fun ancestorMaxImportance(
        task: TaskEntity,
        tasksById: Map<String, TaskEntity>,
    ): Int {
        var maxImp = 0
        var p = task.parentId
        var guard = 0
        while (p != null && guard++ < 1000) {
            val parent = tasksById[p] ?: break
            if (parent.importance > maxImp) maxImp = parent.importance
            p = parent.parentId
        }
        return maxImp
    }

    /** Raw ranking score (ignores gating). Higher = do sooner. */
    fun score(task: TaskEntity, now: Long, tasksById: Map<String, TaskEntity>): Double {
        val ancestorImp = ancestorMaxImportance(task, tasksById)
        val effImportance = 0.7 * task.importance + 0.3 * ancestorImp
        val urgencyFactor = 0.6 + 0.2 * task.urgency        // urgency 1->0.8, 3->1.2, 5->1.6
        val proximity = dueProximity(task, now)
        var s = effImportance * urgencyFactor * proximity
        if (task.star) s *= 1.3
        return s
    }

    /**
     * The Do-Next list: actionable (leaf) tasks that pass gating, ranked by score desc.
     *
     * @param hasIncompleteChild returns true if the task has at least one incomplete child
     * @param contextAvailable returns true if the task may surface now given its contexts
     */
    fun doNext(
        all: List<TaskEntity>,
        now: Long,
        blocked: Set<String>,
        hasIncompleteChild: (String) -> Boolean,
        contextAvailable: (String) -> Boolean,
    ): List<Ranked> {
        val byId = all.associateBy { it.id }
        return all.asSequence()
            .filter { !it.completed }
            .filter { !hasIncompleteChild(it.id) }                       // actionable leaf/next action
            .filter { !(it.hideInTodoUntilStart && it.startDate != null && it.startDate > now) }
            .filter { !(it.hideInTodoIfBlocked && it.id in blocked) }
            .filter { contextAvailable(it.id) }
            .map { Ranked(it, score(it, now, byId)) }
            .sortedByDescending { it.score }
            .toList()
    }

    /** Eisenhower quadrant index for the Matrix view (configurable thresholds). */
    fun quadrant(task: TaskEntity, importanceThreshold: Int = 4, urgencyThreshold: Int = 4): Int {
        val important = task.importance >= importanceThreshold
        val urgent = task.urgency >= urgencyThreshold
        return when {
            urgent && important -> 0   // Do first
            !urgent && important -> 1  // Schedule
            urgent && !important -> 2  // Delegate / quick
            else -> 3                  // Eliminate / someday
        }
    }
}
