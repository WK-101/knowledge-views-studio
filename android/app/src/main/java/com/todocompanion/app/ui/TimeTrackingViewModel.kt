package com.todocompanion.app.ui

import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.time.TimeTrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 3, Stage 3 — the dedicated home for the time-tracking surface, lifted out of the 6.6k-line
 * AppViewModel. It OWNS the workspace-scoped `timeActivities` / `timeEntries` flows and the single time
 * [controller]'s live actions (start / stop / pause / resume, activity CRUD, pin, reassign); it forwards the
 * few time helpers that still live on the ViewModel because they touch broader state (manual-entry edits,
 * automation rules, planned-block fill, habit↔activity linking).
 *
 * It's a plain class (not a ViewModel): the AppViewModel constructs exactly one and drives it with its own
 * `viewModelScope`, so there's no second lifecycle to manage and no chance of a second controller. Every
 * screen — and AppViewModel's own capacity / recap / coach logic — reads the time surface through this one
 * object, so the god-VM no longer carries the time flows and their ~30 actions inline.
 */
class TimeTrackingViewModel(
    private val app: AppViewModel,
    private val scope: CoroutineScope,
    repo: AppRepository,
    private val controller: TimeTrackingController,
) {
    // Workspace-scoped, exactly as the ViewModel declared them (same combine + WhileSubscribed + Default).
    private val activeWs: Flow<String> = app.settings.map { it.activeWorkspaceId }
    private fun <T> Flow<List<T>>.scopedBy(wsOf: (T) -> String): StateFlow<List<T>> =
        combine(this, activeWs) { list, w -> list.filter { wsOf(it) == w } }
            .flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val timeActivities: StateFlow<List<TimeActivityEntity>> = repo.allTimeActivities.scopedBy { it.workspaceId }
    val timeEntries: StateFlow<List<TimeEntryEntity>> = repo.allTimeEntries.scopedBy { it.workspaceId }

    /** Paused-timer memory (Triple<activityId, taskId?, habitId?>) — owned by the controller. */
    val pausedTrack: StateFlow<Triple<String, String?, String?>?> get() = controller.pausedTrack

    // ---- live timer + activity actions (the one controller) ----
    fun createTimeActivity(name: String, emoji: String?, colorArgb: Long?, goalMinutesPerDay: Int = 0) =
        scope.launch { controller.createTimeActivity(name, emoji, colorArgb, goalMinutesPerDay) }
    fun startTimeTrackingByName(name: String) = scope.launch { controller.startTimeTrackingByName(name) }
    fun toggleActivityPin(id: String) = scope.launch { controller.toggleActivityPin(id) }
    fun reassignTimeEntry(entryId: String, activityId: String) = scope.launch { controller.reassignTimeEntry(entryId, activityId) }
    fun refreshTrackShortcuts() = scope.launch { controller.refreshTrackShortcuts() }
    fun updateTimeActivity(a: TimeActivityEntity) = scope.launch { controller.updateTimeActivity(a) }
    fun deleteTimeActivity(id: String) = scope.launch { controller.deleteTimeActivity(id) }
    fun archiveTimeActivity(id: String) = scope.launch { controller.archiveTimeActivity(id) }
    fun setActivityParent(childId: String, parentId: String?) = scope.launch { controller.setActivityParent(childId, parentId) }
    fun startTimeTracking(activityId: String, taskId: String? = null, habitId: String? = null) =
        scope.launch { controller.startTimeTracking(activityId, taskId, habitId) }
    fun stopTimeTracking() = scope.launch { controller.stopTimeTracking() }
    fun stopTimeEntry(id: String) = scope.launch { controller.stopTimeEntry(id) }
    fun pauseTracking() = scope.launch { controller.pauseTracking() }
    fun resumeTracking() = scope.launch { controller.resumeTracking() }
    fun clearPaused() = controller.clearPaused()

    // ---- time helpers still owned by the ViewModel (touch broader state); forwarded so screens have one door ----
    fun untrackedTodayBlocks() = app.untrackedTodayBlocks()
    val addTimeEntryRequests get() = app.addTimeEntryRequests
    fun updateTimeEntry(e: TimeEntryEntity) = app.updateTimeEntry(e)
    fun deleteTimeEntry(id: String) = app.deleteTimeEntry(id)
    fun splitTimeEntry(id: String, atMillis: Long) = app.splitTimeEntry(id, atMillis)
    fun addManualTimeEntry(activityId: String, startMillis: Long, endMillis: Long, note: String = "") =
        app.addManualTimeEntry(activityId, startMillis, endMillis, note)
    fun fillTrackedBlock(block: com.todocompanion.app.domain.TimeInsights.PlannedBlock) = app.fillTrackedBlock(block)
    fun setTimeGridColumns(cols: Int) = app.setTimeGridColumns(cols)
    fun setHabitTimeActivity(habitId: String, activityId: String?) = app.setHabitTimeActivity(habitId, activityId)
    fun saveAutomationRules(rules: List<com.todocompanion.app.domain.AutomationRule>) = app.saveAutomationRules(rules)
}
