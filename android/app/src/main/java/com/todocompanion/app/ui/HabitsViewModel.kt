package com.todocompanion.app.ui

import com.todocompanion.app.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
}
