package com.todocompanion.app.time

import android.content.Context
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.reminders.AutomationRunner
import com.todocompanion.app.reminders.TimeIntentApi
import com.todocompanion.app.util.TrackShortcuts
import com.todocompanion.app.widget.TimeWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * R88 — the live time-tracking control (activity CRUD + the start / stop / pause / resume timer
 * surface), split out of AppViewModel following the R84 controller pattern. Focus is a MODE of this;
 * the pure statistics/reports live in [com.todocompanion.app.domain.FocusStats] and
 * [com.todocompanion.app.domain.TimeReports]. This holds only the imperative side: repo writes, the
 * time-widget + launcher-shortcut refresh, on-start automation, the time-intent broadcast, and the
 * paused-timer memory it owns.
 *
 * It takes the ambient reads as lambdas (`settings()`, `activities()`, `entries()`) and one callback
 * (`onRefreshHabits`) for the habit widgets the ViewModel owns; the time widget it refreshes directly.
 * The ViewModel keeps thin `viewModelScope.launch { … }` wrappers, its StateFlows, and re-exposes
 * [pausedTrack]. Behaviour is identical to the previous in-ViewModel implementation.
 */
class TimeTrackingController(
    private val context: Context,
    private val repo: AppRepository,
    private val settings: () -> AppSettings,
    private val activities: () -> List<TimeActivityEntity>,
    private val entries: () -> List<TimeEntryEntity>,
    private val onRefreshHabits: () -> Unit,
) {
    private fun refreshTimeWidget() = TimeWidget.refresh(context)

    // U3 — pause finalizes the running interval (crediting any linked habit) and remembers what it was
    // (activityId, taskId?, habitId?), so Resume can start it again; the gap between is honestly untracked.
    private val _pausedTrack = MutableStateFlow<Triple<String, String?, String?>?>(null)
    val pausedTrack: StateFlow<Triple<String, String?, String?>?> = _pausedTrack

    suspend fun createTimeActivity(name: String, emoji: String?, colorArgb: Long?, goalMinutesPerDay: Int = 0) {
        repo.createTimeActivity(name, emoji, colorArgb, goalMinutesPerDay); refreshTimeWidget(); refreshTrackShortcuts()
    }

    /** U13: start tracking by activity name (from an NFC/QR deep link), creating it if unknown. */
    suspend fun startTimeTrackingByName(name: String) {
        val existing = activities().firstOrNull { it.name.equals(name.trim(), true) && !it.archived }
        val id = existing?.id ?: repo.createTimeActivity(name.trim().ifBlank { "Activity" }, null, null)
        repo.startTimeTracking(id, stopFirst = !settings().multiTimer)
        AutomationRunner.onStart(context, repo, id); refreshTimeWidget()
    }

    /** Pin/unpin a time activity so it floats to the front of the one-tap tile grid. */
    suspend fun toggleActivityPin(id: String) {
        val s = settings()
        val next = if (id in s.pinnedActivities) s.pinnedActivities - id else s.pinnedActivities + id
        repo.saveSettings(s.copy(pinnedActivities = next))
    }

    /** Reassign the running (or any) time entry to a different activity — "start first, pick later". */
    suspend fun reassignTimeEntry(entryId: String, activityId: String) {
        entries().firstOrNull { it.id == entryId }?.let { repo.upsertTimeEntry(it.copy(activityId = activityId)); refreshTimeWidget() }
    }

    /** U13: publish a launcher shortcut per activity ("Track: Deep work") that fires the track deep link. */
    suspend fun refreshTrackShortcuts() {
        TrackShortcuts.refresh(context, activities().filter { !it.archived })
    }

    suspend fun updateTimeActivity(a: TimeActivityEntity) { repo.upsertTimeActivity(a); refreshTimeWidget(); refreshTrackShortcuts() }

    suspend fun deleteTimeActivity(id: String) {
        // Clear a paused timer on this activity too, or Resume would re-create an orphan entry (audit #7).
        if (_pausedTrack.value?.first == id) _pausedTrack.value = null
        repo.deleteTimeActivity(id); refreshTimeWidget(); refreshTrackShortcuts()
    }

    suspend fun archiveTimeActivity(id: String) { repo.archiveTimeActivity(id); refreshTimeWidget(); refreshTrackShortcuts() }

    /** Nested activities: set (or clear, with null) an activity's parent. Stored in settings (no
     *  migration). Rejects a parent that would create a cycle (A→B→A), which would orphan the grid. */
    suspend fun setActivityParent(childId: String, parentId: String?) {
        val s = settings()
        val map = s.timeActivityParents
        val wouldCycle = parentId != null && run {
            var cur: String? = parentId
            var guard = 0
            while (cur != null && guard < 64) { if (cur == childId) return@run true; cur = map[cur]; guard++ }
            false
        }
        val next = if (parentId == null || parentId == childId || wouldCycle) map - childId
        else map + (childId to parentId)
        repo.saveSettings(s.copy(timeActivityParents = next))
    }

    /** Start (or switch) tracking. U15: with multi-timer on, the running timer isn't stopped first.
     *  U12: on-start rules run after. */
    suspend fun startTimeTracking(activityId: String, taskId: String? = null, habitId: String? = null) {
        repo.startTimeTracking(activityId, taskId, habitId, stopFirst = !settings().multiTimer)
        AutomationRunner.onStart(context, repo, activityId)
        TimeIntentApi.broadcastStarted(context, activities().firstOrNull { it.id == activityId }?.name ?: "")
        refreshTimeWidget()
    }

    suspend fun stopTimeTracking() {
        val nm = entries().firstOrNull { it.running }?.let { r -> activities().firstOrNull { it.id == r.activityId }?.name }
        repo.stopTimeTracking(); onRefreshHabits(); refreshTimeWidget()
        nm?.let { TimeIntentApi.broadcastStopped(context, it) }
    }

    /** U15: stop one specific running timer (when several overlap). */
    suspend fun stopTimeEntry(id: String) {
        val nm = entries().firstOrNull { it.id == id }?.let { r -> activities().firstOrNull { it.id == r.activityId }?.name }
        repo.stopTimeEntry(id); onRefreshHabits(); refreshTimeWidget()
        nm?.let { TimeIntentApi.broadcastStopped(context, it) }
    }

    suspend fun pauseTracking() {
        val running = repo.runningTimeEntry() ?: return
        _pausedTrack.value = Triple(running.activityId, running.taskId, running.habitId)
        repo.stopTimeTracking(); onRefreshHabits(); refreshTimeWidget()
    }

    suspend fun resumeTracking() {
        val p = _pausedTrack.value ?: return
        _pausedTrack.value = null
        repo.startTimeTracking(p.first, p.second, p.third, stopFirst = !settings().multiTimer); refreshTimeWidget()
    }

    fun clearPaused() { _pausedTrack.value = null }
}
