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
        // Tasks in an archived/trashed list or folder are hidden from active badges, exactly like the
        // rendered list hides them — so a count never disagrees with the list it heads.
        hiddenListIds: Set<String> = emptySet(), hiddenFolderIds: Set<String> = emptySet(),
        // Full task universe for resolving dependency prerequisites (see ListPipeline.compute) — a blocker
        // may live outside [wsTasks]; empty falls back to [wsTasks]. Keeps the Waiting-On badge in step
        // with its list.
        allTasks: List<TaskEntity> = emptyList(),
    ): Map<SmartKind, Int> {
        val active = if (hiddenListIds.isEmpty() && hiddenFolderIds.isEmpty()) wsTasks
            else wsTasks.filterNot { com.todocompanion.app.domain.view.ListPipeline.isHiddenContainerTask(it, hiddenListIds, hiddenFolderIds) }
        return SmartKind.entries.associateWith { k ->
            when (k) {
                // The shared Inbox badge counts every workspace's Inbox tasks (matches the shared view).
                SmartKind.INBOX -> TaskViews.filterSmart(inbox, SmartKind.INBOX, now, zone, dayStartMin).size
                // Dependency-aware, so it can't go through the pure filterSmart path.
                SmartKind.WAITING -> {
                    val byId = (if (allTasks.isNotEmpty()) allTasks else wsTasks).associateBy { it.id }
                    val blocked = PriorityEngine.computeBlocked(deps, byId, now)
                    active.count { !it.trashed && !it.completed && !it.abandoned && !it.someday && it.id in blocked }
                }
                // Do-Next uses the SAME focus filter as the rendered list, so the badge matches the list.
                SmartKind.DO_NEXT -> DoNext.focused(wsTasks, now, prioCfg, deps, tcRefs, ctxs, null, null, zone, dayStartMin)
                    .count { !com.todocompanion.app.domain.view.ListPipeline.isHiddenContainerTask(it, hiddenListIds, hiddenFolderIds) }
                // Trash is per-workspace (matches the rendered list); the shared Inbox otherwise leaked
                // trashed tasks into every workspace's count.
                SmartKind.TRASH -> wsTasks.count { it.trashed && it.workspaceId == activeWorkspaceId }
                // Completed / Won't-Do keep archived-container tasks visible (like the rendered list), so
                // count them over the unfiltered set.
                SmartKind.COMPLETED, SmartKind.WONT_DO -> TaskViews.filterSmart(wsTasks, k, now, zone, dayStartMin).size
                else -> TaskViews.filterSmart(active, k, now, zone, dayStartMin).size
            }
        }
    }
}
