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
import kotlinx.coroutines.withContext

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

    // ── `.md` folder interop (Stage 4f) — export / import / two-way mirror of the active workspace's notes.
    // Fully offline (SAF tree grant only). The one cross-feature dependency is content EXPANSION —
    // `app.expandNoteForExport` materializes live transclusion tokens + periodic-recap markers before egress —
    // which stays on AppViewModel because it reaches the whole-app digest/transclusion engine. Everything else
    // (file writing, share intents, tag resolution, mirror reconciliation) is note-owned and lives here.

    // ── Wave I: single-note export (TXT/MD/HTML/JSON via the share sheet; PDF prints from the UI). ──
    fun exportNote(noteId: String, format: com.todocompanion.app.util.NoteExport.Format) = scope.launch {
        val note = repo.getNote(noteId) ?: return@launch
        if (note.sealedUntil != null && note.sealedUntil!! > System.currentTimeMillis()) { app.toast("This note is sealed — unseal it to export"); return@launch }
        val content = com.todocompanion.app.util.NoteExport.buildContent(note.copy(body = app.expandNoteForExport(note)), format)
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(app.appCtx.cacheDir, "shared").apply { mkdirs() }
                val base = note.title.ifBlank { "note" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "note" }
                val f = java.io.File(dir, "$base.${format.ext}").apply { writeText(content) }
                androidx.core.content.FileProvider.getUriForFile(app.appCtx, "${app.appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        } ?: return@launch
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TITLE, note.title.ifBlank { "Note" })
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            app.appCtx.startActivity(android.content.Intent.createChooser(send, "Export note").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { app.toast("No app to share to") }
    }
    // ── Wave K: `.md`-per-note folder interop (Obsidian/Bear-style). Fully offline — SAF tree grant only. ──
    /** Write one `.md` (YAML front-matter + body) per non-trashed note in the active workspace. */
    fun exportNotesToFolder(folderUri: String) = scope.launch {
        val ws = repo.activeWs()
        val now0 = System.currentTimeMillis()
        // Wave 2 · Privacy Governance Dial — a note flagged noExport is kept out of the .md folder export.
        val list = repo.getNotesOnce().filter { !it.trashed && !it.noExport && it.workspaceId == ws && (it.sealedUntil == null || it.sealedUntil!! <= now0) }
        if (list.isEmpty()) { app.toast("No notes to export"); return@launch }
        val tagName = repo.getTagsOnce().associate { it.id to it.name }
        val refs = repo.getNoteTagCrossRefs().groupBy { it.noteId }
        // SEC-corr — expand each note's live tokens + recap marker into real content for this one-way .md
        // export (the guards inside make it a no-op for a plain note).
        val payload = list.map { n -> n.copy(body = app.expandNoteForExport(n)) to refs[n.id].orEmpty().mapNotNull { tagName[it.tagId] } }
        val count = withContext(Dispatchers.IO) {
            com.todocompanion.app.util.NoteFolderSync.exportAll(app.appCtx, folderUri, payload)
        }
        app.toast(if (count > 0) "Exported $count ${if (count == 1) "note" else "notes"} as .md" else "Couldn't write to that folder")
    }

    /** Read every `.md` in the folder and merge into notes — by id where the front-matter carries one
     *  (updating in place, preserving fields the file doesn't hold), else as a fresh note. Tags named in
     *  the header are resolved (created if new) within the active workspace. */
    fun importNotesFromFolder(folderUri: String) = scope.launch {
        val parsed = withContext(Dispatchers.IO) {
            com.todocompanion.app.util.NoteFolderSync.importAll(app.appCtx, folderUri)
        }
        if (parsed.isEmpty()) { app.toast("No .md files found in that folder"); return@launch }
        val ws = repo.activeWs()
        val existingTags = repo.getTagsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }.toMutableMap()
        var imported = 0
        for (p in parsed) {
            val existing = p.id?.let { repo.getNote(it) }
            val base = existing ?: com.todocompanion.app.data.entity.NoteEntity(id = "", workspaceId = ws)
            val merged = base.copy(
                title = p.title, body = p.body, kind = p.kind, pinned = p.pinned, favorite = p.favorite,
                colorArgb = p.colorArgb, coverEmoji = p.coverEmoji, dayEpoch = p.dayEpoch,
                createdAt = p.createdAt ?: base.createdAt,
                workspaceId = base.workspaceId.ifBlank { ws },
            )
            val newId = repo.upsertNote(merged)
            if (p.tags.isNotEmpty()) {
                val tagIds = p.tags.map { name ->
                    val key = name.lowercase()
                    existingTags[key]?.id ?: java.util.UUID.randomUUID().toString().also { id ->
                        repo.upsertTag(com.todocompanion.app.data.entity.TagEntity(id, name, workspaceId = ws))
                        existingTags[key] = com.todocompanion.app.data.entity.TagEntity(id, name, workspaceId = ws)
                    }
                }
                repo.setNoteTags(newId, tagIds.distinct())
            }
            imported++
        }
        app.toast("Imported $imported ${if (imported == 1) "note" else "notes"}")
    }

    /** Wave L — resolve a note's image references (by fileName or attachment id) to local `file://` paths
     *  for the rich WebView renderer. Inline base64 attachments are materialized into cacheDir once. Fully
     *  local — no network, no new permission. */
    suspend fun noteImageMap(noteId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val out = HashMap<String, String>()
        val dir = java.io.File(app.appCtx.cacheDir, "richimg").apply { mkdirs() }
        repo.noteAttachments(noteId).filter { it.isImage }.forEach { a ->
            val path = when {
                !a.filePath.isNullOrBlank() && java.io.File(a.filePath!!).exists() -> runCatching {
                    // SEC-1: the stored file is encrypted at rest; the WebView can't read it, so materialize
                    // a decrypted copy into the app-private cache (same tradeoff as the inline branch). The
                    // copy is keyed by the immutable attachment id, so it's written once.
                    val safe = a.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "img" }
                    val f = java.io.File(dir, "${a.id}_$safe")
                    if (!f.exists()) f.writeBytes(com.todocompanion.app.data.security.FileVault.readDecrypted(java.io.File(a.filePath!!)))
                    f.absolutePath
                }.getOrNull()
                a.contentBase64.isNotBlank() -> runCatching {
                    val safe = a.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "img" }
                    val f = java.io.File(dir, "${a.id}_$safe")
                    if (!f.exists()) f.writeBytes(android.util.Base64.decode(a.contentBase64, android.util.Base64.DEFAULT))
                    f.absolutePath
                }.getOrNull()
                else -> null
            } ?: return@forEach
            val uri = "file://$path"
            out[a.fileName] = uri
            out["attachment:${a.id}"] = uri
            out[a.id] = uri
        }
        out
    }

    // ── Wave N: the living two-way `.md` mirror ─────────────────────────────────────────────────────
    /** A note that changed on BOTH sides since the last sync — surfaced for the user to resolve. */
    data class NoteSyncConflict(
        val noteId: String, val fileName: String,
        val appTitle: String, val appPreview: String, val fileTitle: String, val filePreview: String,
    )
    val noteSyncConflicts = MutableStateFlow<List<NoteSyncConflict>>(emptyList())
    private var syncFolderUri: String = ""
    private val pendingConflicts = mutableMapOf<String, Pair<com.todocompanion.app.data.entity.NoteEntity, com.todocompanion.app.util.NoteMarkdownFile.Parsed>>()

    /** Upsert a note from a parsed `.md` (merge by id, resolve/create tags), returning its id. */
    private suspend fun upsertFromParsed(p: com.todocompanion.app.util.NoteMarkdownFile.Parsed, ws: String, tagMap: MutableMap<String, com.todocompanion.app.data.entity.TagEntity>): String {
        val existing = p.id?.let { repo.getNote(it) }
        val base = existing ?: com.todocompanion.app.data.entity.NoteEntity(id = "", workspaceId = ws)
        val newId = repo.upsertNote(base.copy(
            title = p.title, body = p.body, kind = p.kind, pinned = p.pinned, favorite = p.favorite,
            colorArgb = p.colorArgb, coverEmoji = p.coverEmoji, dayEpoch = p.dayEpoch,
            createdAt = p.createdAt ?: base.createdAt, updatedAt = p.updatedAt ?: base.updatedAt,
            workspaceId = base.workspaceId.ifBlank { ws },
            // Lossless extras recovered from the .md header (fall back to whatever the row already had).
            readonly = p.readonly, archived = p.archived,
            sortOrder = p.sortOrder ?: base.sortOrder,
            notebookId = p.notebookId ?: base.notebookId, folderId = p.folderId ?: base.folderId,
            linkedTaskId = p.linkedTaskId ?: base.linkedTaskId, linkedEventId = p.linkedEventId ?: base.linkedEventId,
            reminderAt = p.reminderAt ?: base.reminderAt, reminderRrule = p.reminderRrule ?: base.reminderRrule,
            reminderExtra = p.reminderExtra.ifBlank { base.reminderExtra }, reminderKeep = p.reminderKeep,
            sealedUntil = p.sealedUntil ?: base.sealedUntil,
        ))
        if (p.tags.isNotEmpty()) {
            val tagIds = p.tags.map { name ->
                val key = name.lowercase()
                tagMap[key]?.id ?: java.util.UUID.randomUUID().toString().also { id ->
                    val t = com.todocompanion.app.data.entity.TagEntity(id, name, workspaceId = ws); repo.upsertTag(t); tagMap[key] = t
                }
            }
            repo.setNoteTags(newId, tagIds.distinct())
        }
        return newId
    }

    private fun canonHash(n: com.todocompanion.app.data.entity.NoteEntity, tags: List<String>) =
        com.todocompanion.app.util.NoteMirror.hash(com.todocompanion.app.util.NoteMirror.canonical(
            n.title, n.body, tags, n.kind, n.pinned, n.favorite, n.colorArgb, n.coverEmoji))
    private fun canonHash(p: com.todocompanion.app.util.NoteMarkdownFile.Parsed) =
        com.todocompanion.app.util.NoteMirror.hash(com.todocompanion.app.util.NoteMirror.canonical(
            p.title, p.body, p.tags, p.kind, p.pinned, p.favorite, p.colorArgb, p.coverEmoji))

    /** Two-way reconcile the active workspace's notes with the `.md` folder: push app-only changes out,
     *  pull file-only changes in, and surface both-changed notes as conflicts (never last-writer-wins).
     *  Baseline hashes live in the folder's own `.kairo/mirror.json`. */
    fun syncNotesFolder(folderUri: String) = scope.launch {
        val ws = repo.activeWs()
        val nowSync = System.currentTimeMillis()
        // Sealed-until-future notes are kept out of the mirror entirely (redaction at egress).
        val notes = repo.getNotesOnce().filter { !it.trashed && it.workspaceId == ws && (it.sealedUntil == null || it.sealedUntil!! <= nowSync) }
        val tagNameById = repo.getTagsOnce().associate { it.id to it.name }
        val refs = repo.getNoteTagCrossRefs().groupBy { it.noteId }
        val tagMap = repo.getTagsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }.toMutableMap()
        fun tagsOf(id: String) = refs[id].orEmpty().mapNotNull { tagNameById[it.tagId] }

        val baselineText = withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.readConfig(app.appCtx, folderUri, ".kairo", "mirror.json") }
        val disk = withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.importWithNames(app.appCtx, folderUri) }
        if (disk.isEmpty() && notes.isEmpty()) { app.toast("Nothing to sync"); return@launch }
        val baseline = com.todocompanion.app.util.NoteMirror.decodeBaseline(baselineText)

        val dbById = notes.associateBy { it.id }
        val diskById = HashMap<String, Pair<String, com.todocompanion.app.util.NoteMarkdownFile.Parsed>>()
        val diskNoId = ArrayList<Pair<String, com.todocompanion.app.util.NoteMarkdownFile.Parsed>>()
        for ((name, p) in disk) { val id = p.id; if (id != null) diskById[id] = name to p else diskNoId.add(name to p) }

        val out = com.todocompanion.app.util.NoteMirror.Baseline()
        val conflicts = LinkedHashMap<String, Pair<com.todocompanion.app.data.entity.NoteEntity, com.todocompanion.app.util.NoteMarkdownFile.Parsed>>()
        var pushed = 0; var pulled = 0
        val taken = mutableSetOf<String>()
        val ids = LinkedHashSet<String>().apply { addAll(dbById.keys); addAll(diskById.keys) }
        for (id in ids) {
            val n = dbById[id]; val fp = diskById[id]
            val dbHash = n?.let { canonHash(it, tagsOf(it.id)) }
            val diskHash = fp?.let { canonHash(it.second) }
            val base = baseline.notes[id]?.hash
            val fileName = fp?.first ?: n?.let { com.todocompanion.app.util.NoteMarkdownFile.fileName(it, taken) } ?: continue
            when (com.todocompanion.app.util.NoteMirror.reconcile(base, diskHash, dbHash)) {
                com.todocompanion.app.util.NoteMirror.Action.PUSH, com.todocompanion.app.util.NoteMirror.Action.NEW_DB ->
                    if (n != null && dbHash != null) {
                        withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.writeOne(app.appCtx, folderUri, fileName, com.todocompanion.app.util.NoteMarkdownFile.serialize(n, tagsOf(n.id))) }
                        out.notes[id] = com.todocompanion.app.util.NoteMirror.Base(fileName, dbHash); pushed++
                    }
                com.todocompanion.app.util.NoteMirror.Action.PULL, com.todocompanion.app.util.NoteMirror.Action.NEW_LOCAL ->
                    if (fp != null && diskHash != null) { upsertFromParsed(fp.second, ws, tagMap); out.notes[id] = com.todocompanion.app.util.NoteMirror.Base(fileName, diskHash); pulled++ }
                com.todocompanion.app.util.NoteMirror.Action.CONFLICT ->
                    if (n != null && fp != null) { conflicts[id] = n to fp.second; baseline.notes[id]?.let { out.notes[id] = it } }
                com.todocompanion.app.util.NoteMirror.Action.NONE -> {
                    val h = dbHash ?: diskHash ?: base
                    if (h != null) out.notes[id] = com.todocompanion.app.util.NoteMirror.Base(fileName, h)
                }
            }
        }
        for ((name, p) in diskNoId) {
            val newId = upsertFromParsed(p, ws, tagMap)
            out.notes[newId] = com.todocompanion.app.util.NoteMirror.Base(name, canonHash(p)); pulled++
        }
        withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.writeConfig(app.appCtx, folderUri, ".kairo", "mirror.json", com.todocompanion.app.util.NoteMirror.encodeBaseline(out)) }
        syncFolderUri = folderUri
        pendingConflicts.clear(); pendingConflicts.putAll(conflicts)
        noteSyncConflicts.value = conflicts.map { (id, pair) ->
            NoteSyncConflict(id, "", pair.first.title.ifBlank { "Untitled" }, pair.first.body.take(140),
                pair.second.title.ifBlank { "Untitled" }, pair.second.body.take(140))
        }
        app.toast("Synced · $pushed out · $pulled in" + if (conflicts.isNotEmpty()) " · ${conflicts.size} conflict${if (conflicts.size == 1) "" else "s"}" else "")
    }

    /** Resolve one mirror conflict: keep "app" (push to file), "file" (pull into app), or "both". */
    fun resolveNoteConflict(noteId: String, keep: String) = scope.launch {
        val pair = pendingConflicts[noteId] ?: return@launch
        val (appNote, fileParsed) = pair
        val ws = repo.activeWs()
        val tagMap = repo.getTagsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }.toMutableMap()
        val refs = repo.getNoteTagCrossRefs().groupBy { it.noteId }
        val tagNameById = repo.getTagsOnce().associate { it.id to it.name }
        val appTags = refs[noteId].orEmpty().mapNotNull { tagNameById[it.tagId] }
        val fileName = com.todocompanion.app.util.NoteMarkdownFile.fileName(appNote, mutableSetOf())
        when (keep) {
            "file" -> upsertFromParsed(fileParsed, ws, tagMap)
            "both" -> { withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.writeOne(app.appCtx, syncFolderUri, fileName, com.todocompanion.app.util.NoteMarkdownFile.serialize(appNote, appTags)) }
                upsertFromParsed(fileParsed.copy(id = null, title = fileParsed.title + " (from file)"), ws, tagMap) }
            else -> withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.writeOne(app.appCtx, syncFolderUri, fileName, com.todocompanion.app.util.NoteMarkdownFile.serialize(appNote, appTags)) }
        }
        pendingConflicts.remove(noteId)
        noteSyncConflicts.value = noteSyncConflicts.value.filterNot { it.noteId == noteId }
    }

    // ── Woven entry points, evergreen review, threads & writing sprints (Stage 4f-2) ──────────────────
    // These are note-local: they find/create a note (by linked task/event id, by title, or as a thread map)
    // or derive purely from the note read-model. The task/event/habit-COUPLED note actions that were
    // interleaved with these on AppViewModel — checkbox→task extraction, the Note-Garden report, meeting-
    // agenda markdown, the habit journal — intentionally stay on the coordinating parent.

    /** Open (creating if needed) the meeting note bound to a calendar event. */
    fun openEventNote(eventId: String, eventTitle: String, onOpen: (String) -> Unit) = scope.launch {
        val ws = activeWorkspace()
        val existing = repo.getNotesOnce().firstOrNull { !it.trashed && it.workspaceId == ws && it.linkedEventId == eventId }
        onOpen(existing?.id ?: repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
            id = "", kind = "meeting", linkedEventId = eventId, title = eventTitle.ifBlank { "Meeting note" }, workspaceId = ws,
        )))
    }

    /** Open (creating if needed) the note bound to a task. */
    fun openTaskNote(taskId: String, taskTitle: String, onOpen: (String) -> Unit) = scope.launch {
        val ws = activeWorkspace()
        val existing = repo.getNotesOnce().firstOrNull { !it.trashed && it.workspaceId == ws && it.linkedTaskId == taskId }
        onOpen(existing?.id ?: repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
            id = "", linkedTaskId = taskId, title = taskTitle.ifBlank { "Note" }, workspaceId = ws,
        )))
    }

    /** Open the note titled [title] (case-insensitive), creating it if none exists — a [[wiki-link]] jump. */
    fun openOrCreateNoteByTitle(title: String, onOpen: (String) -> Unit) = scope.launch {
        val ws = activeWorkspace()
        val t = title.trim()
        val existing = repo.getNotesOnce().firstOrNull { !it.trashed && it.workspaceId == ws && it.title.equals(t, ignoreCase = true) }
        onOpen(existing?.id ?: repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(id = "", title = t, workspaceId = ws)))
    }

    /** Evergreen Resurfacing — notes due for a spaced review right now, most-overdue first. */
    val notesDueForReview: StateFlow<List<com.todocompanion.app.data.entity.NoteEntity>> = notes.map { list ->
        val t = System.currentTimeMillis()
        list.filter {
            !it.trashed && !it.archived && !it.vault && it.reviewEvery > 0 &&
                com.todocompanion.app.domain.NoteReview.isDue(it.reviewEvery, it.lastReviewedAt, it.updatedAt, t)
        }.sortedBy { com.todocompanion.app.domain.NoteReview.nextDue(it.reviewEvery, it.lastReviewedAt, it.updatedAt) ?: Long.MAX_VALUE }
    }.state(emptyList())

    fun setNoteReview(noteId: String, days: Int) = scope.launch {
        repo.getNote(noteId)?.let {
            repo.upsertNote(it.copy(reviewEvery = days,
                lastReviewedAt = if (days > 0 && it.lastReviewedAt == 0L) System.currentTimeMillis() else it.lastReviewedAt))
        }
    }
    fun markNoteReviewed(noteId: String) = scope.launch {
        repo.getNote(noteId)?.let { repo.upsertNote(it.copy(lastReviewedAt = System.currentTimeMillis())) }
    }

    /** Writing Sprints — log a finished sprint as tracked time on the note. */
    fun logNoteSprint(noteId: String, startMillis: Long, endMillis: Long) = scope.launch {
        repo.logNoteTime(noteId, startMillis, endMillis, "Writing sprint")
    }

    // ── Threads (Maps of Content) — a thread is a note (kind="thread") whose body lists ordered [[links]] ──
    private fun threadTriples(): List<Triple<String, String, String>> {
        val ws = activeWorkspace()
        return notes.value.filter {
            it.kind == com.todocompanion.app.domain.NoteThreads.KIND && !it.trashed && it.workspaceId == ws
        }.map { Triple(it.id, it.title.ifBlank { "Untitled thread" }, it.body) }
    }

    /** The threads a note belongs to, with its prev/next neighbours in each (drives the editor's prev/next bar). */
    fun noteThreadPositions(noteTitle: String): List<com.todocompanion.app.domain.NoteThreads.Position> =
        com.todocompanion.app.domain.NoteThreads.positionsFor(noteTitle, threadTriples())

    /** All thread notes in the active workspace as (id, title), most-recent first — for the "add to thread" picker. */
    fun threadList(): List<Pair<String, String>> = threadTriples()
        .map { it.first to it.second }
        .sortedByDescending { p -> notes.value.firstOrNull { it.id == p.first }?.updatedAt ?: 0L }

    /** Add [memberTitle] to an existing thread note. */
    fun addNoteToThread(threadId: String, memberTitle: String) = scope.launch {
        val th = repo.getNote(threadId) ?: return@launch
        repo.upsertNote(th.copy(body = com.todocompanion.app.domain.NoteThreads.addItem(th.body, memberTitle)))
    }

    /** Create a new thread note listing [memberTitle], then open it. */
    fun createThread(threadTitle: String, memberTitle: String, onOpen: (String) -> Unit) = scope.launch {
        val id = repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
            id = "", kind = com.todocompanion.app.domain.NoteThreads.KIND, workspaceId = activeWorkspace(),
            title = threadTitle.ifBlank { "New thread" },
            body = com.todocompanion.app.domain.NoteThreads.addItem(
                "_A thread — an ordered map of content. Reorder the links below to reorder it._\n", memberTitle),
        ))
        onOpen(id)
    }

    /** Open the note whose title matches [title] (prev/next navigation within a thread); no-op if none. */
    fun openNoteByTitle(title: String, onOpen: (String) -> Unit) {
        val key = title.trim().lowercase()
        notes.value.firstOrNull { it.title.trim().lowercase() == key && !it.trashed }?.let { onOpen(it.id) }
    }
}
