package com.todocompanion.app.ui

import com.todocompanion.app.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Phase 3, Stage 4 — the dedicated home for the Notes surface, lifted out of the 6.6k-line [AppViewModel]
 * following the exact collaborator pattern established by [TimeTrackingViewModel]: a plain class (not a
 * ViewModel) that the AppViewModel constructs once and drives with its own `viewModelScope`, so there's no
 * second lifecycle. It OWNS the workspace-scoped note read-model flows and (in later stages) the note actions,
 * reaching back to the parent only for cross-feature state (`app.settings`, `app.undoEvents`, `app.toast`,
 * task/habit/day-review helpers). AppViewModel keeps thin forwarding shims (`val notes get() = notesVm.notes`)
 * so the ~170 existing note call sites across the screens need no edits.
 *
 * Stage 4a (this file's current scope) moves the six pure read-model flows — `notes`, `trashedNotes`,
 * `notebooks`, `noteTagRefs`, `noteContextRefs`, `smartViews` — which derive straight from `repo` observe
 * queries and have no writer on the VM, so the move is clean. The note actions (CRUD, notebooks, settings,
 * export/import, daily/periodic, recall, vault) follow in the next stages.
 */
class NotesViewModel(
    private val app: AppViewModel,
    private val scope: CoroutineScope,
    repo: AppRepository,
) {
    // Re-declared locally, exactly as TimeTrackingViewModel does (same combine + WhileSubscribed + Default),
    // so this collaborator owns its scoping instead of reaching into AppViewModel's private helpers.
    private val activeWs: Flow<String> = app.settings.map { it.activeWorkspaceId }
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)
    private fun <T> Flow<List<T>>.scopedBy(wsOf: (T) -> String): StateFlow<List<T>> =
        combine(this, activeWs) { list, w -> list.filter { wsOf(it) == w } }.state(emptyList())

    // Notes module (v66) — workspace-scoped, non-trashed notes + the optional dedicated notebook tree.
    // The live-notes and Trash lists filter workspace+trashed IN SQL (index-backed via NoteDao.observeByWorkspace),
    // re-subscribing when the active workspace changes (behaviour proven by NoteScopeQueryTest).
    @OptIn(ExperimentalCoroutinesApi::class)
    val notes = activeWs.flatMapLatest { ws -> repo.observeNotesByWorkspace(ws, trashed = false) }.state(emptyList())
    /** The Trash — workspace-scoped notes the user has trashed but not yet permanently deleted. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val trashedNotes = activeWs.flatMapLatest { ws -> repo.observeNotesByWorkspace(ws, trashed = true) }.state(emptyList())
    val notebooks = repo.observeNotebooks().scopedBy { it.workspaceId }
    // Note ↔ tag / context cross-refs (for chips on cards / editor). Observed off their own tables directly so a
    // tag/context toggle reflects immediately — writing them doesn't touch the notes table, so deriving these
    // from the notes flow left the editor's chips stale.
    val noteTagRefs: StateFlow<List<com.todocompanion.app.data.entity.NoteTagCrossRef>> =
        repo.observeNoteTagCrossRefs().state(emptyList())
    val noteContextRefs: StateFlow<List<com.todocompanion.app.data.entity.NoteContextCrossRef>> =
        repo.observeNoteContextCrossRefs().state(emptyList())
    val smartViews = repo.observeSmartViews().scopedBy { it.workspaceId }
}
