package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.TaskViews
import java.time.ZoneId

/**
 * R91 — the pure per-smart-list badge counts for the drawer, lifted out of AppViewModel. Deterministic
 * over snapshots + zone + dayStartMin + now; the priority config is computed by the ViewModel (it is a
 * private projection of settings) and passed in. The ViewModel keeps the reactive combine and calls
 * [compute]. Behaviour is identical to the previous in-ViewModel lambda: Inbox counts the shared
 * cross-workspace set; Waiting is the dependency-blocked tasks; Do-Next mirrors the focused list; Trash
 * is per-workspace; every other kind is the pure filterSmart size.
 */
object SmartCounts {
    fun compute(
        wsTasks: List<TaskEntity>, inbox: List<TaskEntity>, deps: List<DependencyEntity>,
        prioCfg: PriorityEngine.Config, tcRefs: List<TaskContextCrossRef>, ctxs: List<ContextEntity>,
        activeWorkspaceId: String, zone: ZoneId, dayStartMin: Int, now: Long,
    ): Map<SmartKind, Int> =
        SmartKind.entries.associateWith { k ->
            when (k) {
                // The shared Inbox badge counts every workspace's Inbox tasks (matches the shared view).
                SmartKind.INBOX -> TaskViews.filterSmart(inbox, SmartKind.INBOX, now, zone, dayStartMin).size
                // Dependency-aware, so it can't go through the pure filterSmart path.
                SmartKind.WAITING -> {
                    val byId = wsTasks.associateBy { it.id }
                    val blocked = PriorityEngine.computeBlocked(deps, byId, now)
                    wsTasks.count { !it.trashed && !it.completed && !it.abandoned && !it.someday && it.id in blocked }
                }
                // Do-Next uses the SAME focus filter as the rendered list, so the badge matches the list.
                SmartKind.DO_NEXT -> DoNext.focused(wsTasks, now, prioCfg, deps, tcRefs, ctxs, null, null, zone, dayStartMin).size
                // Trash is per-workspace (matches the rendered list); the shared Inbox otherwise leaked
                // trashed tasks into every workspace's count.
                SmartKind.TRASH -> wsTasks.count { it.trashed && it.workspaceId == activeWorkspaceId }
                else -> TaskViews.filterSmart(wsTasks, k, now, zone, dayStartMin).size
            }
        }
}
