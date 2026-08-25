package com.todocompanion.app.domain.priority

import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.TaskEntity
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * MyLifeOrganized-style **computed priority**. Pure Kotlin (no Android) so it is unit-testable.
 *
 * Faithful to MLO's engine:
 *  - importance & urgency map through an *exponential* curve centred at 1.0 for the neutral value,
 *  - both **compound multiplicatively down the outline** (a child's importance is relative to its
 *    parent — the "snowball"),
 *  - a weighted **date term** (due proximity + a start-gate boost + a weekly-goal weight, with an
 *    optional overdue super-boost) is *added* to the importance/urgency product,
 *  - the ranking mode can weigh **importance only**, **urgency only**, or **both**.
 *
 * `score = base(mode) × starBoost + dateTerm`, where `base` is impProduct, urgProduct, or their
 * product. The Do-Next list is every actionable, non-gated task ranked by [score] descending.
 */
object PriorityEngine {

    private const val DAY_MS = 86_400_000.0
    private const val DEFAULT_LEAD_DAYS = 7.0
    private const val NEUTRAL = 3        // importance/urgency are 1..5, neutral 3 → factor 1.0

    enum class Mode { IMPORTANCE, URGENCY, BOTH }

    /** Tunable weights — surfaced in Settings, mirroring MLO's per-profile score weights. */
    data class Config(
        val mode: Mode = Mode.BOTH,
        val dueWeight: Double = 3.0,
        val startWeight: Double = 2.0,
        val goalWeight: Double = 5.0,
        val overdueBoost: Boolean = true,
        val curveBase: Double = 1.5,
        val starBoost: Double = 1.25,
        /** When false, the Do-Next list ignores the computed score and orders by importance then manual. */
        val computed: Boolean = true,
    )

    val DEFAULT = Config()

    data class Ranked(val task: TaskEntity, val score: Double)

    /** Tasks blocked by an incomplete predecessor (respecting AND/OR mode). */
    fun computeBlocked(deps: List<DependencyEntity>, tasksById: Map<String, TaskEntity>, now: Long = System.currentTimeMillis()): Set<String> {
        if (deps.isEmpty()) return emptySet()
        val blocked = mutableSetOf<String>()
        for ((taskId, list) in deps.groupBy { it.taskId }) {
            val preds = list.mapNotNull { tasksById[it.dependsOnTaskId] }
            val mode = list.firstOrNull()?.mode ?: "AND"
            val or = mode.equals("OR", ignoreCase = true)
            val incomplete = preds.filter { !it.completed }
            // Structural block: OR needs one done (all incomplete = blocked); AND needs all done (any incomplete = blocked).
            val structBlocked = if (or) incomplete.size == preds.size else incomplete.isNotEmpty()
            if (structBlocked) { blocked.add(taskId); continue }
            // Delayed activation: hold until the delay has elapsed past the anchor completion —
            // the last completed prerequisite under ALL, the first under ANY.
            val delayDays = list.maxOf { it.delayDays }
            if (delayDays > 0) {
                val times = preds.filter { it.completed }.mapNotNull { it.completedAt }
                val anchor = if (or) times.minOrNull() else times.maxOrNull()
                if (anchor != null && anchor + delayDays * 86_400_000L > now) blocked.add(taskId)
            }
        }
        return blocked
    }

    /** Exponential factor: neutral value → 1.0, higher → >1, lower → <1. */
    private fun factor(value: Int, base: Double): Double = base.pow((value - NEUTRAL).toDouble())

    /** Product of a field's factor across the task and every ancestor (multiplicative inheritance). */
    private fun product(task: TaskEntity, tasksById: Map<String, TaskEntity>, base: Double, of: (TaskEntity) -> Int): Double {
        var p = factor(of(task), base)
        var pid = task.parentId
        var guard = 0
        while (pid != null && guard++ < 1000) {
            val parent = tasksById[pid] ?: break
            p *= factor(of(parent), base)
            pid = parent.parentId
        }
        return p
    }

    private fun leadDays(task: TaskEntity): Double = task.leadTimeMin?.let { it / 1440.0 } ?: DEFAULT_LEAD_DAYS

    private const val OVERDUE_SPIKE = 1.5    // extra urgency the moment a task goes overdue
    private const val OVERDUE_HALFLIFE = 5.0 // days over which that spike decays back down

    /**
     * **Bounded egg-timer** urgency for a due date (0..~1 pre-deadline, capped), fixing MLO's quirk
     * where a far-future due date could score *below* a task with no date at all:
     *  - `lead / (lead + daysToDue)` decays smoothly from 1.0 (at the deadline) toward — but never to
     *    — 0, so **any** scheduled task always edges out an unscheduled one, and the term is bounded;
     *  - once overdue it **spikes then decays** (`1 + spike·e^(-daysOverdue/halfLife)`), so a task
     *    that just slipped its deadline is the most urgent, and one that's been overdue for weeks
     *    quietly recedes instead of dominating the list forever (MLO does the opposite).
     */
    fun dueUrgency(daysToDue: Double, lead: Double, overdueBoost: Boolean): Double = when {
        daysToDue <= 0 -> 1.0 + if (overdueBoost) OVERDUE_SPIKE * exp(daysToDue / OVERDUE_HALFLIFE) else 0.0
        else -> lead / (lead + daysToDue)
    }

    /** Weighted date term added to the importance/urgency base. */
    fun dateTerm(task: TaskEntity, now: Long, cfg: Config = DEFAULT): Double {
        var t = 0.0
        val lead = leadDays(task)
        task.dueDate?.let { due ->
            val daysToDue = (due - now) / DAY_MS
            t += cfg.dueWeight * dueUrgency(daysToDue, lead, cfg.overdueBoost)
        }
        task.startDate?.let { start ->
            val daysSinceStart = (now - start) / DAY_MS
            // Start gate: a task's boost peaks right after it becomes active, then decays over its lead window.
            val startTerm = if (daysSinceStart < 0) 0.0 else max(0.0, 1.0 - daysSinceStart / lead)
            t += cfg.startWeight * startTerm
        }
        if (task.isGoal) t += cfg.goalWeight
        return t
    }

    /**
     * **Dependency → priority propagation** (a genuine step past MLO, which never does this): a task
     * that *blocks* an important/urgent successor is itself high-leverage — finishing it unblocks
     * work — so it inherits a share of its best direct successor's base weight. Returns a boost per
     * predecessor task id, to be *added* to that task's score.
     */
    fun dependencyBoosts(deps: List<DependencyEntity>, tasksById: Map<String, TaskEntity>, cfg: Config = DEFAULT, propagation: Double = 0.5): Map<String, Double> {
        if (deps.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>()
        for (d in deps) {
            val successor = tasksById[d.taskId] ?: continue      // the task that waits on the predecessor
            if (tasksById[d.dependsOnTaskId] == null) continue    // predecessor must exist
            if (successor.completed || successor.trashed || successor.abandoned) continue
            val imp = product(successor, tasksById, cfg.curveBase) { it.importance }
            val urg = product(successor, tasksById, cfg.curveBase) { it.urgency }
            val w = propagation * (imp * urg)
            out[d.dependsOnTaskId] = maxOf(out[d.dependsOnTaskId] ?: 0.0, w)
        }
        return out
    }

    /** Raw ranking score (ignores gating). Higher = do sooner. */
    fun score(task: TaskEntity, now: Long, tasksById: Map<String, TaskEntity>, cfg: Config = DEFAULT): Double {
        val imp = product(task, tasksById, cfg.curveBase) { it.importance }
        val urg = product(task, tasksById, cfg.curveBase) { it.urgency }
        val base = when (cfg.mode) {
            Mode.IMPORTANCE -> imp
            Mode.URGENCY -> urg
            Mode.BOTH -> imp * urg
        }
        return base * (if (task.star) cfg.starBoost else 1.0) + dateTerm(task, now, cfg)
    }

    /** Back-compat proximity multiplier (still used by a couple of call sites / tests). */
    fun dueProximity(task: TaskEntity, now: Long): Double {
        val due = task.dueDate ?: return 1.0
        val lead = leadDays(task)
        val daysToDue = (due - now) / DAY_MS
        return when {
            daysToDue <= 0 -> 2.0 + min(-daysToDue, 7.0) * 0.1
            daysToDue >= lead -> 1.0
            else -> 1.0 + (1.0 - daysToDue / lead)
        }
    }

    /**
     * The Do-Next list: actionable tasks that pass gating, ranked by [score] desc.
     * A task is gated out if it is complete, has an incomplete child, is not-yet-started,
     * is blocked by a dependency, or all of its contexts are currently unavailable.
     */
    fun doNext(
        all: List<TaskEntity>,
        now: Long,
        blocked: Set<String>,
        hasIncompleteChild: (String) -> Boolean,
        contextAvailable: (String) -> Boolean,
        orderBlocked: (String) -> Boolean = { false },
        cfg: Config = DEFAULT,
        depBoost: (String) -> Double = { 0.0 },
    ): List<Ranked> {
        val byId = all.associateBy { it.id }
        val actionable = all.asSequence()
            .filter { !it.completed && !it.trashed && !it.abandoned }
            .filter { !hasIncompleteChild(it.id) }
            .filter { !(it.hideInTodoUntilStart && it.startDate != null && it.startDate!! > now) }
            .filter { !(it.hideInTodoIfBlocked && it.id in blocked) }
            .filter { contextAvailable(it.id) }
            .filter { !orderBlocked(it.id) }
            .toList()
        // Manual mode: skip the computed score entirely — star first, then importance/urgency, then manual order.
        if (!cfg.computed) {
            val cmp = compareByDescending<TaskEntity> { it.star }
                .thenByDescending { maxOf(it.importance, it.urgency) }
                .thenBy { it.sortOrder }
                .thenBy { it.id }
            return actionable.sortedWith(cmp).map { Ranked(it, 0.0) }
        }
        return actionable.map { Ranked(it, score(it, now, byId, cfg) + depBoost(it.id)) }
            .sortedByDescending { it.score }
    }

    /** Eisenhower quadrant index for the Matrix view (configurable thresholds). */
    fun quadrant(task: TaskEntity, importanceThreshold: Int = 4, urgencyThreshold: Int = 4): Int {
        val important = task.importance >= importanceThreshold
        val urgent = task.urgency >= urgencyThreshold
        return when {
            urgent && important -> 0
            !urgent && important -> 1
            urgent && !important -> 2
            else -> 3
        }
    }
}
