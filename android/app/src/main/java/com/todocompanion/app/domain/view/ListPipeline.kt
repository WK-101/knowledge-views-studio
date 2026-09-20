package com.todocompanion.app.domain.view

import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.FilterEntity
import com.todocompanion.app.data.entity.FlagEntity
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.domain.DoNext
import com.todocompanion.app.domain.EntryCounts
import com.todocompanion.app.domain.priority.PriorityEngine
import java.time.ZoneId

/**
 * R93 — the pure task-list rendering pipeline, lifted out of AppViewModel. Given the workspace task set
 * (plus the shared Inbox), the current view + grouping/sort config, and the container/label snapshots,
 * it produces the grouped, sorted [TaskGroup] list the UI renders — the whole view → filter → archive
 * → sort → group path. Deterministic (no Android or coroutine dependency), so it is now independently
 * unit-testable. The ViewModel keeps the reactive combine that assembles [Cfg]/[ViewCtx] and calls
 * [compute]; the shared subtree helpers moved here too (the ViewModel delegates its copies). Behaviour
 * is identical to the previous in-ViewModel lambda.
 */
object ListPipeline {
    data class Cfg(
        val view: ViewRef, val group: GroupMode, val sort: SortMode, val prio: PriorityEngine.Config,
        val flags: List<FlagEntity>, val timeAvail: Int? = null, val energyAvail: Int? = null, val ws: String = "default",
    )

    data class ViewCtx(
        val tcRefs: List<TaskContextCrossRef>, val contexts: List<ContextEntity>, val filters: List<FilterEntity>,
        val lists: List<ListEntity>, val folders: List<FolderEntity>, val tags: List<TagEntity>,
    )

    /** All ids in the subtree rooted at [rootId] — the root plus every descendant, following parent
     *  links. Cycle-safe. Used so a parent tag / context page rolls up its children's tasks. */
    fun <T> subtreeIds(rootId: String, entities: List<T>, idOf: (T) -> String, parentOf: (T) -> String?): Set<String> {
        val children = entities.groupBy { parentOf(it) }
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>().apply { add(rootId) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!out.add(cur)) continue
            children[cur].orEmpty().forEach { queue.add(idOf(it)) }
        }
        return out
    }

    /**
     * List-ids and folder-ids whose tasks must drop out of active/smart views AND their counts: any
     * archived OR trashed list/folder, cascaded down the folder tree (a list in a hidden folder, and a
     * sub-folder of a hidden folder, are hidden too). Shared by [compute] and by SmartCounts/EntryCounts
     * so a sidebar badge never disagrees with the list it heads. Returns (hiddenListIds, hiddenFolderIds).
     */
    fun hiddenContainers(lists: List<ListEntity>, folders: List<FolderEntity>): Pair<Set<String>, Set<String>> {
        val folderIds = folders.filter { it.archived || it.trashed }.mapTo(HashSet()) { it.id }
        var changed = true
        while (changed) {
            changed = false
            folders.forEach { f -> if (f.parentId != null && f.parentId in folderIds && f.id !in folderIds) { folderIds.add(f.id); changed = true } }
        }
        val listIds = lists.filter { it.archived || it.trashed || (it.folderId != null && it.folderId in folderIds) }.mapTo(HashSet()) { it.id }
        return listIds to folderIds
    }

    /** True when [t] lives in an archived/trashed list or folder (incl. folder-direct tasks). */
    fun isHiddenContainerTask(t: TaskEntity, hiddenLists: Set<String>, hiddenFolders: Set<String>): Boolean =
        t.listId in hiddenLists || (t.folderId != null && t.folderId in hiddenFolders)

    /** [ids] plus every descendant task id (follows parentId). Cycle-safe. */
    fun expandWithDescendants(ids: Set<String>, all: List<TaskEntity>): Set<String> {
        val byParent = all.groupBy { it.parentId }
        val out = HashSet(ids)
        val stack = ArrayDeque(ids)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            byParent[id].orEmpty().forEach { if (out.add(it.id)) stack.addLast(it.id) }
        }
        return out
    }

    fun compute(
        wsTasks: List<TaskEntity>, inbox: List<TaskEntity>, cfg: Cfg, ttRefs: List<TaskTagCrossRef>,
        vc: ViewCtx, deps: List<DependencyEntity>, zone: ZoneId, dayStartMin: Int, now: Long,
        // [allTasks] is the FULL task universe used only to resolve a dependency's prerequisite — a blocker
        // may live in another list (or even workspace) than the workspace-scoped [wsTasks] the list renders,
        // and if it isn't found it silently counts as "not blocking", which is exactly the bug where a task
        // with a real blocked-by never shows in Waiting On. Empty = fall back to the local set.
        allTasks: List<TaskEntity> = emptyList(),
    ): List<TaskGroup> {
        val tcRefs = vc.tcRefs; val ctxEntities = vc.contexts; val filterList = vc.filters
        // The base task set is the workspace-clean [wsTasks]; ONLY when viewing the Inbox do we swap in
        // the shared, cross-workspace Inbox set (deduped so a current-workspace Inbox task isn't doubled).
        val viewingInbox = (cfg.view as? ViewRef.Smart)?.kind == SmartKind.INBOX ||
            (cfg.view as? ViewRef.ListView)?.listId == ListEntity.INBOX_ID
        val all = if (viewingInbox) {
            val ids = wsTasks.mapTo(HashSet()) { it.id }
            wsTasks + inbox.filter { it.id !in ids }
        } else wsTasks
        val filteredRaw = when (val v = cfg.view) {
            is ViewRef.Smart -> {
                when (v.kind) {
                    SmartKind.DO_NEXT -> DoNext.focused(all, now, cfg.prio, deps, tcRefs, ctxEntities, cfg.timeAvail, cfg.energyAvail, zone, dayStartMin)
                    // Waiting-on (GTD): the two ways a task's next action isn't yours to take right now —
                    //  • delegated to / awaiting an EXTERNAL party (waitingForWho set) — the ball is in their
                    //    court; the canonical GTD "Waiting For".
                    //  • blocked by your own incomplete prerequisite task (the dependency engine).
                    // We union both here; the grouping step splits them into two labelled sections. Blockers
                    // resolve against the FULL task universe (falls back to the local set) so a prerequisite in
                    // another list/workspace is still recognized.
                    SmartKind.WAITING -> {
                        val byId = (if (allTasks.isNotEmpty()) allTasks else all).associateBy { it.id }
                        val blocked = PriorityEngine.computeBlocked(deps, byId, now)
                        all.filter { !it.trashed && !it.completed && !it.abandoned && !it.someday && (it.id in blocked || it.waitingForWho.isNotBlank()) }
                    }
                    // Trash is per-workspace — the shared Inbox otherwise leaked trashed tasks into every
                    // workspace. A trashed task is stamped with the workspace it was deleted in.
                    SmartKind.TRASH -> all.filter { it.trashed && it.workspaceId == cfg.ws }
                    else -> TaskViews.filterSmart(all, v.kind, now, zone, dayStartMin)
                }
            }
            is ViewRef.FilterView -> {
                val q = Filters.parse(filterList.firstOrNull { it.id == v.filterId }?.queryJson)
                val tagsByTask = ttRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
                val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
                val listFolderById = vc.lists.associate { it.id to it.folderId }
                val hit = all.filter { Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, zone, it.folderId ?: listFolderById[it.listId]) }
                if (q.includeChildren) {
                    val keep = expandWithDescendants(hit.map { it.id }.toSet(), all)
                    all.filter { it.id in keep && !it.trashed }
                } else hit
            }
            is ViewRef.ListView -> all.filter { !it.trashed && !it.completed && !it.abandoned && !it.someday && it.listId == v.listId }
            is ViewRef.FolderView -> {
                val listIds = EntryCounts.folderListIds(v.folderId, vc.lists, vc.folders)
                // Tasks in the folder's lists, plus tasks captured directly into the folder.
                all.filter { !it.trashed && !it.completed && !it.abandoned && !it.someday && (it.listId in listIds || it.folderId == v.folderId) }
            }
            is ViewRef.TagView -> {
                // A parent tag's page rolls up every descendant tag's tasks (matching the sidebar count).
                val tagIds = subtreeIds(v.tagId, vc.tags, { it.id }, { it.parentId })
                val ids = ttRefs.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
                all.filter { it.id in ids && !it.trashed && !it.completed && !it.abandoned && !it.someday }
            }
            is ViewRef.ContextView -> {
                // Same subtree rollup for contexts / sub-contexts.
                val ctxIds = subtreeIds(v.contextId, ctxEntities, { it.id }, { it.parentId })
                val ids = tcRefs.filter { it.contextId in ctxIds }.map { it.taskId }.toSet()
                all.filter { it.id in ids && !it.trashed && !it.completed && !it.abandoned && !it.someday }
            }
        }
        // Tasks in an archived OR trashed list/folder drop out of every active/smart view (Todoist-style),
        // including tasks captured directly into a folder (folderId match, not just listId). They stay
        // visible in Trash/Completed/Won't-Do so nothing is silently lost, and — crucially — when you
        // OPEN that specific list/folder (ListView/FolderView) so you can still browse its contents.
        val (hiddenListIds, hiddenFolderIds) = hiddenContainers(vc.lists, vc.folders)
        val kindNow = (cfg.view as? ViewRef.Smart)?.kind
        val keepHidden = kindNow == SmartKind.TRASH || kindNow == SmartKind.COMPLETED || kindNow == SmartKind.WONT_DO ||
            cfg.view is ViewRef.ListView || cfg.view is ViewRef.FolderView
        val filtered = if (keepHidden || (hiddenListIds.isEmpty() && hiddenFolderIds.isEmpty())) filteredRaw
            else filteredRaw.filter { !isHiddenContainerTask(it, hiddenListIds, hiddenFolderIds) }
        val flagRank = cfg.flags.sortedBy { it.sortOrder }.mapIndexed { i, f -> f.id to i }.toMap()
        val sorted = when {
            (cfg.view as? ViewRef.Smart)?.kind == SmartKind.DO_NEXT -> filtered
            // Wave D — "why-now score" sort: rank the visible tasks by the same explainable Do-Next score
            // the editor chip shows (base + star + date urgency + dependency boost), highest first.
            cfg.sort == SortMode.SCORE -> {
                val byId = all.associateBy { it.id }
                val boosts = PriorityEngine.dependencyBoosts(deps, byId, cfg.prio)
                // P7: compute the sort key ONCE per task with the allocation-free score() (was
                // explain().total, which builds ~7 String.format lines per call and ran O(n log n)
                // times during the sort). score() + depBoost == explain().total, so ordering is identical.
                val scoreRank = filtered
                    .map { it to (PriorityEngine.score(it, now, byId, cfg.prio) + (boosts[it.id] ?: 0.0)) }
                    .sortedByDescending { it.second }
                    .mapIndexed { i, (t, _) -> t.id to i }.toMap()
                TaskViews.sort(filtered, cfg.sort, flagRank, scoreRank)
            }
            else -> TaskViews.sort(filtered, cfg.sort, flagRank)
        }
        // Waiting-on renders as two GTD sections regardless of the group setting: "Waiting on others"
        // (delegated/external — the ball's in their court) first, then "Blocked by your task" (your own
        // prerequisite). A task that is both delegated and blocked reads as delegated (the salient point).
        if (kindNow == SmartKind.WAITING) {
            val (delegated, blockedOnly) = sorted.partition { it.waitingForWho.isNotBlank() }
            return listOfNotNull(
                delegated.takeIf { it.isNotEmpty() }?.let { TaskGroup("waiting:others", "Waiting on others", it) },
                blockedOnly.takeIf { it.isNotEmpty() }?.let { TaskGroup("waiting:blocked", "Blocked by your task", it) },
            )
        }
        // Manual sort flattens the view into ONE ungrouped list so long-press drag (reorder + nest)
        // works everywhere — folders, lists and smart lists alike — not only when grouping is off.
        val gm = if ((cfg.view as? ViewRef.Smart)?.kind == SmartKind.DO_NEXT || cfg.sort == SortMode.MANUAL) GroupMode.NONE else cfg.group
        return if (gm == GroupMode.FLAG) {
            // Group by flag, in the user's flag order; unflagged tasks fall into a trailing bucket.
            val ordered = cfg.flags.sortedBy { it.sortOrder }
            val nameById = ordered.associate { it.id to it.name }
            val orderById = ordered.mapIndexed { i, f -> f.id to i }.toMap()
            val buckets = LinkedHashMap<String, MutableList<TaskEntity>>()
            sorted.forEach { t ->
                val key = t.flagId?.takeIf { it in nameById } ?: "￿No flag"
                buckets.getOrPut(key) { mutableListOf() }.add(t)
            }
            buckets.entries
                .sortedBy { (k, _) -> if (k.startsWith("￿")) Int.MAX_VALUE else (orderById[k] ?: Int.MAX_VALUE - 1) }
                .map { (k, ts) ->
                    val label = if (k.startsWith("￿")) "No flag" else (nameById[k] ?: "Flag")
                    TaskGroup("flag:$k", label, ts)
                }
        } else if (gm == GroupMode.CONTEXT) {
            // Active-by-context (GTD): group each task under every context it carries.
            val ctxNameById = ctxEntities.associate { it.id to it.name }
            val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId } }
            val buckets = LinkedHashMap<String, MutableList<TaskEntity>>()
            sorted.forEach { t ->
                val cids = ctxByTask[t.id].orEmpty()
                if (cids.isEmpty()) buckets.getOrPut("￿No context") { mutableListOf() }.add(t)
                else cids.forEach { cid -> buckets.getOrPut(ctxNameById[cid] ?: "?") { mutableListOf() }.add(t) }
            }
            buckets.entries.sortedBy { it.key }.map { (name, ts) ->
                val label = if (name.startsWith("￿")) "No context" else "@$name"
                TaskGroup("ctx:$name", label, ts)
            }
        } else {
            // The Completed / Won't-Do views group by COMPLETION date, not the (always-past) due date.
            val doneView = (cfg.view as? ViewRef.Smart)?.kind.let { it == SmartKind.COMPLETED || it == SmartKind.WONT_DO }
            TaskViews.group(sorted, gm, now, zone, dayStartMin, byCompletion = doneView)
        }
    }
}
