package com.todocompanion.app.ui

import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.domain.view.GroupMode
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.SortMode
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.domain.view.ViewTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Re-audit #15 — the task-list surface's VIEW STATE, split out of AppViewModel into its own collaborator
 * (same pattern as timeVm / notesVm / …). It owns the reactive UI state of the task list — which view is
 * selected, how it's grouped/sorted, outline vs flat vs board, multi-select, filter-hierarchy, outline zoom,
 * and the "I have N minutes / X energy" planners — plus the view-navigation actions (select, toggleOutline,
 * zoom). AppViewModel keeps thin forwarding shims (`val currentView get() = tasksVm.currentView`, `fun
 * select(...) = tasksVm.select(...)`) so every screen call site and the VM's own read-model pipeline are
 * unchanged.
 *
 * Deliberate boundary: the derived render pipeline (`groups` / `outlineRows` / `hierarchyRows` and the drawer
 * counts) stays in AppViewModel. It is woven into the VM's own zone / day-start / priority-config internals,
 * so it reads this collaborator's view-state through the shims rather than moving with it — the view-state is
 * the cleanly-separable half.
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
}
