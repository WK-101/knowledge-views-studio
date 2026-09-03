package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.context.ContextAvailability
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.TaskViews
import java.time.Instant
import java.time.ZoneId

/**
 * R90 — the pure Do-Next ranking + focus logic, lifted out of AppViewModel. Both functions are
 * deterministic over plain snapshots plus a zone and the day-start offset, holding no Android or
 * coroutine dependency, so the ranking (priority + due urgency + dependency propagation + context
 * open-hours + ordered-subtask gating) is now independently unit-testable. The ViewModel keeps the
 * snapshot-reading wrappers (doNextRanked / topDoNext, the smartCounts and list combines) and calls
 * in. Behaviour is identical to the previous in-ViewModel implementation.
 */
object DoNext {
    /** Rank the Do-Next base list, most-actionable first. */
    fun rank(
        base: List<TaskEntity>, all: List<TaskEntity>, now: Long, cfg: PriorityEngine.Config,
        deps: List<DependencyEntity>, tcRefs: List<TaskContextCrossRef>, ctxs: List<ContextEntity>,
        zone: ZoneId,
    ): List<TaskEntity> {
        val byParent = all.groupBy { it.parentId }
        val byId = all.associateBy { it.id }
        val blocked = PriorityEngine.computeBlocked(deps, byId, now)
        // "Complete subtasks in order": a task is gated while an earlier sibling under the same
        // ordered parent is still open — only the current step of the sequence surfaces.
        fun orderBlocked(id: String): Boolean {
            val t = byId[id] ?: return false
            val parent = t.parentId?.let { byId[it] } ?: return false
            if (!parent.completeInOrder) return false
            val sibs = byParent[parent.id].orEmpty().filter { !it.trashed && !it.abandoned }.sortedBy { it.sortOrder }
            val firstOpen = sibs.firstOrNull { !it.completed } ?: return false
            return firstOpen.id != id
        }
        // Context availability (open-hours), evaluated once for now.
        val dt = Instant.ofEpochMilli(now).atZone(zone)
        val dow = dt.dayOfWeek.value; val minute = dt.hour * 60 + dt.minute
        val availById = ctxs.associate { it.id to ContextAvailability.isAvailable(it, dow, minute) }
        val ctxByTask = tcRefs.groupBy { it.taskId }
        // Dependency → priority propagation: a task blocking important work rises in the ranking.
        val depBoosts = PriorityEngine.dependencyBoosts(deps, byId, cfg)
        return PriorityEngine.doNext(
            all = base,
            now = now,
            blocked = blocked,
            hasIncompleteChild = { id -> byParent[id].orEmpty().any { !it.completed && !it.trashed && !it.abandoned && !it.someday } },
            contextAvailable = { id ->
                val ids = ctxByTask[id].orEmpty().map { it.contextId }
                ids.isEmpty() || ids.any { availById[it] == true }
            },
            orderBlocked = ::orderBlocked,
            cfg = cfg,
            depBoost = { id -> depBoosts[id] ?: 0.0 },
        ).map { it.task }
    }

    /** The focused Do-Next list: ranked, then narrowed to what's actionable now (not blocked, not
     *  future-started, due today-or-earlier or starred/flagged) and optionally within a time / energy
     *  budget. */
    fun focused(
        all: List<TaskEntity>, now: Long, prioCfg: PriorityEngine.Config,
        deps: List<DependencyEntity>, tcRefs: List<TaskContextCrossRef>, ctxs: List<ContextEntity>,
        timeAvail: Int?, energyAvail: Int?, zone: ZoneId, dayStartMin: Int,
    ): List<TaskEntity> {
        val base = TaskViews.filterSmart(all, SmartKind.DO_NEXT, now, zone, dayStartMin)
        val ranked = rank(base, all, now, prioCfg, deps, tcRefs, ctxs, zone)
        val byId = all.associateBy { it.id }
        val blocked = PriorityEngine.computeBlocked(deps, byId, now)
        val today = Instant.ofEpochMilli(now - dayStartMin * 60_000L).atZone(zone).toLocalDate()
        fun dueDay(t: TaskEntity) = t.dueDate?.let { Instant.ofEpochMilli(it - dayStartMin * 60_000L).atZone(zone).toLocalDate() }
        val actionable = ranked.filter { t ->
            if (t.id in blocked) return@filter false
            if (t.startDate != null && t.startDate!! > now) return@filter false
            val d = dueDay(t)
            if (d != null) !d.isAfter(today) else (t.star || t.flagId != null)
        }
        val timed = timeAvail?.let { avail -> actionable.filter { t -> (t.estimateMin ?: t.estimateMax ?: t.durationMin)?.let { it <= avail } ?: true } } ?: actionable
        return energyAvail?.let { cap -> timed.filter { t -> (t.energy ?: 0) <= cap } } ?: timed
    }
}
