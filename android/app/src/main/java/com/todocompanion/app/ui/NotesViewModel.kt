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
import kotlinx.coroutines.flow.MutableStateFlow
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

    // ── Note CRUD + lifecycle + smart-views + revisions (Stage 4c) ──
    /** Create a brand-new note (stamped with the active workspace) and return its id via [onCreated]. */
    fun createNote(
        title: String = "",
        body: String = "",
        notebookId: String? = null,
        folderId: String? = null,
        kind: String = "note",
        onCreated: (String) -> Unit = {},
    ) = scope.launch {
        val id = repo.upsertNote(
            com.todocompanion.app.data.entity.NoteEntity(
                id = "", title = title, body = body, notebookId = notebookId, folderId = folderId,
                kind = kind, workspaceId = activeWorkspace(),
            )
        )
        onCreated(id)
    }
    fun trashNote(id: String) = scope.launch {
        val prev = repo.getNote(id)   // exact pre-trash snapshot for a full undo
        repo.trashNote(id, true)
        if (prev != null) app.undoEvents.tryEmit(UndoEvent(UndoKind.NOTE_TRASHED, id, "Moved to Trash", noteRestore = prev))
    }
    fun deleteNote(id: String) = scope.launch { repo.deleteNote(id) }
    /** Bring a note back out of the Trash. */
    fun restoreNoteFromTrash(id: String) = scope.launch { repo.trashNote(id, false) }
    /** Permanently delete every trashed note in the active workspace ("Empty Trash"). */
    fun emptyNoteTrash() = scope.launch {
        val ws = activeWorkspace()
        repo.getNotesOnce().filter { it.trashed && it.workspaceId == ws }.forEach { repo.deleteNote(it.id) }
    }
    fun setNoteTags(noteId: String, tagIds: List<String>) = scope.launch { repo.setNoteTags(noteId, tagIds) }
    fun setNoteContexts(noteId: String, contextIds: List<String>) = scope.launch { repo.setNoteContexts(noteId, contextIds) }
    // ── Wave B: archive · duplicate · version history · auto-empty-trash ──
    fun archiveNote(id: String, archived: Boolean = true) = scope.launch {
        val prev = repo.getNote(id)
        repo.archiveNote(id, archived)
        if (archived && prev != null) app.undoEvents.tryEmit(UndoEvent(UndoKind.NOTE_ARCHIVED, id, "Archived", noteRestore = prev))
    }
    fun duplicateNote(id: String, onDone: (String) -> Unit = {}) = scope.launch { repo.duplicateNote(id)?.let { onDone(it) } }
    /** Capture a version snapshot (bounded to the user's "keep versions" setting). Call on editor close. */
    fun saveNoteRevision(id: String) = scope.launch { repo.saveNoteRevision(id, app.settings.value.notesMaxRevisions) }
    fun observeNoteRevisions(id: String) = repo.observeNoteRevisions(id)
    fun observeNoteLinks(id: String) = repo.observeNoteLinks(id)
    suspend fun unlinkedMentions(body: String, excludeNoteId: String) = repo.unlinkedMentions(body, excludeNoteId)
    suspend fun noteLinksSnapshot() = repo.getNoteLinksOnce()
    fun saveSmartView(id: String?, title: String, icon: String?, predicate: com.todocompanion.app.domain.NotePredicate) = scope.launch {
        val existing = id?.let { vid -> smartViews.value.firstOrNull { it.id == vid } }
        repo.upsertSmartView(
            (existing ?: com.todocompanion.app.data.entity.SmartViewEntity(id = "", title = title, predicateJson = "", workspaceId = activeWorkspace()))
                .copy(title = title.trim().ifBlank { "View" }, icon = icon, predicateJson = com.todocompanion.app.domain.NoteSmartViews.encode(predicate)),
        )
    }
    fun deleteSmartView(id: String) = scope.launch { repo.deleteSmartView(id) }
    fun restoreNoteRevision(noteId: String, title: String, body: String) = scope.launch {
        repo.getNote(noteId)?.let { repo.upsertNote(it.copy(title = title, body = body)) }
    }

    // ── Note reminders + seal (Stage 4d) — reuse the existing AlarmScheduler, no new permission ──
    /** Set a note's whole reminder set: a primary [atMillis] with optional [rrule], any [extra] one-shots,
     *  and "keep reminding until opened" ([keep]). A null primary + no extras clears everything. */
    fun setNoteReminder(noteId: String, atMillis: Long?, rrule: String? = null, extra: List<Long> = emptyList(), keep: Boolean = false) = scope.launch {
        val now = System.currentTimeMillis()
        val extraCsv = extra.filter { it > now }.sorted().joinToString(",")
        if (atMillis == null && extraCsv.isEmpty()) {
            repo.clearNoteReminder(noteId)
            com.todocompanion.app.reminders.AlarmScheduler.cancelNoteReminder(app.appCtx, noteId)
            return@launch
        }
        repo.setNoteReminderAll(noteId, atMillis, rrule?.ifBlank { null }, extraCsv, keep)
        repo.getNote(noteId)?.let { com.todocompanion.app.reminders.AlarmScheduler.armNoteReminders(app.appCtx, it) }
    }
    /** Wave J — seal a note to your future self: hidden until [untilMillis], when a reveal reminder resurfaces it. */
    fun sealNote(noteId: String, untilMillis: Long) = scope.launch {
        if (untilMillis <= System.currentTimeMillis()) return@launch
        repo.setNoteSealedUntil(noteId, untilMillis)
        repo.setNoteReminderAll(noteId, untilMillis, null, "", false)
        repo.getNote(noteId)?.let { com.todocompanion.app.reminders.AlarmScheduler.armNoteReminders(app.appCtx, it) }
    }
    fun unsealNote(noteId: String) = scope.launch {
        repo.setNoteSealedUntil(noteId, null)
        repo.clearNoteReminder(noteId)
        com.todocompanion.app.reminders.AlarmScheduler.cancelNoteReminder(app.appCtx, noteId)
    }
    fun setNotesTrashRetention(days: Int) = scope.launch { repo.saveSettings(app.settings.value.copy(notesTrashRetentionDays = days)) }
    fun setNotesMaxRevisions(n: Int) = scope.launch { repo.saveSettings(app.settings.value.copy(notesMaxRevisions = n)) }
    /** Lazy on-open sweep — hard-delete trashed notes older than the retention setting (0 = never). */
    fun purgeExpiredNoteTrash() = scope.launch { repo.purgeExpiredTrashedNotes(app.settings.value.notesTrashRetentionDays) }

    // ── Note search / ask / right-now (Stage 4d) ──
    /** Note-search results (ids), driven by [searchNotes]; empty when the query is blank. */
    val noteSearchIds = MutableStateFlow<List<String>>(emptyList())
    fun searchNotes(query: String) = scope.launch {
        noteSearchIds.value = if (query.isBlank()) emptyList() else repo.searchNoteIds(query)
    }
    /** L9 — "Ask your notes": on-device, extractive answers (no model, no network). A model-free MinHash
     *  semantic pass ([NoteSemantic]) broadens recall when the literal, term-coverage pass finds little. */
    val noteAnswers = MutableStateFlow<List<com.todocompanion.app.domain.NoteAsk.Answer>>(emptyList())
    fun askNotes(query: String) = scope.launch {
        if (query.isBlank()) { noteAnswers.value = emptyList(); return@launch }
        val now = System.currentTimeMillis()
        val ws = activeWorkspace()   // scope Ask-your-notes to the active workspace (no cross-workspace leak)
        val notes = repo.getNotesOnce()
            .filter { it.workspaceId == ws && !it.trashed && !it.noIndex && (it.sealedUntil == null || it.sealedUntil!! <= now) }
        val docs = notes.map { com.todocompanion.app.domain.NoteAsk.Doc(it.id, it.title, it.body) }
        val literal = com.todocompanion.app.domain.NoteAsk.answer(query, docs)
        val answers = if (literal.size >= 4) literal else {
            val qSig = com.todocompanion.app.domain.NoteSemantic.signature(query)
            val have = literal.map { it.id }.toSet()
            val semantic = notes.asSequence()
                .filter { it.id !in have }
                .map { n ->
                    val sim = com.todocompanion.app.domain.NoteSemantic.similarity(
                        qSig, com.todocompanion.app.domain.NoteSemantic.signature("${n.title}\n${n.body}"))
                    n to sim
                }
                .filter { it.second >= 0.12f }
                .sortedByDescending { it.second }
                .take(6 - literal.size)
                .map { (n, sim) ->
                    val body = n.body.split(Regex("\\n\\s*\\n")).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    com.todocompanion.app.domain.NoteAsk.Answer(
                        n.id, n.title.ifBlank { "Untitled" },
                        (if (body.length > 240) body.take(237).trimEnd() + "…" else body).ifBlank { n.title },
                        sim.toDouble())
                }
                .toList()
            literal + semantic
        }
        noteAnswers.value = answers
    }
    /** L10 — Right Note, Right Now: notes whose @context is scheduled and open at this moment (permission-free,
     *  via ContextAvailability open-hours; joins the app's existing context engine on `app.contexts`). */
    val notesNow = MutableStateFlow<List<com.todocompanion.app.data.entity.NoteEntity>>(emptyList())
    fun refreshNotesForNow() = scope.launch {
        val t = java.time.LocalDateTime.now()
        val dow = t.dayOfWeek.value            // 1..7, matching ContextAvailability
        val minute = t.hour * 60 + t.minute
        val ws = activeWorkspace()
        val openCtx = app.contexts.value.filter {
            it.workspaceId == ws &&
                com.todocompanion.app.domain.context.ContextAvailability.parse(it.openHoursJson) != null &&
                com.todocompanion.app.domain.context.ContextAvailability.isAvailable(it, dow, minute)
        }.map { it.id }.toSet()
        if (openCtx.isEmpty()) { notesNow.value = emptyList(); return@launch }
        val ids = repo.getNoteContextCrossRefs().filter { it.contextId in openCtx }.map { it.noteId }.toSet()
        val now = System.currentTimeMillis()
        notesNow.value = repo.getNotesOnce().filter {
            !it.trashed && !it.archived && it.id in ids && (it.sealedUntil == null || it.sealedUntil!! <= now)
        }.sortedByDescending { it.updatedAt }
    }

    // ── L11 Vault — passphrase-based encryption of a note's BODY at rest (Stage 4e) ──
    // The passphrase lives only in memory for the session; it is never persisted. A note stays vaulted
    // (ciphertext) until unlocked; the editor decrypts for editing and re-encrypts on save. The security-side
    // helpers (cache-wipe, panic-wipe, key readouts) stay on AppViewModel; lockVault forwards to them.
    @Volatile private var vaultPass: CharArray? = null
    val vaultUnlocked = MutableStateFlow(false)
    /** True once the user has chosen a vault passphrase (a verifier is stored). */
    fun vaultConfigured(): Boolean = app.settings.value.notesVaultCheck.isNotBlank()
    /** First-time setup: choose the vault passphrase and store only a verifier (never the passphrase). */
    fun setUpVault(pass: String) = scope.launch {
        if (pass.isBlank()) return@launch
        repo.saveSettings(app.settings.value.copy(notesVaultCheck = com.todocompanion.app.domain.NoteVault.makeCheck(pass.toCharArray())))
        vaultPass = pass.toCharArray(); vaultUnlocked.value = true
    }
    /** Unlock the vault for this session; true if the passphrase is correct. */
    fun unlockVault(pass: String): Boolean {
        val ok = com.todocompanion.app.domain.NoteVault.verify(app.settings.value.notesVaultCheck, pass.toCharArray())
        if (ok) { vaultPass = pass.toCharArray(); vaultUnlocked.value = true }
        return ok
    }
    /** Re-lock the vault (clears the in-memory passphrase) and wipe any decrypted image / share copies. */
    fun lockVault() {
        vaultPass?.fill(' ')   // SEC: zeroize the in-memory passphrase before dropping the reference
        vaultPass = null
        vaultUnlocked.value = false
        scope.launch(Dispatchers.IO) { runCatching { app.purgeRichImgCache() }; runCatching { app.purgeSharedCache() } }
    }
    /** Decrypt a vaulted note's body for display/editing (unchanged when not vaulted / locked; never throws). */
    fun decryptNoteBody(n: com.todocompanion.app.data.entity.NoteEntity): String {
        val p = vaultPass
        return if (n.vault && com.todocompanion.app.domain.NoteVault.isLocked(n.body) && p != null)
            com.todocompanion.app.domain.NoteVault.unlock(n.body, p) ?: n.body else n.body
    }
    /** Encrypt a note's plaintext body if it is vaulted and unlocked; leaves an already-encrypted body untouched. */
    private fun sealForVault(n: com.todocompanion.app.data.entity.NoteEntity): com.todocompanion.app.data.entity.NoteEntity {
        val p = vaultPass
        return if (n.vault && p != null && !com.todocompanion.app.domain.NoteVault.isLocked(n.body))
            n.copy(body = com.todocompanion.app.domain.NoteVault.lock(n.body, p)) else n
    }
    /** Persist an edited note; stamps the active workspace if the row arrives without one, sealing a vaulted body. */
    fun saveNote(n: com.todocompanion.app.data.entity.NoteEntity) = scope.launch {
        repo.upsertNote(sealForVault(n.copy(workspaceId = n.workspaceId.ifBlank { activeWorkspace() })))
    }
    /** Save the note + capture a version snapshot in one ordered coroutine (used on editor close). A note in
     *  an auto-vault notebook is encrypted as the editor closes (only when the vault is set up + unlocked). */
    fun closeNoteEditor(n: com.todocompanion.app.data.entity.NoteEntity) = scope.launch {
        val autoVault = n.notebookId != null && !n.vault && vaultConfigured() && vaultUnlocked.value &&
            notebooks.value.firstOrNull { it.id == n.notebookId }?.autoVault == true
        val toSave = if (autoVault) n.copy(vault = true) else n
        repo.upsertNote(sealForVault(toSave.copy(workspaceId = toSave.workspaceId.ifBlank { activeWorkspace() })))
        repo.saveNoteRevision(n.id, app.settings.value.notesMaxRevisions)
    }
}
