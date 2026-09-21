package com.todocompanion.app.ui

import com.todocompanion.app.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 3, Stage 5 — the dedicated home for the Habits surface (habit tracking + the habit-science /
 * life-systems coaching layer built on top of it), lifted out of the 6k-line [AppViewModel] following the
 * exact collaborator pattern already proven by [TimeTrackingViewModel] and [NotesViewModel]: a plain class
 * (not a ViewModel) that the AppViewModel constructs once and drives with its own `viewModelScope`, so there
 * is no second lifecycle. It OWNS the workspace-scoped habit read-model flows and (in later stages) the habit
 * actions, reaching back to the parent only for cross-feature state (`app.settings`, `app.appCtx`, `app.toast`,
 * `app.undoEvents`, `app.appendAction`) and leaving the habits↔tasks/time/day-review/goals/Focus bridges on
 * the coordinating parent. AppViewModel keeps thin forwarding shims (`val habits get() = habitsVm.habits`) so
 * the many existing habit call sites across the screens need no edits.
 *
 * Stage 5-A (this file's current scope) moves the read-model flows — the three habit lists (`habits`,
 * `habitsWithArchived`, `trashedHabits`), `habitCheckins`, and the habit-science tables (`cravings`,
 * `witnessEvents`, `scorecardItems`, `buddies`, `integrityReviews`, `experiments`, `escrows`, `nudgeEvents`) —
 * which derive straight from `repo` observe queries and have no writer on the VM, so the move is clean. The
 * habit actions (CRUD, check-ins, coach/builder, reminders, stats, reserve/time) follow in the next stages.
 */
class HabitsViewModel(
    private val app: AppViewModel,
    private val scope: CoroutineScope,
    private val repo: AppRepository,
) {
    // Re-declared locally, exactly as TimeTrackingViewModel/NotesViewModel do (same combine + WhileSubscribed
    // + Default), so this collaborator owns its scoping instead of reaching into AppViewModel's private helpers.
    private val activeWs: Flow<String> = app.settings.map { it.activeWorkspaceId }
    /** The active workspace id, read synchronously — used to STAMP new rows (mirrors AppViewModel's helper). */
    private fun activeWorkspace(): String = app.settings.value.activeWorkspaceId
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)
    private fun <T> Flow<List<T>>.scopedBy(wsOf: (T) -> String): StateFlow<List<T>> =
        combine(this, activeWs) { list, w -> list.filter { wsOf(it) == w } }.state(emptyList())

    // ── Habit read-model flows (Stage 5-A) — active-workspace scoped off repo observe queries ──────────────
    val habits = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && !it.archived && !it.trashed } }.state(emptyList())
    /** Active-workspace habits INCLUDING archived (but never trashed) — for surfaces that must tell
     *  "archived" apart from "deleted" (e.g. a goal's lead-measure hint) and for the Archived view. */
    val habitsWithArchived = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && !it.trashed } }.state(emptyList())
    /** Trashed habits in the active workspace, newest-deleted first — the source for the habits Trash. */
    val trashedHabits = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && it.trashed }.sortedByDescending { it.trashedAt ?: 0L } }.state(emptyList())
    // Check-ins are intentionally NOT workspace-scoped (a check-in has no workspaceId; it is reached through
    // its parent habit's workspace wherever scoping matters) — same behaviour as before the split.
    val habitCheckins = repo.allCheckins.state(emptyList())

    // ── Habit-science / life-systems tables (the coaching layer built on habits) ───────────────────────────
    val cravings = repo.allCravings.scopedBy { it.workspaceId }
    val witnessEvents = repo.allWitnessEvents.scopedBy { it.workspaceId }
    val scorecardItems = repo.allScorecardItems.scopedBy { it.workspaceId }
    val buddies = repo.allBuddies.scopedBy { it.workspaceId }
    val integrityReviews = repo.allIntegrityReviews.scopedBy { it.workspaceId }
    val experiments = repo.allExperiments.scopedBy { it.workspaceId }
    val escrows = repo.allEscrows.scopedBy { it.workspaceId }
    val nudgeEvents = repo.allNudgeEvents.scopedBy { it.workspaceId }

    // ── Habits-tab view-state + settings-backed setters + overlay flags (Stage 5-B) ───────────────────────
    /** Fusion F2: a habit pre-selected to Focus on; the Focus screen consumes it and auto-logs. */
    val pendingFocusHabitId = MutableStateFlow<String?>(null)
    // Matrix mode and density are persisted in settings, so the choice survives an app restart.
    val habitMatrixMode: StateFlow<Boolean> = app.settings.map { it.habitMatrixMode }.stateIn(scope, SharingStarted.Eagerly, false)
    val habitDensity: StateFlow<Int> = app.settings.map { it.habitDensity }.stateIn(scope, SharingStarted.Eagerly, 1)
    fun setHabitMatrixMode(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(habitMatrixMode = on)) }
    fun setHabitDensity(level: Int) = scope.launch { repo.saveSettings(app.settings.value.copy(habitDensity = level.coerceIn(0, 2))) }
    fun setHabitGroupByCategory(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(habitGroupByCategory = on)) }
    fun setHabitSort(mode: String) = scope.launch { repo.saveSettings(app.settings.value.copy(habitSort = mode)) }
    fun setHabitInsightsExpanded(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(habitInsightsExpanded = on)) }
    /** Persist a habit's time-planning config (HabitTime) into settings-JSON, keyed by habit id. */
    fun setHabitTimeCfg(habitId: String, cfg: com.todocompanion.app.domain.habit.HabitTime.Cfg) = scope.launch {
        if (habitId.isBlank()) return@launch
        val m = app.settings.value.habitTimeCfg.toMutableMap()
        if (com.todocompanion.app.domain.habit.HabitTime.isDefault(cfg)) m.remove(habitId)
        else m[habitId] = com.todocompanion.app.domain.habit.HabitTime.encodeCfg(cfg)
        repo.saveSettings(app.settings.value.copy(habitTimeCfg = m))
    }
    /** R108 — persist the minute-of-day a habit's flexible calendar block was dragged to (display placement
     *  only; not a reminder). Merges into the existing HabitTime cfg keyed by habit id. */
    fun setHabitBlockMinute(habitId: String, minute: Int) = scope.launch {
        if (habitId.isBlank()) return@launch
        val cur = com.todocompanion.app.domain.habit.HabitTime.cfgFor(app.settings.value, habitId)
        setHabitTimeCfg(habitId, cur.copy(blockMin = minute.coerceIn(0, 1439)))
    }
    val habitDetailId = MutableStateFlow<String?>(null)    // non-null → the analytics screen overlays the tab
    val habitBatchOpen = MutableStateFlow(false)
    val habitPresetOpen = MutableStateFlow(false)
    val habitEditor = MutableStateFlow<HabitEditRequest?>(null)   // non-null → the full-screen editor is open
    val habitQuickAddOpen = MutableStateFlow(false)               // L6: natural-language "type a habit" dialog
    val habitTrendsOpen = MutableStateFlow(false)                 // M5: full trends & correlations dashboard
    val habitArchiveOpen = MutableStateFlow(false)                // Archived habits + Trash management overlay

    // ── Habit CRUD / lifecycle (Stage 5-C) ────────────────────────────────────────────────────────────────
    /** Refresh the three habit-touching home-screen widgets (habits list, stats, and momentum, which folds in
     *  habit strength). Public so the parent's still-there check-in/Focus bridges reach it through a shim. */
    fun refreshHabitWidgets() {
        com.todocompanion.app.widget.HabitsWidget.refresh(app.appCtx)
        com.todocompanion.app.widget.HabitStatsWidget.refresh(app.appCtx)
        // R104 — the momentum score folds in habit strength, so keep it live on habit changes too.
        com.todocompanion.app.widget.MomentumWidget.refresh(app.appCtx)
    }
    fun createHabit(name: String, emoji: String?, colorArgb: Long?, target: Int, unit: String? = null, scheduleDays: String = "", reminderTimes: String = "") = scope.launch {
        repo.createHabit(name.trim(), emoji, colorArgb, target, activeWorkspace(), unit, scheduleDays, reminderTimes)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(app.appCtx)
    }
    fun saveHabit(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        repo.upsertHabit(h)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(app.appCtx)
    }
    /** Create from a fully-built habit (Tier I editor). Workspace defaults to the active one. */
    fun addHabit(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        repo.createHabit(h.copy(workspaceId = h.workspaceId.ifBlank { activeWorkspace() }))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        refreshHabitWidgets()
    }
    fun addHabits(habits: List<com.todocompanion.app.data.entity.HabitEntity>) = scope.launch {
        val ws = activeWorkspace()
        habits.forEach { repo.createHabit(it.copy(workspaceId = it.workspaceId.ifBlank { ws })) }
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        refreshHabitWidgets()
    }
    /** Soft-delete a habit to Trash (recoverable), with an Undo. History is preserved; restore is lossless. */
    fun trashHabit(h: com.todocompanion.app.data.entity.HabitEntity) = scope.launch {
        repo.setHabitTrashed(h.id, true); refreshHabitWidgets()
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        app.undoEvents.tryEmit(UndoEvent(UndoKind.HABIT_TRASHED, h.id, "Habit moved to Trash", habitRestore = h))
    }
    /** Restore a trashed habit back to the active list. */
    fun restoreHabit(id: String) = scope.launch {
        repo.setHabitTrashed(id, false); refreshHabitWidgets()
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
    }
    /** Archive / unarchive a habit (kept out of the active list & analysis, never deleted), with an Undo. */
    fun setHabitArchived(h: com.todocompanion.app.data.entity.HabitEntity, archived: Boolean) = scope.launch {
        repo.setHabitArchived(h.id, archived); refreshHabitWidgets()
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        if (archived) app.undoEvents.tryEmit(UndoEvent(UndoKind.HABIT_ARCHIVED, h.id, "Habit archived", habitRestore = h))
    }
    /** Permanently erase a single trashed habit (Trash → Delete forever). */
    fun deleteHabit(id: String) = scope.launch {
        repo.deleteHabit(id); refreshHabitWidgets()
    }
    /** Permanently erase every trashed habit in the active workspace (Trash → Empty). */
    fun emptyHabitTrash() = scope.launch {
        repo.emptyHabitTrash(activeWorkspace()); refreshHabitWidgets()
    }
    fun setHabitOrder(ids: List<String>) = scope.launch { repo.setHabitOrder(ids); refreshHabitWidgets() }
}
