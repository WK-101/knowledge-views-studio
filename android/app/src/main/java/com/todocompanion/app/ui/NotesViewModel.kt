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
import kotlinx.coroutines.launch

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
    private val repo: AppRepository,
) {
    // Re-declared locally, exactly as TimeTrackingViewModel does (same combine + WhileSubscribed + Default),
    // so this collaborator owns its scoping instead of reaching into AppViewModel's private helpers.
    private val activeWs: Flow<String> = app.settings.map { it.activeWorkspaceId }
    /** The active workspace id, read synchronously — used to STAMP new rows (mirrors AppViewModel's helper). */
    private fun activeWorkspace(): String = app.settings.value.activeWorkspaceId
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

    // ── Notebooks — the optional dedicated notebook tree (Stage 4b) ──
    /** Wave 2 · Privacy Dial — toggle auto-vault-on-close for a whole notebook. */
    fun setNotebookAutoVault(notebookId: String, on: Boolean) = scope.launch {
        notebooks.value.firstOrNull { it.id == notebookId }?.let { repo.upsertNotebook(it.copy(autoVault = on)) }
    }
    fun saveNotebook(id: String?, name: String, icon: String?, colorArgb: Long?) = scope.launch {
        val existing = id?.let { nid -> notebooks.value.firstOrNull { it.id == nid } }
        repo.upsertNotebook(
            (existing ?: com.todocompanion.app.data.entity.NotebookEntity(id = "", name = name, workspaceId = activeWorkspace()))
                .copy(name = name.trim().ifBlank { "Notebook" }, icon = icon, colorArgb = colorArgb)
        )
    }
    fun deleteNotebook(id: String) = scope.launch { repo.deleteNotebook(id) }
    /** Create a notebook and hand back its new id (so the caller can assign the current note to it). */
    fun createNotebook(name: String, icon: String? = null, onCreated: (String) -> Unit = {}) = scope.launch {
        val id = repo.upsertNotebook(
            com.todocompanion.app.data.entity.NotebookEntity(
                id = "", name = name.trim().ifBlank { "Notebook" }, icon = icon, workspaceId = activeWorkspace(),
            )
        )
        onCreated(id)
    }

    // ── Notes settings — all pure repo.saveSettings(app.settings.value.copy(...)) (Stage 4b) ──
    fun setNoteDefaultView(v: String) = scope.launch { repo.saveSettings(app.settings.value.copy(noteDefaultView = v)) }
    fun setNotesViewMode(v: String) = scope.launch { repo.saveSettings(app.settings.value.copy(notesViewMode = v)) }
    // Custom note templates (user-created) — stored as JSON in settings; sit beside the built-in starters.
    fun saveNoteTemplate(name: String, emoji: String, body: String) = scope.launch {
        val list = com.todocompanion.app.domain.NoteTemplates.parseCustom(app.settings.value.notesTemplatesJson) +
            com.todocompanion.app.domain.NoteTemplates.newCustom(name, emoji, body)
        repo.saveSettings(app.settings.value.copy(notesTemplatesJson = com.todocompanion.app.domain.NoteTemplates.encodeCustom(list)))
    }
    fun deleteNoteTemplate(id: String) = scope.launch {
        val list = com.todocompanion.app.domain.NoteTemplates.parseCustom(app.settings.value.notesTemplatesJson).filterNot { it.id == id }
        repo.saveSettings(app.settings.value.copy(notesTemplatesJson = com.todocompanion.app.domain.NoteTemplates.encodeCustom(list)))
    }
    fun setNotesSort(v: String) = scope.launch { repo.saveSettings(app.settings.value.copy(notesSort = v)) }
    fun setNotesNotebookMode(mode: String) = scope.launch { repo.saveSettings(app.settings.value.copy(notesNotebookMode = mode)) }
    fun setPeriodicRecapEmbed(v: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(periodicRecapEmbed = v)) }
    // Wave Q — the reading experience: live-styling, reading theme, and typography setters.
    fun setNotesLiveStyle(v: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(notesLiveStyle = v)) }
    fun setNotesReadingTheme(v: String) = scope.launch { repo.saveSettings(app.settings.value.copy(notesReadingTheme = v)) }
    fun setNotesFont(v: String) = scope.launch { repo.saveSettings(app.settings.value.copy(notesFont = v)) }
    fun setNotesFontScale(v: Int) = scope.launch { repo.saveSettings(app.settings.value.copy(notesFontScale = v)) }
    fun setNotesLineHeight(v: String) = scope.launch { repo.saveSettings(app.settings.value.copy(notesLineHeight = v)) }
    fun setNotesMeasure(v: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(notesMeasure = v)) }
    fun setNotesFocusMode(v: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(notesFocusMode = v)) }
}
