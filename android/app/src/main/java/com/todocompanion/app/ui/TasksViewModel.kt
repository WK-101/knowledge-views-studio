package com.todocompanion.app.ui

import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.FilterEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.domain.view.Filters
import com.todocompanion.app.domain.view.GroupMode
import com.todocompanion.app.domain.view.ListPipeline
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.SortMode
import com.todocompanion.app.domain.view.TaskGroup
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.domain.view.ViewTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Re-audit #15 — the task-list surface's VIEW STATE, split out of AppViewModel into its own collaborator
 * (same pattern as timeVm / notesVm / …). It owns the reactive UI state of the task list — which view is
 * selected, how it's grouped/sorted, outline vs flat vs board, multi-select, filter-hierarchy, outline zoom,
 * and the "I have N minutes / X energy" planners — plus the view-navigation actions (select, toggleOutline,
 * zoom). AppViewModel keeps thin forwarding shims (`val currentView get() = tasksVm.currentView`, `fun
 * select(...) = tasksVm.select(...)`) so every screen call site is unchanged.
 *
 * Re-audit ceiling 1 — the derived RENDER pipeline (`groups` / `outlineRows` / `hierarchyRows`) now lives here
 * too, with the view-state it consumes, rather than back on AppViewModel. It still delegates the heavy compute
 * to the pure `domain.view.ListPipeline`; this collaborator owns only the reactive wiring plus the two small
 * outline builders. The flows are `by lazy` so building them (which reads AppViewModel's `wsTasks` /
 * `inboxTasksAll`) is deferred to first collection, after AppViewModel has finished initialising those flows —
 * this collaborator is constructed before them. The drawer counts (`smartCounts` / `entryCounts`) stay on the
 * coordinator: they are cross-feature badges, not the task-list render.
 */
class TasksViewModel(
    app: AppViewModel,
    scope: CoroutineScope,
    private val repo: AppRepository,
) : FeatureViewModel(app, scope) {

    val currentView = MutableStateFlow<ViewRef>(ViewRef.Smart(SmartKind.TODAY))
    val groupMode = MutableStateFlow(GroupMode.DATE)
    val sortMode = MutableStateFlow(SortMode.MANUAL)
    val outlineMode = MutableStateFlow(false)
    val boardMode = MutableStateFlow(false)
    /** True while the task list is in multi-select mode — used to hide the add FAB so its
     *  action bar (cancel/complete/flag/move/delete) doesn't overlap the button. */
    val selectionActive = MutableStateFlow(false)
    /** MLO-style "show matches in the tree" for filter/tag/context views. */
    val filterHierarchy = MutableStateFlow(false)
    /** When set, the outline is zoomed into this task's subtree (MLO-style focus). */
    val outlineZoom = MutableStateFlow<String?>(null)
    /** "I have N minutes" planner: when set, Do-Next hides tasks whose estimate exceeds N. null = off. */
    val timeAvailableMin = MutableStateFlow<Int?>(null)
    /** "Right now I have X energy" planner: when set (1/2/3), Do-Next keeps tasks needing at most that
     *  much energy (plus untagged). null = off. */
    val energyAvailable = MutableStateFlow<Int?>(null)

    fun select(view: ViewRef) {
        currentView.value = view
        groupMode.value = if (view is ViewRef.ListView) GroupMode.NONE else GroupMode.DATE
        // R28 #2 — the Completed / Won't-Do views open sorted by when things were finished (newest first).
        val doneKind = (view as? ViewRef.Smart)?.kind.let { it == SmartKind.COMPLETED || it == SmartKind.WONT_DO }
        if (doneKind) sortMode.value = SortMode.COMPLETED
        else if (sortMode.value == SortMode.COMPLETED) sortMode.value = SortMode.MANUAL
        // Seed outline mode from the list's own persisted viewMode so a list remembers nested vs flat.
        outlineMode.value = (view as? ViewRef.ListView)?.let { lv -> app.lists.value.firstOrNull { it.id == lv.listId }?.viewMode == "outline" } ?: false
        // Remember the last place, when the user opted into resuming there.
        val s = app.settings.value
        if (s.resumeLastView) scope.launch { repo.saveSettings(repo.settingsSnapshot().copy(lastViewRef = ViewTabs.refOf(view))) }
    }

    /** Flip the current list's outline (nested) vs flat view and persist it on the ListEntity so it sticks. */
    fun toggleOutline() {
        val on = !outlineMode.value
        outlineMode.value = on
        (currentView.value as? ViewRef.ListView)?.let { lv ->
            scope.launch { app.lists.value.firstOrNull { it.id == lv.listId }?.let { repo.saveList(it.copy(viewMode = if (on) "outline" else "list")) } }
        }
    }

    fun zoomInto(taskId: String?) { outlineZoom.value = taskId }

    /** Title of the current zoom root, for the breadcrumb, or null when not zoomed. */
    fun zoomTitle(): String? = outlineZoom.value?.let { z -> app.tasks.value.firstOrNull { it.id == z }?.title }

    /** True when the current view can render as a hierarchy-preserving filter (filter/tag/context). */
    fun canHierarchy(): Boolean = currentView.value.let { it is ViewRef.FilterView || it is ViewRef.TagView || it is ViewRef.ContextView }

    // ---------------------------------------------------------------------------------------------
    // Re-audit ceiling 1 — the derived task RENDER pipeline, moved here from AppViewModel so it lives with
    // the view-state it reads. Bodies are unchanged from the AppViewModel originals (proven equivalent by
    // AppViewModelCharacterizationTest); the heavy compute still delegates to the pure ListPipeline. `by lazy`
    // defers the first read of app.wsTasks / app.inboxTasksAll to first collection (this collaborator is
    // constructed before AppViewModel initialises those flows). `.state(...)` comes from FeatureViewModel:
    // upstream on Default, lazy WhileSubscribed(5s) StateFlow.

    val groups: StateFlow<List<TaskGroup>> by lazy {
        combine(
            // wsTasks + shared Inbox + the FULL task set (used only to resolve dependency prerequisites that
            // may live outside the workspace-scoped render set — see ListPipeline.compute's allTasks param).
            combine(app.wsTasks, app.inboxTasksAll, repo.allTasks) { ws, inbox, allT -> Triple(ws, inbox, allT) },
            combine(currentView, groupMode, sortMode, app.settings, combine(repo.allFlags, timeAvailableMin, energyAvailable) { fl, ta, ea -> Triple(fl, ta, ea) }) { v, g, s, set, fte -> ListPipeline.Cfg(v, g, s, set.priorityConfig(), fte.first, fte.second, fte.third, set.activeWorkspaceId) },
            repo.taskTagRefs,
            combine(repo.taskContextRefs, repo.allContexts, repo.allFilters, repo.allLists, combine(repo.allFolders, repo.allTags) { fo, tg -> fo to tg }) { r, c, f, l, foTg -> ListPipeline.ViewCtx(r, c, f, l, foTg.first, foTg.second) },
            repo.allDependencies,
        ) { wsTriple, cfg, ttRefs, vc, deps ->
            ListPipeline.compute(wsTriple.first, wsTriple.second, cfg, ttRefs, vc, deps, app.zoneId, app.settings.value.dayStartMinuteOfDay(), System.currentTimeMillis(), wsTriple.third)
        }.state(emptyList())
    }

    val outlineRows: StateFlow<List<OutlineRow>> by lazy {
        combine(app.wsTasks, currentView, outlineZoom) { all, v, zoom ->
            val listId = (v as? ViewRef.ListView)?.listId ?: return@combine emptyList()
            val listTasks = all.filter { it.listId == listId && !it.trashed }
            // Zoom only holds while its task still exists in this list.
            val start = zoom?.takeIf { z -> listTasks.any { it.id == z } }
            buildOutline(listTasks, start)
        }.state(emptyList())
    }

    /**
     * MLO "outline filtering": the matched tasks of a filter/tag/context view rendered in their real
     * tree position — matches solid, structural ancestors dimmed. Empty unless [filterHierarchy] is on.
     */
    val hierarchyRows: StateFlow<List<OutlineRow>> by lazy {
        combine(
            app.wsTasks, currentView, filterHierarchy,
            combine(repo.taskTagRefs, repo.taskContextRefs, repo.allFilters, repo.allLists, combine(repo.allTags, repo.allContexts) { tg, cx -> tg to cx }) { tt, tc, f, ls, tgcx -> listOf(tt, tc, f, ls, tgcx.first, tgcx.second) },
        ) { all, v, on, refs ->
            if (!on) return@combine emptyList()
            @Suppress("UNCHECKED_CAST")
            val ttRefs = refs[0] as List<TaskTagCrossRef>
            @Suppress("UNCHECKED_CAST")
            val tcRefs = refs[1] as List<TaskContextCrossRef>
            @Suppress("UNCHECKED_CAST")
            val filters = refs[2] as List<FilterEntity>
            @Suppress("UNCHECKED_CAST")
            val hLists = refs[3] as List<ListEntity>
            @Suppress("UNCHECKED_CAST")
            val hTags = refs[4] as List<TagEntity>
            @Suppress("UNCHECKED_CAST")
            val hContexts = refs[5] as List<ContextEntity>
            val listFolderById = hLists.associate { it.id to it.folderId }
            val now = System.currentTimeMillis()
            val matched: Set<String> = when (v) {
                is ViewRef.FilterView -> {
                    val q = Filters.parse(filters.firstOrNull { it.id == v.filterId }?.queryJson)
                    val tagsByTask = ttRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
                    val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
                    val hit = all.filter { Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, app.zoneId, it.folderId ?: listFolderById[it.listId]) }.map { it.id }.toSet()
                    if (q.includeChildren) ListPipeline.expandWithDescendants(hit, all) else hit
                }
                is ViewRef.TagView -> {
                    val tagIds = ListPipeline.subtreeIds(v.tagId, hTags, { it.id }, { it.parentId })
                    ttRefs.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
                }
                is ViewRef.ContextView -> {
                    val ctxIds = ListPipeline.subtreeIds(v.contextId, hContexts, { it.id }, { it.parentId })
                    tcRefs.filter { it.contextId in ctxIds }.map { it.taskId }.toSet()
                }
                else -> return@combine emptyList()
            }
            buildFilteredOutline(all.filter { !it.trashed }, matched)
        }.state(emptyList())
    }

    private fun buildOutline(all: List<TaskEntity>, startId: String? = null): List<OutlineRow> {
        val byParent = all.groupBy { it.parentId }
        val out = ArrayList<OutlineRow>(all.size)
        fun dfs(parentId: String?, depth: Int) {
            byParent[parentId]?.sortedBy { it.sortOrder }?.forEach { t ->
                val kids = byParent[t.id].orEmpty()
                out.add(OutlineRow(t, depth, kids.isNotEmpty(), t.collapsed))
                if (!t.collapsed) dfs(t.id, depth + 1)
            }
        }
        if (startId != null) {
            val root = all.firstOrNull { it.id == startId } ?: return emptyList()
            val kids = byParent[startId].orEmpty()
            out.add(OutlineRow(root, 0, kids.isNotEmpty(), root.collapsed))
            if (!root.collapsed) dfs(startId, 1)
        } else dfs(null, 0)
        return out
    }

    /** Build an outline of the [matched] tasks plus every ancestor needed to place them in the tree.
     *  Ancestors that aren't themselves matches are flagged (rendered dimmed). Ignores collapse. */
    private fun buildFilteredOutline(all: List<TaskEntity>, matched: Set<String>): List<OutlineRow> {
        if (matched.isEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        val included = HashSet<String>()
        matched.forEach { id ->
            var cur: String? = id
            while (cur != null && cur !in included && cur in byId) { included.add(cur); cur = byId[cur]?.parentId }
        }
        val inc = all.filter { it.id in included }
        val byParent = inc.groupBy { it.parentId }
        val out = ArrayList<OutlineRow>(inc.size)
        fun dfs(t: TaskEntity, depth: Int) {
            val kids = byParent[t.id].orEmpty()
            out.add(OutlineRow(t, depth, kids.isNotEmpty(), collapsed = false, matched = t.id in matched))
            kids.sortedBy { it.sortOrder }.forEach { dfs(it, depth + 1) }
        }
        // Roots = included tasks whose parent isn't part of this filtered forest.
        inc.filter { it.parentId == null || it.parentId !in included }
            .sortedWith(compareBy({ it.listId }, { it.sortOrder }))
            .forEach { dfs(it, 0) }
        return out
    }
}
