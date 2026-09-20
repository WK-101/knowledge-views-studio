package com.todocompanion.app.ui

import androidx.lifecycle.ViewModel

/**
 * Phase 3, Stage 1 — a dedicated per-surface handle for the two dedicated Time screens
 * ([com.todocompanion.app.ui.screens.TimeTrackingScreen], [com.todocompanion.app.ui.screens.TimeStatsScreen]).
 *
 * It forwards to [app]'s SINGLE `TimeTrackingController` and its workspace-scoped time flows, so there is
 * exactly one source of truth: no second controller, no diverged paused-timer state, no chance of a
 * cross-workspace read. Behaviour is identical to calling `vm.<x>` directly — this is a rename of the
 * access path, not a change to it.
 *
 * This is the seam, not the destination. The Time screens now depend on `TimeTrackingViewModel` instead of
 * reaching into the 6.6k-line god-VM's time surface. Stage 2 turns this into a standalone ViewModel that
 * OWNS the controller (decoupled from the VM's workspace-scoped flows), and Stage 3 deletes the VM's copies;
 * because the screens already talk to this type, those stages won't touch them again.
 */
class TimeTrackingViewModel(private val app: AppViewModel) : ViewModel() {

    // ---- read surface: the very same workspace-scoped flows the VM exposes ----
    val timeActivities get() = app.timeActivities
    val timeEntries get() = app.timeEntries
    val addTimeEntryRequests get() = app.addTimeEntryRequests
    /** Today's planned-but-untracked blocks — a computed snapshot, not a flow. */
    fun untrackedTodayBlocks() = app.untrackedTodayBlocks()

    // ---- actions: forwarded to the one controller (each is a thin viewModelScope.launch in the VM) ----
    fun createTimeActivity(name: String, emoji: String?, colorArgb: Long?, goalMinutesPerDay: Int = 0) =
        app.createTimeActivity(name, emoji, colorArgb, goalMinutesPerDay)
    fun updateTimeActivity(a: com.todocompanion.app.data.entity.TimeActivityEntity) = app.updateTimeActivity(a)
    fun deleteTimeActivity(id: String) = app.deleteTimeActivity(id)
    fun archiveTimeActivity(id: String) = app.archiveTimeActivity(id)
    fun setActivityParent(childId: String, parentId: String?) = app.setActivityParent(childId, parentId)
    fun toggleActivityPin(id: String) = app.toggleActivityPin(id)
    fun startTimeTracking(activityId: String, taskId: String? = null, habitId: String? = null) =
        app.startTimeTracking(activityId, taskId, habitId)
    fun stopTimeEntry(id: String) = app.stopTimeEntry(id)
    fun updateTimeEntry(e: com.todocompanion.app.data.entity.TimeEntryEntity) = app.updateTimeEntry(e)
    fun deleteTimeEntry(id: String) = app.deleteTimeEntry(id)
    fun splitTimeEntry(id: String, atMillis: Long) = app.splitTimeEntry(id, atMillis)
    fun addManualTimeEntry(activityId: String, startMillis: Long, endMillis: Long, note: String = "") =
        app.addManualTimeEntry(activityId, startMillis, endMillis, note)
    fun fillTrackedBlock(block: com.todocompanion.app.domain.TimeInsights.PlannedBlock) = app.fillTrackedBlock(block)
    fun setTimeGridColumns(cols: Int) = app.setTimeGridColumns(cols)
    fun setHabitTimeActivity(habitId: String, activityId: String?) = app.setHabitTimeActivity(habitId, activityId)
    fun saveAutomationRules(rules: List<com.todocompanion.app.domain.AutomationRule>) = app.saveAutomationRules(rules)
}
