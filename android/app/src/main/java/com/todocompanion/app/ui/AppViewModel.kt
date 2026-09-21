package com.todocompanion.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.todocompanion.app.App
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ChecklistItemEntity
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.FlagEntity
import com.todocompanion.app.data.entity.TemplateEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.DailyQuestion
import com.todocompanion.app.domain.DailyQuestions
import com.todocompanion.app.domain.nlp.QuickAddParser
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.domain.view.GroupMode
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.SortMode
import com.todocompanion.app.domain.view.TaskGroup
import com.todocompanion.app.domain.view.TaskViews
import com.todocompanion.app.domain.view.ListPipeline
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.reminders.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

/** A flattened, indented outline row. [matched] is false for a structural ancestor shown only
 *  to keep a filtered match in its tree position (rendered dimmed). */
data class OutlineRow(val task: TaskEntity, val depth: Int, val hasChildren: Boolean, val collapsed: Boolean, val matched: Boolean = true)

/** Options captured by the quick-add option toolbar; override anything parsed from text. */
data class QuickAddOptions(
    val dueMillis: Long? = null,
    val priority: PriorityLevel? = null,
    val listId: String? = null,
    val tagIds: List<String> = emptyList(),
    val reminderMillis: Long? = null,
    val note: String = "",
    // V9: applied from inline capture tokens (#t25, *).
    val estimateMin: Int? = null,
    val star: Boolean = false,
    // R21: the quick-add sheet now offers the same first-class options as the editor.
    val contextIds: List<String> = emptyList(),
    val folderId: String? = null,       // folder-direct capture (no list)
    val rrule: String? = null,          // recurrence chosen in the date sheet
    val durationMin: Int? = null,
    val attachmentUris: List<android.net.Uri> = emptyList(),
    // P3: capture now surfaces the same start/deadline the editor's schedule sheet does.
    val startMillis: Long? = null,
    val deadlineMillis: Long? = null,
    // Wave B — reversible parse: when true the title is taken verbatim and the NL parser is NOT applied
    // (no date/priority/tag/context/list/recurrence/reminder extracted from the words). The explicit sheet
    // options below still apply. This is the one-tap "use what I typed as plain text" escape hatch.
    val plainText: Boolean = false,
    // Wave B (F3) — relative/place reminders at capture, matching the editor. When [reminderPlace] is set a
    // permission-free place reminder is armed; else [reminderOffsetMin] (minutes before the anchor date)
    // creates a relative reminder against [reminderAnchor] ("due"/"start"/"deadline"). These take precedence
    // over the legacy absolute [reminderMillis] when present.
    val reminderOffsetMin: Int? = null,
    val reminderAnchor: String = "due",
    val reminderPlace: String? = null,
    // Wave D (N3) — inline "a > b > c" subtree creation is now opt-in (a one-tap capture suggestion), not
    // automatic, so a literal " > " in a title isn't turned into an accidental parent+child.
    val splitSubtasks: Boolean = false,
)

enum class UndoKind { COMPLETED, ABANDONED, TRASHED, CREATED_MANY, NOTE_TRASHED, NOTE_ARCHIVED, HABIT_TRASHED, HABIT_ARCHIVED, EVENTS_CREATED, CONTAINER_TRASHED }

/** What the full-screen habit editor is editing. A null [habit] means "create a new habit". */
data class HabitEditRequest(val habit: com.todocompanion.app.data.entity.HabitEntity? = null)
/** [restore], when set, is the exact pre-action task snapshot to write back on Undo — used for a
 *  recurring task's roll-forward, where "uncomplete" isn't enough (the due date & rule advanced). */
data class UndoEvent(
    val kind: UndoKind,
    val taskId: String,
    val message: String,
    val restore: TaskEntity? = null,
    val taskIds: List<String> = emptyList(),
    /** The exact pre-action note snapshot to write back on Undo (note trash / archive). */
    val noteRestore: com.todocompanion.app.data.entity.NoteEntity? = null,
    /** The exact pre-action habit snapshot to write back on Undo (habit trash / archive). */
    val habitRestore: com.todocompanion.app.data.entity.HabitEntity? = null,
    /** Calendar event ids created by this action, deleted on Undo (auto-fill / auto-schedule). */
    val eventIds: List<String> = emptyList(),
    /** Pre-action snapshots to write back wholesale on Undo when a list/folder is deleted to Trash —
     *  restores the container(s) AND every task the delete moved or trashed, to exactly how they were. */
    val listSnapshots: List<com.todocompanion.app.data.entity.ListEntity> = emptyList(),
    val folderSnapshots: List<com.todocompanion.app.data.entity.FolderEntity> = emptyList(),
    val taskSnapshots: List<TaskEntity> = emptyList(),
)

class AppViewModel internal constructor(
    app: Application,
    private val repo: AppRepository,
    // Stage 2 (Phase 3) — the time controller is now SELF-CONTAINED (reads workspace-scoped data from the
    // repo, no VM closures), so it's constructed once here at the composition root instead of lazily
    // reaching back into VM state. Defaulted so the sole (app, repo) constructor the factory and tests use
    // keeps building exactly one instance; a test may inject one over the same in-memory repo.
    private val timeCtl: com.todocompanion.app.time.TimeTrackingController =
        com.todocompanion.app.time.TimeTrackingController(app, repo),
) : AndroidViewModel(app) {

    // A2 (Phase 3, decomposition) — the repository is *received*, never looked up. The VM has exactly one
    // constructor: (app, repo). Production wires it at the composition root through [AppViewModelFactory]
    // (which reads the repo from the App service-locator once, outside the VM), and both `viewModel()` call
    // sites pass that factory. Tests construct the VM directly with an isolated in-memory-backed repository.
    // Previously the VM carried a secondary `(app)` ctor that reached into `(app as App).repository` itself;
    // that self-lookup is exactly the coupling that kept the largest, most logic-dense class at 0% coverage
    // and blocks carving per-feature ViewModels out of it. The Android-framework couplings (appCtx below)
    // remain, so VM tests run under Robolectric.

    private val appCtx get() = getApplication<App>()

    /** One-shot events for the "Undo" snackbar after a completion / won't-do / trash. */
    val undoEvents = kotlinx.coroutines.flow.MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)

    // P1 — every derived StateFlow flows through here, so one flowOn(Default) moves ALL of the reactive
    // pipeline (workspace scoping, task grouping over 500+ rows, smart counts, reliability scoring, period
    // recaps) off the main thread. Previously each combine{}.filter/groupBy/sort ran on Dispatchers.Main
    // (viewModelScope), janking the UI on every task edit/complete/reorder. The produced StateFlow still
    // publishes on the main thread for Compose collectors; only the upstream computation is offloaded.
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val settings: StateFlow<AppSettings> =
        repo.allSettings.map { rows ->
            val base = AppSettings.fromMap(rows.associate { s -> s.key to s.value })
            // SEC (R2-A/H2) — the sync passphrase is KeyStore-wrapped in SecurePrefs, not the DB table; the
            // sync_pass_rev row (bumped on every save) makes this flow re-emit so the value stays fresh.
            val sp = runCatching { com.todocompanion.app.data.security.SecurePrefs.getSecret(appCtx, AppSettings.Keys.SYNC_PASS) }.getOrNull()
            if (sp != null) base.copy(syncPassphrase = sp) else base
        }
            // Mirror theme fields to a synchronous cache so the next cold start's first frame is correct.
            .onEach { com.todocompanion.app.domain.ThemePrefs.save(appCtx, it) }
            // Seed the initial value from that cache — no dark→light flash on launch.
            .state(com.todocompanion.app.domain.ThemePrefs.read(appCtx).let { (mode, dyn, accent) ->
                AppSettings(themeMode = mode, dynamicColor = dyn, accentArgb = accent)
            })

    // R28 #4 — the seeded [settings] above only carries theme fields, so every OTHER field reads its
    // default on the first frame (e.g. onboardedModules=false), which briefly flashed the module picker on
    // every launch. This flips true once the real settings load from the DB, so launch-time dialogs gated on
    // a settings value wait for the real value instead of the default.
    val settingsLoaded: StateFlow<Boolean> = repo.allSettings.map { true }.state(false)

    // R28 #5/#7 — a query the command palette can push into the Settings search box ("setting dark mode").
    val settingsSearchQuery = MutableStateFlow("")

    // ---------- workspaces ----------
    val workspaces = repo.allWorkspaces.state(emptyList())
    private val activeWs: Flow<String> = settings.map { it.activeWorkspaceId }
    /** The current workspace id, read synchronously — used to STAMP new rows so every feature is isolated. */
    private fun activeWorkspace(): String = settings.value.activeWorkspaceId
    /** R62 — the one scoping helper: keep only rows whose [wsOf] equals the active workspace. Every
     *  per-workspace feature flow funnels through this, so isolation is uniform and auditable. */
    private fun <T> Flow<List<T>>.scopedBy(wsOf: (T) -> String): StateFlow<List<T>> =
        combine(this, activeWs) { list, w -> list.filter { wsOf(it) == w } }.state(emptyList())

    // Phase 3, Stage 3 — the time-tracking surface lives in TimeTrackingViewModel now: it owns the
    // workspace-scoped time flows + the single controller's actions. Declared here (early, non-lazy) so this
    // VM's own capacity/recap/coach logic reads the flows through `timeVm` without a forward reference, and
    // every screen reaches the whole time surface through this one object.
    val timeVm = TimeTrackingViewModel(this, viewModelScope, repo, timeCtl)

    // W2 (scale) — the active-workspace task set now comes from SQL (TaskDao.observeWorkspaceScoped, backed
    // by the restored workspaceId/folderId indices), re-subscribing when the workspace changes, instead of
    // loading the whole tasks table and filtering here. The SQL mirrors the old rule exactly — proven by
    // TaskScopeQueryTest, guarded by AppViewModelCharacterizationTest — so what the user sees is unchanged:
    // R64 keeps a shared-Inbox task in the workspace it was captured in; non-Inbox tasks belong via their
    // list's or folder's workspace; the Inbox LIST view stays the one shared surface (it reads inboxTasksAll).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val wsTasks: Flow<List<TaskEntity>> =
        activeWs.flatMapLatest { ws -> repo.observeTasksByWorkspace(ws) }

    /** The one shared surface: every Inbox task across all workspaces. The Inbox list view (and its count)
     *  read this so the Inbox stays the single cross-workspace zone; nothing else does. */
    private val inboxTasksAll: Flow<List<TaskEntity>> =
        repo.allTasks.map { all -> all.filter { it.listId == ListEntity.INBOX_ID } }

    val tasks: StateFlow<List<TaskEntity>> = wsTasks.state(emptyList())
    // R29 #4 — the recap counts what you actually finished, across every workspace (an accomplishment
    // recap shouldn't vanish when you switch spaces). Exposed as its own flow so the Recap screen can
    // collect it: that both warms it (WhileSubscribed) and re-runs the recap when tasks load/change, so
    // "Tasks done" can no longer read a stale/empty snapshot and show zero.
    val allTasksLive: StateFlow<List<TaskEntity>> = repo.allTasks.state(emptyList())
    val folders = combine(repo.allFolders, activeWs) { f, ws -> f.filter { it.workspaceId == ws } }.state(emptyList())
    val lists = combine(repo.allLists, activeWs) { l, ws -> l.filter { it.workspaceId == ws || it.id == ListEntity.INBOX_ID } }.state(emptyList())
    val tags: StateFlow<List<TagEntity>> = combine(repo.allTags, activeWs) { t, ws -> t.filter { it.workspaceId == ws } }.state(emptyList())
    val contexts: StateFlow<List<ContextEntity>> = combine(repo.allContexts, activeWs) { c, ws -> c.filter { it.workspaceId == ws } }.state(emptyList())
    // R62 — every one of these is now workspace-isolated (scopedBy filters to the active workspace).
    val flags: StateFlow<List<FlagEntity>> = repo.allFlags.scopedBy { it.workspaceId }
    val templates: StateFlow<List<TemplateEntity>> = repo.allTemplates.scopedBy { it.workspaceId }
    val countdowns = repo.allCountdowns.scopedBy { it.workspaceId }
    val sealedNotes = repo.allSealedNotes.scopedBy { it.workspaceId }
    val cravings = repo.allCravings.scopedBy { it.workspaceId }
    // Notes module (v66) — workspace-scoped, non-trashed notes + the optional dedicated notebook tree.
    // W2 (scale) — the live-notes and Trash lists now filter workspace+trashed IN SQL (index-backed via
    // NoteDao.observeByWorkspace), re-subscribing when the active workspace changes, instead of loading the
    // whole notes table and filtering in memory here. Behaviour is identical (proven by NoteScopeQueryTest).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val notes = activeWs.flatMapLatest { ws -> repo.observeNotesByWorkspace(ws, trashed = false) }.state(emptyList())
    /** The Trash — workspace-scoped notes the user has trashed but not yet permanently deleted. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val trashedNotes = activeWs.flatMapLatest { ws -> repo.observeNotesByWorkspace(ws, trashed = true) }.state(emptyList())
    val notebooks = repo.observeNotebooks().scopedBy { it.workspaceId }
    // Note ↔ tag cross-refs (for chips on cards / editor). Observe the note_tags table directly so a tag
    // toggle reflects immediately — writing note_tags doesn't touch the notes table, so deriving this from
    // the notes flow left the editor's chips stale (tags looked un-addable/un-selectable).
    val noteTagRefs: StateFlow<List<com.todocompanion.app.data.entity.NoteTagCrossRef>> =
        repo.observeNoteTagCrossRefs().state(emptyList())
    val noteContextRefs: StateFlow<List<com.todocompanion.app.data.entity.NoteContextCrossRef>> =
        repo.observeNoteContextCrossRefs().state(emptyList())
    val smartViews = repo.observeSmartViews().scopedBy { it.workspaceId }
    // R34 — life-systems layer flows.
    val coreValues = repo.allCoreValues.scopedBy { it.workspaceId }
    // R67 — temptation-bundling + implementation-intention micro-plans (settings-JSON, no schema).
    val microPlans: StateFlow<List<com.todocompanion.app.domain.MicroPlan>> =
        settings.map { com.todocompanion.app.domain.MicroPlans.parse(it.microPlansJson) }.state(emptyList())
    fun addMicroPlan(kind: String, a: String, b: String, habitId: String = "") = viewModelScope.launch {
        if (a.isBlank() || b.isBlank()) return@launch
        val list = com.todocompanion.app.domain.MicroPlans.parse(settings.value.microPlansJson) +
            com.todocompanion.app.domain.MicroPlan(UUID.randomUUID().toString(), kind, a.trim(), b.trim(), System.currentTimeMillis(), habitId.trim())
        repo.saveSettings(settings.value.copy(microPlansJson = com.todocompanion.app.domain.MicroPlans.encode(list)))
    }
    fun deleteMicroPlan(id: String) = viewModelScope.launch {
        val list = com.todocompanion.app.domain.MicroPlans.parse(settings.value.microPlansJson).filterNot { it.id == id }
        repo.saveSettings(settings.value.copy(microPlansJson = com.todocompanion.app.domain.MicroPlans.encode(list)))
    }
    // R67 — values card-sort: move a value up/down, normalising orderIndex to positions.
    fun moveCoreValue(id: String, up: Boolean) = viewModelScope.launch {
        val list = coreValues.value.sortedBy { it.orderIndex }.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val swap = if (up) idx - 1 else idx + 1
        if (idx < 0 || swap !in list.indices) return@launch
        val tmp = list[idx]; list[idx] = list[swap]; list[swap] = tmp
        list.forEachIndexed { i, v -> if (v.orderIndex != i) repo.upsertCoreValue(v.copy(orderIndex = i)) }
    }
    val witnessEvents = repo.allWitnessEvents.scopedBy { it.workspaceId }
    val scorecardItems = repo.allScorecardItems.scopedBy { it.workspaceId }
    val buddies = repo.allBuddies.scopedBy { it.workspaceId }
    val integrityReviews = repo.allIntegrityReviews.scopedBy { it.workspaceId }
    // R35 — third-wave flows.
    val experiments = repo.allExperiments.scopedBy { it.workspaceId }
    val activationItems = repo.allActivationItems.scopedBy { it.workspaceId }
    // R62 — day-log bookends are now per-workspace too (composite key epochDay + workspaceId).
    val dayLogs = repo.allDayLogs.scopedBy { it.workspaceId }
    // R36 — fourth-wave flows.
    val escrows = repo.allEscrows.scopedBy { it.workspaceId }
    val nudgeEvents = repo.allNudgeEvents.scopedBy { it.workspaceId }
    val eventCalendars = repo.allEventCalendars.scopedBy { it.workspaceId }
    // Events are scoped transitively through their calendar (an event's workspace IS its calendar's), so
    // moving/removing a calendar can never leave an event stranded in the wrong space.
    private val activeCalendarIds: Flow<Set<String>> =
        combine(repo.allEventCalendars, activeWs) { all, w -> all.filter { it.workspaceId == w }.map { it.id }.toSet() }
    val events = combine(repo.allEvents, activeCalendarIds) { evs, ids -> evs.filter { it.calendarId in ids } }.state(emptyList())
    // Non-null → the Life-Systems hub/screen overlays the tab (route key: hub|values|scorecard|correlations|reviews|ledger|buddies|friction|experiments|activation|forecast|heatmap|valuestime|runner|companion).
    val lifeSystemsRoute = MutableStateFlow<String?>(null)
    fun saveCountdown(id: String?, title: String, targetMillis: Long, emoji: String?, colorArgb: Long?) = viewModelScope.launch {
        val existing = id?.let { cid -> countdowns.value.firstOrNull { it.id == cid } }
        repo.upsertCountdown(
            (existing ?: com.todocompanion.app.data.entity.CountdownEntity(id = UUID.randomUUID().toString(), title = title, targetMillis = targetMillis, createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
                .copy(title = title.trim().ifBlank { "Countdown" }, targetMillis = targetMillis, emoji = emoji, colorArgb = colorArgb)
        )
        com.todocompanion.app.widget.CountdownWidget.refresh(appCtx)
    }

    // ── Notes module (v66) ───────────────────────────────────────────────────────────────────────
    /** Create a brand-new note (stamped with the active workspace) and return its id via [onCreated]. */
    fun createNote(
        title: String = "",
        body: String = "",
        notebookId: String? = null,
        folderId: String? = null,
        kind: String = "note",
        onCreated: (String) -> Unit = {},
    ) = viewModelScope.launch {
        val id = repo.upsertNote(
            com.todocompanion.app.data.entity.NoteEntity(
                id = "", title = title, body = body, notebookId = notebookId, folderId = folderId,
                kind = kind, workspaceId = activeWorkspace(),
            )
        )
        onCreated(id)
    }

    /** Persist an edited note; stamps the active workspace if the row arrives without one. */
    fun saveNote(n: com.todocompanion.app.data.entity.NoteEntity) = viewModelScope.launch {
        // L11 — encrypt a vaulted note's body before it reaches the DB (no-op if already ciphertext / not vaulted).
        repo.upsertNote(sealForVault(n.copy(workspaceId = n.workspaceId.ifBlank { activeWorkspace() })))
    }
    fun trashNote(id: String) = viewModelScope.launch {
        val prev = repo.getNote(id)   // exact pre-trash snapshot for a full undo
        repo.trashNote(id, true)
        if (prev != null) undoEvents.tryEmit(UndoEvent(UndoKind.NOTE_TRASHED, id, "Moved to Trash", noteRestore = prev))
    }
    fun deleteNote(id: String) = viewModelScope.launch { repo.deleteNote(id) }
    /** Bring a note back out of the Trash. */
    fun restoreNoteFromTrash(id: String) = viewModelScope.launch { repo.trashNote(id, false) }
    /** Permanently delete every trashed note in the active workspace ("Empty Trash"). */
    fun emptyNoteTrash() = viewModelScope.launch {
        val ws = activeWorkspace()
        repo.getNotesOnce().filter { it.trashed && it.workspaceId == ws }.forEach { repo.deleteNote(it.id) }
    }
    fun setNoteTags(noteId: String, tagIds: List<String>) = viewModelScope.launch { repo.setNoteTags(noteId, tagIds) }
    fun setNoteContexts(noteId: String, contextIds: List<String>) = viewModelScope.launch { repo.setNoteContexts(noteId, contextIds) }

    // ── Wave B: archive · duplicate · version history · auto-empty-trash ──
    fun archiveNote(id: String, archived: Boolean = true) = viewModelScope.launch {
        val prev = repo.getNote(id)
        repo.archiveNote(id, archived)
        if (archived && prev != null) undoEvents.tryEmit(UndoEvent(UndoKind.NOTE_ARCHIVED, id, "Archived", noteRestore = prev))
    }
    fun duplicateNote(id: String, onDone: (String) -> Unit = {}) = viewModelScope.launch { repo.duplicateNote(id)?.let { onDone(it) } }
    /** Capture a version snapshot (bounded to the user's "keep versions" setting). Call on editor close. */
    fun saveNoteRevision(id: String) = viewModelScope.launch { repo.saveNoteRevision(id, settings.value.notesMaxRevisions) }
    fun observeNoteRevisions(id: String) = repo.observeNoteRevisions(id)
    fun observeNoteLinks(id: String) = repo.observeNoteLinks(id)
    suspend fun unlinkedMentions(body: String, excludeNoteId: String) = repo.unlinkedMentions(body, excludeNoteId)
    suspend fun noteLinksSnapshot() = repo.getNoteLinksOnce()
    suspend fun dayDigestMarkdown(epochDay: Long) = repo.dayDigestMarkdown(epochDay)
    fun saveSmartView(id: String?, title: String, icon: String?, predicate: com.todocompanion.app.domain.NotePredicate) = viewModelScope.launch {
        val existing = id?.let { vid -> smartViews.value.firstOrNull { it.id == vid } }
        repo.upsertSmartView(
            (existing ?: com.todocompanion.app.data.entity.SmartViewEntity(id = "", title = title, predicateJson = "", workspaceId = activeWorkspace()))
                .copy(title = title.trim().ifBlank { "View" }, icon = icon, predicateJson = com.todocompanion.app.domain.NoteSmartViews.encode(predicate)),
        )
    }
    fun deleteSmartView(id: String) = viewModelScope.launch { repo.deleteSmartView(id) }
    fun restoreNoteRevision(noteId: String, title: String, body: String) = viewModelScope.launch {
        repo.getNote(noteId)?.let { repo.upsertNote(it.copy(title = title, body = body)) }
    }
    // ── Wave F/H: a note's own local reminder(s) (reuses the existing AlarmScheduler — no new permission).
    /** Set a note's whole reminder set: a primary [atMillis] with an optional recurrence [rrule], any
     *  [extra] one-shot times, and "keep reminding until opened" ([keep]). Passing a null primary and no
     *  extras clears everything. Persists (metadata-only) then reconciles the alarms. */
    fun setNoteReminder(noteId: String, atMillis: Long?, rrule: String? = null, extra: List<Long> = emptyList(), keep: Boolean = false) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val extraCsv = extra.filter { it > now }.sorted().joinToString(",")
        if (atMillis == null && extraCsv.isEmpty()) {
            repo.clearNoteReminder(noteId)
            com.todocompanion.app.reminders.AlarmScheduler.cancelNoteReminder(appCtx, noteId)
            return@launch
        }
        repo.setNoteReminderAll(noteId, atMillis, rrule?.ifBlank { null }, extraCsv, keep)
        repo.getNote(noteId)?.let { com.todocompanion.app.reminders.AlarmScheduler.armNoteReminders(appCtx, it) }
    }
    // ── Wave J (M8): seal a note to your future self. Hidden from the list until [untilMillis], when a
    // reveal reminder (the existing engine — no new permission) resurfaces it.
    fun sealNote(noteId: String, untilMillis: Long) = viewModelScope.launch {
        if (untilMillis <= System.currentTimeMillis()) return@launch
        repo.setNoteSealedUntil(noteId, untilMillis)
        repo.setNoteReminderAll(noteId, untilMillis, null, "", false)
        repo.getNote(noteId)?.let { com.todocompanion.app.reminders.AlarmScheduler.armNoteReminders(appCtx, it) }
    }
    fun unsealNote(noteId: String) = viewModelScope.launch {
        repo.setNoteSealedUntil(noteId, null)
        repo.clearNoteReminder(noteId)
        com.todocompanion.app.reminders.AlarmScheduler.cancelNoteReminder(appCtx, noteId)
    }
    fun setNotesTrashRetention(days: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesTrashRetentionDays = days)) }
    fun setNotesMaxRevisions(n: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesMaxRevisions = n)) }
    /** Lazy on-open sweep — hard-delete trashed notes older than the retention setting (0 = never). */
    fun purgeExpiredNoteTrash() = viewModelScope.launch { repo.purgeExpiredTrashedNotes(settings.value.notesTrashRetentionDays) }

    /** SEC-corr — produce the EXPORT-ready body for a note, matching what the reading / split view shows:
     *  (1) expand live `{{today:agenda}}` / `{{events:today}}`-style transclusion tokens, and (2) materialize
     *  a periodic note's `<!-- kairo:recap -->` marker into its real recap. Both are otherwise stored as raw
     *  placeholders, so exports leaked them. Export-only — the result is never written back, so the two-way
     *  `.md` mirror stays clean (no round-trip pollution). A note with neither returns unchanged. */
    suspend fun expandNoteForExport(note: com.todocompanion.app.data.entity.NoteEntity): String {
        var body = note.body
        // 1) Live transclusion tokens ({{today:agenda}}, {{events:today}}, …). Unknown ones stay verbatim.
        if (com.todocompanion.app.util.NoteTransclusion.hasTokens(body))
            body = expandNoteTransclusion(body, note.id)
        // 2) Periodic-note recap marker → real recap (drop the comment fences; strip the block if no data).
        val re = Regex("(?s)" + Regex.escape(com.todocompanion.app.domain.PeriodicNotes.RECAP_OPEN) + ".*?" +
            Regex.escape(com.todocompanion.app.domain.PeriodicNotes.RECAP_CLOSE))
        if (re.containsMatchIn(body)) {
            val period = com.todocompanion.app.domain.PeriodicNotes.periodOf(note.kind)
            val anchor = note.dayEpoch
            val digest = if (period != null && anchor != null) {
                val win = periodWindow(period, anchor)
                if (period == com.todocompanion.app.domain.PeriodRange.DAY)
                    repo.dayDigestMarkdown(win.startDay).trim()
                else
                    periodDigestMarkdown(period, win.startDay, win.endDay,
                        com.todocompanion.app.domain.PeriodicNotes.titleFor(period, win.startDay)).trim()
            } else ""
            body = re.replace(body) { if (digest.isBlank()) "" else digest }
        }
        return body.trim()
    }

    // ── Wave I: single-note export (TXT/MD/HTML/JSON via the share sheet; PDF prints from the UI). ──
    fun exportNote(noteId: String, format: com.todocompanion.app.util.NoteExport.Format) = viewModelScope.launch {
        val note = repo.getNote(noteId) ?: return@launch
        if (note.sealedUntil != null && note.sealedUntil!! > System.currentTimeMillis()) { toast("This note is sealed — unseal it to export"); return@launch }
        val content = com.todocompanion.app.util.NoteExport.buildContent(note.copy(body = expandNoteForExport(note)), format)
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val base = note.title.ifBlank { "note" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "note" }
                val f = java.io.File(dir, "$base.${format.ext}").apply { writeText(content) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        } ?: return@launch
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TITLE, note.title.ifBlank { "Note" })
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            appCtx.startActivity(android.content.Intent.createChooser(send, "Export note").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("No app to share to") }
    }
    // ── Wave K: `.md`-per-note folder interop (Obsidian/Bear-style). Fully offline — SAF tree grant only. ──
    /** Write one `.md` (YAML front-matter + body) per non-trashed note in the active workspace. */
    fun exportNotesToFolder(folderUri: String) = viewModelScope.launch {
        val ws = repo.activeWs()
        val now0 = System.currentTimeMillis()
        // Wave 2 · Privacy Governance Dial — a note flagged noExport is kept out of the .md folder export.
        val list = repo.getNotesOnce().filter { !it.trashed && !it.noExport && it.workspaceId == ws && (it.sealedUntil == null || it.sealedUntil!! <= now0) }
        if (list.isEmpty()) { toast("No notes to export"); return@launch }
        val tagName = repo.getTagsOnce().associate { it.id to it.name }
        val refs = repo.getNoteTagCrossRefs().groupBy { it.noteId }
        // SEC-corr — expand each note's live tokens + recap marker into real content for this one-way .md
        // export (the guards inside make it a no-op for a plain note).
        val payload = list.map { n -> n.copy(body = expandNoteForExport(n)) to refs[n.id].orEmpty().mapNotNull { tagName[it.tagId] } }
        val count = withContext(Dispatchers.IO) {
            com.todocompanion.app.util.NoteFolderSync.exportAll(appCtx, folderUri, payload)
        }
        toast(if (count > 0) "Exported $count ${if (count == 1) "note" else "notes"} as .md" else "Couldn't write to that folder")
    }

    /** Read every `.md` in the folder and merge into notes — by id where the front-matter carries one
     *  (updating in place, preserving fields the file doesn't hold), else as a fresh note. Tags named in
     *  the header are resolved (created if new) within the active workspace. */
    fun importNotesFromFolder(folderUri: String) = viewModelScope.launch {
        val parsed = withContext(Dispatchers.IO) {
            com.todocompanion.app.util.NoteFolderSync.importAll(appCtx, folderUri)
        }
        if (parsed.isEmpty()) { toast("No .md files found in that folder"); return@launch }
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
                    existingTags[key]?.id ?: UUID.randomUUID().toString().also { id ->
                        repo.upsertTag(TagEntity(id, name, workspaceId = ws))
                        existingTags[key] = TagEntity(id, name, workspaceId = ws)
                    }
                }
                repo.setNoteTags(newId, tagIds.distinct())
            }
            imported++
        }
        toast("Imported $imported ${if (imported == 1) "note" else "notes"}")
    }

    /** Wave L — resolve a note's image references (by fileName or attachment id) to local `file://` paths
     *  for the rich WebView renderer. Inline base64 attachments are materialized into cacheDir once. Fully
     *  local — no network, no new permission. */
    suspend fun noteImageMap(noteId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val out = HashMap<String, String>()
        val dir = java.io.File(appCtx.cacheDir, "richimg").apply { mkdirs() }
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
    private suspend fun upsertFromParsed(p: com.todocompanion.app.util.NoteMarkdownFile.Parsed, ws: String, tagMap: MutableMap<String, TagEntity>): String {
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
                tagMap[key]?.id ?: UUID.randomUUID().toString().also { id ->
                    val t = TagEntity(id, name, workspaceId = ws); repo.upsertTag(t); tagMap[key] = t
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
    fun syncNotesFolder(folderUri: String) = viewModelScope.launch {
        val ws = repo.activeWs()
        val nowSync = System.currentTimeMillis()
        // Sealed-until-future notes are kept out of the mirror entirely (redaction at egress).
        val notes = repo.getNotesOnce().filter { !it.trashed && it.workspaceId == ws && (it.sealedUntil == null || it.sealedUntil!! <= nowSync) }
        val tagNameById = repo.getTagsOnce().associate { it.id to it.name }
        val refs = repo.getNoteTagCrossRefs().groupBy { it.noteId }
        val tagMap = repo.getTagsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }.toMutableMap()
        fun tagsOf(id: String) = refs[id].orEmpty().mapNotNull { tagNameById[it.tagId] }

        val baselineText = withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.readConfig(appCtx, folderUri, ".kairo", "mirror.json") }
        val disk = withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.importWithNames(appCtx, folderUri) }
        if (disk.isEmpty() && notes.isEmpty()) { toast("Nothing to sync"); return@launch }
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
                        withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.writeOne(appCtx, folderUri, fileName, com.todocompanion.app.util.NoteMarkdownFile.serialize(n, tagsOf(n.id))) }
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
        withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.writeConfig(appCtx, folderUri, ".kairo", "mirror.json", com.todocompanion.app.util.NoteMirror.encodeBaseline(out)) }
        syncFolderUri = folderUri
        pendingConflicts.clear(); pendingConflicts.putAll(conflicts)
        noteSyncConflicts.value = conflicts.map { (id, pair) ->
            NoteSyncConflict(id, "", pair.first.title.ifBlank { "Untitled" }, pair.first.body.take(140),
                pair.second.title.ifBlank { "Untitled" }, pair.second.body.take(140))
        }
        toast("Synced · $pushed out · $pulled in" + if (conflicts.isNotEmpty()) " · ${conflicts.size} conflict${if (conflicts.size == 1) "" else "s"}" else "")
    }

    /** Resolve one mirror conflict: keep "app" (push to file), "file" (pull into app), or "both". */
    fun resolveNoteConflict(noteId: String, keep: String) = viewModelScope.launch {
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
            "both" -> { withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.writeOne(appCtx, syncFolderUri, fileName, com.todocompanion.app.util.NoteMarkdownFile.serialize(appNote, appTags)) }
                upsertFromParsed(fileParsed.copy(id = null, title = fileParsed.title + " (from file)"), ws, tagMap) }
            else -> withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteFolderSync.writeOne(appCtx, syncFolderUri, fileName, com.todocompanion.app.util.NoteMarkdownFile.serialize(appNote, appTags)) }
        }
        pendingConflicts.remove(noteId)
        noteSyncConflicts.value = noteSyncConflicts.value.filterNot { it.noteId == noteId }
    }

    // ── Wave P (moat · N1): the dynamic note — live transclusion of app data ───────────────────────
    /** Expand `{{today:agenda}}` / `{{tasks:overdue|today}}` / `{{note:Title}}` tokens against live data,
     *  recomputed on open. Read-only projection — a briefing the note writes from your tasks and notes
     *  (this is also M3's daily cockpit). Unknown/failed tokens are left verbatim. */
    suspend fun expandNoteTransclusion(body: String, noteId: String? = null): String {
        if (!com.todocompanion.app.util.NoteTransclusion.hasTokens(body)) return body
        // L6 — time tracked against this note (its own entries + its linked task's), for {{time:this-note}}.
        val thisNoteMinutes = noteId?.let { runCatching { repo.trackedMinutesForNote(it) }.getOrDefault(0) } ?: 0
        val zone = java.time.ZoneId.systemDefault()
        val todayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEnd = todayStart + 86_400_000L
        val open = tasks.value.filter { !it.completed }
        val notes = repo.getNotesOnce()
        fun list(ts: List<com.todocompanion.app.data.entity.TaskEntity>): String =
            if (ts.isEmpty()) "_Nothing here_"
            else ts.sortedBy { it.dueDate ?: Long.MAX_VALUE }.joinToString("\n") { "- [ ] ${it.title}" }
        // Wave T — the self-writing life note draws on events and habits too, not just tasks.
        val evAll = events.value
        fun evList(evs: List<com.todocompanion.app.data.entity.EventEntity>): String =
            if (evs.isEmpty()) "_No events_"
            else evs.sortedBy { it.startMillis }.joinToString("\n") { e ->
                val t = if (e.allDay) "all-day" else java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone)
                    .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                "- $t · ${e.title}"
            }
        val hbs = habits.value
        fun habitList(): String = if (hbs.isEmpty()) "_No habits_"
            else hbs.joinToString("\n") { h -> "- [ ] ${h.emoji?.let { "$it " } ?: ""}${h.name}" }
        // L4 — a tiny quote-aware query parser for Living Query Blocks: bare flags (overdue), key=value
        // (list=Work, tag="Deep Work"), and comparisons (due<7d). Returns null-safe empty on a blank arg.
        fun parseQ(raw: String): Triple<Set<String>, Map<String, String>, List<Triple<String, Char, Long>>> {
            val flags = HashSet<String>(); val kv = HashMap<String, String>(); val cmp = ArrayList<Triple<String, Char, Long>>()
            Regex("\"[^\"]*\"|\\S+").findAll(raw).map { it.value }.forEach { tok ->
                val eq = tok.indexOf('='); val lt = tok.indexOf('<'); val gt = tok.indexOf('>')
                when {
                    eq > 0 -> kv[tok.take(eq).lowercase()] = tok.substring(eq + 1).trim('"')
                    lt > 0 || gt > 0 -> {
                        val i = if (lt > 0) lt else gt
                        tok.substring(i + 1).trimEnd('d', 'D').toLongOrNull()?.let { cmp.add(Triple(tok.take(i).lowercase(), tok[i], it)) }
                    }
                    else -> flags.add(tok.lowercase())
                }
            }
            return Triple(flags, kv, cmp)
        }
        // L4 — data the notes/time query blocks draw on (cheap live snapshots).
        val allLists = lists.value
        val tagsByName = tags.value.associateBy { it.name.lowercase() }
        val ctxByName = contexts.value.associateBy { it.name.lowercase() }
        val tagRefs = noteTagRefs.value
        val ctxRefs = noteContextRefs.value
        return com.todocompanion.app.util.NoteTransclusion.expand(body) { scope, arg ->
            when (scope) {
                "tasks" -> {
                    val (flags, kv, cmp) = parseQ(arg)
                    if (flags.isEmpty() && kv.isEmpty() && cmp.isEmpty()) null
                    else {
                        var sel = open.asSequence()
                        if ("overdue" in flags) sel = sel.filter { it.dueDate != null && it.dueDate!! < todayStart }
                        if ("today" in flags) sel = sel.filter { it.dueDate != null && it.dueDate!! in todayStart until todayEnd }
                        if ("week" in flags) sel = sel.filter { it.dueDate != null && it.dueDate!! in todayStart until todayStart + 7 * 86_400_000L }
                        if ("flagged" in flags) sel = sel.filter { it.flagId != null }
                        kv["list"]?.let { name -> val id = allLists.firstOrNull { it.name.equals(name, true) }?.id; sel = sel.filter { it.listId == id } }
                        kv["energy"]?.let { e -> val lvl = when (e.lowercase()) { "high" -> 3; "med", "medium" -> 2; "low" -> 1; else -> e.toIntOrNull() ?: 0 }; sel = sel.filter { it.energy == lvl } }
                        cmp.firstOrNull { it.first == "due" }?.let { (_, op, n) ->
                            val bound = todayStart + n * 86_400_000L
                            sel = if (op == '<') sel.filter { it.dueDate != null && it.dueDate!! < bound } else sel.filter { it.dueDate != null && it.dueDate!! > bound }
                        }
                        list(sel.toList())
                    }
                }
                "notes" -> {
                    val (flags, kv, _) = parseQ(arg)
                    var sel = notes.asSequence().filter { !it.trashed && !it.archived && (it.sealedUntil == null || it.sealedUntil!! <= System.currentTimeMillis()) }
                    kv["tag"]?.let { name -> val id = tagsByName[name.lowercase()]?.id; val ids = tagRefs.filter { it.tagId == id }.mapTo(HashSet()) { it.noteId }; sel = sel.filter { it.id in ids } }
                    kv["context"]?.let { name -> val id = ctxByName[name.lowercase()]?.id; val ids = ctxRefs.filter { it.contextId == id }.mapTo(HashSet()) { it.noteId }; sel = sel.filter { it.id in ids } }
                    kv["kind"]?.let { k -> sel = sel.filter { it.kind.equals(k, true) } }
                    kv["contains"]?.let { s -> sel = sel.filter { it.title.contains(s, true) || it.body.contains(s, true) } }
                    val hit = sel.sortedByDescending { it.updatedAt }.let { if (flags.isEmpty() && kv.isEmpty()) it.take(10) else it.take(40) }.toList()
                    if (hit.isEmpty()) "_No notes_" else hit.joinToString("\n") { "- [[${it.title.ifBlank { "Untitled" }}]]" }
                }
                "time" -> if (arg.trim() == "this-note") {
                    if (thisNoteMinutes <= 0) "_No time tracked on this note yet_"
                    else "⏱ **${thisNoteMinutes / 60}h ${thisNoteMinutes % 60}m** tracked on this note"
                } else {
                    val (flags, kv, _) = parseQ(arg)
                    val nowMs = System.currentTimeMillis()
                    val days = when { "month" in flags -> 30L; "week" in flags -> 7L; else -> 1L }
                    val windowStart = todayStart - (days - 1) * 86_400_000L
                    var entries = timeVm.timeEntries.value.asSequence().filter { it.startMillis >= windowStart }
                    kv["activity"]?.let { name -> val id = timeVm.timeActivities.value.firstOrNull { it.name.equals(name, true) }?.id; entries = entries.filter { it.activityId == id } }
                    val es = entries.toList()
                    val total = es.sumOf { it.minutes(nowMs) }
                    if (total <= 0) "_No time tracked_" else buildString {
                        val range = when { days == 30L -> "last 30 days"; days == 7L -> "last 7 days"; else -> "today" }
                        append("**${total / 60}h ${total % 60}m** tracked ($range)\n")
                        val actName = timeVm.timeActivities.value.associate { it.id to it.name }
                        es.groupBy { it.activityId }.mapValues { it.value.sumOf { e -> e.minutes(nowMs) } }
                            .entries.sortedByDescending { it.value }.take(6)
                            .forEach { (aid, min) -> append("- ${actName[aid] ?: "Untracked"}: ${min / 60}h ${min % 60}m\n") }
                    }
                }
                "today" -> if (arg.isEmpty() || arg == "agenda")
                    list(open.filter { it.dueDate != null && it.dueDate!! < todayEnd }) else null
                "events" -> when (arg) {
                    "", "today" -> evList(evAll.filter { it.startMillis in todayStart until todayEnd })
                    "week" -> evList(evAll.filter { it.startMillis in todayStart until todayStart + 7 * 86_400_000L })
                    else -> null
                }
                "habits" -> if (arg.isEmpty() || arg == "due" || arg == "today") habitList() else null
                "goal" -> {
                    // Wave 3 · Notes ⇄ Goals — live goal progress inside a note. arg = goal id or name.
                    val g = goals().firstOrNull { it.id == arg } ?: goals().firstOrNull { it.name.equals(arg, true) }
                    if (g == null) "_Goal not found_" else buildString {
                        val today = java.time.LocalDate.now().toEpochDay()
                        append("${g.emoji} **${g.name}**")
                        com.todocompanion.app.domain.GoalScore.cycle(g, today)?.let {
                            append(" · week ${it.weekIndex}/${it.totalWeeks} · ${it.daysLeft}d left")
                        }
                        append("\n")
                        g.keyResultFraction?.let { append("- Key results: ${(it * 100).toInt()}%\n") }
                        if (g.milestones.isNotEmpty()) append("- Milestones: ${g.milestonesDone}/${g.milestones.size}\n")
                        val chain = com.todocompanion.app.domain.GoalScore.integrityChain(goalReviews(), g.reviewCadenceDays, today, g.id)
                        if (chain > 0) append("- Integrity chain: $chain ${if (chain == 1) "review" else "reviews"}\n")
                    }
                }
                "note" -> {
                    // Wave T — block-level transclusion: {{note:Title#Heading}} pulls just that section.
                    val title = arg.substringBefore("#").trim()
                    val heading = arg.substringAfter("#", "").trim()
                    val n = notes.firstOrNull { !it.trashed && it.title.trim().equals(title, true) }
                    when {
                        n == null -> "_Note “$title” not found_"
                        heading.isEmpty() -> n.body
                        else -> com.todocompanion.app.domain.MarkdownSections.sections(n.body)
                            .firstOrNull { it.heading.trimStart('#', ' ').trim().equals(heading, true) }
                            ?.let { (it.heading + "\n" + it.body).trim() } ?: "_Heading “$heading” not found_"
                    }
                }
                else -> null
            }
        }
    }

    /** Save the note and capture a version snapshot in one ordered coroutine (used on editor close).
     *  L11 — a vaulted note is encrypted (sealForVault) before it ever reaches the DB. */
    fun closeNoteEditor(n: com.todocompanion.app.data.entity.NoteEntity) = viewModelScope.launch {
        // Wave 2 · auto-vault-on-close — a note in an auto-vault notebook is encrypted as the editor closes,
        // but only when the vault is set up and unlocked this session (else it would flag without encrypting).
        val autoVault = n.notebookId != null && !n.vault && vaultConfigured() && vaultUnlocked.value &&
            notebooks.value.firstOrNull { it.id == n.notebookId }?.autoVault == true
        val toSave = if (autoVault) n.copy(vault = true) else n
        repo.upsertNote(sealForVault(toSave.copy(workspaceId = toSave.workspaceId.ifBlank { activeWorkspace() })))
        repo.saveNoteRevision(n.id, settings.value.notesMaxRevisions)
    }

    /** Wave 2 · Privacy Dial — toggle auto-vault-on-close for a whole notebook. */
    fun setNotebookAutoVault(notebookId: String, on: Boolean) = viewModelScope.launch {
        notebooks.value.firstOrNull { it.id == notebookId }?.let { repo.upsertNotebook(it.copy(autoVault = on)) }
    }
    fun saveNotebook(id: String?, name: String, icon: String?, colorArgb: Long?) = viewModelScope.launch {
        val existing = id?.let { nid -> notebooks.value.firstOrNull { it.id == nid } }
        repo.upsertNotebook(
            (existing ?: com.todocompanion.app.data.entity.NotebookEntity(id = "", name = name, workspaceId = activeWorkspace()))
                .copy(name = name.trim().ifBlank { "Notebook" }, icon = icon, colorArgb = colorArgb)
        )
    }
    fun deleteNotebook(id: String) = viewModelScope.launch { repo.deleteNotebook(id) }
    /** Create a notebook and hand back its new id (so the caller can assign the current note to it). */
    fun createNotebook(name: String, icon: String? = null, onCreated: (String) -> Unit = {}) = viewModelScope.launch {
        val id = repo.upsertNotebook(
            com.todocompanion.app.data.entity.NotebookEntity(
                id = "", name = name.trim().ifBlank { "Notebook" }, icon = icon, workspaceId = activeWorkspace(),
            )
        )
        onCreated(id)
    }

    fun setNoteDefaultView(v: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(noteDefaultView = v)) }
    fun setNotesViewMode(v: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesViewMode = v)) }
    // Custom note templates (user-created) — stored as JSON in settings; sit beside the built-in starters.
    fun saveNoteTemplate(name: String, emoji: String, body: String) = viewModelScope.launch {
        val list = com.todocompanion.app.domain.NoteTemplates.parseCustom(settings.value.notesTemplatesJson) +
            com.todocompanion.app.domain.NoteTemplates.newCustom(name, emoji, body)
        repo.saveSettings(settings.value.copy(notesTemplatesJson = com.todocompanion.app.domain.NoteTemplates.encodeCustom(list)))
    }
    fun deleteNoteTemplate(id: String) = viewModelScope.launch {
        val list = com.todocompanion.app.domain.NoteTemplates.parseCustom(settings.value.notesTemplatesJson).filterNot { it.id == id }
        repo.saveSettings(settings.value.copy(notesTemplatesJson = com.todocompanion.app.domain.NoteTemplates.encodeCustom(list)))
    }
    fun setNotesSort(v: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesSort = v)) }
    fun setNotesNotebookMode(mode: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesNotebookMode = mode)) }
    fun setPeriodicRecapEmbed(v: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(periodicRecapEmbed = v)) }
    // Wave Q — the reading experience: live-styling, reading theme, and typography setters.
    fun setNotesLiveStyle(v: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesLiveStyle = v)) }
    fun setNotesReadingTheme(v: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesReadingTheme = v)) }
    fun setNotesFont(v: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesFont = v)) }
    fun setNotesFontScale(v: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesFontScale = v)) }
    fun setNotesLineHeight(v: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesLineHeight = v)) }
    fun setNotesMeasure(v: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesMeasure = v)) }
    fun setNotesFocusMode(v: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(notesFocusMode = v)) }

    /** Note-search results (ids), driven by [searchNotes]; empty when the query is blank. */
    val noteSearchIds = MutableStateFlow<List<String>>(emptyList())
    fun searchNotes(query: String) = viewModelScope.launch {
        noteSearchIds.value = if (query.isBlank()) emptyList() else repo.searchNoteIds(query)
    }

    /** L9 — "Ask your notes": on-device, extractive answers (no model, no network). Driven by [askNotes].
     *  Wave 2 · when the literal, term-coverage pass finds little, a model-free MinHash semantic pass
     *  ([NoteSemantic]) broadens the recall so paraphrases and synonyms of the question still surface. */
    val noteAnswers = MutableStateFlow<List<com.todocompanion.app.domain.NoteAsk.Answer>>(emptyList())
    fun askNotes(query: String) = viewModelScope.launch {
        if (query.isBlank()) { noteAnswers.value = emptyList(); return@launch }
        val now = System.currentTimeMillis()
        val ws = activeWorkspace()   // scope Ask-your-notes to the active workspace (no cross-workspace leak)
        val notes = repo.getNotesOnce()
            .filter { it.workspaceId == ws && !it.trashed && !it.noIndex && (it.sealedUntil == null || it.sealedUntil!! <= now) }
        val docs = notes.map { com.todocompanion.app.domain.NoteAsk.Doc(it.id, it.title, it.body) }
        val literal = com.todocompanion.app.domain.NoteAsk.answer(query, docs)
        // Semantic fallback: fill up to 6 results with conceptually-near notes the literal pass missed.
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

    /** L10 — Right Note, Right Now: notes whose @context is *scheduled and open at this moment* (permission-
     *  free, via ContextAvailability open-hours). Notes join the app's existing context engine — arrive in
     *  a context's window and the notes you keep for it surface. */
    val notesNow = MutableStateFlow<List<com.todocompanion.app.data.entity.NoteEntity>>(emptyList())
    fun refreshNotesForNow() = viewModelScope.launch {
        val t = java.time.LocalDateTime.now()
        val dow = t.dayOfWeek.value            // 1..7, matching ContextAvailability
        val minute = t.hour * 60 + t.minute
        val ws = activeWorkspace()
        val openCtx = contexts.value.filter {
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

    // ── Phase 2: woven notes — find-or-create the note bound to a day / event / task ─────────────────
    private fun dayNoteTitle(epochDay: Long): String = runCatching {
        java.time.LocalDate.ofEpochDay(epochDay)
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
    }.getOrDefault("Daily note")

    /** Open (creating if needed) the journal note for [epochDay] — the day's page, tied to Day Review. */
    fun openDailyNote(epochDay: Long, onOpen: (String) -> Unit) = viewModelScope.launch {
        val ws = activeWorkspace()
        val existing = repo.getNotesOnce().firstOrNull { !it.trashed && it.workspaceId == ws && it.kind == "journal" && it.dayEpoch == epochDay }
        onOpen(existing?.id ?: repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
            id = "", kind = "journal", dayEpoch = epochDay, title = dayNoteTitle(epochDay), workspaceId = ws,
        )))
    }

    // ── Periodic Notes — one canonical note per day / week / month / year, linked to that period's review ──
    // Storage reuses NoteEntity.kind (PeriodicNotes.kindFor) + the existing dayEpoch column as the period
    // anchor (its first day). A note is matched by period-window membership so it survives a week-start
    // change; daily notes keep their historical kind == "journal", so every daily-note feature keeps working.
    private fun periodWindow(period: com.todocompanion.app.domain.PeriodRange, anchorDay: Long) =
        period.window(anchorDay, settings.value.weekStart, today())

    /** The id of the existing periodic note for [period] at [anchorDay], or null — a fast, synchronous
     *  lookup over the active-workspace note flow (for calendar dots and "open vs create" affordances). */
    fun periodicNoteId(period: com.todocompanion.app.domain.PeriodRange, anchorDay: Long): String? {
        val kind = com.todocompanion.app.domain.PeriodicNotes.kindFor(period)
        val win = periodWindow(period, anchorDay)
        return notes.value.firstOrNull { it.kind == kind && (it.dayEpoch ?: Long.MIN_VALUE) in win.startDay..win.endDay }?.id
    }

    /** Open (creating if needed) the periodic note for [period] at [anchorDay]. Daily delegates to the
     *  historical journal note; week/month/year seed a reflection scaffold and embed the live period recap. */
    fun openPeriodicNote(period: com.todocompanion.app.domain.PeriodRange, anchorDay: Long, onOpen: (String) -> Unit) {
        if (period == com.todocompanion.app.domain.PeriodRange.DAY || period == com.todocompanion.app.domain.PeriodRange.ALL) {
            openDailyNote(minOf(anchorDay, today()), onOpen); return
        }
        viewModelScope.launch {
            val ws = activeWorkspace()
            val kind = com.todocompanion.app.domain.PeriodicNotes.kindFor(period)
            val win = periodWindow(period, anchorDay)
            val existing = repo.getNotesOnce().firstOrNull {
                !it.trashed && it.workspaceId == ws && it.kind == kind && (it.dayEpoch ?: Long.MIN_VALUE) in win.startDay..win.endDay
            }
            if (existing != null) { onOpen(existing.id); return@launch }
            val title = com.todocompanion.app.domain.PeriodicNotes.titleFor(period, win.startDay)
            val id = repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
                id = "", kind = kind, dayEpoch = win.startDay, title = title,
                body = com.todocompanion.app.domain.PeriodicNotes.seedBody(period, title), workspaceId = ws,
            ))
            if (settings.value.periodicRecapEmbed) writePeriodDigestToNote(period, win.startDay, createIfMissing = false)
            onOpen(id)
        }
    }

    /** Render the period's recap as Markdown (folded via the same [PeriodRecap]/[ReviewRollup] the review
     *  screens use), so a period note's embedded digest agrees with its review to the number. */
    fun periodDigestMarkdown(period: com.todocompanion.app.domain.PeriodRange, startDay: Long, endDay: Long, title: String): String {
        val recap = periodRecap(startDay, endDay, title)
        if (!recap.hasData) return ""
        return buildString {
            append("## ").append(title).append("\n")
            if (recap.narrative.isNotBlank()) append(recap.narrative).append("\n\n")
            recap.lines.forEach { l ->
                append("- ").append(l.icon).append(" ").append(l.label).append(": ").append(l.value)
                if (l.delta != 0) append(" (").append(if (l.delta > 0) "+" else "").append(l.delta)
                    .append(if (l.deltaUnit.isNotBlank()) " " + l.deltaUnit else "").append(" vs prior)")
                append("\n")
            }
        }.trim()
    }

    /** Fold the period recap into the periodic note between the recap markers (idempotent replace), like
     *  [writeDayRecapToNote] does for the day. Daily delegates to that. */
    suspend fun writePeriodDigestToNote(period: com.todocompanion.app.domain.PeriodRange, anchorDay: Long, createIfMissing: Boolean) {
        if (period == com.todocompanion.app.domain.PeriodRange.DAY) { writeDayRecapToNote(anchorDay, createIfMissing); return }
        if (period == com.todocompanion.app.domain.PeriodRange.ALL) return
        val ws = activeWorkspace()
        val kind = com.todocompanion.app.domain.PeriodicNotes.kindFor(period)
        val win = periodWindow(period, anchorDay)
        val existing = repo.getNotesOnce().firstOrNull {
            !it.trashed && it.workspaceId == ws && it.kind == kind && (it.dayEpoch ?: Long.MIN_VALUE) in win.startDay..win.endDay
        }
        if (existing == null && !createIfMissing) return
        val title = com.todocompanion.app.domain.PeriodicNotes.titleFor(period, win.startDay)
        val digest = periodDigestMarkdown(period, win.startDay, win.endDay, title).trim()
        if (digest.isBlank()) return   // no data yet — never wipe an existing block with nothing
        val block = "${com.todocompanion.app.domain.PeriodicNotes.RECAP_OPEN}\n$digest\n${com.todocompanion.app.domain.PeriodicNotes.RECAP_CLOSE}"
        val base = existing?.body ?: com.todocompanion.app.domain.PeriodicNotes.seedBody(period, title)
        val re = Regex("(?s)" + Regex.escape(com.todocompanion.app.domain.PeriodicNotes.RECAP_OPEN) + ".*?" + Regex.escape(com.todocompanion.app.domain.PeriodicNotes.RECAP_CLOSE))
        val newBody = when {
            re.containsMatchIn(base) -> re.replace(base) { block }
            base.isBlank() -> block
            else -> base.trimEnd() + "\n\n" + block
        }
        val note = existing ?: com.todocompanion.app.data.entity.NoteEntity(
            id = "", kind = kind, dayEpoch = win.startDay, title = title, workspaceId = ws,
        )
        repo.upsertNote(note.copy(body = newBody))
    }

    /** Manual "save this period's recap to its note" (creates the note if needed). */
    fun savePeriodRecapToNote(period: com.todocompanion.app.domain.PeriodRange, anchorDay: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        writePeriodDigestToNote(period, anchorDay, createIfMissing = true); onDone()
    }

    /** The journaling streak: consecutive days ending today (or yesterday, if today isn't written yet)
     *  that have a daily note — a gentle, shame-free "you've kept the thread" signal for the journal hub. */
    fun journalStreak(): Int {
        val days = notes.value.asSequence().filter { it.kind == "journal" }.mapNotNull { it.dayEpoch }.toHashSet()
        if (days.isEmpty()) return 0
        val t = today()
        var cursor = when { t in days -> t; (t - 1) in days -> t - 1; else -> return 0 }
        var n = 0
        while (cursor in days) { n++; cursor-- }
        return n
    }

    /** Open (creating if needed) the meeting note bound to a calendar event. */
    fun openEventNote(eventId: String, eventTitle: String, onOpen: (String) -> Unit) = viewModelScope.launch {
        val ws = activeWorkspace()
        val existing = repo.getNotesOnce().firstOrNull { !it.trashed && it.workspaceId == ws && it.linkedEventId == eventId }
        onOpen(existing?.id ?: repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
            id = "", kind = "meeting", linkedEventId = eventId, title = eventTitle.ifBlank { "Meeting note" }, workspaceId = ws,
        )))
    }

    /** Open (creating if needed) the note bound to a task. */
    fun openTaskNote(taskId: String, taskTitle: String, onOpen: (String) -> Unit) = viewModelScope.launch {
        val ws = activeWorkspace()
        val existing = repo.getNotesOnce().firstOrNull { !it.trashed && it.workspaceId == ws && it.linkedTaskId == taskId }
        onOpen(existing?.id ?: repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
            id = "", linkedTaskId = taskId, title = taskTitle.ifBlank { "Note" }, workspaceId = ws,
        )))
    }

    // ── Phase 3: expert connection layer — [[wiki-links]] (by title) + checkbox → task ──────────────
    /** Open the note titled [title] (case-insensitive), creating it if none exists — a [[wiki-link]] jump. */
    fun openOrCreateNoteByTitle(title: String, onOpen: (String) -> Unit) = viewModelScope.launch {
        val ws = activeWorkspace()
        val t = title.trim()
        val existing = repo.getNotesOnce().firstOrNull { !it.trashed && it.workspaceId == ws && it.title.equals(t, ignoreCase = true) }
        onOpen(existing?.id ?: repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(id = "", title = t, workspaceId = ws)))
    }

    /** Turn each unchecked "- [ ]" line in a note into a real Inbox task; reports how many were created.
     *  L5 — each extracted line is then *bound* to its new task by rewriting it to `- [ ] [[Task title]]`,
     *  so from then on ticking it in the note completes the task and vice-versa (Shared Checkboxes). */
    fun extractNoteCheckboxes(noteId: String, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val n = repo.getNote(noteId) ?: return@launch
        // Wave J (M2) — a meeting/event-linked note's action items become tasks due at the event's start,
        // so the checkboxes you jot in a meeting land on your list dated to the meeting itself.
        val due = n.linkedEventId?.let { repo.eventById(it)?.startMillis }
        val boxRe = Regex("""^(\s*[-*+]\s+\[ ]\s+)(.*\S)\s*$""")
        val lines = n.body.split("\n")
        val bindText = HashMap<Int, String>()   // line index → task title to create + bind
        lines.forEachIndexed { i, line ->
            boxRe.matchEntire(line)?.let { m ->
                val t = m.groupValues[2].trim()
                if (t.isNotBlank() && !t.contains("[[")) bindText[i] = t   // skip lines already bound
            }
        }
        // L8 — Capture-to-anything: a recurring intention ("meditate every morning") becomes a habit; every
        // other line becomes a bound task. Only lines routed to tasks get rewritten to "- [ ] [[Task]]".
        val ws = activeWorkspace()
        val existingHabitNames = habits.value.map { it.name.trim().lowercase() }.toHashSet()
        val taskLines = HashMap<Int, String>()   // line index → task title to create + bind
        var habitsMade = 0
        bindText.forEach { (i, text) ->
            when (val item = com.todocompanion.app.domain.NoteCapture.classify(text)) {
                is com.todocompanion.app.domain.NoteCapture.Item.Habit -> {
                    if (item.name.trim().lowercase() !in existingHabitNames) {
                        repo.createHabit(name = item.name, emoji = null, colorArgb = null, target = 1, workspaceId = ws)
                        existingHabitNames.add(item.name.trim().lowercase()); habitsMade++
                    }
                }
                is com.todocompanion.app.domain.NoteCapture.Item.Task -> {
                    repo.createTask(com.todocompanion.app.data.entity.ListEntity.INBOX_ID, item.title, dueDate = due)
                    taskLines[i] = item.title
                }
            }
        }
        if (taskLines.isNotEmpty()) {
            val newBody = lines.mapIndexed { i, line ->
                taskLines[i]?.let { t -> boxRe.matchEntire(line)!!.groupValues[1] + "[[$t]]" } ?: line
            }.joinToString("\n")
            repo.upsertNote(n.copy(body = newBody))
        }
        onDone(taskLines.size + habitsMade)
    }

    /** L5 — pull a note's bound checkbox lines into line with their tasks' live state (called on open). */
    fun reconcileNoteCheckboxes(noteId: String) = viewModelScope.launch { repo.reconcileNoteCheckboxesFromTasks(noteId) }

    /** L6 — start a time-tracking session against this note (Work-on-this-note), on the "Notes" activity. */
    fun startTimeTrackingForNote(noteId: String) = viewModelScope.launch { repo.startTimeTrackingForNote(noteId) }

    // ── Wave 2 — cross-module note extensions ────────────────────────────────────────────────────────
    /** Evergreen Resurfacing — notes due for a spaced review right now, most-overdue first. */
    val notesDueForReview: StateFlow<List<com.todocompanion.app.data.entity.NoteEntity>> = notes.map { list ->
        val t = System.currentTimeMillis()
        list.filter {
            !it.trashed && !it.archived && !it.vault && it.reviewEvery > 0 &&
                com.todocompanion.app.domain.NoteReview.isDue(it.reviewEvery, it.lastReviewedAt, it.updatedAt, t)
        }.sortedBy { com.todocompanion.app.domain.NoteReview.nextDue(it.reviewEvery, it.lastReviewedAt, it.updatedAt) ?: Long.MAX_VALUE }
    }.state(emptyList())

    fun setNoteReview(noteId: String, days: Int) = viewModelScope.launch {
        repo.getNote(noteId)?.let {
            repo.upsertNote(it.copy(reviewEvery = days,
                lastReviewedAt = if (days > 0 && it.lastReviewedAt == 0L) System.currentTimeMillis() else it.lastReviewedAt))
        }
    }
    fun markNoteReviewed(noteId: String) = viewModelScope.launch {
        repo.getNote(noteId)?.let { repo.upsertNote(it.copy(lastReviewedAt = System.currentTimeMillis())) }
    }

    /** The Note-Garden review — a snapshot of entropy in the vault (orphans, stale, dropped intentions,
     *  near-duplicates), assembled on-device from projections. Refresh with [refreshNoteGarden]. */
    data class NoteGardenReport(
        val orphans: List<com.todocompanion.app.domain.NoteGarden.NoteView> = emptyList(),
        val stale: List<com.todocompanion.app.domain.NoteGarden.NoteView> = emptyList(),
        val dropped: List<Pair<com.todocompanion.app.domain.NoteGarden.NoteView, String>> = emptyList(),
        val duplicates: List<com.todocompanion.app.domain.NoteGarden.DupePair> = emptyList(),
        val scanned: Int = 0,
    )
    val noteGarden = MutableStateFlow(NoteGardenReport())
    val noteGardenLoading = MutableStateFlow(false)
    fun refreshNoteGarden() = viewModelScope.launch {
        noteGardenLoading.value = true
        val report = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val ws = activeWorkspace()
            val live = notes.value.filter { it.workspaceId == ws && !it.trashed && !it.archived && !it.vault }
            val links = runCatching { repo.getNoteLinksOnce() }.getOrDefault(emptyList())
            val tagCounts = noteTagRefs.value.groupingBy { it.noteId }.eachCount()
            val outCounts = links.filter { it.targetId.isNotBlank() }.groupingBy { it.noteId }.eachCount()
            val inCounts = links.filter { it.targetType == "note" && it.targetId.isNotBlank() }
                .groupingBy { it.targetId }.eachCount()
            val views = live.map {
                com.todocompanion.app.domain.NoteGarden.NoteView(
                    id = it.id, title = it.title, body = it.body, updatedAt = it.updatedAt,
                    tagCount = tagCounts[it.id] ?: 0, outLinks = outCounts[it.id] ?: 0, inLinks = inCounts[it.id] ?: 0)
            }
            val taskTitles = tasks.value.filter { !it.completed }.map { it.title }.toHashSet()
            // Cap the O(n²) duplicate pass to the most recent notes so a huge vault stays responsive.
            val dupeInput = views.sortedByDescending { it.updatedAt }.take(200)
            com.todocompanion.app.ui.AppViewModel.NoteGardenReport(
                orphans = com.todocompanion.app.domain.NoteGarden.orphans(views).sortedByDescending { it.updatedAt },
                stale = com.todocompanion.app.domain.NoteGarden.stale(views, now),
                dropped = com.todocompanion.app.domain.NoteGarden.droppedIntentions(views, taskTitles),
                duplicates = com.todocompanion.app.domain.NoteGarden.duplicates(dupeInput),
                scanned = views.size)
        }
        noteGarden.value = report
        noteGardenLoading.value = false
    }
    /** Note-Garden "Make a task" — turn a dropped intention (a bare checkbox line) into a real task. */
    fun createTaskFromIntention(title: String) = viewModelScope.launch {
        val t = title.trim(); if (t.isBlank()) return@launch
        repo.createTask(com.todocompanion.app.data.entity.ListEntity.INBOX_ID, t.take(140))
    }

    /** Habit Practice Journal — open (creating if needed) the reflective note bound to a habit. */
    fun openHabitJournal(habitId: String, habitName: String, onOpen: (String) -> Unit) = viewModelScope.launch {
        val ws = activeWorkspace()
        val existing = repo.habitJournalNote(habitId)
        onOpen(existing?.id ?: repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
            id = "", linkedHabitId = habitId, kind = "note", workspaceId = ws,
            title = "$habitName — journal",
            body = "_Practice journal for **$habitName**. A dated line is appended on each check-in._\n\n**Streak:** {{habits:due}}\n",
        )))
    }

    /** Meeting Mode — an agenda block assembled from the linked calendar event. */
    suspend fun eventAgendaMarkdown(eventId: String): String = withContext(Dispatchers.IO) {
        val e = repo.eventById(eventId) ?: return@withContext ""
        val z = java.time.ZoneId.systemDefault()
        val start = java.time.Instant.ofEpochMilli(e.startMillis).atZone(z)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM · h:mm a")
        buildString {
            append("## ").append(e.title.ifBlank { "Meeting" }).append("\n")
            append("*").append(start.format(fmt))
            if (e.location.isNotBlank()) append(" · ").append(e.location)
            append("*\n\n")
            if (e.notes.isNotBlank()) append(e.notes.trim()).append("\n\n")
            append("### Agenda\n- \n\n### Action items\n- [ ] \n")
        }
    }

    /** Writing Sprints — log a finished sprint as tracked time on the note. */
    fun logNoteSprint(noteId: String, startMillis: Long, endMillis: Long) = viewModelScope.launch {
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
    fun addNoteToThread(threadId: String, memberTitle: String) = viewModelScope.launch {
        val th = repo.getNote(threadId) ?: return@launch
        repo.upsertNote(th.copy(body = com.todocompanion.app.domain.NoteThreads.addItem(th.body, memberTitle)))
    }

    /** Create a new thread note listing [memberTitle], then open it. */
    fun createThread(threadTitle: String, memberTitle: String, onOpen: (String) -> Unit) = viewModelScope.launch {
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

    // ── Wave 3 — Active Recall · Outcome Ledger · Notes⇄Goals · Courier · Read-Anywhere ───────────────

    /** Active Recall — the current review session's due cards, and the live due count for the badge. */
    val recallDue = MutableStateFlow<List<com.todocompanion.app.data.entity.NoteCardEntity>>(emptyList())
    val recallDueCount = MutableStateFlow(0)
    fun refreshRecall() = viewModelScope.launch {
        // Scope due cards to the active workspace: note-cards have no workspaceId, so join through
        // their parent note's workspace (else Active-Recall would surface cards from every workspace).
        val ws = activeWorkspace()
        val wsNoteIds = repo.getNotesOnce().asSequence().filter { it.workspaceId == ws }.map { it.id }.toSet()
        val due = repo.dueNoteCards().filter { it.noteId in wsNoteIds }
        recallDue.value = due
        recallDueCount.value = due.size
    }
    /** Grade the current card (SM-2) and advance; AGAIN re-queues it to the end of this session. */
    fun gradeCard(id: String, grade: com.todocompanion.app.domain.NoteCards.Grade) = viewModelScope.launch {
        repo.gradeNoteCard(id, grade)
        val current = recallDue.value.firstOrNull { it.id == id }
        val rest = recallDue.value.filter { it.id != id }
        recallDue.value = if (grade == com.todocompanion.app.domain.NoteCards.Grade.AGAIN && current != null) rest + current else rest
        // Badge tracks the (workspace-scoped) remaining due set, not the global table count.
        recallDueCount.value = recallDue.value.size
    }
    suspend fun cardCountForNote(noteId: String): Int = repo.noteCardsForNote(noteId).size

    /** Outcome Ledger — roll up the live state of everything this note's [[links]] spawned. */
    suspend fun noteOutcome(noteId: String): com.todocompanion.app.domain.NoteOutcome.Rollup = withContext(Dispatchers.IO) {
        val links = runCatching { repo.getNoteLinksOnce() }.getOrDefault(emptyList())
            .filter { it.noteId == noteId && it.targetId.isNotBlank() }
        val taskById = tasks.value.associateBy { it.id }
        val habitById = habits.value.associateBy { it.id }
        val checkins = runCatching { repo.getHabitCheckinsOnce() }.getOrDefault(emptyList())
        val today = java.time.LocalDate.now().toEpochDay()
        val nowMs = System.currentTimeMillis()
        val targets = links.mapNotNull { l ->
            when (l.targetType) {
                "task" -> taskById[l.targetId]?.let { com.todocompanion.app.domain.NoteOutcome.Target("task", it.id, it.title, done = it.completed) }
                "event" -> runCatching { repo.eventById(l.targetId) }.getOrNull()?.let {
                    com.todocompanion.app.domain.NoteOutcome.Target("event", it.id, it.title, past = it.startMillis < nowMs)
                }
                "habit" -> habitById[l.targetId]?.let { h ->
                    // Break-aware streak: days since relapse for a quit habit, frequency-aware for a build one.
                    // isSuccessDay excludes slips; count>0 alone would count a partial/slip as done.
                    val hs = com.todocompanion.app.domain.habit.HabitStats
                    val hc = checkins.filter { it.habitId == h.id }
                    val done = hc.filter { hs.isSuccessDay(h, it) }.map { it.epochDay }.toSet()
                    val skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
                    val rel = hc.filter { hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
                    com.todocompanion.app.domain.NoteOutcome.Target("habit", h.id, h.name,
                        streak = hs.currentStreak(h, done, skip, rel, today))
                }
                "note" -> com.todocompanion.app.domain.NoteOutcome.Target("note", l.targetId, l.targetTitle)
                else -> null
            }
        }
        val minutes = runCatching { repo.trackedMinutesForNote(noteId) }.getOrDefault(0)
        com.todocompanion.app.domain.NoteOutcome.rollup(targets, minutes)
    }

    /** Notes ⇄ Goals — open (creating if needed) the reflective evidence note bound to a goal. */
    fun openGoalJournal(goalId: String, goalName: String, onOpen: (String) -> Unit) = viewModelScope.launch {
        val existing = repo.goalJournalNote(goalId)
        onOpen(existing?.id ?: repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
            id = "", linkedGoalId = goalId, kind = "note", workspaceId = activeWorkspace(),
            title = "$goalName — journal",
            body = "_Reflective journal & evidence for the goal **$goalName**._\n\n**Progress:** {{goal:$goalId}}\n\n## Reflections\n",
        )))
    }

    /** Encrypted Note Courier — export a note as a passphrase-encrypted file, shared via the system sheet. */
    fun sendNoteEncrypted(noteId: String, passphrase: String) = viewModelScope.launch {
        if (passphrase.isBlank()) { toast("Enter a passphrase"); return@launch }
        val note = repo.getNote(noteId) ?: return@launch
        if (note.vault && com.todocompanion.app.domain.NoteVault.isLocked(note.body)) { toast("Unlock the note first"); return@launch }
        if (note.sealedUntil != null && note.sealedUntil!! > System.currentTimeMillis()) { toast("This note is sealed — unseal it first"); return@launch }
        val byId = repo.getTagsOnce().associate { it.id to it.name }
        val tagNames = repo.getNoteTagCrossRefs().filter { it.noteId == noteId }.mapNotNull { byId[it.tagId] }
        val payload = com.todocompanion.app.util.NoteCourier.Payload(
            title = note.title, body = note.body, kind = note.kind, emoji = note.coverEmoji,
            colorArgb = note.colorArgb, tags = tagNames)
        val blob = com.todocompanion.app.util.NoteCourier.seal(payload, passphrase.toCharArray())
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val base = note.title.ifBlank { "note" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "note" }
                val f = java.io.File(dir, "$base.${com.todocompanion.app.util.NoteCourier.FILE_EXT}").apply { writeText(blob) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        } ?: return@launch
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TITLE, "Encrypted note")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            appCtx.startActivity(android.content.Intent.createChooser(send, "Send encrypted note").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("No app to share to") }
    }

    /** Encrypted Note Courier — read a picked courier file, then decrypt + create the note. */
    fun receiveEncryptedNote(uriString: String, passphrase: String, onOpen: (String) -> Unit) = viewModelScope.launch {
        val blob = withContext(Dispatchers.IO) { readImportTextBounded(android.net.Uri.parse(uriString)) }
        if (blob.isNullOrBlank()) { toast("Couldn't read that file"); return@launch }
        if (!com.todocompanion.app.util.NoteCourier.looksEncrypted(blob)) { toast("Not a Kairo encrypted-note file"); return@launch }
        importEncryptedNote(blob, passphrase, onOpen)
    }

    /** Encrypted Note Courier — decrypt a received courier envelope with [passphrase] and create the note. */
    fun importEncryptedNote(blob: String, passphrase: String, onOpen: (String) -> Unit) = viewModelScope.launch {
        val payload = com.todocompanion.app.util.NoteCourier.open(blob, passphrase.toCharArray())
        if (payload == null) { toast("Wrong passphrase, or not a Kairo note file"); return@launch }
        val ws = activeWorkspace()
        val id = repo.upsertNote(com.todocompanion.app.data.entity.NoteEntity(
            id = "", workspaceId = ws, title = payload.title, body = payload.body, kind = payload.kind,
            coverEmoji = payload.emoji, colorArgb = payload.colorArgb))
        if (payload.tags.isNotEmpty()) {
            val existing = repo.getTagsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }.toMutableMap()
            val ids = payload.tags.map { name ->
                val key = name.lowercase()
                existing[key]?.id ?: UUID.randomUUID().toString().also { tid ->
                    repo.upsertTag(TagEntity(tid, name, workspaceId = ws)); existing[key] = TagEntity(tid, name, workspaceId = ws)
                }
            }
            repo.setNoteTags(id, ids.distinct())
        }
        toast("Note received"); onOpen(id)
    }

    /** Read-Anywhere — publish the workspace's notes as a self-contained offline website into a SAF folder. */
    fun publishSite(folderUri: String) = viewModelScope.launch {
        val ws = activeWorkspace()
        val now0 = System.currentTimeMillis()
        val list = repo.getNotesOnce().filter {
            !it.trashed && !it.archived && !it.vault && !it.noExport && it.workspaceId == ws &&
                (it.sealedUntil == null || it.sealedUntil!! <= now0)
        }
        if (list.isEmpty()) { toast("No notes to publish"); return@launch }
        val tagName = repo.getTagsOnce().associate { it.id to it.name }
        val refs = repo.getNoteTagCrossRefs().groupBy { it.noteId }
        val siteNotes = list.sortedByDescending { it.updatedAt }.map { n ->
            com.todocompanion.app.util.NoteSite.Note(n.id, n.title.ifBlank { "Untitled" }, n.body, n.updatedAt,
                refs[n.id].orEmpty().mapNotNull { tagName[it.tagId] })
        }
        val files = com.todocompanion.app.util.NoteSite.build(siteNotes, "My Kairo notes")
        val count = withContext(Dispatchers.IO) { com.todocompanion.app.util.NoteSite.writeToTree(appCtx, folderUri, files) }
        toast(if (count > 0) "Published ${files.size} pages — open index.html in any browser" else "Couldn't write to that folder")
    }

    // ── L11 — Vault: real, portable, passphrase-based encryption for a note's body at rest ────────────
    // The passphrase lives only in memory for the session; it is never persisted. A note stays vaulted
    // (ciphertext) until unlocked; the editor decrypts for editing and re-encrypts on save.
    @Volatile private var vaultPass: CharArray? = null
    val vaultUnlocked = MutableStateFlow(false)

    /** True once the user has chosen a vault passphrase (a verifier is stored). */
    fun vaultConfigured(): Boolean = settings.value.notesVaultCheck.isNotBlank()

    // ── SEC (Batch 6) — frontier: honest readouts + panic wipe ────────────────────────────────────────
    /** The DB-wrap key's real hardware security level (StrongBox / TEE / Software), for Settings → Security. */
    fun keySecurityLevel(): String? = com.todocompanion.app.data.security.SecureDb.keySecurityLevel()

    /** A one-line advisory when the OS itself undercuts at-rest guarantees (root/emulator), else null. */
    fun securityAdvisory(): String? = com.todocompanion.app.data.security.SecurityAdvisory.advisory()

    /** Panic wipe: destroy every on-device key and securely erase the encrypted DB + attachments, then kill
     *  the process so the next launch starts clean. IRREVERSIBLE — guarded by a typed confirmation in the UI. */
    fun panicWipe() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { com.todocompanion.app.data.security.SecureDb.panicWipe(appCtx) }
            runCatching { purgeRichImgCache() }
            runCatching { purgeSharedCache() }   // SEC (R2-C) — stale exports/captures/share cards too
            // The live SQLCipher handle still holds the passphrase in RAM until the process dies — end it now.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** First-time setup: choose the vault passphrase and store only a verifier (never the passphrase). */
    fun setUpVault(pass: String) = viewModelScope.launch {
        if (pass.isBlank()) return@launch
        repo.saveSettings(settings.value.copy(notesVaultCheck = com.todocompanion.app.domain.NoteVault.makeCheck(pass.toCharArray())))
        vaultPass = pass.toCharArray(); vaultUnlocked.value = true
    }

    /** Unlock the vault for this session; true if the passphrase is correct. */
    fun unlockVault(pass: String): Boolean {
        val ok = com.todocompanion.app.domain.NoteVault.verify(settings.value.notesVaultCheck, pass.toCharArray())
        if (ok) { vaultPass = pass.toCharArray(); vaultUnlocked.value = true }
        return ok
    }

    /** Re-lock the vault (clears the in-memory passphrase) and wipe any decrypted image copies the
     *  WebView renderer left in the cache, so re-locking a vaulted note also removes its plaintext images. */
    fun lockVault() {
        vaultPass?.fill(' ')   // SEC: zeroize the in-memory passphrase before dropping the reference
        vaultPass = null
        vaultUnlocked.value = false
        viewModelScope.launch(Dispatchers.IO) { runCatching { purgeRichImgCache() }; runCatching { purgeSharedCache() } }
    }

    /** Best-effort secure wipe of the decrypted-image cache the rich-note renderer materializes. */
    fun purgeRichImgCache() = secureWipeCacheDir("richimg")

    /** SEC (R2-C) — the cacheDir/shared folder accumulates single-note exports, camera captures and share
     *  cards handed to other apps via FileProvider. Once the share is done those are stale plaintext copies
     *  of your content sitting in cache, so sweep them on vault-lock, cold start and panic wipe. */
    fun purgeSharedCache() = secureWipeCacheDir("shared")

    /** SEC (R2-C) — read an imported text file with an upper bound, so a hostile multi-gigabyte file can't
     *  OOM the app on import (share exports were already capped; imports were not). Returns null past the cap
     *  or on any read error. 64 MB comfortably covers a large full-JSON backup while refusing a pathological
     *  file. */
    private fun readImportTextBounded(uri: android.net.Uri, maxBytes: Long = 64L * 1024 * 1024): String? =
        runCatching {
            appCtx.contentResolver.openInputStream(uri)?.use { ins ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(chunk); if (n < 0) break
                    total += n; if (total > maxBytes) return null
                    buf.write(chunk, 0, n)
                }
                buf.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()

    /** Overwrite each file in cacheDir/[name] with random bytes (bounded) before unlinking. IO-thread only. */
    private fun secureWipeCacheDir(name: String) {
        val dir = java.io.File(appCtx.cacheDir, name)
        val files = dir.listFiles() ?: return
        val rnd = java.security.SecureRandom()
        for (f in files) {
            runCatching {
                if (f.isFile && f.length() in 1..(8L * 1024 * 1024)) {
                    val buf = ByteArray(f.length().toInt()); rnd.nextBytes(buf)
                    java.io.RandomAccessFile(f, "rw").use { it.write(buf); it.fd.sync() }
                }
            }
            f.delete()
        }
    }

    /** Decrypt a vaulted note's body for display/editing; returns the body unchanged when not vaulted or
     *  when locked and un-unlockable (never throws). */
    fun decryptNoteBody(n: com.todocompanion.app.data.entity.NoteEntity): String {
        val p = vaultPass
        return if (n.vault && com.todocompanion.app.domain.NoteVault.isLocked(n.body) && p != null)
            com.todocompanion.app.domain.NoteVault.unlock(n.body, p) ?: n.body else n.body
    }

    /** Encrypt a note's plaintext body if it is vaulted and unlocked; leaves an already-encrypted body
     *  untouched (so a list-level toggle on a locked note never double-encrypts). */
    private fun sealForVault(n: com.todocompanion.app.data.entity.NoteEntity): com.todocompanion.app.data.entity.NoteEntity {
        val p = vaultPass
        return if (n.vault && p != null && !com.todocompanion.app.domain.NoteVault.isLocked(n.body))
            n.copy(body = com.todocompanion.app.domain.NoteVault.lock(n.body, p)) else n
    }

    /**
     * R43 — save a full life-event / occasion (birthday, anniversary, memorial, name day, holiday or a
     * plain countdown) and keep its optional "prepare" task in sync: when prepLeadDays > 0 we create or
     * re-date a task in the Inbox that falls that many days before the next occurrence — the one thing a
     * single-purpose reminder app can't do, because it doesn't own the task list. Fully offline.
     */
    /** R45 — save a fully-built occasion row and keep its auto "prepare" task in sync. */
    fun saveOccasionRow(input: com.todocompanion.app.data.entity.CountdownEntity) = viewModelScope.launch {
        var row = input.copy(
            title = input.title.trim().ifBlank { com.todocompanion.app.domain.LifeEvent.EventType.from(input.eventType).label },
            personName = input.personName.trim(), notes = input.notes.trim(),
        )
        val prepLeadDays = row.prepLeadDays
        // Keep the auto "prepare" task in step with the occasion.
        val next = com.todocompanion.app.domain.LifeEvent.nextOccurrence(row)
        if (prepLeadDays > 0) {
            val dueDay = next.minusDays(prepLeadDays.toLong())
            val dueMillis = dueDay.atStartOfDay(zone).toInstant().toEpochMilli()
            val who = row.personName.ifBlank { row.title }
            val t = com.todocompanion.app.domain.LifeEvent.type(row)
            val prepTitle = when (t) {
                com.todocompanion.app.domain.LifeEvent.EventType.BIRTHDAY -> "🎁 Gift for $who — birthday"
                com.todocompanion.app.domain.LifeEvent.EventType.ANNIVERSARY -> "🎁 Plan for $who — anniversary"
                com.todocompanion.app.domain.LifeEvent.EventType.MEMORIAL -> "🕯️ Remember $who"
                else -> "Prepare for ${row.title}"
            }
            val existingTask = row.prepTaskId?.let { repo.getTask(it) }
            if (existingTask != null && !existingTask.trashed) {
                repo.saveTask(existingTask.copy(title = prepTitle, dueDate = dueMillis, completed = false, completedAt = null))
            } else {
                // #11/#16 — turn the milestone into a real plan: seed a checklist (and last year's gift, so you
                // don't repeat it) as the task's notes, once at creation so later edits are never clobbered.
                val lastGift = com.todocompanion.app.domain.Moments.lastGift(row)
                val prepNote = buildString {
                    appendLine("Prep checklist:")
                    appendLine("• Idea / gift")
                    appendLine("• Card or message")
                    appendLine("• Plan the day / budget")
                    lastGift?.let { appendLine("Last year you gave: ${it.second} (${it.first.year}) — pick something new.") }
                }.trim()
                val newId = repo.createTask(listId = com.todocompanion.app.data.entity.ListEntity.INBOX_ID, title = prepTitle, dueDate = dueMillis)
                repo.getTask(newId)?.let { repo.saveTask(it.copy(note = prepNote)) }
                row = row.copy(prepTaskId = newId)
            }
        } else if (row.prepTaskId != null) {
            // Prep turned off — trash the auto task so it doesn't linger, and forget the link.
            row.prepTaskId?.let { pid -> repo.getTask(pid)?.let { repo.saveTask(it.copy(trashed = true, trashedAt = System.currentTimeMillis())) } }
            row = row.copy(prepTaskId = null)
        }
        repo.upsertCountdown(row)
        com.todocompanion.app.widget.CountdownWidget.refresh(appCtx)
    }

    fun deleteCountdown(id: String) = viewModelScope.launch {
        // Also clean up the occasion's auto "prepare" task, if any.
        countdowns.value.firstOrNull { it.id == id }?.prepTaskId?.let { pid ->
            repo.getTask(pid)?.let { repo.saveTask(it.copy(trashed = true, trashedAt = System.currentTimeMillis())) }
        }
        repo.deleteCountdown(id); com.todocompanion.app.widget.CountdownWidget.refresh(appCtx)
    }
    fun toggleCountdownPin(c: com.todocompanion.app.data.entity.CountdownEntity) = viewModelScope.launch { repo.upsertCountdown(c.copy(pinned = !c.pinned)); com.todocompanion.app.widget.CountdownWidget.refresh(appCtx) }
    fun toggleOccasionFavorite(c: com.todocompanion.app.data.entity.CountdownEntity) = viewModelScope.launch { repo.upsertCountdown(c.copy(favorite = !c.favorite)) }
    fun setOccasionArchived(c: com.todocompanion.app.data.entity.CountdownEntity, archived: Boolean) = viewModelScope.launch { repo.upsertCountdown(c.copy(archived = archived)) }
    /** R46 — relationship loop / know-them deck: log a moment (or a question's answer) against an occasion.
     *  Stored as JSON on the row, so it rides the existing backup/sync; refreshes the live-countdown notif. */
    fun logOccasionMoment(c: com.todocompanion.app.data.entity.CountdownEntity, note: String) = viewModelScope.launch {
        if (note.isBlank()) return@launch
        repo.upsertCountdown(c.copy(momentsJson = com.todocompanion.app.domain.Moments.add(c, note)))
        refreshOccasionNotification()
    }
    fun removeOccasionMoment(c: com.todocompanion.app.data.entity.CountdownEntity, moment: com.todocompanion.app.domain.Moment) = viewModelScope.launch {
        repo.upsertCountdown(c.copy(momentsJson = com.todocompanion.app.domain.Moments.remove(c, moment)))
    }
    /** #9 — refresh (or clear) the ongoing "next occasion" notification from the current data + setting.
     *  Posted on demand (app open, occasion saved) rather than by a background worker — no new permission. */
    fun refreshOccasionNotification() = viewModelScope.launch {
        val on = settings.value.occasionLiveNotif
        com.todocompanion.app.reminders.Notifications.refreshOccasion(appCtx, if (on) countdowns.value else emptyList())
    }
    /** R55 — (re)schedule or cancel the daily-reflection nudge to match the current setting + hour. Call
     *  it whenever the Daily-reflection toggle or its hour changes (the toggle previously did neither). */
    fun applyOccasionNudge() = viewModelScope.launch {
        val s = settings.value
        if (s.occasionNudge) com.todocompanion.app.reminders.AlarmScheduler.scheduleOccasionNudge(appCtx, s.occasionNudgeHour)
        else com.todocompanion.app.reminders.AlarmScheduler.cancelOccasionNudge(appCtx)
    }

    // ---- R47 "next frontier" read models ----------------------------------------------------------
    // R78 — the pure computations now live in domain/LifeReadModels (independently unit-tested); these
    // accessors just pass in the current `.value` snapshots, so behaviour is unchanged.
    fun trackedHoursThisYear(today: java.time.LocalDate = java.time.LocalDate.now(zone)): Int =
        com.todocompanion.app.domain.LifeReadModels.trackedHoursThisYear(timeVm.timeEntries.value, today, zone)

    fun weekDigest(today: java.time.LocalDate = java.time.LocalDate.now(zone)): com.todocompanion.app.domain.LifeReadModels.WeekDigest =
        com.todocompanion.app.domain.LifeReadModels.weekDigest(countdowns.value, allTasksLive.value, habits.value, today, zone)

    fun driftPeople(today: java.time.LocalDate = java.time.LocalDate.now(zone)): List<com.todocompanion.app.data.entity.CountdownEntity> =
        com.todocompanion.app.domain.LifeReadModels.driftPeople(countdowns.value, today)

    fun achievementAnniversaries(today: java.time.LocalDate = java.time.LocalDate.now(zone)): List<Pair<Int, TaskEntity>> =
        com.todocompanion.app.domain.LifeReadModels.achievementAnniversaries(allTasksLive.value, today, zone)

    fun yearInPeople(today: java.time.LocalDate = java.time.LocalDate.now(zone)): com.todocompanion.app.domain.LifeReadModels.YearInPeople =
        com.todocompanion.app.domain.LifeReadModels.yearInPeople(countdowns.value, today)

    /** #27 — log a gift against an occasion (rides the moments store). */
    fun logOccasionGift(c: com.todocompanion.app.data.entity.CountdownEntity, gift: String) = viewModelScope.launch {
        if (gift.isBlank()) return@launch
        repo.upsertCountdown(c.copy(momentsJson = com.todocompanion.app.domain.Moments.addGift(c, gift)))
    }

    fun lifeChapters(): List<com.todocompanion.app.domain.LifeReadModels.Chapter> =
        com.todocompanion.app.domain.LifeReadModels.lifeChapters(allTasksLive.value, zone)

    fun onThisDay(today: java.time.LocalDate = java.time.LocalDate.now(zone)): List<Pair<Int, TaskEntity>> =
        com.todocompanion.app.domain.LifeReadModels.onThisDay(allTasksLive.value, today, zone)
    val filters = combine(repo.allFilters, activeWs) { f, ws -> f.filter { it.workspaceId == ws } }.state(emptyList())
    val habits = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && !it.archived && !it.trashed } }.state(emptyList())
    /** Active-workspace habits INCLUDING archived (but never trashed) — for surfaces that must tell
     *  "archived" apart from "deleted" (e.g. a goal's lead-measure hint) and for the Archived view. */
    val habitsWithArchived = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && !it.trashed } }.state(emptyList())
    /** Trashed habits in the active workspace, newest-deleted first — the source for the habits Trash. */
    val trashedHabits = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && it.trashed }.sortedByDescending { it.trashedAt ?: 0L } }.state(emptyList())

    // W3 (cross-module) — ONE read-model over every domain's reminders through the shared UnifiedReminder
    // lens (task ReminderEntity, habit/event CSV, note fields, routine JSON-int, occasion lead-days), so any
    // surface can list/count "all reminders" uniformly instead of re-parsing five different storage shapes.
    // The mapping is pure (UnifiedReminders, covered by UnifiedReminderTest); unifying the STORAGE into one
    // table is a separate, migration-bearing step. Declared after `habits` so all five source flows exist.
    // W3 — routines now come from their Room table (repo.observeRoutines()) instead of the frozen routinesJson.
    val allReminders: StateFlow<List<com.todocompanion.app.domain.reminders.UnifiedReminder>> =
        combine(habits, events, notes, countdowns, repo.observeRoutines()) { hs, es, ns, cs, rs ->
            val R = com.todocompanion.app.domain.reminders.UnifiedReminders
            R.ordered(
                hs.flatMap { R.fromHabit(it.id, it.name, it.reminderTimes) },
                es.flatMap { R.fromEvent(it.id, it.title, it.alertsMinutes) },
                ns.flatMap { R.fromNote(it.id, it.title, it.reminderAt, it.reminderExtra, it.reminderRrule) },
                cs.flatMap { R.fromOccasion(it.id, it.title, it.prepLeadDays, it.keepInTouchDays) },
                rs.flatMap { R.fromRoutine(it.id, it.name, it.whenReminderMin) },
            )
        }.state(emptyList())
    val habitCheckins = repo.allCheckins.state(emptyList())
    val focusSessions = repo.allFocusSessions.scopedBy { it.workspaceId }
    // R37 · Port 5 — the receptive hour (0..23) learned from when you actually finish habits & tasks, or
    // null when there isn't enough signal / the setting is off. Feeds the daily-brief scheduler.
    // R64 — reads the active workspace's habits & tasks (habitCheckins/tasks are scoped), not every space's.
    val receptiveHour: StateFlow<Int?> = combine(habitCheckins, tasks, settings) { c, t, s ->
        if (!s.receptivityTiming) null
        else {
            // A quit habit's slip must not count as a positive check-in when learning the receptive hour.
            val byId = habits.value.associateBy { it.id }
            val ok = c.filter { ck -> byId[ck.habitId]?.let { com.todocompanion.app.domain.habit.HabitStats.isSuccessDay(it, ck) } == true }
            com.todocompanion.app.domain.habit.FourthWave.receptivity(ok, t, zone)?.let { (it.bestBucket * 3 + 1).coerceIn(0, 23) }
        }
    }.state(null)
    // Tier S: time tracking.
    // (time flows moved to TimeTrackingViewModel — read them via `timeVm.timeActivities` / `timeVm.timeEntries`)
    val taskTags = repo.taskTagRefs.state(emptyList())
    val taskContexts = repo.taskContextRefs.state(emptyList())
    val checklist = repo.allChecklist.state(emptyList())
    val reminders = repo.allReminders.state(emptyList())
    val dependencies = repo.allDependencies.state(emptyList())

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

    // Honour the configured time zone (Settings) for all date math; fall back to the device zone.
    private val zone: ZoneId get() = settings.value.timeZone.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    /** Public read-only view of the configured zone, so calendar UI expands occurrences consistently. */
    val zoneId: ZoneId get() = zone

    /** The single source of "today" for habit/routine check-in day math. UI screens MUST use this rather
     *  than a bare LocalDate.now(), so the grid and the VM's award/stamp logic agree in the configured
     *  zone — otherwise a manual timezone or midnight crossing yields off-by-one check-ins. */
    fun today(): Long = java.time.LocalDate.now(zone).toEpochDay()

    /** "Day starts at" rollover, in minutes past midnight, for Today/Tomorrow/overdue math. */
    private val dayStartMin: Int get() = settings.value.dayStartMinuteOfDay()

    /** Live task count per smart list, for the drawer. The pure math lives in domain/SmartCounts; the
     *  VM keeps the reactive combine (and computes the settings-derived priority config to pass in). */
    val smartCounts: StateFlow<Map<SmartKind, Int>> =
        combine(
            combine(wsTasks, inboxTasksAll, repo.allTasks) { ws, inbox, allT -> Triple(ws, inbox, allT) }, repo.allDependencies, settings,
            combine(repo.taskContextRefs, repo.allContexts) { r, c -> r to c },
            combine(repo.allLists, repo.allFolders) { l, f -> l to f },
        ) { tTriple, deps, set, rc, lf ->
            // Archived/trashed-container tasks are hidden from the badges, matching the rendered lists.
            val (hiddenListIds, hiddenFolderIds) = com.todocompanion.app.domain.view.ListPipeline.hiddenContainers(lf.first, lf.second)
            com.todocompanion.app.domain.SmartCounts.compute(
                wsTasks = tTriple.first, inbox = tTriple.second, deps = deps, prioCfg = set.priorityConfig(),
                tcRefs = rc.first, ctxs = rc.second, activeWorkspaceId = set.activeWorkspaceId,
                zone = zone, dayStartMin = dayStartMin, now = System.currentTimeMillis(),
                hiddenListIds = hiddenListIds, hiddenFolderIds = hiddenFolderIds, allTasks = tTriple.third,
            )
        }.state(emptyMap())

    /** Live entry counts for the drawer, mirroring each view's own filter. The pure math lives in
     *  domain/EntryCounts; the VM keeps the reactive combine and the "show counts" gate (so the
     *  default drawer stays cheap). */
    val entryCounts: StateFlow<com.todocompanion.app.domain.EntryCounts.Result> =
        combine(
            wsTasks,
            combine(repo.taskTagRefs, repo.taskContextRefs) { tt, tc -> tt to tc },
            combine(repo.allLists, repo.allFolders) { l, f -> l to f },
            combine(repo.allTags, repo.allContexts, repo.allFilters) { t, c, fi -> Triple(t, c, fi) },
            settings,
        ) { all, refs, lf, tcf, set ->
            if (!set.showEntryCounts) return@combine com.todocompanion.app.domain.EntryCounts.Result()
            val (ttRefs, tcRefs) = refs
            val (lists, folders) = lf
            val (allTagsL, _, filtersL) = tcf
            com.todocompanion.app.domain.EntryCounts.compute(
                all = all, tagRefs = ttRefs, ctxRefs = tcRefs, lists = lists, folders = folders,
                tags = allTagsL, filters = filtersL, zone = zone, now = System.currentTimeMillis(),
            )
        }.state(com.todocompanion.app.domain.EntryCounts.Result())

    // P1: reliability (0–100) for every recurring task, from the completion activity trail.
    val taskReliability: StateFlow<Map<String, com.todocompanion.app.domain.task.TaskReliability.Reliability>> =
        combine(wsTasks, repo.allActivity) { t, acts ->
            val now = System.currentTimeMillis()
            t.filter { !it.rrule.isNullOrBlank() && !it.trashed }
                .mapNotNull { task -> com.todocompanion.app.domain.task.TaskReliability.score(task, acts, now, zone)?.let { task.id to it } }
                .toMap()
        }.state(emptyMap())

    /** "I have N minutes" planner: when set, Do-Next hides tasks whose estimate exceeds N. null = off. */
    val timeAvailableMin = MutableStateFlow<Int?>(null)

    /** A counter the shared scaffold's FAB bumps to ask the Time tab to add a new entry — so the Time
     *  screen keeps its own dialog logic while its add button lives with every other tab's FAB. */
    val addTimeEntryRequests = MutableStateFlow(0)

    /** "Right now I have X energy" planner: when set (1/2/3), Do-Next keeps tasks needing at most that
     *  much energy (plus untagged). null = off. */
    val energyAvailable = MutableStateFlow<Int?>(null)

    /** All ids in the subtree rooted at [rootId] — root plus every descendant. Cycle-safe. Delegates to
     *  the pure ListPipeline copy (shared with the extracted rendering pipeline). */
    private fun <T> subtreeIds(rootId: String, entities: List<T>, idOf: (T) -> String, parentOf: (T) -> String?): Set<String> =
        ListPipeline.subtreeIds(rootId, entities, idOf, parentOf)

    /** All list ids inside a folder, including nested folders and nested lists. */
    private fun folderListIds(folderId: String, lists: List<ListEntity>, folders: List<FolderEntity>): Set<String> =
        com.todocompanion.app.domain.EntryCounts.folderListIds(folderId, lists, folders)

    private fun AppSettings.priorityConfig() = PriorityEngine.Config(
        mode = when (priorityMode) { "importance" -> PriorityEngine.Mode.IMPORTANCE; "urgency" -> PriorityEngine.Mode.URGENCY; else -> PriorityEngine.Mode.BOTH },
        dueWeight = priorityDueWeight, startWeight = priorityStartWeight, goalWeight = priorityGoalWeight, overdueBoost = priorityOverdueBoost,
        starBoost = priorityStarBoost, curveBase = priorityCurveBase, computed = priorityComputed,
    )

    val groups: StateFlow<List<TaskGroup>> =
        combine(
            // wsTasks + shared Inbox + the FULL task set (used only to resolve dependency prerequisites that
            // may live outside the workspace-scoped render set — see ListPipeline.compute's allTasks param).
            combine(wsTasks, inboxTasksAll, repo.allTasks) { ws, inbox, allT -> Triple(ws, inbox, allT) },
            combine(currentView, groupMode, sortMode, settings, combine(repo.allFlags, timeAvailableMin, energyAvailable) { fl, ta, ea -> Triple(fl, ta, ea) }) { v, g, s, set, fte -> ListPipeline.Cfg(v, g, s, set.priorityConfig(), fte.first, fte.second, fte.third, set.activeWorkspaceId) },
            repo.taskTagRefs,
            combine(repo.taskContextRefs, repo.allContexts, repo.allFilters, repo.allLists, combine(repo.allFolders, repo.allTags) { fo, tg -> fo to tg }) { r, c, f, l, foTg -> ListPipeline.ViewCtx(r, c, f, l, foTg.first, foTg.second) },
            repo.allDependencies,
        ) { wsTriple, cfg, ttRefs, vc, deps ->
            ListPipeline.compute(wsTriple.first, wsTriple.second, cfg, ttRefs, vc, deps, zone, dayStartMin, System.currentTimeMillis(), wsTriple.third)
        }.state(emptyList())

    /** When set, the outline is zoomed into this task's subtree (MLO-style focus). */
    val outlineZoom = MutableStateFlow<String?>(null)
    fun zoomInto(taskId: String?) { outlineZoom.value = taskId }

    val outlineRows: StateFlow<List<OutlineRow>> =
        combine(wsTasks, currentView, outlineZoom) { all, v, zoom ->
            val listId = (v as? ViewRef.ListView)?.listId ?: return@combine emptyList()
            val listTasks = all.filter { it.listId == listId && !it.trashed }
            // Zoom only holds while its task still exists in this list.
            val start = zoom?.takeIf { z -> listTasks.any { it.id == z } }
            buildOutline(listTasks, start)
        }.state(emptyList())

    /** Title of the current zoom root, for the breadcrumb, or null when not zoomed. */
    fun zoomTitle(): String? = outlineZoom.value?.let { z -> tasks.value.firstOrNull { it.id == z }?.title }

    /** True when the current view can render as a hierarchy-preserving filter (filter/tag/context). */
    fun canHierarchy(): Boolean = currentView.value.let { it is ViewRef.FilterView || it is ViewRef.TagView || it is ViewRef.ContextView }

    /**
     * MLO "outline filtering": the matched tasks of a filter/tag/context view rendered in their real
     * tree position — matches solid, structural ancestors dimmed. Empty unless [filterHierarchy] is on.
     */
    val hierarchyRows: StateFlow<List<OutlineRow>> =
        combine(
            wsTasks, currentView, filterHierarchy,
            combine(repo.taskTagRefs, repo.taskContextRefs, repo.allFilters, repo.allLists, combine(repo.allTags, repo.allContexts) { tg, cx -> tg to cx }) { tt, tc, f, ls, tgcx -> listOf(tt, tc, f, ls, tgcx.first, tgcx.second) },
        ) { all, v, on, refs ->
            if (!on) return@combine emptyList()
            @Suppress("UNCHECKED_CAST")
            val ttRefs = refs[0] as List<com.todocompanion.app.data.entity.TaskTagCrossRef>
            @Suppress("UNCHECKED_CAST")
            val tcRefs = refs[1] as List<com.todocompanion.app.data.entity.TaskContextCrossRef>
            @Suppress("UNCHECKED_CAST")
            val filters = refs[2] as List<com.todocompanion.app.data.entity.FilterEntity>
            @Suppress("UNCHECKED_CAST")
            val hLists = refs[3] as List<ListEntity>
            @Suppress("UNCHECKED_CAST")
            val hTags = refs[4] as List<TagEntity>
            @Suppress("UNCHECKED_CAST")
            val hContexts = refs[5] as List<ContextEntity>
            val listFolderById = hLists.associate { it.id to it.folderId }
            val now = System.currentTimeMillis()
            val matched: Set<String> = when (v) {
                is ViewRef.FilterView -> {
                    val q = com.todocompanion.app.domain.view.Filters.parse(filters.firstOrNull { it.id == v.filterId }?.queryJson)
                    val tagsByTask = ttRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
                    val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
                    val hit = all.filter { com.todocompanion.app.domain.view.Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, zone, it.folderId ?: listFolderById[it.listId]) }.map { it.id }.toSet()
                    if (q.includeChildren) expandWithDescendants(hit, all) else hit
                }
                is ViewRef.TagView -> {
                    val tagIds = subtreeIds(v.tagId, hTags, { it.id }, { it.parentId })
                    ttRefs.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
                }
                is ViewRef.ContextView -> {
                    val ctxIds = subtreeIds(v.contextId, hContexts, { it.id }, { it.parentId })
                    tcRefs.filter { it.contextId in ctxIds }.map { it.taskId }.toSet()
                }
                else -> return@combine emptyList()
            }
            buildFilteredOutline(all.filter { !it.trashed }, matched)
        }.state(emptyList())

    private fun rankDoNext(
        base: List<TaskEntity>, all: List<TaskEntity>, now: Long, cfg: PriorityEngine.Config,
        deps: List<DependencyEntity>, tcRefs: List<com.todocompanion.app.data.entity.TaskContextCrossRef>, ctxs: List<ContextEntity>,
    ): List<TaskEntity> = com.todocompanion.app.domain.DoNext.rank(base, all, now, cfg, deps, tcRefs, ctxs, zone)

    /** The full Do-Next ranking (priority + due urgency + dependency propagation + context
     *  open-hours availability), most-actionable first. */
    private fun doNextRanked(): List<TaskEntity> {
        val now = System.currentTimeMillis()
        val all = tasks.value
        val base = TaskViews.filterSmart(all, SmartKind.DO_NEXT, now, zone, dayStartMin)
        return rankDoNext(base, all, now, settings.value.priorityConfig(), dependencies.value, taskContexts.value, contexts.value)
    }

    /** The single best task to do right now. Powers "Suggest a task" in Focus. */
    fun topDoNext(): TaskEntity? = doNextRanked().firstOrNull()

    /** Ranked backlog candidates for "pick N for today": actionable, not already due today or earlier. */
    fun pickTodayCandidates(limit: Int = 20): List<TaskEntity> {
        val zone = this.zone
        val today = java.time.LocalDate.now(zone)
        return doNextRanked().filter { t ->
            t.dueDate == null || java.time.Instant.ofEpochMilli(t.dueDate!!).atZone(zone).toLocalDate().isAfter(today)
        }.take(limit)
    }
    /** Commit a task to today (due today, 9am) — the pick-N-today action. */
    fun commitToToday(t: TaskEntity) = viewModelScope.launch {
        val due = java.time.LocalDate.now(zone).atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()
        repo.saveTask(t.copy(dueDate = due))
    }
    /** Place an existing task onto the calendar at [atMillis] as a timed block (G4). */
    fun scheduleTaskAt(taskId: String, atMillis: Long, durationMin: Int? = null) = viewModelScope.launch {
        val t = repo.getTask(taskId) ?: return@launch
        val dur = durationMin ?: t.durationMin ?: t.estimateMin ?: t.estimateMax ?: 60
        repo.saveTask(t.copy(dueDate = atMillis, isAllDay = false, durationMin = dur))
    }
    /** Ranked unscheduled candidates for dropping onto the calendar. */
    fun unscheduledForBlocking(limit: Int = 12): List<TaskEntity> =
        doNextRanked().filter { it.dueDate == null }.take(limit)

    /**
     * Lay today's actionable Do-Next tasks onto the timeline between the configured working hours,
     * packing each by its estimate (default 30 min) in ranked order. Undated and today/overdue tasks
     * are candidates; future-dated ones are left alone. Reports (scheduled, didn't-fit). Fully offline.
     */
    /** Deadline-risk radar (G3): does the week's committed work fit the time you actually have? */
    data class DeadlineRisk(val neededH: Double, val freeH: Double, val atRisk: Int, val days: Int) {
        val overCommitted get() = neededH > freeH
    }
    fun deadlineRisk(days: Int = 7): DeadlineRisk {
        val now = System.currentTimeMillis()
        val end = java.time.LocalDate.now(zone).plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        fun est(t: TaskEntity) = (t.estimateMin ?: t.estimateMax ?: t.durationMin ?: 30)
        val open = tasks.value.filter { !it.trashed && !it.completed && !it.abandoned && !it.someday && !it.isNote }
        // A task "has a deadline in the window" if its hard deadline OR its due date falls inside it.
        fun deadlineIn(t: TaskEntity): Boolean {
            val d = listOfNotNull(t.deadlineDate, t.dueDate).minOrNull() ?: return false
            return d in (now + 1)..end
        }
        val atRisk = open.filter { deadlineIn(it) }
        val neededMin = atRisk.sumOf { est(it) }
        // Free time = the window's capacity minus other planned (dated, non-deadline) work in it.
        val otherPlannedMin = open.filter { !deadlineIn(it) && it.dueDate != null && it.dueDate!! in (now + 1)..end }.sumOf { est(it) }
        // Sum each upcoming day's capacity (honours per-weekday overrides when set).
        // X3: if "honest capacity" is on and there's enough tracked signal, use your real median
        // focus-hours/day instead of the assumed figure — planning against reality, not optimism.
        val today = java.time.LocalDate.now(zone)
        val trackedCapH = if (settings.value.honestCapacity) trackedCapacityHours() else null
        val capacityMin = if (trackedCapH != null) trackedCapH * 60 * days
            else (0 until days).sumOf { settings.value.capacityMinutesFor(today.plusDays(it.toLong()).dayOfWeek) }
        // Habits consume time too — subtract the pending habit reserve so "free" isn't overstated.
        val habitReserve = habitReserveForWindow(today.toEpochDay(), days)
        val freeMin = (capacityMin - otherPlannedMin - habitReserve).coerceAtLeast(0)
        return DeadlineRisk(neededMin / 60.0, freeMin / 60.0, atRisk.size, days)
    }

    /** The hour of day you most often finish things (learned from completion history) — your peak.
     *  Falls back to the configured work-start hour when there's not enough history. */
    fun peakHour(): Int {
        val hist = IntArray(24)
        tasks.value.forEach { t -> t.completedAt?.let { hist[java.time.Instant.ofEpochMilli(it).atZone(zone).hour]++ } }
        val total = hist.sum()
        if (total < 8) return settings.value.workStartHour.coerceIn(0, 23)  // too little signal
        return hist.indices.maxByOrNull { hist[it] } ?: settings.value.workStartHour
    }

    /** F4: one proposed placement — a task and the time the auto-scheduler would move it to. */
    data class ScheduleProposal(val task: TaskEntity, val newDueMillis: Long, val durationMin: Int)
    data class SchedulePlan(val proposals: List<ScheduleProposal>, val didNotFit: Int)

    /**
     * F4: compute the auto-schedule WITHOUT writing anything, so the user can preview, edit and
     * confirm. Same rhythm-aware placement as before; the caller applies the accepted subset.
     */
    fun computeAutoSchedule(): SchedulePlan {
        val s = settings.value
        val startHour = s.workStartHour.coerceIn(0, 23)
        val endHour = s.workEndHour.coerceIn(startHour + 1, 24)
        val dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone)
        // Keep the day's habit time out of the packing window, so Plan-my-day doesn't over-fill it.
        val habitReserveToday = habitReserveForWindow(java.time.LocalDate.now(zone).toEpochDay(), 1)
        val windowEnd = dayStart.plusHours(endHour.toLong()).minusMinutes(habitReserveToday.toLong())
        // Rhythm-aware start: begin at your learned peak hour when it sits inside the work window.
        val peak = peakHour().coerceIn(startHour, endHour - 1)
        var cursor = dayStart.plusHours(peak.toLong())
        val now = System.currentTimeMillis()
        if (cursor.toInstant().toEpochMilli() < now) {
            val nowZ = java.time.Instant.ofEpochMilli(now).atZone(zone).withSecond(0).withNano(0)
            cursor = nowZ.withMinute(0).plusMinutes((((nowZ.minute) / 15) + 1) * 15L)
        }
        val todayDate = java.time.LocalDate.now(zone)
        val ranked = doNextRanked().filter { t ->
            t.dueDate == null || java.time.Instant.ofEpochMilli(t.dueDate!!).atZone(zone).toLocalDate() <= todayDate
        }
        val candidates = ranked.filter { (it.energy ?: 0) >= 3 } + ranked.filter { (it.energy ?: 0) < 3 }
        val proposals = ArrayList<ScheduleProposal>()
        var skipped = 0
        for (t in candidates) {
            val dur = (t.estimateMin ?: t.estimateMax ?: t.durationMin ?: 30).coerceIn(10, 480)
            val end = cursor.plusMinutes(dur.toLong())
            if (end.isAfter(windowEnd)) { skipped++; continue }
            proposals += ScheduleProposal(t, cursor.toInstant().toEpochMilli(), dur)
            cursor = end
        }
        return SchedulePlan(proposals, skipped)
    }

    /** F4: apply only the proposals the user kept, in order. */
    fun applyAutoSchedule(accepted: List<ScheduleProposal>, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        accepted.forEach { p -> repo.saveTask(p.task.copy(dueDate = p.newDueMillis, isAllDay = false)) }
        onDone(accepted.size)
    }

    /** P2: open tasks whose due date has already passed — the recovery-mode signal. */
    fun overdueOpenTasks(): List<TaskEntity> {
        val startToday = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return tasks.value.filter { !it.completed && !it.trashed && !it.abandoned && !it.someday && it.dueDate != null && it.dueDate!! < startToday }
    }

    /**
     * P2 — recovery mode: bulk-move every overdue task to today or tomorrow, keeping its time of day.
     * The kind way out of a wall-of-red pileup, borrowed from the habit recovery mode.
     */
    fun rescheduleOverdue(toTomorrow: Boolean, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val target = java.time.LocalDate.now(zone).plusDays(if (toTomorrow) 1 else 0)
        val overdue = overdueOpenTasks()
        overdue.forEach { t ->
            val oldTime = java.time.Instant.ofEpochMilli(t.dueDate!!).atZone(zone).toLocalTime()
            val newDue = target.atTime(oldTime).atZone(zone).toInstant().toEpochMilli()
            repo.saveTask(t.copy(dueDate = newDue))
        }
        onDone(overdue.size)
    }

    fun observeTask(id: String): Flow<TaskEntity?> = repo.observeTask(id)

    /** The private, on-device activity trail for one task (created / completed / rescheduled …). */
    fun taskActivity(id: String): Flow<List<com.todocompanion.app.data.entity.ActivityEntity>> = repo.taskActivity(id)
    // R23: delete a single activity-log entry, or clear a task's whole history. Independent log rows, so
    // there's no cascade; a recurring task's reliability just recomputes from the remaining completions.
    fun deleteActivityEntry(id: String) = viewModelScope.launch { repo.deleteActivity(id) }
    fun clearTaskActivity(taskId: String) = viewModelScope.launch { repo.clearTaskActivity(taskId) }
    fun taskRevisions(id: String): Flow<List<com.todocompanion.app.data.entity.TaskRevisionEntity>> = repo.taskRevisions(id)
    fun restoreRevision(revisionId: String) = viewModelScope.launch { repo.restoreRevision(revisionId) }
    /** How many times each task has been rescheduled — the procrastination signal (E2). */
    suspend fun rescheduleCounts(): Map<String, Int> =
        repo.getActivitiesOnce().filter { it.type == "rescheduled" }.groupingBy { it.taskId }.eachCount()

    // ---------- navigation ----------
    fun select(view: ViewRef) {
        currentView.value = view
        groupMode.value = if (view is ViewRef.ListView) GroupMode.NONE else GroupMode.DATE
        // R28 #2 — the Completed / Won't-Do views open sorted by when things were finished (newest first).
        val doneKind = (view as? ViewRef.Smart)?.kind.let { it == SmartKind.COMPLETED || it == SmartKind.WONT_DO }
        if (doneKind) sortMode.value = SortMode.COMPLETED
        else if (sortMode.value == SortMode.COMPLETED) sortMode.value = SortMode.MANUAL
        // Seed outline mode from the list's own persisted viewMode so a list remembers nested vs flat.
        outlineMode.value = (view as? ViewRef.ListView)?.let { lv -> lists.value.firstOrNull { it.id == lv.listId }?.viewMode == "outline" } ?: false
        // Remember the last place, when the user opted into resuming there.
        val s = settings.value
        if (s.resumeLastView) viewModelScope.launch { repo.saveSettings(repo.settingsSnapshot().copy(lastViewRef = com.todocompanion.app.domain.view.ViewTabs.refOf(view))) }
    }

    /** Flip the current list's outline (nested) vs flat view and persist it on the ListEntity so it sticks. */
    fun toggleOutline() {
        val on = !outlineMode.value
        outlineMode.value = on
        (currentView.value as? ViewRef.ListView)?.let { lv ->
            viewModelScope.launch { lists.value.firstOrNull { it.id == lv.listId }?.let { repo.saveList(it.copy(viewMode = if (on) "outline" else "list")) } }
        }
    }

    /** On launch, open the resume-last view (if enabled) or the configured default view. */
    init {
        // SEC (privacy hardening) — the rich-note WebView needs decrypted image copies on disk to render
        // (it can't read FileVault ciphertext or a KeyStore key). Those plaintext copies live in the
        // app-private cache; bound their lifetime to a single process by wiping them on every cold start,
        // so a decrypted image never survives a reboot or lingers after the app is killed.
        viewModelScope.launch(Dispatchers.IO) { runCatching { purgeRichImgCache() }; runCatching { purgeSharedCache() } }
        // N1 — give the repo a Context-free way to cancel a reminder's alarm on permanent delete.
        repo.onCancelReminder = { reminderId -> com.todocompanion.app.reminders.AlarmScheduler.cancelById(appCtx, reminderId) }
        viewModelScope.launch {
            val s = repo.settingsSnapshot()
            val ref = if (s.resumeLastView && s.lastViewRef.isNotBlank()) s.lastViewRef else s.defaultViewRef.ifBlank { null }
            if (ref != null) com.todocompanion.app.domain.view.ViewTabs.viewOf(ref)?.let { v ->
                currentView.value = v
                groupMode.value = if (v is ViewRef.ListView) GroupMode.NONE else GroupMode.DATE
            }
        }
        // A habit auto-credited by a finished timer/Focus session celebrates like a manual tap.
        viewModelScope.launch {
            repo.habitCredited.collect { ev ->
                habits.value.firstOrNull { it.id == ev.habitId }?.let { h ->
                    celebrateIfRewardReached(h, ev.epochDay); awardIfNewlyDone(h, ev.epochDay, ev.oldCount)
                }
            }
        }
        // F1 (task-editor audit) — the one place task reminders are re-armed. Every repository write that
        // moves a date or flips a scheduling gate (completed/abandoned/trashed/someday) emits the task id on
        // repo.remindersDirty; we drain it here into ReminderController.rescheduleForTask. This makes the
        // re-arm guarantee hold by construction, so no individual date-mutating call site (postpone, snooze,
        // calendar drag, carry-forward, auto-schedule, Someday, recurrence roll-forward, …) has to remember.
        viewModelScope.launch {
            repo.remindersDirty.collect { taskId ->
                repo.getTask(taskId)?.let { reminderCtl.rescheduleForTask(it) }
            }
        }
    }

    fun currentTitle(): String = when (val v = currentView.value) {
        is ViewRef.Smart -> com.todocompanion.app.domain.smartTitle(settings.value, v.kind)
        is ViewRef.ListView -> lists.value.firstOrNull { it.id == v.listId }?.name ?: "List"
        is ViewRef.FolderView -> folders.value.firstOrNull { it.id == v.folderId }?.name ?: "Folder"
        is ViewRef.TagView -> "#" + (tags.value.firstOrNull { it.id == v.tagId }?.name ?: "tag")
        is ViewRef.ContextView -> "@" + (contexts.value.firstOrNull { it.id == v.contextId }?.name ?: "context")
        is ViewRef.FilterView -> filters.value.firstOrNull { it.id == v.filterId }?.name ?: "Filter"
    }

    fun canOutline(): Boolean = currentView.value is ViewRef.ListView

    // Resolve where a new task lands from the current view, as (listId, folderId). A folder view
    // captures the task *directly into the folder* (empty listId + folderId set) — no phantom list —
    // so it shows in the folder alongside its lists' tasks.
    private fun resolveAddTarget(): Pair<String, String?> {
        // R-archive — an archived (or trashed) list/folder is read-only reference. Even if a capture funnel
        // somehow resolves to one (e.g. the command palette's "new task" while such a view is open), never
        // bury the task there: fall back to the Inbox. The UI already hides the add affordance on these
        // views; this is the data-layer backstop that guarantees the invariant.
        val (hiddenLists, hiddenFolders) = com.todocompanion.app.domain.view.ListPipeline.hiddenContainers(lists.value, folders.value)
        return when (val v = currentView.value) {
            is ViewRef.ListView -> if (v.listId in hiddenLists) ListEntity.INBOX_ID to null else v.listId to null
            is ViewRef.FolderView -> if (v.folderId in hiddenFolders) ListEntity.INBOX_ID to null else "" to v.folderId
            else -> ListEntity.INBOX_ID to null
        }
    }

    // A new task captured from a date-scoped smart list inherits that date (TickTick behaviour): adding
    // from Today lands it today, from Tomorrow lands it tomorrow. Other views leave the date unset.
    private fun defaultDueForView(): Long? {
        val kind = (currentView.value as? ViewRef.Smart)?.kind ?: return null
        val day = when (kind) {
            SmartKind.TODAY -> java.time.LocalDate.now(zone)
            SmartKind.TOMORROW -> java.time.LocalDate.now(zone).plusDays(1)
            else -> return null
        }
        return day.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()
    }

    /** A legible breakdown of a task's Do-Next priority score — surfaced in the task detail. */
    fun explainScore(task: TaskEntity): PriorityEngine.ScoreBreakdown {
        val byId = tasks.value.associateBy { it.id }
        val cfg = settings.value.priorityConfig()
        val boost = PriorityEngine.dependencyBoosts(dependencies.value, byId, cfg)[task.id] ?: 0.0
        return PriorityEngine.explain(task, System.currentTimeMillis(), byId, cfg, boost)
    }

    // ---------- quick add ----------
    fun submitQuickAdd(text: String, opts: QuickAddOptions) = viewModelScope.launch { quickAddOne(text, opts) }

    /** Wave B — bulk capture: a multi-line paste / brain-dump becomes one task per non-blank line, each run
     *  through the same NL parser as a single capture, created in order. N2: the whole batch is one Undo. */
    fun addManyLines(lines: List<String>, opts: QuickAddOptions = QuickAddOptions()) = viewModelScope.launch {
        val created = lines.map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { quickAddOne(it, opts) }
        if (created.isNotEmpty())
            undoEvents.tryEmit(UndoEvent(UndoKind.CREATED_MANY, created.first(), "Added ${created.size} task${if (created.size == 1) "" else "s"}", taskIds = created))
    }

    private suspend fun quickAddOne(text: String, opts: QuickAddOptions): String? {
        // Single capture funnel: inline tokens (#t25 estimate, * star, !/!!/!!! priority) are applied
        // HERE so every entry point — the quick-add sheet and the omnibox alike — supports them
        // identically. `@` is deliberately left in the text (handleActivity = false) so QuickAddParser
        // reads it as a context. Explicit opts chosen via the sheet's icons win over inline tokens.
        // Wave B — "use plain text" reversible parse: when set, the words are taken verbatim and NONE of
        // the NL extraction applies. Only the explicit sheet options (date/priority/list/… picked with the
        // icons) are honoured. useParse gates every parsed-field application below.
        val useParse = !opts.plainText
        val tok = com.todocompanion.app.domain.nlp.QuickTokens.parse(text, handleActivity = false)
        val parsed = QuickAddParser.parse(tok.text)
        if (useParse) { if (parsed.title.isBlank() && parsed.tags.isEmpty()) return null }
        else if (text.isBlank()) return null
        val estimateMin = opts.estimateMin ?: (if (useParse) tok.estimateMin else null)
        val star = opts.star || (useParse && tok.star)
        val tokPriority = if (!useParse) null else when (tok.priorityLevel) {
            3 -> com.todocompanion.app.domain.priority.PriorityLevel.HIGH
            2 -> com.todocompanion.app.domain.priority.PriorityLevel.MEDIUM
            1 -> com.todocompanion.app.domain.priority.PriorityLevel.LOW
            else -> null
        }
        val due = opts.dueMillis ?: (if (useParse) parsed.dateTime?.atZone(zone)?.toInstant()?.toEpochMilli() else null) ?: defaultDueForView()
        val level = opts.priority ?: tokPriority ?: (if (useParse) parsed.priority else null)
        // No priority chosen ⇒ "None" (importance/urgency 2), not Low.
        val imp = level?.importance ?: com.todocompanion.app.domain.priority.PriorityLevel.NONE.importance
        val urg = level?.urgency ?: com.todocompanion.app.domain.priority.PriorityLevel.NONE.urgency
        // ~list resolves to an existing list by name (case-insensitive); otherwise fall back to the
        // current view's target, which for a folder view is the folder itself (no list).
        val explicitList = opts.listId
            ?: (if (useParse) parsed.list?.let { name -> lists.value.firstOrNull { !it.archived && it.name.equals(name, ignoreCase = true) }?.id } else null)
        val (listId, folderId) = when {
            opts.folderId != null -> "" to opts.folderId       // explicit folder-direct capture (R21)
            explicitList != null -> explicitList to null
            else -> resolveAddTarget()
        }
        // P3 · keep-vs-strip: by default the recognized words are stripped for a lean title; when the user
        // prefers to keep what they typed, keep them. In plain-text mode the raw text is the title verbatim.
        val baseTitle = when {
            !useParse -> text.replace(Regex("\\s+"), " ").trim()
            settings.value.keepParsedText -> tok.text.replace(Regex("\\s+"), " ").trim().ifBlank { parsed.title }
            else -> parsed.title
        }.ifBlank { "Untitled" }
        // Wave B/D — inline subtask grammar: "Plan trip > book flight > pick seat" → parent + a child per
        // segment. N3: opt-in only (opts.splitSubtasks, offered as a one-tap capture chip), so a literal
        // " > " in a normal title is never silently turned into a subtree.
        val segs = if (useParse && opts.splitSubtasks) baseTitle.split(Regex("\\s*>\\s*")).map { it.trim() }.filter { it.isNotBlank() } else listOf(baseTitle)
        val childTitles = if (segs.size > 1) segs.drop(1) else emptyList()
        val finalTitle = if (segs.size > 1) segs.first() else baseTitle
        val id = repo.createTask(listId, finalTitle, importance = imp, urgency = urg, dueDate = due, folderId = folderId)

        val ws = settings.value.activeWorkspaceId
        val tagIds = opts.tagIds.toMutableList()
        if (useParse && parsed.tags.isNotEmpty()) {
            // Match names only within the active workspace so a same-named tag elsewhere isn't reused.
            val existing = repo.getTagsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }
            parsed.tags.forEach { name ->
                tagIds += existing[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertTag(TagEntity(it, name, workspaceId = ws)) }
            }
        }
        if (tagIds.isNotEmpty()) repo.setTaskTags(id, tagIds.distinct())

        // @contexts (parsed) + contexts chosen in the sheet (R21) resolve to existing contexts by name,
        // creating any that are new.
        run {
            val ctxIds = ArrayList(opts.contextIds)
            if (useParse && parsed.contexts.isNotEmpty()) {
                val existingCtx = repo.getContextsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }
                parsed.contexts.forEach { name ->
                    ctxIds += existingCtx[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertContext(ContextEntity(id = it, name = name, workspaceId = ws)) }
                }
            }
            if (ctxIds.isNotEmpty()) repo.setTaskContexts(id, ctxIds.distinct())
        }

        // Natural-language recurrence ("every Tuesday", "monthly", "every 2 weeks") or the date sheet's
        // recurrence + optional note / duration (R21). V9: inline-token estimate and star too.
        val rrule = opts.rrule ?: (if (useParse) parsed.rrule else null)
        if (rrule != null || opts.note.isNotBlank() || estimateMin != null || star || opts.durationMin != null ||
            opts.startMillis != null || opts.deadlineMillis != null) repo.getTask(id)?.let {
            repo.saveTask(it.copy(
                rrule = rrule ?: it.rrule,
                note = opts.note.ifBlank { it.note },
                estimateMin = estimateMin ?: it.estimateMin,
                durationMin = opts.durationMin ?: it.durationMin,
                startDate = opts.startMillis ?: it.startDate,
                deadlineDate = opts.deadlineMillis ?: it.deadlineDate,
                star = it.star || star,
            ))
        }
        // Attachments picked in the sheet — applied after the task exists (each reads bytes off-thread).
        opts.attachmentUris.forEach { addAttachment(id, it) }

        // Wave B (F3) — reminders at capture now match the editor: a place reminder, or a relative reminder
        // anchored to due / start / deadline. A relative reminder (not an absolute time) means it re-arms
        // with the task's dates automatically (F1). These win over the legacy absolute reminderMillis.
        val reminderTask = repo.getTask(id)
        when {
            opts.reminderPlace != null && reminderTask != null ->
                reminderCtl.addPlace(reminderTask, opts.reminderPlace!!)
            opts.reminderOffsetMin != null && reminderTask != null -> {
                val type = when (opts.reminderAnchor) {
                    "start" -> "relativeToStart"; "deadline" -> "relativeToDeadline"; else -> "relativeToDue"
                }
                val anchorSet = when (opts.reminderAnchor) {
                    "start" -> reminderTask.startDate != null
                    "deadline" -> reminderTask.deadlineDate != null
                    else -> due != null
                }
                if (anchorSet) reminderCtl.addRelative(reminderTask, type, opts.reminderOffsetMin!!)
            }
            else -> {
                val reminderAt = opts.reminderMillis ?: (if (useParse && parsed.hasTime && due != null) due else null)
                if (reminderAt != null && reminderTask != null) {
                    val r = ReminderEntity(UUID.randomUUID().toString(), taskId = id, type = "absolute", atTime = reminderAt)
                    repo.upsertReminder(r)
                    AlarmScheduler.schedule(appCtx, r, reminderTask)
                }
            }
        }
        // "!30m / !2h / !1d" shortcut: a lead-time reminder relative to the due date.
        if (useParse) parsed.reminderOffsetMin?.let { off ->
            if (due != null) {
                val r = ReminderEntity(UUID.randomUUID().toString(), taskId = id, type = "relativeToDue", offsetMin = off)
                repo.upsertReminder(r)
                repo.getTask(id)?.let { AlarmScheduler.schedule(appCtx, r, it) }
            }
        }
        // Wave B — inline subtask grammar: create each "> child" segment as a real child of the new task.
        childTitles.forEach { ct -> repo.createTask(listId, ct, parentId = id, folderId = folderId) }
        return id
    }

    // ---------- task actions ----------
    fun toggleComplete(t: TaskEntity) = viewModelScope.launch {
        // Completing a repeating task rolls it forward to the next occurrence instead of closing it
        // — unless its recurrence has ended (until-date reached or count exhausted). The pure decision
        // (does it roll, the shifted date bundle, and the subtask-reset mode) lives in RecurringRollForward
        // so it's JVM-unit-tested; the VM keeps the side effects (persist, log, sound, reminders, undo).
        val roll = com.todocompanion.app.domain.task.RecurringRollForward.onComplete(t, zone, System.currentTimeMillis())
        if (roll != null) {
            // The roll moves the start→due lead time AND the hard deadline forward by the same delta, so a
            // repeating task keeps its shape (previously the deadline froze on the first occurrence and then
            // read as permanently overdue).
            repo.saveTask(roll.next)
            repo.logRecurringCompletion(t.id)   // P1: record this occurrence so reliability can be scored
            if (settings.value.completionSound) playCompletionChime()
            // Reset the subtasks of a recurring task per its chosen mode (all / only-if-all-done / keep).
            val kids = tasks.value.filter { it.parentId == t.id && !it.trashed }
            com.todocompanion.app.domain.task.RecurringRollForward.subtasksToReset(roll.subtaskReset, kids)
                .forEach { repo.setCompleted(it, false) }
            shiftAbsoluteReminders(t.id, roll.delta)
            // The shift above moves absolute reminders with the occurrence; relative reminders
            // (relativeToDue/…) re-arm automatically — repo.saveTask above emitted remindersDirty because
            // the due date moved, and the init collector re-arms this task against the new occurrence (F1).
            // R31 #4 — a repeating task rolls forward silently; offer Undo too, restoring the exact
            // occurrence (due date + rule) so completing a repeat is as reversible as any other finish.
            undoEvents.tryEmit(UndoEvent(UndoKind.COMPLETED, t.id, "Completed “${t.title.take(30)}” — rolled to next occurrence", restore = t))
        } else {
            repo.setCompleted(t, !t.completed)
            if (!t.completed) {
                undoEvents.tryEmit(UndoEvent(UndoKind.COMPLETED, t.id, "Completed “${t.title.take(30)}”"))
                if (settings.value.completionSound) playCompletionChime()
                // P5/Q2: finishing a goal or project is a milestone — celebrate it, and lead with the
                // reward the user promised themselves if they set one.
                if (t.isGoal || t.isProject) goalCelebration.value =
                    if (t.rewardText.isNotBlank()) "“${t.title.take(32)}” done — you earned it: ${t.rewardText.take(50)} 🎉"
                    else "Milestone reached — “${t.title.take(40)}” done! 🎉"
                // PC3: a one-time celebration on the very first completion — the moment a trial becomes a habit.
                if (!settings.value.firstWinCelebrated) {
                    repo.saveSettings(settings.value.copy(firstWinCelebrated = true))
                    if (goalCelebration.value == null) goalCelebration.value = "Your first one, done 🎉 Small wins compound — you're off."
                }
            }
        }
    }

    /** P5: a completed goal/project title, surfaced to the tasks screen for a confetti + toast moment. */
    val goalCelebration = MutableStateFlow<String?>(null)

    /** A short, pleasant two-note chime on completing a task. Built-in tones — no bundled audio. */
    private fun playCompletionChime() = runCatching {
        val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
        viewModelScope.launch { kotlinx.coroutines.delay(600); tg.release() }
    }

    /** When a repeat rolls (complete or skip), move every ABSOLUTE reminder on the task forward by the same
     *  [delta] the dates moved and re-arm its alarm. Relative reminders re-arm themselves off remindersDirty.
     *  Shared by toggleComplete and skipOccurrence so the shift is written once. */
    private suspend fun shiftAbsoluteReminders(taskId: String, delta: Long) {
        val updated = repo.getTask(taskId)
        reminders.value.filter { it.taskId == taskId && it.atTime != null }.forEach { r ->
            val nr = r.copy(atTime = r.atTime!! + delta)
            repo.upsertReminder(nr)
            updated?.let { AlarmScheduler.schedule(appCtx, nr, it) }
        }
    }
    /**
     * Q5 — adaptive cadence: make a chronically-missed recurring task less demanding by easing it one
     * step (daily→less often, or a longer interval), the task analogue of the habit adaptive-goal ease.
     * Returns the new human label, or null if it can't be eased.
     */
    fun easeCadence(t: TaskEntity, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val rec = com.todocompanion.app.domain.recurrence.Recurrence
        val r = t.rrule?.let { rec.parse(it) }
        if (r == null) { onDone(null); return@launch }
        val rule = rec.encode(rec.ease(r))
        repo.saveTask(t.copy(rrule = rule))
        onDone(rec.label(rule))
    }

    /** Advance a repeating task to its next occurrence without logging a completion (MLO "skip"). */
    fun skipOccurrence(t: TaskEntity) = viewModelScope.launch {
        val rolled = com.todocompanion.app.domain.task.RecurringRollForward.onSkip(t, zone, System.currentTimeMillis())
        if (rolled == null) {
            // No advance: either not a repeat (no rule / no due — do nothing) or the recurrence has ended
            // (rule + due present) — close it, exactly as before.
            if (!t.rrule.isNullOrBlank() && t.dueDate != null) repo.setCompleted(t, true)
            return@launch
        }
        val (next, delta) = rolled
        repo.saveTask(next)
        shiftAbsoluteReminders(t.id, delta)
        // Relative reminders re-arm automatically: repo.saveTask above emitted remindersDirty (F1).
    }
    fun setAbandoned(t: TaskEntity, v: Boolean) = viewModelScope.launch {
        repo.setAbandoned(t, v)
        if (v) undoEvents.tryEmit(UndoEvent(UndoKind.ABANDONED, t.id, "Marked won't do"))
    }
    fun toggleCollapsed(t: TaskEntity) = viewModelScope.launch { repo.setCollapsed(t, !t.collapsed) }

    // ---------- batch actions (multi-select) ----------
    fun completeMany(ids: Set<String>) = viewModelScope.launch { ids.mapNotNull { repo.getTask(it) }.filter { !it.completed }.forEach { repo.setCompleted(it, true) } }
    fun trashMany(ids: Set<String>) = viewModelScope.launch { val ws = settings.value.activeWorkspaceId; ids.forEach { repo.setTrashed(it, true, ws) } }
    /** R53 — batch-park the selection in Someday/Maybe (clears their dates so they leave the active lists). */
    fun setSomedayMany(ids: Set<String>) = viewModelScope.launch {
        ids.mapNotNull { repo.getTask(it) }.forEach { repo.saveTask(it.copy(someday = true, dueDate = null, startDate = null, updatedAt = System.currentTimeMillis())) }
        toast("Parked ${ids.size} in Someday / Maybe.")
    }
    fun setPriorityMany(ids: Set<String>, level: PriorityLevel) = viewModelScope.launch {
        ids.mapNotNull { repo.getTask(it) }.forEach { repo.saveTask(it.copy(importance = level.importance, urgency = level.urgency)) }
    }
    /** Move a task's due date to [target] keeping its time-of-day (all-day stays all-day). */
    private fun redate(t: TaskEntity, target: java.time.LocalDate): Long {
        val cur = t.dueDate?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) }
        val time = if (cur != null && (cur.hour != 0 || cur.minute != 0)) java.time.LocalTime.of(cur.hour, cur.minute) else java.time.LocalTime.MIDNIGHT
        return target.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }
    /** Wave C — deterministic bulk reschedule of a multi-selection: shift every task by [days] (a dateless
     *  one anchors at today), the offline analogue of "rebuild my week" without any cloud auto-scheduler.
     *  Each write re-arms reminders via F1. */
    fun shiftSelectionDays(ids: Set<String>, days: Int) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone)
        ids.mapNotNull { repo.getTask(it) }.forEach { t ->
            // N4 — shift the local DATE by N days (keeping time-of-day), not raw 24h-millis, so a task
            // doesn't drift an hour across a daylight-saving boundary. redate() preserves the wall clock.
            val base = t.dueDate?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today
            repo.saveTask(t.copy(dueDate = redate(t, base.plusDays(days.toLong()))))
        }
        toast(if (ids.size == 1) "Moved ${if (days >= 0) "+$days" else "$days"} day${if (kotlin.math.abs(days) == 1) "" else "s"}." else "Moved ${ids.size} tasks.")
    }
    /** Wave C — bulk move the selection to Today, keeping each task's time-of-day. */
    fun rescheduleSelectionToToday(ids: Set<String>) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone)
        ids.mapNotNull { repo.getTask(it) }.forEach { t -> repo.saveTask(t.copy(dueDate = redate(t, today))) }
        toast("Moved ${ids.size} to Today.")
    }
    /** Wave C — bulk move the selection to the next weekday (skips Sat/Sun), keeping time-of-day. */
    fun rescheduleSelectionToNextWeekday(ids: Set<String>) = viewModelScope.launch {
        var d = java.time.LocalDate.now(zone).plusDays(1)
        while (d.dayOfWeek == java.time.DayOfWeek.SATURDAY || d.dayOfWeek == java.time.DayOfWeek.SUNDAY) d = d.plusDays(1)
        ids.mapNotNull { repo.getTask(it) }.forEach { t -> repo.saveTask(t.copy(dueDate = redate(t, d))) }
        toast("Moved ${ids.size} to ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}.")
    }
    fun moveMany(ids: Set<String>, listId: String) = viewModelScope.launch { ids.forEach { repo.moveToList(it, listId) } }
    fun moveManyToFolder(ids: Set<String>, folderId: String) = viewModelScope.launch { ids.forEach { repo.moveToFolder(it, folderId) } }
    /** Smart "Move to" suggestions: rank list/folder targets by how the item(s) being filed resemble
     *  earlier tasks (by title), falling back to how heavily each destination is used. Shared by every
     *  place the move picker is opened (task editor, bulk move, quick-add) so ranking is uniform. */
    fun suggestMoveTargets(titles: List<String>, excludeTaskIds: Set<String> = emptySet(), max: Int = 3): List<String> =
        com.todocompanion.app.domain.MoveSuggester.rank(
            queryTitles = titles.filter { it.isNotBlank() },
            tasks = tasks.value, folders = folders.value, lists = lists.value,
            excludeTaskIds = excludeTaskIds, max = max)
    fun moveTaskToFolder(t: TaskEntity, folderId: String) = viewModelScope.launch { repo.moveToFolder(t.id, folderId) }
    fun trash(t: TaskEntity) = viewModelScope.launch {
        repo.setTrashed(t.id, true, settings.value.activeWorkspaceId)
        undoEvents.tryEmit(UndoEvent(UndoKind.TRASHED, t.id, "Moved to Trash"))
    }
    fun undo(e: UndoEvent) = viewModelScope.launch {
        // A recurring roll-forward carries the exact pre-completion snapshot; restore it wholesale so the
        // due date and rule return to where they were, not just the completed flag.
        if (e.restore != null) { repo.saveTask(e.restore); return@launch }
        // Note trash / archive carry the exact pre-action note snapshot — write it back wholesale.
        if (e.noteRestore != null) { repo.upsertNote(e.noteRestore); return@launch }
        // Habit trash / archive carry the exact pre-action habit snapshot — restore it and refresh widgets.
        if (e.habitRestore != null) { repo.upsertHabit(e.habitRestore); refreshHabitWidgets(); return@launch }
        // List/folder delete-to-Trash: write every pre-action snapshot back wholesale — the container(s)
        // return un-trashed and each task returns to its original list/folder and trashed state.
        if (e.kind == UndoKind.CONTAINER_TRASHED) {
            e.folderSnapshots.forEach { repo.saveFolder(it) }
            e.listSnapshots.forEach { repo.saveList(it) }
            e.taskSnapshots.forEach { repo.saveTask(it) }
            return@launch
        }
        when (e.kind) {
            UndoKind.COMPLETED -> repo.getTask(e.taskId)?.let { repo.setCompleted(it, false) }
            UndoKind.ABANDONED -> repo.getTask(e.taskId)?.let { repo.setAbandoned(it, false) }
            UndoKind.TRASHED -> repo.setTrashed(e.taskId, false)
            // N2 — undo a bulk-paste / inline-subtree add by trashing everything it created (recoverable).
            UndoKind.CREATED_MANY -> e.taskIds.forEach { repo.setTrashed(it, true, settings.value.activeWorkspaceId) }
            // Undo auto-fill / auto-schedule by deleting exactly the events it created (nothing else touched).
            UndoKind.EVENTS_CREATED -> e.eventIds.forEach { repo.deleteEvent(it) }
            // Note & habit trash/archive and container delete are restored via the snapshot short-circuits above.
            UndoKind.NOTE_TRASHED, UndoKind.NOTE_ARCHIVED, UndoKind.HABIT_TRASHED, UndoKind.HABIT_ARCHIVED, UndoKind.CONTAINER_TRASHED -> Unit
        }
    }
    fun restore(t: TaskEntity) = viewModelScope.launch { repo.setTrashed(t.id, false) }
    fun deleteForever(t: TaskEntity) = viewModelScope.launch { repo.deleteSubtree(t.id) }
    /** Permanently erase several tasks (and their subtrees) — the Trash multi-select "Delete forever". */
    fun deleteForeverMany(ids: Set<String>) = viewModelScope.launch { ids.forEach { repo.deleteSubtree(it) } }
    fun emptyTrash() = viewModelScope.launch { repo.emptyTrash(settings.value.activeWorkspaceId) }
    fun moveToList(t: TaskEntity, listId: String) = viewModelScope.launch { repo.moveToList(t.id, listId) }
    /** Drag-to-nest: make [childId] a subtask of [parentId] (null promotes it to top level). Cycle-safe. */
    fun nestUnder(childId: String, parentId: String?) = viewModelScope.launch {
        if (childId == parentId) return@launch
        val child = repo.getTask(childId) ?: return@launch
        if (child.parentId == parentId) return@launch
        if (parentId != null) {
            // Refuse to nest a task under one of its own descendants (would orphan a subtree).
            val all = tasks.value
            var p: String? = parentId
            var guard = 0
            while (p != null && guard++ < 1000) {
                if (p == childId) return@launch
                p = all.firstOrNull { it.id == p }?.parentId
            }
            val parent = repo.getTask(parentId)
            repo.saveTask(child.copy(parentId = parentId, listId = parent?.listId ?: child.listId, folderId = parent?.folderId ?: child.folderId))
        } else {
            repo.saveTask(child.copy(parentId = null))
        }
    }
    fun save(t: TaskEntity) = viewModelScope.launch {
        // F1 — saveTask emits repo.remindersDirty when a date (or scheduling-gate flag) changed, and the
        // init collector re-arms this task's relative reminders. No per-call reschedule needed here anymore.
        repo.saveTask(t)
    }

    // ---------- The Done Record (R27) ----------
    /** Flip the "this was a win" flag — one tap from the accomplishment feed or the task editor. */
    fun toggleWin(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(winFlag = !t.winFlag)) }
    /** Save a generated Done-Record document (Markdown) to a reachable location, no system picker needed. */
    fun exportBragDoc(markdown: String, filename: String = "brag-document.md", onDone: (String?) -> Unit) = viewModelScope.launch {
        val loc = runCatching {
            com.todocompanion.app.util.FileExport.saveToDownloads(appCtx, filename, "text/markdown", markdown.toByteArray())
        }.getOrNull()
        onDone(loc)
    }
    /** Selection-bar "Make subtask of…": nest every selected task under [parentId] (the parent itself,
     *  if it happens to be in the selection, is skipped so it can't become its own child). Cycle-safe. */
    fun nestManyUnder(childIds: Set<String>, parentId: String) = viewModelScope.launch {
        val all = tasks.value
        val parent = repo.getTask(parentId) ?: return@launch
        childIds.filter { it != parentId }.forEach { cid ->
            val child = repo.getTask(cid) ?: return@forEach
            if (child.parentId == parentId) return@forEach
            // Refuse to nest a task under one of its own descendants.
            var p: String? = parentId; var guard = 0; var cyclic = false
            while (p != null && guard++ < 1000) { if (p == cid) { cyclic = true; break }; p = all.firstOrNull { it.id == p }?.parentId }
            if (cyclic) return@forEach
            repo.saveTask(child.copy(parentId = parentId, listId = parent.listId, folderId = parent.folderId))
        }
    }
    /** Persist a manual drag reorder by writing each id's index as its sortOrder. */
    fun setManualOrder(orderedIds: List<String>) = viewModelScope.launch {
        val byId = tasks.value.associateBy { it.id }
        orderedIds.forEachIndexed { i, id ->
            byId[id]?.let { if (it.sortOrder != i.toDouble()) repo.saveTask(it.copy(sortOrder = i.toDouble())) }
        }
    }

    /** P4 — add a real nested subtask (a full child task) under [parent], inheriting its list/folder. */
    fun addSubtask(parent: TaskEntity, title: String) = viewModelScope.launch {
        if (title.isBlank()) return@launch
        repo.createTask(parent.listId, title.trim(), parentId = parent.id, folderId = parent.folderId)
    }

    // ---------- checklist ----------
    fun checklistFor(taskId: String) = checklist.value.filter { it.taskId == taskId }.sortedBy { it.sortOrder }
    fun addChecklistItem(taskId: String, text: String) = viewModelScope.launch { repo.addChecklistItem(taskId, text) }
    /** Break a task into steps at once: one checklist item per non-blank line (C2 breakdown). */
    fun addChecklistItems(taskId: String, lines: List<String>) = viewModelScope.launch {
        lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { repo.addChecklistItem(taskId, it) }
    }

    /** "Just start" (C2): a task pre-selected for the next time the Focus screen opens. */
    val pendingFocusTaskId = MutableStateFlow<String?>(null)
    /** Fusion F2: a habit pre-selected to Focus on; the Focus screen consumes it and auto-logs. */
    val pendingFocusHabitId = MutableStateFlow<String?>(null)

    // ---- Habits tab view-state, hoisted so the app's single top bar can drive it (one header) ----
    // Matrix mode and density are now persisted in settings, so the choice survives an app restart
    // (they used to reset to list/medium every launch).
    val habitMatrixMode: StateFlow<Boolean> = settings.map { it.habitMatrixMode }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val habitDensity: StateFlow<Int> = settings.map { it.habitDensity }.stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    fun setHabitMatrixMode(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(habitMatrixMode = on)) }
    fun setHabitDensity(level: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(habitDensity = level.coerceIn(0, 2))) }
    fun setHabitGroupByCategory(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(habitGroupByCategory = on)) }
    fun setHabitSort(mode: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(habitSort = mode)) }
    fun setHabitInsightsExpanded(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(habitInsightsExpanded = on)) }
    /** Persist a habit's time-planning config (HabitTime) into settings-JSON, keyed by habit id. */
    fun setHabitTimeCfg(habitId: String, cfg: com.todocompanion.app.domain.habit.HabitTime.Cfg) = viewModelScope.launch {
        if (habitId.isBlank()) return@launch
        val m = settings.value.habitTimeCfg.toMutableMap()
        if (com.todocompanion.app.domain.habit.HabitTime.isDefault(cfg)) m.remove(habitId)
        else m[habitId] = com.todocompanion.app.domain.habit.HabitTime.encodeCfg(cfg)
        repo.saveSettings(settings.value.copy(habitTimeCfg = m))
    }
    /** R108 — persist the minute-of-day a habit's flexible calendar block was dragged to (display placement
     *  only; not a reminder). Merges into the existing HabitTime cfg keyed by habit id. */
    fun setHabitBlockMinute(habitId: String, minute: Int) = viewModelScope.launch {
        if (habitId.isBlank()) return@launch
        val cur = com.todocompanion.app.domain.habit.HabitTime.cfgFor(settings.value, habitId)
        setHabitTimeCfg(habitId, cur.copy(blockMin = minute.coerceIn(0, 1439)))
    }
    fun setTimeGridColumns(cols: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(timeGridColumns = cols.coerceIn(2, 5))) }
    val habitDetailId = MutableStateFlow<String?>(null)    // non-null → the analytics screen overlays the tab
    val habitBatchOpen = MutableStateFlow(false)
    val habitPresetOpen = MutableStateFlow(false)
    val habitEditor = MutableStateFlow<HabitEditRequest?>(null)   // non-null → the full-screen editor is open
    val habitQuickAddOpen = MutableStateFlow(false)               // L6: natural-language "type a habit" dialog
    val habitTrendsOpen = MutableStateFlow(false)                 // M5: full trends & correlations dashboard
    val habitArchiveOpen = MutableStateFlow(false)                // Archived habits + Trash management overlay
    fun toggleChecklist(item: ChecklistItemEntity) = viewModelScope.launch { repo.saveChecklistItem(item.copy(checked = !item.checked)) }
    fun deleteChecklistItem(id: String) = viewModelScope.launch { repo.deleteChecklistItem(id) }

    // ---------- attachments ----------
    fun attachmentMeta(taskId: String) = repo.attachmentMeta(taskId)
    // Scope the Attachments hub to the active workspace: an attachment belongs to a task or a note,
    // so keep only those whose owner lives in the active workspace (re-scopes on workspace switch too).
    val allAttachments = combine(repo.allAttachmentMeta, repo.allTasks, repo.observeNotes(), activeWs) { atts, tks, nts, ws ->
        val taskIds = tks.asSequence().filter { it.workspaceId == ws }.map { it.id }.toSet()
        val noteIds = nts.asSequence().filter { it.workspaceId == ws }.map { it.id }.toSet()
        atts.filter { it.taskId in taskIds || (it.noteId != null && it.noteId in noteIds) }
    }.state(emptyList())
    /** Attachment bytes as Base64 — reads a file-backed attachment (F4) from disk, else the DB. */
    suspend fun attachmentContent(id: String): String? = withContext(Dispatchers.IO) {
        repo.attachmentFilePath(id)?.let { path ->
            val f = java.io.File(path)
            // SEC-1: file-backed attachments are encrypted at rest; decrypt here (tolerant of legacy plaintext).
            if (f.exists()) return@withContext android.util.Base64.encodeToString(
                com.todocompanion.app.data.security.FileVault.readDecrypted(f), android.util.Base64.NO_WRAP)
        }
        repo.attachmentContent(id)
    }
    /**
     * R46 — read a content:// URI's bytes robustly, the way Tasks.org does. Some providers on de-Googled
     * ROMs (and cloud/"recent" backends) refuse a plain openInputStream but serve a file descriptor, or
     * vice-versa, so we try both before giving up. Returns the bytes, or null with the failure reason.
     */
    private fun readUriBytes(uri: Uri): Pair<ByteArray?, String?> {
        val cr = appCtx.contentResolver
        // 1) the normal path (SAF content:// URIs carry a read grant — this is the route that works).
        runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { return it to null }
        val firstErr = runCatching { cr.openInputStream(uri) }.exceptionOrNull()
        // 2) the file-descriptor path — works for providers that only implement openFile().
        runCatching {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            }
        }.getOrNull()?.let { return it to null }
        // 3) a raw file:// URI (some legacy file managers return these) — readable only where the OS lets
        //    us: our own sandbox, or a world-readable path. Outside that it needs a storage permission we
        //    never request, so this fails cleanly rather than crashing.
        if (uri.scheme == "file") uri.path?.let { p ->
            runCatching { java.io.File(p).readBytes() }.getOrNull()?.let { return it to null }
        }
        val ex = firstErr ?: runCatching { cr.openInputStream(uri) }.exceptionOrNull()
        val why = when {
            ex is SecurityException || ex?.cause is SecurityException ->
                "no permission to read a ${uri.scheme ?: "?"} file — pick it via Documents/Files, not a basic file manager"
            ex != null -> "${ex.javaClass.simpleName} (${uri.scheme})"
            else -> "no data (${uri.scheme})"
        }
        return null to why
    }

    /** After a one-shot attachment copy we no longer need the SAF grant MainActivity may have persisted, so
     *  release it to keep clear of the ~512 persisted-permission ceiling (backup/sync folders keep theirs). */
    private fun releasePersistedRead(uri: Uri) {
        runCatching {
            appCtx.contentResolver.releasePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun addAttachment(taskId: String, uri: Uri, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val cr = appCtx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val name = displayNameOf(uri) ?: "attachment"
        val (bytes, why) = withContext(Dispatchers.IO) { readUriBytes(uri) }
        if (bytes == null) { toast("Couldn't read that file ($why). Try Share ▸ Kairo from your file manager."); onDone(false); return@launch }
        if (bytes.size > repo.maxAttachmentBytes) { toast("File too large (max 50 MB per file)"); onDone(false); return@launch }
        // F4: write the bytes to an app-private file and store only the path — the DB stays lean.
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(appCtx.filesDir, "attachments").apply { mkdirs() }
                val f = java.io.File(dir, UUID.randomUUID().toString())
                com.todocompanion.app.data.security.FileVault.writeEncrypted(f, bytes)   // SEC-1: at-rest encrypted
                repo.addAttachmentFile(taskId, name, mime, bytes.size.toLong(), f.absolutePath)
                true
            }.getOrDefault(false)
        }
        if (!ok) toast("Could not save attachment")
        releasePersistedRead(uri)
        onDone(ok)
    }
    fun removeAttachment(id: String) = viewModelScope.launch { repo.deleteAttachment(id) }
    /** L14 — save a handwriting/ink drawing as a note image attachment and hand back a Markdown reference
     *  (`![ink](attachment:<id>)`) the editor appends. Fully on-device; the PNG lives in the note like any
     *  image and renders via [noteImageMap]. [onDone] receives the reference, or null on failure. */
    fun addInkToNote(noteId: String, png: ByteArray, caption: String = "", onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val id = withContext(Dispatchers.IO) {
            runCatching { repo.addNoteAttachment(noteId, "ink-${System.currentTimeMillis()}.png", "image/png", png) }.getOrNull()
        }
        if (id == null) { toast("Couldn't save the drawing"); onDone(null) }
        // Wave 2 · Searchable Ink — the caption becomes the image alt-text, which the body FTS indexes.
        else onDone("![${caption.ifBlank { "ink" }}](attachment:$id)")
    }
    /** R40 — attach several files at once from the system picker (SAF, multi-select). Each URI's bytes are
     *  copied into app-private storage; no storage permission is involved. [onDone] reports how many landed,
     *  so the editor can acknowledge the save (R46). */
    fun addAttachments(taskId: String, uris: List<Uri>, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        var ok = 0
        for (u in uris) {
            val cr = appCtx.contentResolver
            val mime = cr.getType(u) ?: "application/octet-stream"
            val name = displayNameOf(u) ?: "attachment"
            val (bytes, why) = withContext(Dispatchers.IO) { readUriBytes(u) }
            if (bytes == null) { toast("Couldn't read one file ($why). Try Share ▸ Kairo from your file manager."); continue }
            if (bytes.size > repo.maxAttachmentBytes) { toast("Skipped one file over 50 MB"); continue }
            val wrote = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(appCtx.filesDir, "attachments").apply { mkdirs() }
                    val f = java.io.File(dir, UUID.randomUUID().toString())
                    f.writeBytes(bytes)
                    repo.addAttachmentFile(taskId, name, mime, bytes.size.toLong(), f.absolutePath)
                    true
                }.getOrDefault(false)
            }
            if (wrote) ok++
            releasePersistedRead(u)
        }
        if (ok > 0) toast(if (ok == 1) "Attachment added" else "$ok attachments added")
        onDone(ok)
    }

    /** Set a list's background image: decode, downscale to ≤1280px, re-encode JPEG, store as base64. */
    fun setListBackgroundFromUri(listId: String, uri: Uri) = viewModelScope.launch {
        val b64 = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val maxDim = 1280
                val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1))
                val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull()
        }
        if (b64 == null) { toast("Could not read image"); return@launch }
        repo.setListBackground(listId, b64)
    }
    fun clearListBackground(listId: String) = viewModelScope.launch { repo.setListBackground(listId, null) }

    /** R45 — decode+downscale a picked image to a small square-ish face (≤512px JPEG base64), for an
     *  occasion photo. Calls back on the main thread with the base64 (or null). Permission-free. */
    fun imageUriToBase64(uri: Uri, onDone: (String?) -> Unit) = viewModelScope.launch {
        val b64 = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val maxDim = 512
                val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1))
                val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull()
        }
        onDone(b64)
    }
    /** Decode an attachment to a temp cache file and hand it to a local viewer app. */
    fun openAttachment(id: String, fileName: String, mime: String) = viewModelScope.launch {
        val b64 = attachmentContent(id) ?: return@launch
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
                val f = java.io.File(dir, safe).apply { writeBytes(bytes) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        } ?: return@launch
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appCtx.startActivity(intent) }.onFailure { toast("No app can open this file") }
    }
    private fun displayNameOf(uri: Uri): String? = runCatching {
        appCtx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    private fun toast(msg: String) = android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
    /** R42 — public toast for UI-side fallback messages (e.g. a picker that couldn't open). */
    fun toastMsg(msg: String) = toast(msg)

    // ---------- folders / lists ----------
    fun createFolder(name: String, parentId: String? = null) = viewModelScope.launch { repo.createFolder(name, parentId, settings.value.activeWorkspaceId) }
    fun renameFolder(f: FolderEntity, name: String) = viewModelScope.launch { repo.saveFolder(f.copy(name = name)) }
    fun saveFolder(f: FolderEntity) = viewModelScope.launch { repo.saveFolder(f) }
    fun setFolderIcon(f: FolderEntity, icon: String?) = viewModelScope.launch { repo.saveFolder(f.copy(icon = icon)) }
    fun toggleFolder(f: FolderEntity) = viewModelScope.launch { repo.saveFolder(f.copy(collapsed = !f.collapsed)) }
    fun deleteFolder(id: String) = viewModelScope.launch { repo.deleteFolder(id) }
    // R52 — archive/restore a folder (and, by extension, its lists' tasks drop out of active views).
    fun setFolderArchived(f: FolderEntity, archived: Boolean) = viewModelScope.launch { repo.saveFolder(f.copy(archived = archived)) }

    /** Count of active (open, incomplete) tasks inside [folder] and its sub-folders/lists — drives the
     *  "this has N unfinished tasks" confirmation before archiving or deleting. */
    fun activeTaskCountInFolder(folder: FolderEntity): Int {
        val descFolderIds = descendantFolderIds(folder.id)
        val descListIds = lists.value.filter { it.folderId in descFolderIds }.map { it.id }.toSet()
        return tasks.value.count { !it.trashed && !it.completed && !it.abandoned && (it.listId in descListIds || it.folderId in descFolderIds) }
    }
    fun activeTaskCountInList(listId: String): Int =
        tasks.value.count { !it.trashed && !it.completed && !it.abandoned && it.listId == listId }

    private fun descendantFolderIds(rootId: String): Set<String> {
        val all = folders.value
        val ids = mutableSetOf(rootId); var changed = true
        while (changed) { changed = false; all.forEach { if (it.parentId in ids && it.id !in ids) { ids.add(it.id); changed = true } } }
        return ids
    }

    /** Delete a folder to Trash (recoverable). Trashing just the folder hides its whole subtree (sub-folders,
     *  lists and their tasks) via the shared container-visibility cascade. [moveTasksToRef]
     *  ("list:<id>"/"folder:<id>") first moves the folder's tasks there; null keeps everything with the
     *  folder in Trash. One Undo restores the folder and every moved task. */
    fun trashFolder(folder: FolderEntity, moveTasksToRef: String? = null) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val descFolderIds = descendantFolderIds(folder.id)
        val descListIds = lists.value.filter { it.folderId in descFolderIds }.map { it.id }.toSet()
        val folderTasks = repo.allTasksOnce().filter { it.listId in descListIds || (it.folderId != null && it.folderId in descFolderIds) }
        if (moveTasksToRef != null) moveTasksTo(folderTasks.filter { it.parentId == null }, moveTasksToRef)
        repo.saveFolder(folder.copy(trashed = true, trashedAt = now))
        undoEvents.tryEmit(UndoEvent(UndoKind.CONTAINER_TRASHED, folder.id,
            if (moveTasksToRef != null) "Deleted “${folder.name}”, kept its tasks" else "Deleted “${folder.name}” to Trash",
            folderSnapshots = listOf(folder), taskSnapshots = folderTasks))
    }
    fun restoreTrashedFolder(f: FolderEntity) = viewModelScope.launch { repo.saveFolder(f.copy(trashed = false, trashedAt = null)) }
    fun deleteFolderForever(id: String) = viewModelScope.launch { repo.purgeFolder(id) }

    /** Move the given root tasks to a "list:<id>" / "folder:<id>" ref (subtrees follow). */
    private suspend fun moveTasksTo(roots: List<TaskEntity>, ref: String) {
        val kind = ref.substringBefore(':'); val id = ref.substringAfter(':', "")
        if (id.isBlank()) return
        roots.forEach { if (kind == "folder") repo.moveToFolder(it.id, id) else repo.moveToList(it.id, id) }
    }
    fun createList(name: String, folderId: String?, colorArgb: Long?) = viewModelScope.launch { repo.createList(name, folderId, colorArgb, workspaceId = settings.value.activeWorkspaceId) }
    /** Create a nested list under [parent]. */
    fun createSubList(parent: ListEntity, name: String = "New list") = viewModelScope.launch {
        repo.createList(name, parent.folderId, null, workspaceId = settings.value.activeWorkspaceId, parentListId = parent.id)
    }
    /** Nest a list beneath the sibling directly above it (outline-style indent). */
    fun indentList(list: ListEntity) = viewModelScope.launch {
        val sibs = lists.value.filter { it.folderId == list.folderId && it.parentListId == list.parentListId && it.id != ListEntity.INBOX_ID && !it.archived }.sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == list.id }
        if (idx > 0) repo.setListParent(list.id, sibs[idx - 1].id)
    }
    /** Move a nested list back up to its grandparent level. */
    fun outdentList(list: ListEntity) = viewModelScope.launch {
        val parent = lists.value.firstOrNull { it.id == list.parentListId } ?: return@launch
        repo.setListParent(list.id, parent.parentListId)
    }

    // ---------- workspaces ----------
    fun createWorkspace(name: String) = viewModelScope.launch {
        val id = repo.createWorkspace(name.trim())
        repo.saveSettings(settings.value.copy(activeWorkspaceId = id))
        currentView.value = ViewRef.Smart(SmartKind.TODAY)
    }
    fun switchWorkspace(id: String) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(activeWorkspaceId = id))
        currentView.value = ViewRef.Smart(SmartKind.TODAY)
        // Clear cross-workspace-derived transient state so nothing from the old workspace lingers
        // (allAttachments/notes/tasks flows re-scope reactively; these MutableStateFlows don't).
        recallDue.value = emptyList(); recallDueCount.value = 0
        noteAnswers.value = emptyList(); noteSearchIds.value = emptyList(); notesNow.value = emptyList()
    }
    fun renameWorkspace(w: com.todocompanion.app.data.entity.WorkspaceEntity, name: String) = viewModelScope.launch { repo.upsertWorkspace(w.copy(name = name.trim())) }
    fun deleteWorkspace(id: String) = viewModelScope.launch {
        repo.deleteWorkspace(id)
        if (settings.value.activeWorkspaceId == id) repo.saveSettings(settings.value.copy(activeWorkspaceId = com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID))
    }

    // ---------- habits ----------
    fun createHabit(name: String, emoji: String?, colorArgb: Long?, target: Int, unit: String? = null, scheduleDays: String = "", reminderTimes: String = "") = viewModelScope.launch {
        repo.createHabit(name.trim(), emoji, colorArgb, target, settings.value.activeWorkspaceId, unit, scheduleDays, reminderTimes)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
    }
    fun saveHabit(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        repo.upsertHabit(h)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
    }
    /** Create from a fully-built habit (Tier I editor). Workspace defaults to the active one. */
    fun addHabit(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        repo.createHabit(h.copy(workspaceId = h.workspaceId.ifBlank { settings.value.activeWorkspaceId }))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        refreshHabitWidgets()
    }
    /** M4: render a habit's progress to a PNG on-device and open the share sheet. onDone gets the saved location. */
    fun shareHabitProgress(h: com.todocompanion.app.data.entity.HabitEntity, onDone: (String?) -> Unit) = viewModelScope.launch {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val cks = habitCheckins.value.filter { it.habitId == h.id }
        val (done, skip, relapse) = hs.daySets(h, cks)   // R108 audit C2 — one canonical derivation
        val today = today()
        val strength = hs.strength(h, done, skip, relapse, today)
        val cur = hs.currentStreak(h, done, skip, relapse, today)
        val best = hs.bestStreak(h, done, skip, relapse, today)
        val total = if (h.unit != null) cks.sumOf { it.count } else done.size
        val safe = h.name.filter { it.isLetterOrDigit() }.take(20).ifBlank { "habit" }
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.render(h.emoji, h.name, h.colorArgb, strength, cur, best, h.unit, total, done, skip, today)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "todo-companion-$safe-progress.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }
    /** N4: render a shareable "your week" recap card (habits + tasks) on-device. */
    fun shareWeeklyRecap(onDone: (String?) -> Unit) = viewModelScope.launch {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val weekDays = (0 until 7).map { today - it }.toSet()
        val cks = habitCheckins.value
        val habitsList = habits.value.filter { !it.archived }
        val habitById = habitsList.associateBy { it.id }
        // Count genuine successes only — a quit habit's slip is stored as status="done" over its limit.
        val checkinsThisWeek = cks.count { c -> c.epochDay in weekDays && habitById[c.habitId]?.let { hs.isSuccessDay(it, c) } == true }
        val bestStreak = habitsList.maxOfOrNull { h ->
            val d = cks.filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val s = cks.filter { it.habitId == h.id && it.status == "skip" }.map { it.epochDay }.toSet()
            val r = cks.filter { it.habitId == h.id && hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            hs.currentStreak(h, d, s, r, today)
        } ?: 0
        val tasksDone = tasks.value.count { t -> t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in weekDays } == true }
        val focusMin = focusViews().filter { it.epochDay in weekDays }.sumOf { it.minutes }
        val stats = listOf(
            "check-ins" to checkinsThisWeek.toString(),
            "tasks done" to tasksDone.toString(),
            "best streak" to "${bestStreak}d",
            "focus" to "${focusMin}m",
        )
        val sub = "Last 7 days · " + java.time.LocalDate.now(zone).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.renderStatsCard("Your week", sub, stats)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "todo-companion-week.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }
    /** R1: render a shareable "momentum" card — the unified habit+task+focus snapshot — on-device. Track: now
     *  rendered through the modular [DayCard] week card (the one card system every review surface shares),
     *  honouring the saved Personal/Professional share style, instead of the retired stats-card renderer. */
    fun shareMomentum(onDone: (String?) -> Unit) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone)
        val endDay = today.toEpochDay(); val startDay = endDay - 6
        val label = "${today.minusDays(6).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} – ${today.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}"
        val cfg = com.todocompanion.app.domain.PeriodShareConfigs.parse(settings.value.periodShareConfigJson)
        val pd = weekPeriodShareData(startDay, endDay, label)
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.DayCard.renderPeriodShare(pd, cfg, com.todocompanion.app.util.DayCard.PeriodKind.WEEK)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "todo-companion-momentum.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }

    /** V7 — Reality Replay: a shareable recap of the last 7 days across all three modules, on-device. Track: now
     *  rendered through [DayCard.renderPeriodShare] (WEEK) — the same modular week card the Day-review and Recap
     *  screens use — honouring the saved Personal/Professional share style. */
    fun shareRecap(onDone: (String?) -> Unit) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone)
        val endDay = today.toEpochDay(); val startDay = endDay - 6
        val label = "${today.minusDays(6).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} – ${today.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}"
        val cfg = com.todocompanion.app.domain.PeriodShareConfigs.parse(settings.value.periodShareConfigJson)
        val pd = weekPeriodShareData(startDay, endDay, label)
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.DayCard.renderPeriodShare(pd, cfg, com.todocompanion.app.util.DayCard.PeriodKind.WEEK)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "todo-companion-recap.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }

    /** Track 1.3 — share the Wrapped / year story as a PNG through the modular [DayCard.renderPeriodShare] (YEAR),
     *  the same professional-capable card the "Year, reviewed" share uses, over the inclusive calendar-year
     *  window. Honours the saved period-share style; reuses the [ProgressCard] FileProvider plumbing. */
    fun shareYearReview(startDay: Long, endDay: Long, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val recap = yearReviewed(startDay, endDay)
        val cfg = com.todocompanion.app.domain.PeriodShareConfigs.parse(settings.value.periodShareConfigJson)
        val yearLabel = java.time.LocalDate.ofEpochDay(endDay).year.toString()
        val pd = periodDataFromYear(recap, shareThemesFor(startDay, endDay), settings.value.accentArgb.takeIf { it != 0L }, yearLabel)
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.DayCard.renderPeriodShare(pd, cfg, com.todocompanion.app.util.DayCard.PeriodKind.YEAR)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "kairo-wrapped-$endDay.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }

    // ── Share mapping · domain roll-ups → the modular DayCard period-share model ────────────────────────
    // These mirror the Day-review screen's share mappers so the Momentum, Recap and Wrapped shares report the
    // very same numbers as the Day-review period cards, all through the one [DayCard] renderer.

    /** Build the WEEK period-share data for the inclusive [startDay]..[endDay] window from the shared domain
     *  folds ([ReviewRollup] + [ExecutionScore]) — the identical aggregation the Day-review week card reads. */
    private fun weekPeriodShareData(startDay: Long, endDay: Long, label: String): com.todocompanion.app.util.DayCard.PeriodShareData {
        val now = System.currentTimeMillis()
        val questions = com.todocompanion.app.domain.DailyQuestions.parseQuestions(settings.value.dailyQuestionsJson)
        val rollup = com.todocompanion.app.domain.ReviewRollup.compute(
            startDay, endDay, dayLogs.value, questions, habits.value, habitCheckins.value,
            timeVm.timeEntries.value, timeVm.timeActivities.value, zone, now, goals(), tasks.value, focusSessions.value)
        val exec = com.todocompanion.app.domain.ExecutionScore.fromRollup(rollup, tasks.value, zone)
        return periodDataFromRollup(label, rollup, exec, shareThemesFor(startDay, endDay), settings.value.accentArgb.takeIf { it != 0L })
    }

    /** The recurring theme words over an inclusive window, from the day logs' free text (same idiom the
     *  on-screen roll-up and the Day-review share use). */
    private fun shareThemesFor(startDay: Long, endDay: Long): List<String> {
        val docs = dayLogs.value.asSequence().filter { it.epochDay in startDay..endDay }.map { l ->
            listOf(l.pmReflection, l.highlight, l.gratitude, l.lesson, l.good1, l.good2, l.good3, l.promptAnswer, l.amIntention)
                .filter { it.isNotBlank() }.joinToString(" ")
        }.filter { it.isNotBlank() }.toList()
        return com.todocompanion.app.domain.TextInsights.threeWords(docs)
    }

    private fun shareMoodFace(v: Int): String = when (v.coerceIn(0, 5)) { 1 -> "😞"; 2 -> "🙁"; 3 -> "😐"; 4 -> "🙂"; 5 -> "😄"; else -> "😐" }

    /** Map a week / month [ReviewRollup.Rollup] (+ its execution score) to the renderer's data model. */
    private fun periodDataFromRollup(
        label: String,
        rollup: com.todocompanion.app.domain.ReviewRollup.Rollup,
        exec: com.todocompanion.app.domain.ExecutionScore.Score,
        themes: List<String>,
        accent: Long?,
    ): com.todocompanion.app.util.DayCard.PeriodShareData = com.todocompanion.app.util.DayCard.PeriodShareData(
        periodLabel = label,
        reviewedDays = rollup.reviewedDays,
        periodDays = rollup.periodDays,
        avgRating = rollup.avgRating,
        avgMood = rollup.avgMood,
        moodFace = if (rollup.moodCount > 0) shareMoodFace(rollup.avgMood.roundToInt()) else "",
        hasExec = exec.hasData,
        execPlanned = exec.planned,
        execCompleted = exec.completed,
        execPct = exec.pct,
        wins = rollup.wins.map { it.text },
        winsCount = 0,
        highlight = "",
        habits = rollup.habitConsistency.map { com.todocompanion.app.util.DayCard.ConsistencyLine(it.name, it.kept, it.expected) },
        habitsKept = rollup.habitConsistency.sumOf { it.kept },
        habitsExpected = rollup.habitConsistency.sumOf { it.expected },
        activities = rollup.topActivities.map { com.todocompanion.app.util.DayCard.ActivityLine(it.name, it.minutes) },
        trackedMin = rollup.topActivities.sumOf { it.minutes },
        goals = rollup.goalsMoved.map { it.text + (if (it.count > 1) " · ${it.count} days" else "") },
        themes = themes,
        tasksDone = exec.doneTasks,
        accentArgb = accent,
    )

    /** Map a [YearReviewed.Recap] to the renderer's data model (a highlight + counts rather than win texts). */
    private fun periodDataFromYear(
        recap: com.todocompanion.app.domain.YearReviewed.Recap,
        themes: List<String>,
        accent: Long?,
        label: String = "The last 12 months",
    ): com.todocompanion.app.util.DayCard.PeriodShareData = com.todocompanion.app.util.DayCard.PeriodShareData(
        periodLabel = label,
        reviewedDays = recap.daysReviewed,
        periodDays = recap.periodDays,
        avgRating = recap.avgRating,
        avgMood = recap.avgMood,
        moodFace = if (recap.moodDays > 0) shareMoodFace(recap.avgMood.roundToInt()) else "",
        hasExec = false,
        execPlanned = 0,
        execCompleted = 0,
        execPct = 0,
        wins = emptyList(),
        winsCount = recap.winsCount,
        highlight = recap.highlightText,
        habits = recap.habitConsistency.map { com.todocompanion.app.util.DayCard.ConsistencyLine(it.name, it.kept, it.expected) },
        habitsKept = recap.habitConsistency.sumOf { it.kept },
        habitsExpected = recap.habitConsistency.sumOf { it.expected },
        activities = recap.topActivities.map { com.todocompanion.app.util.DayCard.ActivityLine(it.name, it.minutes) },
        trackedMin = recap.trackedMinutes,
        goals = emptyList(),
        themes = themes,
        tasksDone = recap.tasksFinished,
        accentArgb = accent,
    )

    /** R1/R2: the unified momentum snapshot — one place both the dashboard, widget and digest read. */
    data class Momentum(val momentum: Int, val habitStrength: Int?, val taskReliability: Int?, val focusWeek: Int)
    fun momentumSnapshot(): Momentum {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val cks = habitCheckins.value
        val strengths = habits.value.filter { !it.archived }.map { h ->
            val d = cks.filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val s = cks.filter { it.habitId == h.id && it.status == "skip" }.map { it.epochDay }.toSet()
            val r = cks.filter { it.habitId == h.id && hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            hs.strength(h, d, s, r, today)
        }
        val habitStrength = if (strengths.isEmpty()) null else strengths.average().toInt()
        val relVals = taskReliability.value.values.map { it.score }
        val taskRel = if (relVals.isEmpty()) null else relVals.average().toInt()
        val weekDays = (0 until 7).map { today - it }.toSet()
        val focusWeek = focusViews().filter { it.epochDay in weekDays }.sumOf { it.minutes }
        val parts = buildList {
            habitStrength?.let { add(it.toDouble() to 0.5) }
            taskRel?.let { add(it.toDouble() to 0.35) }
            add((focusWeek.coerceAtMost(300) / 300.0 * 100) to 0.15)
        }
        val wsum = parts.sumOf { it.second }
        val momentum = if (wsum == 0.0) 0 else (parts.sumOf { it.first * it.second } / wsum).toInt()
        return Momentum(momentum, habitStrength, taskRel, focusWeek)
    }

    /** R2: the weekly "state of you" digest — this week vs last, across habits, tasks and focus. Track 1 — now
     *  derived from two [ReviewRollup]s (this week + the week before), the shared aggregation the recap and the
     *  Day-review roll-up also read, so the three surfaces report identical numbers. Time entries stay gated by
     *  the Time module, exactly as before, so the Time tile appears only when that module is on and has data. */
    fun weeklyDigest(): com.todocompanion.app.domain.WeeklyDigest.Digest {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val now = System.currentTimeMillis()
        val timeOn = com.todocompanion.app.domain.Modules.isEnabled(settings.value, com.todocompanion.app.domain.Modules.TIME)
        val te = if (timeOn) timeVm.timeEntries.value else emptyList()
        val RR = com.todocompanion.app.domain.ReviewRollup
        val cur = RR.compute(
            today - 6, today, emptyList(), emptyList(), habits.value, habitCheckins.value, te, emptyList(),
            zone, now, tasks = tasks.value, focusSessions = focusSessions.value,
        )
        val prev = RR.compute(
            today - 13, today - 7, emptyList(), emptyList(), habits.value, habitCheckins.value, te, emptyList(),
            zone, now, tasks = tasks.value, focusSessions = focusSessions.value,
        )
        return com.todocompanion.app.domain.WeeklyDigest.fromRollups(
            cur, prev, habits.value, habitCheckins.value, tasks.value, focusSessions.value,
            momentumSnapshot().momentum, today,
        )
    }

    /**
     * R3: unified capture — one line becomes a habit or a task. [forceKind] lets the UI honour a manual
     * flip of the auto-classification; null means "use the classifier's guess".
     */
    fun smartCapture(text: String, forceKind: com.todocompanion.app.domain.nlp.SmartCapture.Kind? = null, onDone: (com.todocompanion.app.domain.nlp.SmartCapture.Kind) -> Unit = {}) {
        // Classify task-vs-habit on the token-free text; the actual task creation passes the RAW text
        // to submitQuickAdd, which is the single funnel for inline tokens (#t25, *, !) AND @contexts.
        val trimmed = com.todocompanion.app.domain.nlp.QuickTokens.parse(text).text.trim()
        if (trimmed.isBlank()) return
        val hbt = com.todocompanion.app.domain.nlp.SmartCapture.Kind.HABIT
        val tsk = com.todocompanion.app.domain.nlp.SmartCapture.Kind.TASK
        val M = com.todocompanion.app.domain.Modules
        val s = settings.value
        val kind0 = forceKind ?: com.todocompanion.app.domain.nlp.SmartCapture.classify(trimmed).kind
        // I6: never file into a disabled module. A habit line with Habits off becomes a task (and vice
        // versa when Tasks is off but Habits is on).
        val kind = when {
            kind0 == hbt && !M.isEnabled(s, M.HABITS) -> tsk
            kind0 == tsk && !M.isEnabled(s, M.TASKS) && M.isEnabled(s, M.HABITS) -> hbt
            else -> kind0
        }
        if (kind == com.todocompanion.app.domain.nlp.SmartCapture.Kind.HABIT) {
            addHabit(com.todocompanion.app.domain.habit.HabitQuickParser.parse(trimmed))
        } else {
            submitQuickAdd(text, QuickAddOptions())
        }
        onDone(kind)
    }

    // ---------- Tier S: time tracking ----------
    // The live timer + activity actions and the scoped flows now live in TimeTrackingViewModel (declared
    // above as `timeVm`). Only helpers that touch broader VM state remain here (below); screens reach the
    // whole surface through `timeVm`, which forwards those helpers back to this VM.
    private fun refreshTimeWidget() = com.todocompanion.app.widget.TimeWidget.refresh(appCtx)

    /**
     * One-tap "start now, decide later" — starts the clock against the most sensible activity
     * (last-used, else a pinned one, else the first). Returns false only when there is no activity at
     * all, so the caller can open the new-activity dialog.
     */
    fun startTimeTrackingSmart(): Boolean {
        val acts = timeVm.timeActivities.value.filter { !it.archived }
        if (acts.isEmpty()) return false
        val lastUsed = timeVm.timeEntries.value.maxByOrNull { it.startMillis }?.activityId?.let { id -> acts.firstOrNull { it.id == id } }
        val pinned = acts.firstOrNull { it.id in settings.value.pinnedActivities }
        val pick = lastUsed ?: pinned ?: acts.first()
        timeVm.startTimeTracking(pick.id)
        return true
    }

    // ── U12 · automation rules ──────────────────────────────────────────────────────────────────
    fun automationRules(): List<com.todocompanion.app.domain.AutomationRule> =
        com.todocompanion.app.domain.AutomationRules.parse(settings.value.automationRulesJson)
    fun saveAutomationRules(rules: List<com.todocompanion.app.domain.AutomationRule>) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(automationRulesJson = com.todocompanion.app.domain.AutomationRules.encode(rules)))
    }

    // ── U2 · (re)schedule today's timebox → track prompts ───────────────────────────────────────
    fun rescheduleTrackPrompts() = viewModelScope.launch {
        if (settings.value.autoTrackPrompt) com.todocompanion.app.reminders.AlarmScheduler.scheduleTrackPrompts(appCtx, repo)
    }

    // ── U1 · untracked planned blocks + one-tap fill ────────────────────────────────────────────
    fun untrackedTodayBlocks(): List<com.todocompanion.app.domain.TimeInsights.PlannedBlock> =
        com.todocompanion.app.domain.TimeReports.untrackedTodayBlocks(
            tasks.value, timeVm.timeEntries.value, zone, System.currentTimeMillis())
    /** U1: backfill a planned block's time interval against its task, in one tap. */
    fun fillTrackedBlock(block: com.todocompanion.app.domain.TimeInsights.PlannedBlock) = viewModelScope.launch {
        val zone = this@AppViewModel.zone
        val dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = dayStart + block.startMin * 60_000L
        val end = (start + block.durMin * 60_000L).coerceAtMost(System.currentTimeMillis())
        if (end <= start) return@launch
        val task = repo.getTask(block.taskId)
        val actId = task?.defaultActivityId?.takeIf { id -> timeVm.timeActivities.value.any { it.id == id && !it.archived } }
            ?: repo.ensureTaskActivity()
        repo.addManualTimeEntry(actId, start, end, note = "", taskId = block.taskId)
        refreshTimeWidget()
    }

    // ── U6 · plan vs actual (this week) + calibration ───────────────────────────────────────────
    fun planVsActualWeek(): com.todocompanion.app.domain.TimeInsights.PlanActual =
        com.todocompanion.app.domain.TimeReports.planVsActualWeek(
            tasks.value, timeVm.timeEntries.value, zone, System.currentTimeMillis())

    // ── U7 · cross-type correlation ("what moves your momentum") ────────────────────────────────
    fun momentumLinks(windowDays: Int = 60): List<String> =
        com.todocompanion.app.domain.TimeReports.momentumLinks(
            habits.value, habitCheckins.value, timeVm.timeActivities.value, timeVm.timeEntries.value,
            zone, windowDays)

    // ── V6 · cross-type tag report — hours + tasks + habit-days grouped by one tag ──────────────
    fun crossTypeTagReport(windowDays: Int = 7): List<com.todocompanion.app.domain.TimeReports.TagLine> =
        com.todocompanion.app.domain.TimeReports.crossTypeTagReport(
            tasks.value, timeVm.timeEntries.value, habits.value, habitCheckins.value, tags.value, taskTags.value,
            zone, System.currentTimeMillis(), windowDays)

    // ── V12 · rewards store ─────────────────────────────────────────────────────────────────────
    fun rewards(): List<com.todocompanion.app.domain.Reward> = com.todocompanion.app.domain.Rewards.parse(settings.value.rewardsJson)
    fun saveRewards(list: List<com.todocompanion.app.domain.Reward>) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(rewardsJson = com.todocompanion.app.domain.Rewards.encode(list)))
    }
    fun redeemReward(r: com.todocompanion.app.domain.Reward) = viewModelScope.launch {
        val s = settings.value
        if (s.pointsBalance >= r.cost) {
            val list = com.todocompanion.app.domain.Rewards.parse(s.rewardsJson).map { if (it.id == r.id) it.copy(redeemed = it.redeemed + 1) else it }
            repo.saveSettings(s.copy(pointsBalance = s.pointsBalance - r.cost, rewardsJson = com.todocompanion.app.domain.Rewards.encode(list)))
            toast("Enjoy your ${r.name}! 🎉")
        } else toast("${r.cost - s.pointsBalance} more point${if (r.cost - s.pointsBalance == 1) "" else "s"} to go")
    }

    // ══ Tier W ══════════════════════════════════════════════════════════════════════════════════

    // ── W1 · Omnibox — one field routes to timer / habit / task ─────────────────────────────────
    /** Detect a "track now" intent (a leading track verb, or a bare @activity) → start a timer; else
     *  fall through to smart capture (habit vs task). onDone reports "timer" | "habit" | "task". */
    fun omniCapture(text: String, onDone: (String) -> Unit = {}) {
        val raw = text.trim()
        if (raw.isBlank()) return
        val verb = Regex("^(?:track|start|timer)\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw)
        val tok = com.todocompanion.app.domain.nlp.QuickTokens.parse(raw)
        val bareActivity = tok.activity != null && tok.text.isBlank()
        if ((verb != null || bareActivity) && com.todocompanion.app.domain.Modules.isEnabled(settings.value, com.todocompanion.app.domain.Modules.TIME)) {
            val actName = (tok.activity ?: verb!!.groupValues[1]).trim().removePrefix("@")
            if (actName.isNotBlank()) { timeVm.startTimeTrackingByName(actName); onDone("timer"); return }
        }
        smartCapture(raw) { k -> onDone(if (k == com.todocompanion.app.domain.nlp.SmartCapture.Kind.HABIT) "habit" else "task") }
    }

    // ── W2 · Right Now — the single next best action across modules ──────────────────────────────
    data class RightNow(val kind: String, val title: String, val subtitle: String, val actionLabel: String,
                        val taskId: String? = null, val habitId: String? = null)
    fun rightNow(): RightNow? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val zone = this.zone
        val nowMin = java.time.LocalTime.now(zone).let { it.hour * 60 + it.minute }
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val dueHabits = habits.value.filter { !it.paused && !it.archived }.filter { h ->
            val hc = habitCheckins.value.filter { it.habitId == h.id }
            val done = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            hs.dueToday(h, today, done, hc.firstOrNull { it.epochDay == today }?.count ?: 0)
        }
        // 0) Y2 — your keystone habit, if it's still due today, beats everything: it's your highest lever.
        keystoneHabitId()?.let { kid -> dueHabits.firstOrNull { it.id == kid }?.let { k ->
            return RightNow("habit", (k.emoji?.plus(" ") ?: "") + k.name, "🗝️ Your keystone — the habit that lifts your whole day", "Check off", habitId = k.id)
        } }
        // 1) A due habit whose typical done-time is near now (rhythm) — the strongest "do this now" signal.
        val rhythm = dueHabits.mapNotNull { h ->
            val mins = habitCheckins.value.filter { it.habitId == h.id }.mapNotNull { it.doneAtMinute }
            if (mins.size < 3) null else {
                val typical = mins.sorted()[mins.size / 2]
                val delta = kotlin.math.abs(typical - nowMin)
                if (delta <= 90) h to delta else null
            }
        }.minByOrNull { it.second }?.first
        if (rhythm != null) return RightNow("habit", (rhythm.emoji?.plus(" ") ?: "") + rhythm.name, "You usually do this around now", "Check off", habitId = rhythm.id)
        // 2) The top do-next task.
        topDoNext()?.let { t -> return RightNow("task", t.title, if (t.dueDate != null) "Your top task, due soon" else "Your top task", "Start", taskId = t.id) }
        // 3) Any due habit.
        dueHabits.firstOrNull()?.let { return RightNow("habit", (it.emoji?.plus(" ") ?: "") + it.name, "Due today", "Check off", habitId = it.id) }
        return null
    }

    // ── W4 · Balance — where the week actually went, by life area (cross-type tags) ───────────────
    fun balanceBreakdown(windowDays: Int = 7): List<com.todocompanion.app.domain.TimeReports.BalanceSlice> =
        com.todocompanion.app.domain.TimeReports.balanceBreakdown(
            tasks.value, timeVm.timeEntries.value, habits.value, habitCheckins.value, tags.value, taskTags.value,
            zone, System.currentTimeMillis(), windowDays)

    // ── W7 · Self-writing weekly review ─────────────────────────────────────────────────────────
    fun weeklyReviewText(): String {
        val zone = this.zone
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val startDay = today.minusDays(6).toEpochDay(); val endDay = today.toEpochDay()
        val tracked = com.todocompanion.app.domain.TimeTracking.totalMinutes(timeVm.timeEntries.value, weekStart, now + 1, now)
        val tasksDone = tasks.value.count { t -> t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in startDay..endDay } == true }
        val habitByIdWk = habits.value.associateBy { it.id }
        val habitDays = habitCheckins.value.count { c -> c.epochDay in startDay..endDay && habitByIdWk[c.habitId]?.let { com.todocompanion.app.domain.habit.HabitStats.isSuccessDay(it, c) } == true }
        val digest = weeklyDigest()
        val pa = planVsActualWeek()
        val leftover = tasks.value.count { t -> !t.completed && !t.trashed && !t.abandoned && t.dueDate?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() <= endDay } == true }
        return buildString {
            appendLine("Your week — ${today.minusDays(6).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} to ${today.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}")
            appendLine()
            appendLine("• ${tasksDone} task${if (tasksDone == 1) "" else "s"} finished, ${tracked / 60}h ${tracked % 60}m tracked, ${habitDays} habit check-in${if (habitDays == 1) "" else "s"}.")
            if (pa.items.isNotEmpty()) appendLine("• Planned ${pa.plannedMin / 60}h ${pa.plannedMin % 60}m vs tracked ${pa.actualMin / 60}h ${pa.actualMin % 60}m.")
            digest.bestHabit?.let { appendLine("• Strongest habit: $it.") }
            digest.slippingHabit?.let { appendLine("• Room to grow: $it.") }
            if (leftover > 0) appendLine("• ${leftover} open task${if (leftover == 1) "" else "s"} carry into next week.")
            appendLine()
            appendLine(digest.takeaway)
        }.trim()
    }

    // ── W6 · Routine tags ───────────────────────────────────────────────────────────────────────
    // Routines are per-workspace: a blank workspaceId is legacy data, treated as the default workspace.
    private fun routineWs(r: com.todocompanion.app.domain.Routine) = r.workspaceId.ifBlank { com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID }
    // W3 (cross-module unification) — Routines now live in a Room table, not the settings-JSON blob. These flows
    // drive the routine screens (the settings `routinesJson` no longer changes); `routines()` returns the active-
    // workspace value so the many synchronous internal callers (routinesDueToday/logRoutineRun/runRoutineByName)
    // are unchanged.
    private val allRoutines: StateFlow<List<com.todocompanion.app.domain.Routine>> = repo.observeRoutines().state(emptyList())
    val routinesState: StateFlow<List<com.todocompanion.app.domain.Routine>> =
        combine(allRoutines, activeWs) { all, ws -> all.filter { routineWs(it) == ws } }.state(emptyList())
    val routineRunsState: StateFlow<List<com.todocompanion.app.domain.RoutineRun>> = repo.observeRoutineRuns().state(emptyList())
    fun routines(): List<com.todocompanion.app.domain.Routine> = routinesState.value
    /** [list] is the ACTIVE workspace's routines; other workspaces' are left intact. Blank ids are stamped with
     *  the active workspace. Re-arms the daily routine nudges whenever the set/times change (self-healing). */
    fun saveRoutines(list: List<com.todocompanion.app.domain.Routine>) = viewModelScope.launch {
        val ws = activeWorkspace()
        val mine = list.map { if (it.workspaceId.isBlank()) it.copy(workspaceId = ws) else it }
        repo.replaceWorkspaceRoutines(ws, mine)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleRoutineReminders(appCtx, repo)
    }
    /** A routine to auto-open in the runner (set by the reminder deep-link; RoutinesScreen consumes it). */
    val pendingRoutineRun = MutableStateFlow<String?>(null)
    fun requestRoutineRun(id: String) { pendingRoutineRun.value = id }

    /** The single in-progress run persisted so a routine survives the app being killed mid-run. */
    fun activeRoutineRun(): com.todocompanion.app.domain.ActiveRoutineRun? =
        com.todocompanion.app.domain.ActiveRoutineRuns.parse(settings.value.activeRoutineRunJson)
    fun saveActiveRoutineRun(run: com.todocompanion.app.domain.ActiveRoutineRun) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(activeRoutineRunJson = com.todocompanion.app.domain.ActiveRoutineRuns.encode(run)))
    }
    fun clearActiveRoutineRun() = viewModelScope.launch {
        if (settings.value.activeRoutineRunJson.isNotBlank())
            repo.saveSettings(settings.value.copy(activeRoutineRunJson = ""))
    }
    fun runRoutine(r: com.todocompanion.app.domain.Routine) = viewModelScope.launch {
        if (r.activityId.isNotBlank() && timeVm.timeActivities.value.any { it.id == r.activityId && !it.archived }) {
            repo.startTimeTracking(r.activityId, stopFirst = !settings.value.multiTimer)
            com.todocompanion.app.reminders.AutomationRunner.onStart(appCtx, repo, r.activityId)
            // SEC (R2-B/M4) — only emit the activity name to another app when the automation API is on, and
            // only to the user's pinned package (blank target = no outgoing event).
            if (settings.value.automationApi)
                com.todocompanion.app.reminders.TimeIntentApi.broadcastStarted(appCtx, timeVm.timeActivities.value.firstOrNull { it.id == r.activityId }?.name ?: "", settings.value.automationTargetPackage)
            refreshTimeWidget()
        }
        toast("Routine “${r.name}” started")
    }
    fun runRoutineByName(name: String) = viewModelScope.launch {
        com.todocompanion.app.domain.Routines.byName(routines(), name)?.let { runRoutine(it) }
    }
    // ── Routines · press-play sequences (add/edit/delete, run history, cross-module tick) ────────────
    /** Insert or replace a routine by id, then persist. */
    fun upsertRoutine(r: com.todocompanion.app.domain.Routine) {
        val list = routines()
        val idx = list.indexOfFirst { it.id == r.id }
        saveRoutines(if (idx >= 0) list.toMutableList().also { it[idx] = r } else list + r)
    }
    fun deleteRoutine(id: String) = viewModelScope.launch {
        repo.deleteRoutine(id)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleRoutineReminders(appCtx, repo)
    }
    /** Capped press-play run history (adherence, keystone, on-this-day) — W3: Room-backed. */
    fun routineRuns(): List<com.todocompanion.app.domain.RoutineRun> = routineRunsState.value
    /** Runnable routines scheduled today (by cadence) that recur — reminder-set or with explicit days — and
     *  aren't yet FINISHED today. The "press play" set the Today screen surfaces, so a scheduled ritual is
     *  reachable from the daily plan, not only the drawer. */
    fun routinesDueToday(): List<com.todocompanion.app.domain.Routine> {
        val t = today()
        // Only a FINISHED run clears the ritual from Today — a partial/abandoned run (finished=false) shouldn't
        // make it disappear as if kept. Surface a ritual that's scheduled today and is either reminder-set OR
        // has an explicit day cadence, so a cadenced routine reaches the daily plan even without a reminder.
        val ranToday = routineRuns().filter { it.epochDay == t && it.finished }.map { it.routineId }.toSet()
        return routines().filter {
            it.isRunnable && it.scheduledOn(t) && it.id !in ranToday && (it.whenReminderMin != null || it.days.isNotEmpty())
        }
    }
    /** Tick a habit for today — the same check-off path the Habits screen uses (setHabitValue). */
    fun completeHabitToday(habitId: String) {
        val h = habits.value.firstOrNull { it.id == habitId } ?: return
        setHabitValue(h, java.time.LocalDate.now(zone).toEpochDay(), h.targetPerDay.coerceAtLeast(1))
    }
    /** Log a run and, for every completed step, tick its linked habit (today) / complete its linked
     *  task — the cross-module magic where one press-play flows across habits, tasks and the tracker. */
    fun logRoutineRun(run: com.todocompanion.app.domain.RoutineRun) = viewModelScope.launch {
        routines().firstOrNull { it.id == run.routineId }?.steps
            ?.filter { it.id in run.completedStepIds }
            ?.forEach { step ->
                step.linkedHabitId?.let { completeHabitToday(it) }
                step.linkedTaskId?.let { tid -> repo.getTask(tid)?.let { if (!it.completed) toggleComplete(it) } }
            }
        repo.appendRoutineRun(run)   // W3 — Room-backed, capped to the newest 400 (matches the old JSON cap)
    }

    // ── W3 · Plan my day — auto-block, then measure the loop ────────────────────────────────────
    /** Lay today's estimated do-next tasks into time-blocks (rhythm-aware), and turn on the
     *  timebox→track prompt so each block asks to be tracked — closing plan → do → measure. */
    fun planMyDay(onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val plan = computeAutoSchedule()
        if (plan.proposals.isEmpty()) { onDone(0); return@launch }
        val dues = plan.proposals.joinToString(",") { "\"${it.task.id}\":\"${it.task.dueDate ?: 0L}\"" }   // Z6: old due dates for undo
        plan.proposals.forEach { p -> repo.saveTask(p.task.copy(dueDate = p.newDueMillis, isAllDay = false)) }
        if (!settings.value.autoTrackPrompt) repo.saveSettings(settings.value.copy(autoTrackPrompt = true))
        rescheduleTrackPrompts()
        appendAction("plan", "Scheduled ${plan.proposals.size} task${if (plan.proposals.size == 1) "" else "s"} for today", "{\"dues\":{$dues}}")
        onDone(plan.proposals.size)
    }

    // ── W8 · per-item reminder mute ─────────────────────────────────────────────────────────────
    fun toggleMutedHabit(id: String) = viewModelScope.launch {
        val cur = settings.value.mutedHabits
        val muting = id !in cur
        repo.saveSettings(settings.value.copy(mutedHabits = if (muting) cur + id else cur - id))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        // Y2: quietly guard the keystone — warn before silencing your highest-leverage habit.
        if (muting && keystoneHabitId() == id) toast("Heads up — this is your keystone habit")
    }
    fun toggleMutedList(id: String) = viewModelScope.launch {
        val cur = settings.value.mutedLists
        repo.saveSettings(settings.value.copy(mutedLists = if (id in cur) cur - id else cur + id))
    }
    /** R107 — mute/unmute reminders for a whole folder (silences every list inside it). */
    fun toggleMutedFolder(id: String) = viewModelScope.launch {
        val cur = settings.value.mutedFolders
        repo.saveSettings(settings.value.copy(mutedFolders = if (id in cur) cur - id else cur + id))
    }

    // ══ Tier X · the reasoning layer ═════════════════════════════════════════════════════════════

    private fun fmt1(x: Double): String = String.format(java.util.Locale.US, "%.1f", x)
    private fun hourLabel(h24: Int): String {
        val h = ((h24 % 24) + 24) % 24
        val ampm = if (h < 12) "am" else "pm"; val h12 = ((h + 11) % 12) + 1
        return "$h12$ampm"
    }
    /** Tasks completed per epoch-day within [sinceDay]..[untilDay] inclusive. */
    private fun tasksCompletedByDay(sinceDay: Long, untilDay: Long): Map<Long, Int> {
        val m = HashMap<Long, Int>()
        tasks.value.forEach { t ->
            val ca = t.completedAt ?: return@forEach
            val d = java.time.Instant.ofEpochMilli(ca).atZone(zone).toLocalDate().toEpochDay()
            if (d in sinceDay..untilDay) m[d] = (m[d] ?: 0) + 1
        }
        return m
    }

    // ── X1 · Unified Goals ────────────────────────────────────────────────────────────────────────
    // Goals are per-workspace: a blank workspaceId is legacy data, treated as the default workspace.
    private fun goalWs(g: com.todocompanion.app.domain.Goal) = g.workspaceId.ifBlank { com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID }
    // W3 (cross-module unification) — Goals now live in a Room table, not the settings-JSON blob. These flows
    // drive the goal screens (the settings `goalsJson` no longer changes, so a `remember(goalsJson)` would go
    // stale). `goalsState` is the active-workspace set the UI collects; `goals()` returns its value so the many
    // synchronous internal callers (goalHealth/goalCapacity/search/note-embed/upsert) are unchanged.
    private val allGoals: StateFlow<List<com.todocompanion.app.domain.Goal>> = repo.observeGoals().state(emptyList())
    val goalsState: StateFlow<List<com.todocompanion.app.domain.Goal>> =
        combine(allGoals, activeWs) { all, ws -> all.filter { goalWs(it) == ws } }.state(emptyList())
    val goalReviewsState: StateFlow<List<com.todocompanion.app.domain.GoalReview>> = repo.observeGoalReviews().state(emptyList())
    fun goals(): List<com.todocompanion.app.domain.Goal> = goalsState.value
    /** [list] is the ACTIVE workspace's goals; other workspaces' goals are left intact so a save here never
     *  wipes them. Blank ids are stamped with the active workspace. */
    fun saveGoals(list: List<com.todocompanion.app.domain.Goal>) = viewModelScope.launch {
        val ws = activeWorkspace()
        val mine = list.map { if (it.workspaceId.isBlank()) it.copy(workspaceId = ws) else it }
        repo.replaceWorkspaceGoals(ws, mine)
    }
    data class GoalHealth(
        val goal: com.todocompanion.app.domain.Goal,
        val taskDone: Int, val taskTotal: Int,
        val habitStreak: Int, val habitStrength: Int,
        val minutesTracked: Int, val budgetMin: Int,
        val overall: Double, val daysLeft: Int?,
    )
    fun goalHealth(g: com.todocompanion.app.domain.Goal): GoalHealth {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val now = System.currentTimeMillis()
        var tDone = 0; var tTotal = 0
        if (g.hasTasks) {
            val inList = tasks.value.filter { it.listId == g.listId && !it.trashed && !it.isNote && !it.abandoned }
            tTotal = inList.size; tDone = inList.count { it.completed }
        }
        var streak = 0; var strength = 0
        // Archived-inclusive: an archived lead habit's strength/streak should freeze at its last value (its
        // check-ins simply stop growing), not drop to 0 — otherwise the goal reads as failing the moment the
        // supporting practice is retired. habits.value strips archived, so use the archived-inclusive flow.
        if (g.hasHabit) habitsWithArchived.value.firstOrNull { it.id == g.habitId }?.let { h ->
            val (done, skip, relapse) = hs.daySets(h, habitCheckins.value)   // R108 audit C2
            val today = java.time.LocalDate.now(zone).toEpochDay()
            streak = hs.displayStreak(h, done, skip, relapse, today, settings.value.forgivingStreaks)
            strength = strengthOf(h)   // Z8: honours the graded-strength opt-in
        }
        var mins = 0
        if (g.hasBudget) mins = timeVm.timeEntries.value.filter { it.activityId == g.activityId }.sumOf { it.minutes(now) }
        val fracs = ArrayList<Double>()
        if (g.hasTasks && tTotal > 0) fracs += tDone.toDouble() / tTotal
        if (g.hasHabit) fracs += strength / 100.0
        if (g.hasBudget && g.budgetMinutes > 0) fracs += (mins.toDouble() / g.budgetMinutes).coerceAtMost(1.0)
        // A goal's outcomes count toward its health too — a KR-only or milestone-only goal must not read 0%.
        g.keyResultFraction?.let { fracs += it }
        if (g.milestones.isNotEmpty()) fracs += g.milestonesDone.toDouble() / g.milestones.size
        val overall = if (fracs.isEmpty()) 0.0 else fracs.average()
        val daysLeft = if (g.targetEpochDay > 0) (g.targetEpochDay - java.time.LocalDate.now(zone).toEpochDay()).toInt() else null
        return GoalHealth(g, tDone, tTotal, streak, strength, mins, g.budgetMinutes, overall, daysLeft)
    }

    // ── Phase B · goal editing + the review/accountability layer ─────────────────────────────────
    fun upsertGoal(g: com.todocompanion.app.domain.Goal) {
        val cur = goals()
        saveGoals(if (cur.any { it.id == g.id }) cur.map { if (it.id == g.id) g else it } else cur + g)
    }
    fun deleteGoal(id: String) = viewModelScope.launch { repo.deleteGoal(id) }

    /** The weekly-review log (newest last). Drives the scoreboard trend + the integrity chain. */
    fun goalReviews(): List<com.todocompanion.app.domain.GoalReview> = goalReviewsState.value
    fun saveGoalReviews(list: List<com.todocompanion.app.domain.GoalReview>) = viewModelScope.launch {
        repo.replaceGoalReviews(list)
    }
    /** Record one review sitting (portfolio when goalId is blank). */
    fun logGoalReview(goalId: String, executionPct: Int, commitmentsKept: Int, commitmentsTotal: Int, note: String) {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val total = commitmentsTotal.coerceAtLeast(0)
        val r = com.todocompanion.app.domain.GoalReview(
            id = java.util.UUID.randomUUID().toString(), goalId = goalId, epochDay = today,
            executionPct = executionPct.coerceIn(0, 100), commitmentsKept = commitmentsKept.coerceIn(0, total),
            commitmentsTotal = total, note = note.trim(), createdAt = System.currentTimeMillis(),
        )
        saveGoalReviews(com.todocompanion.app.domain.GoalReviews.append(goalReviews(), r))
    }

    /**
     * moat #4 — capacity-honest check for a single goal: the weekly hours its time budget implies vs
     * the honest tracked-focus capacity a week actually holds. Returns null when the goal has no time
     * arm or no deadline to spread the budget across.
     */
    data class GoalCapacity(val weeklyNeedH: Double, val weeklyHaveH: Double, val overcommitted: Boolean)
    fun goalCapacity(g: com.todocompanion.app.domain.Goal): GoalCapacity? {
        if (!g.hasBudget) return null
        val today = java.time.LocalDate.now(zone).toEpochDay()
        // Weeks remaining: from the cycle window, else the deadline, else assume a 12-week horizon.
        val weeksLeft: Double = when {
            g.hasCycle -> (com.todocompanion.app.domain.GoalScore.cycle(g, today)?.daysLeft ?: (g.cycleWeeks * 7)).toDouble() / 7.0
            g.targetEpochDay > today -> (g.targetEpochDay - today).toDouble() / 7.0
            else -> 12.0
        }.coerceAtLeast(0.5)
        val mins = timeVm.timeEntries.value.filter { it.activityId == g.activityId }.sumOf { it.minutes(System.currentTimeMillis()) }
        val remainingMin = (g.budgetMinutes - mins).coerceAtLeast(0)
        val needH = (remainingMin / 60.0) / weeksLeft
        val haveH = trackedCapacityHours()?.let { it * 7.0 } ?: (settings.value.dailyCapacityHours * 7.0)
        // Flag when this one goal's weekly demand exceeds your whole weekly focus budget — the number
        // shown (haveH) is the same one the threshold tests, so the warning never contradicts its own text.
        // (Competition *between* goals for the same hours is caught separately by goalContention.)
        return GoalCapacity(needH, haveH, overcommitted = needH > haveH)
    }

    // ── X2 · keystone insight — the habit that lifts your output ─────────────────────────────────
    private fun bestKeystone(windowDays: Int = 60): Pair<com.todocompanion.app.data.entity.HabitEntity, com.todocompanion.app.domain.Reasoning.Keystone>? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val universe = (0 until windowDays).map { today - it }
        val metric = tasksCompletedByDay(today - windowDays + 1, today)
        val doneByHabit = habitCheckins.value.filter { it.status == "done" }.groupBy { it.habitId }
        val candidates = habits.value.filter { !it.paused && !it.archived && it.habitType != "break" }
        return com.todocompanion.app.domain.Reasoning.bestKeystone(candidates, universe, metric) { h ->
            (doneByHabit[h.id] ?: emptyList()).asSequence()
                .filter { hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
        }
    }
    fun keystoneInsight(windowDays: Int = 60): String? {
        val (h, k) = bestKeystone(windowDays) ?: return null
        val pct = Math.round(k.lift * 100).toInt()
        return "On days you keep ‘${h.name}’, you finish ${pct}% more tasks (${fmt1(k.avgWith)} vs ${fmt1(k.avgWithout)} a day)."
    }
    /** Y2 — the id of your highest-leverage (keystone) habit, or null. */
    fun keystoneHabitId(): String? = bestKeystone()?.first?.id

    // ── X3 · honest capacity — plan against real tracked focus-hours ─────────────────────────────
    fun trackedFocusMinutesByDay(days: Int = 21): Map<Long, Int> {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val m = HashMap<Long, Int>()
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val ds = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val de = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val mins = com.todocompanion.app.domain.TimeTracking.totalMinutes(timeVm.timeEntries.value, ds, minOf(de, now + 1), now)
            if (mins > 0) m[d.toEpochDay()] = mins
        }
        return m
    }
    /** Median daily tracked minutes over the recent window, or null if too little signal. */
    fun trackedFocusMedianMinutes(): Int? =
        com.todocompanion.app.domain.Reasoning.medianDailyFocusMinutes(trackedFocusMinutesByDay())
    fun trackedCapacityHours(): Int? = trackedFocusMedianMinutes()?.let { Math.round(it / 60.0).toInt().coerceAtLeast(1) }

    // ── X4 · peak focus window ────────────────────────────────────────────────────────────────────
    fun focusByHour(days: Int = 30): IntArray {
        val agg = IntArray(24); val now = System.currentTimeMillis(); val today = java.time.LocalDate.now(zone)
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val ds = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val de = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val bh = com.todocompanion.app.domain.TimeInsights.minutesByHour(timeVm.timeEntries.value, ds, de, now)
            for (h in 0..23) agg[h] += bh[h]
        }
        return agg
    }
    fun focusPeakWindow(len: Int = 2): com.todocompanion.app.domain.Reasoning.PeakWindow? =
        com.todocompanion.app.domain.Reasoning.peakWindow(focusByHour(), len)
    fun focusPeakLabel(): String? = focusPeakWindow()?.let { "${hourLabel(it.startHour)}–${hourLabel(it.endHour)}" }

    // ── X5 · end-of-day forecast ──────────────────────────────────────────────────────────────────
    data class DayForecast(val willFinish: Int, val willSlip: Int, val neededMin: Int, val availMin: Int,
                           val calibrated: Boolean, val slipTitles: List<String>) {
        val total get() = willFinish + willSlip
    }
    fun dayForecast(): DayForecast? {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val endOfWorkMillis = today.atStartOfDay(zone).plusHours(settings.value.workEndHour.coerceIn(1, 24).toLong()).toInstant().toEpochMilli()
        // Habits pending for the rest of today eat into the time left before work-end.
        val rawAvail = (((endOfWorkMillis - now) / 60_000L).toInt()).coerceAtLeast(0)
        val availMin = (rawAvail - habitReserveForWindow(today.toEpochDay(), 1)).coerceAtLeast(0)
        val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val remaining = doNextRanked().filter { t -> t.dueDate != null && t.dueDate!! < endToday }
        if (remaining.isEmpty()) return null
        fun est(t: TaskEntity) = (t.estimateMin ?: t.estimateMax ?: t.durationMin ?: 30)
        // Y7: cost each task by its own activity's calibration when known, else the global factor.
        val global = planVsActualWeek().calibration
        val actCal = calibrationByActivity()
        fun factorFor(t: TaskEntity): Double = (dominantActivityOf(t.id)?.let { actCal[it] } ?: global ?: 1.0).coerceIn(0.25, 4.0)
        val costs = remaining.map { (Math.round(est(it) * factorFor(it)).toInt()).coerceAtLeast(1) }
        val needed = costs.sum()
        val (finish, slip) = com.todocompanion.app.domain.Reasoning.fitCount(costs, availMin)
        val slipTitles = if (slip > 0) remaining.takeLast(slip).map { it.title } else emptyList()
        return DayForecast(finish, slip, needed, availMin, global != null || actCal.isNotEmpty(), slipTitles)
    }

    // ── X6 · rhythm-matched schedule ──────────────────────────────────────────────────────────────
    data class RhythmSuggestion(val habitId: String, val weekdays: Set<Int>, val minute: Int?) {
        fun weekdayLabel(): String {
            val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            return weekdays.sorted().joinToString(", ") { names[it - 1] }
        }
    }
    fun rhythmSuggestion(habitId: String, windowDays: Int = 120): RhythmSuggestion? {
        val h = habits.value.firstOrNull { it.id == habitId } ?: return null
        if (h.habitType == "break") return null   // a quit habit has no positive daily action to schedule
        if (!(h.freqType == com.todocompanion.app.domain.habit.HabitStats.FREQ_WEEKLY && h.scheduleDays.isBlank())) return null   // only for "every day" weekly habits
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val counts = IntArray(7)
        val hc = habitCheckins.value.filter { it.habitId == h.id && it.status == "done" }
        hc.forEach { ci ->
            if (today - ci.epochDay in 0 until windowDays && hs.meetsGoal(h, ci.count)) {
                val dow = java.time.LocalDate.ofEpochDay(ci.epochDay).dayOfWeek.value
                counts[dow - 1]++
            }
        }
        val wd = com.todocompanion.app.domain.Reasoning.rhythmWeekdays(counts) ?: return null
        return RhythmSuggestion(habitId, wd, hs.typicalDoneMinute(hc))
    }
    fun applyRhythmSuggestion(s: RhythmSuggestion) = viewModelScope.launch {
        val h = habits.value.firstOrNull { it.id == s.habitId } ?: return@launch
        val days = s.weekdays.sorted().joinToString(",")
        repo.upsertHabit(h.copy(freqType = com.todocompanion.app.domain.habit.HabitStats.FREQ_WEEKLY, scheduleDays = days))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
        appendAction("rhythm", "Matched ‘${h.name}’ to ${s.weekdayLabel()}", "{\"habit\":\"${h.id}\",\"freq\":\"${h.freqType}\",\"days\":\"${h.scheduleDays}\"}")   // Z6: undoable
        toast("Schedule matched to your rhythm")
    }

    // ── X7 · insights feed — what your data noticed (Z1 why · Z2 dismiss · Z3 confidence) ──────────
    /** [key] is stable so Z2 can dismiss/snooze it; [why] shows Z1's working; [confidence] gates Z3. */
    data class Insight(val key: String, val text: String, val why: String? = null, val confidence: String? = null)
    fun insightsFeed(): List<Insight> {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val prefs = insightPrefs()
        val out = ArrayList<Insight>()
        fun add(i: Insight) { if (!prefs.suppressed(i.key, today)) out += i }

        bestKeystone()?.let { (h, k) ->
            val pct = Math.round(k.lift * 100).toInt()
            val conf = if (k.withN >= 8 && k.withoutN >= 8) "High confidence" else "Early signal"
            add(Insight("keystone", "On days you keep ‘${h.name}’, you finish ${pct}% more tasks.",
                "Compared ${k.withN} days you kept it (avg ${fmt1(k.avgWith)} tasks) with ${k.withoutN} days you didn't (avg ${fmt1(k.avgWithout)}), over the last 60 days. This is a correlation, not proof that the habit causes the difference.", conf))
        }
        val pa = planVsActualWeek()
        pa.calibration?.let { cal -> if (kotlin.math.abs(cal - 1.0) >= 0.2) {
            val pct = Math.round((cal - 1.0) * 100).toInt()
            add(Insight("calibration",
                if (cal > 1) "Your tasks take about ${pct}% longer than you estimate — the forecast corrects for it."
                else "You beat your estimates by about ${-pct}% — you could plan a little more into a day.",
                "The median actual-over-estimate ratio across ${pa.items.size} tasks this week that had both an estimate and tracked time.",
                if (pa.items.size >= 6) "High confidence" else "Medium confidence"))
        } }
        focusPeakWindow()?.let { w -> add(Insight("peak",
            "You focus most between ${hourLabel(w.startHour)}–${hourLabel(w.endHour)} — a good window to protect for your hardest work.",
            "Summed your tracked minutes by hour over the last 30 days; that two-hour window held ${w.minutes} minutes, the most of any.", null)) }
        val actCal = calibrationByActivity()
        if (actCal.size >= 2) actCal.maxByOrNull { kotlin.math.abs(it.value - 1.0) }?.let { (actId, ratio) ->
            val name = timeVm.timeActivities.value.firstOrNull { it.id == actId }?.name
            if (name != null && kotlin.math.abs(ratio - 1.0) >= 0.2) {
                val pct = Math.round((ratio - 1.0) * 100).toInt()
                add(Insight("actcal",
                    if (pct > 0) "‘$name’ runs about ${pct}% over your estimate — the forecast corrects it on its own."
                    else "‘$name’ comes in about ${-pct}% under your estimate — the forecast accounts for it.",
                    "From at least 3 ‘$name’ tasks with an estimate and tracked time in the last 4 weeks.", null))
            }
        }
        crossTypeTagReport(7).firstOrNull()?.let { line -> if (line.tag.isNotBlank())
            add(Insight("area", "‘${line.tag}’ took the most of your week: ${line.minutes / 60}h ${line.minutes % 60}m, ${line.tasksDone} task${if (line.tasksDone == 1) "" else "s"}, ${line.habitDays} habit-day${if (line.habitDays == 1) "" else "s"}.",
                "Tagged tracked time, completed tasks and kept habits over the last 7 days, grouped by tag.", null))
        }
        seasonality()?.let { add(Insight("seasonality", it, "From tracked minutes per weekday over the last 8 weeks.", null)) }
        momentumLinks(60).firstOrNull()?.let { add(Insight("link", it, "A conditional-rate correlation over the last 60 days. Not proof of cause.", null)) }
        return out.distinctBy { it.key }.take(5)
    }

    // ── Z2 · nudge control — dismiss / snooze / restore any insight ───────────────────────────────
    fun insightPrefs(): com.todocompanion.app.domain.InsightPrefs = com.todocompanion.app.domain.InsightPrefsCodec.parse(settings.value.insightPrefsJson)
    private fun saveInsightPrefs(p: com.todocompanion.app.domain.InsightPrefs) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(insightPrefsJson = com.todocompanion.app.domain.InsightPrefsCodec.encode(p)))
    }
    fun dismissInsight(key: String) = saveInsightPrefs(insightPrefs().dismiss(key))
    fun snoozeInsight(key: String, days: Int = 7) = saveInsightPrefs(insightPrefs().snooze(key, java.time.LocalDate.now(zone).toEpochDay() + days))
    fun restoreInsight(key: String) = saveInsightPrefs(insightPrefs().restore(key))
    fun isInsightSuppressed(key: String): Boolean = insightPrefs().suppressed(key, java.time.LocalDate.now(zone).toEpochDay())

    // ── X8 · day replay & one-tap backfill ────────────────────────────────────────────────────────
    data class ReplayBlock(val taskId: String, val title: String, val startMin: Int, val durMin: Int)
    fun dayReplay(): List<ReplayBlock> {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val blocks = tasks.value.mapNotNull { t ->
            val due = t.dueDate ?: return@mapNotNull null
            if (t.isAllDay || t.trashed || t.isNote) return@mapNotNull null
            val d = java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
            if (d != today) return@mapNotNull null
            val startMin = ((due - dayStart) / 60_000L).toInt().coerceIn(0, 1439)
            val dur = (t.durationMin ?: t.estimateMin ?: t.estimateMax ?: 30).coerceIn(5, 600)
            com.todocompanion.app.domain.TimeInsights.PlannedBlock(t.id, t.title, startMin, dur)
        }
        val passed = blocks.filter { dayStart + (it.startMin + it.durMin) * 60_000L <= now }
        return com.todocompanion.app.domain.TimeInsights.untrackedBlocks(passed, timeVm.timeEntries.value, dayStart, now)
            .map { ReplayBlock(it.taskId, it.label, it.startMin, it.durMin) }
    }
    fun backfillBlock(b: ReplayBlock, activityId: String? = null) = viewModelScope.launch {
        val dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = dayStart + b.startMin * 60_000L
        val end = start + b.durMin * 60_000L
        val act = activityId ?: repo.ensureTaskActivity()
        val id = java.util.UUID.randomUUID().toString()
        repo.upsertTimeEntry(com.todocompanion.app.data.entity.TimeEntryEntity(id, act, start, end, "", b.taskId, null, System.currentTimeMillis(), workspaceId = activeWorkspace()))
        refreshTimeWidget()
        appendAction("backfill", "Logged ${b.durMin}m to ‘${b.title}’", "{\"entry\":\"$id\"}")   // Z6: undoable
        toast("Logged ${b.durMin}m to ‘${b.title}’")
    }

    // ══ Tier Y · the assistant acts on what it knows ═════════════════════════════════════════════

    private fun hm(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
    private fun medianOf(xs: List<Double>): Double {
        val s = xs.sorted(); val n = s.size
        return if (n == 0) 0.0 else if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    // ── Y1 · self-coaching Goals ──────────────────────────────────────────────────────────────────
    data class GoalCoach(val text: String, val startActivityId: String?)
    /** For a goal that's behind pace or has a slipping arm, the single most useful nudge — plus the
     *  activity to start a catch-up session on, when the time arm is the one behind. */
    fun goalCoaching(g: com.todocompanion.app.domain.Goal): GoalCoach? {
        val gh = goalHealth(g)
        // Time arm: required run-rate to hit the target date.
        if (g.hasBudget && gh.daysLeft != null) {
            val remaining = (g.budgetMinutes - gh.minutesTracked).coerceAtLeast(0)
            if (remaining > 0) {
                val text = if (gh.daysLeft <= 0) "Past the target date with ${hm(remaining)} of the budget left — a session still counts."
                    else "To hit the target, about ${hm(remaining / gh.daysLeft.coerceAtLeast(1))}/day for ${gh.daysLeft} more day${if (gh.daysLeft == 1) "" else "s"}."
                return GoalCoach(text, g.activityId)
            }
        }
        // Habit arm slipping.
        if (g.hasHabit && gh.habitStrength in 1..39)
            return GoalCoach("Its habit is slipping (${gh.habitStrength}%) — a check-in today is the highest-leverage move.", null)
        // Task arm with a near deadline.
        if (g.hasTasks && gh.taskTotal > 0 && gh.taskDone < gh.taskTotal && gh.daysLeft != null && gh.daysLeft in 0..3)
            return GoalCoach("${gh.taskTotal - gh.taskDone} task${if (gh.taskTotal - gh.taskDone == 1) "" else "s"} left with the deadline near.", null)
        return null
    }
    fun startActivityTimer(activityId: String) = viewModelScope.launch {
        if (activityId.isNotBlank() && timeVm.timeActivities.value.any { it.id == activityId && !it.archived }) {
            repo.startTimeTracking(activityId, stopFirst = !settings.value.multiTimer)
            com.todocompanion.app.reminders.AutomationRunner.onStart(appCtx, repo, activityId)
            refreshTimeWidget(); toast("Session started")
        }
    }

    // ── Y8 · goal contention — two goals drawing on the same hours ────────────────────────────────
    fun goalContention(): List<String> {
        val gs = goals().filter { it.hasBudget }
        val actName = timeVm.timeActivities.value.associate { it.id to ((it.emoji?.plus(" ") ?: "") + it.name) }
        return gs.groupBy { it.activityId }.filter { it.value.size >= 2 }
            .map { (act, list) ->
                val (label, verb) = if (list.size == 2) "‘${list[0].name}’ and ‘${list[1].name}’" to "both draw"
                    else list.joinToString(", ") { "‘${it.name}’" } to "all draw"
                "$label $verb on ${actName[act] ?: "the same activity"} — they compete for the same hours."
            }
            .take(2)
    }

    // ── Y3 · what-if capacity — will new work fit your real hours? ─────────────────────────────────
    data class CapacitySnapshot(val committedMin: Int, val capacityMin: Int, val tracked: Boolean) {
        val freeMin get() = (capacityMin - committedMin).coerceAtLeast(0)
    }
    fun capacitySnapshot(days: Int = 14): CapacitySnapshot {
        val now = System.currentTimeMillis()
        val end = java.time.LocalDate.now(zone).plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        fun est(t: TaskEntity) = (t.estimateMin ?: t.estimateMax ?: t.durationMin ?: 30)
        val committed = tasks.value.filter { !it.trashed && !it.completed && !it.abandoned && !it.someday && !it.isNote && it.dueDate != null && it.dueDate!! in (now + 1)..end }.sumOf { est(it) }
        val trackedCapH = if (settings.value.honestCapacity) trackedCapacityHours() else null
        val today = java.time.LocalDate.now(zone)
        val capMin = if (trackedCapH != null) trackedCapH * 60 * days
            else (0 until days).sumOf { settings.value.capacityMinutesFor(today.plusDays(it.toLong()).dayOfWeek) }
        // Count pending habit time as committed, so free capacity reflects habits as well as tasks.
        val habitReserve = habitReserveForWindow(today.toEpochDay(), days)
        return CapacitySnapshot(committed + habitReserve, capMin, trackedCapH != null)
    }

    // ── habit time reserve (HabitTime) — habits that consume time reduce reported capacity ────────────
    /** Learned per-habit median daily minutes, from a linked time-tracking activity's history. */
    private fun learnedHabitMinutes(): Map<String, Int> {
        val linked = habits.value.filter { !it.timeActivityId.isNullOrBlank() }
        if (linked.isEmpty()) return emptyMap()
        val entries = timeVm.timeEntries.value
        if (entries.isEmpty()) return emptyMap()
        val byAct = HashMap<String, HashMap<Long, Int>>()   // activityId → (epochDay → minutes)
        for (e in entries) {
            val end = e.endMillis ?: continue
            if (end <= e.startMillis) continue
            val day = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate().toEpochDay()
            val min = ((end - e.startMillis) / 60_000L).toInt()
            if (min <= 0) continue
            byAct.getOrPut(e.activityId) { HashMap() }.merge(day, min, Int::plus)
        }
        fun median(xs: Collection<Int>): Int { if (xs.isEmpty()) return 0; val s = xs.sorted(); return s[s.size / 2] }
        val medByAct = byAct.mapValues { median(it.value.values) }
        return linked.mapNotNull { h -> h.timeActivityId?.let { aid -> medByAct[aid]?.takeIf { it > 0 }?.let { h.id to it } } }.toMap()
    }

    /** Every habit's time reservation on [epochDay] (see HabitTime). */
    fun habitDayReserve(epochDay: Long, learned: Map<String, Int> = learnedHabitMinutes()): List<com.todocompanion.app.domain.habit.HabitTime.DayHabit> =
        com.todocompanion.app.domain.habit.HabitTime.forDay(
            habits.value, habitCheckins.value, settings.value, epochDay,
            java.time.LocalDate.now(zone).toEpochDay(), learned)

    /** Dedicated, timed habit blocks across [days] as busy intervals — feed into Availability.extraBusy. */
    fun habitBusyIntervals(days: List<java.time.LocalDate>): List<Pair<Long, Long>> {
        val learned = learnedHabitMinutes()
        return days.flatMap { d ->
            com.todocompanion.app.domain.habit.HabitTime.busyIntervals(habitDayReserve(d.toEpochDay(), learned), d.toEpochDay(), zone)
        }
    }

    /** Ambient (unplaced) pending habit minutes on [epochDay] — the "habits & upkeep" band. */
    fun habitAmbientReserveMin(epochDay: Long): Int =
        com.todocompanion.app.domain.habit.HabitTime.ambientReserveMin(habitDayReserve(epochDay))

    /** Total pending habit minutes across [days] days from [startDay] — the capacity every surface subtracts. */
    fun habitReserveForWindow(startDay: Long, days: Int): Int {
        val learned = learnedHabitMinutes()
        return (0 until days).sumOf {
            com.todocompanion.app.domain.habit.HabitTime.totalReserveMin(habitDayReserve(startDay + it, learned))
        }
    }

    // ── Y4 · your ideal day — a scaffold from your real patterns ──────────────────────────────────
    data class IdealBlock(val minute: Int, val label: String, val kind: String)
    fun idealDay(): List<IdealBlock> {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val out = ArrayList<IdealBlock>()
        focusPeakWindow(2)?.let { w -> out += IdealBlock(w.startHour * 60, "Deep work — your peak focus window", "focus") }
        val today = java.time.LocalDate.now(zone).toEpochDay()
        habits.value.filter { !it.paused && !it.archived }.forEach { h ->
            val hc = habitCheckins.value.filter { it.habitId == h.id }
            val done = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val due = hs.dueToday(h, today, done, hc.firstOrNull { it.epochDay == today }?.count ?: 0)
            if (due) hs.typicalDoneMinute(hc)?.let { m -> out += IdealBlock(m, (h.emoji?.plus(" ") ?: "") + h.name, "habit") }
        }
        return out.sortedBy { it.minute }
    }

    // ── Y6 · anti-burnout radar — hours up while habit adherence falls ────────────────────────────
    fun burnoutSignal(): String? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val wkStartMs = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val prevStartMs = today.minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli()
        val hoursThis = com.todocompanion.app.domain.TimeTracking.totalMinutes(timeVm.timeEntries.value, wkStartMs, now + 1, now) / 60.0
        val hoursPrev = com.todocompanion.app.domain.TimeTracking.totalMinutes(timeVm.timeEntries.value, prevStartMs, wkStartMs, now) / 60.0
        fun adherence(startDay: Long, endDay: Long): Double {
            var exp = 0; var done = 0
            habits.value.filter { !it.paused && !it.archived && it.habitType != "break" }.forEach { h ->
                val dd = habitCheckins.value.filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                (startDay..endDay).forEach { d -> if (hs.isExpectedDay(h, d)) { exp++; if (d in dd) done++ } }
            }
            return if (exp == 0) -1.0 else done.toDouble() / exp
        }
        val rThis = adherence(today.minusDays(6).toEpochDay(), today.toEpochDay())
        val rPrev = adherence(today.minusDays(13).toEpochDay(), today.minusDays(7).toEpochDay())
        if (rThis < 0 || rPrev < 0) return null
        if (!com.todocompanion.app.domain.Reasoning.burnoutDiverges(hoursThis, hoursPrev, rThis, rPrev)) return null
        val up = Math.round((hoursThis - hoursPrev) / hoursPrev * 100).toInt()
        return "Your tracked hours are up ${up}% this week, but your health-building habits are slipping. That combination is the early shape of burnout — it may be a week to ease off and protect the habits."
    }

    // ── Y7 · per-activity calibration — sharper, category-specific forecasts ──────────────────────
    fun calibrationByActivity(windowDays: Int = 28): Map<String, Double> {
        val now = System.currentTimeMillis()
        val winStart = java.time.LocalDate.now(zone).minusDays((windowDays - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val byTask = timeVm.timeEntries.value.filter { it.taskId != null }.groupBy { it.taskId!! }
        val ratiosByAct = HashMap<String, MutableList<Double>>()
        byTask.forEach { (taskId, es) ->
            val t = tasks.value.firstOrNull { it.id == taskId } ?: return@forEach
            val planned = (t.estimateMin ?: t.durationMin ?: 0); if (planned <= 0) return@forEach
            val actual = es.sumOf { com.todocompanion.app.domain.TimeTracking.minutesInWindow(it.startMillis, it.endMillis, winStart, now + 1, now) }
            if (actual <= 0) return@forEach
            val domAct = es.groupBy { it.activityId }.maxByOrNull { (_, v) -> v.sumOf { it.minutes(now) } }?.key ?: return@forEach
            ratiosByAct.getOrPut(domAct) { mutableListOf() } += actual.toDouble() / planned
        }
        return ratiosByAct.filter { it.value.size >= 3 }.mapValues { medianOf(it.value) }
    }
    /** The activity a task's tracked time predominantly falls under (all history), or null. */
    private fun dominantActivityOf(taskId: String): String? {
        val now = System.currentTimeMillis()
        return timeVm.timeEntries.value.filter { it.taskId == taskId }
            .groupBy { it.activityId }.maxByOrNull { (_, v) -> v.sumOf { it.minutes(now) } }?.key
    }

    // ── Y8 · seasonality — heaviest / lightest weekday ────────────────────────────────────────────
    fun seasonality(weeks: Int = 8): String? {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val byWeekday = DoubleArray(7)
        for (i in 0 until weeks * 7) {
            val d = today.minusDays(i.toLong())
            val ds = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val de = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            byWeekday[d.dayOfWeek.value - 1] += com.todocompanion.app.domain.TimeTracking.totalMinutes(timeVm.timeEntries.value, ds, minOf(de, now + 1), now).toDouble()
        }
        val hl = com.todocompanion.app.domain.Reasoning.heaviestLightestWeekday(byWeekday) ?: return null
        val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        return "${names[hl.first - 1]}s are your heaviest tracked day; ${names[hl.second - 1]}s your lightest — plan the demanding work accordingly."
    }

    // ══ Tier Z · the trustworthy assistant ═══════════════════════════════════════════════════════

    // ── Z4 · morning brief ────────────────────────────────────────────────────────────────────────
    fun setMorningBrief(enabled: Boolean, hour: Int) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(morningBriefEnabled = enabled, morningBriefHour = hour.coerceIn(0, 23)))
        if (enabled) com.todocompanion.app.reminders.AlarmScheduler.scheduleMorningBrief(appCtx, hour.coerceIn(0, 23))
        else com.todocompanion.app.reminders.AlarmScheduler.cancelMorningBrief(appCtx)
    }
    /** The full in-app brief — richer than the notification: next action + forecast + one insight. */
    fun morningBriefLines(): List<String> {
        val out = ArrayList<String>()
        rightNow()?.let { out += "▸ ${it.title} — ${it.subtitle}" }
        dayForecast()?.let { f ->
            out += if (f.willSlip == 0) "▸ All ${f.willFinish} remaining task${if (f.willFinish == 1) "" else "s"} fit today."
            else "▸ ${f.willFinish} of ${f.total} tasks fit today — ${f.willSlip} may slip."
        }
        insightsFeed().firstOrNull()?.let { out += "▸ ${it.text}" }
        return out
    }

    // ── Z6 · assistant action log & undo ──────────────────────────────────────────────────────────
    fun assistantLog(): List<com.todocompanion.app.domain.AssistantAction> = com.todocompanion.app.domain.AssistantLog.parse(settings.value.assistantLogJson)
    private suspend fun appendAction(kind: String, description: String, undo: String = "") {
        val a = com.todocompanion.app.domain.AssistantAction(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), kind, description, undo)
        val next = com.todocompanion.app.domain.AssistantLog.push(assistantLog(), a)
        repo.saveSettings(settings.value.copy(assistantLogJson = com.todocompanion.app.domain.AssistantLog.encode(next)))
    }
    fun undoAction(a: com.todocompanion.app.domain.AssistantAction) = viewModelScope.launch {
        if (!a.reversible) return@launch
        val el = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(a.undo) }.getOrNull()
        val obj = el as? kotlinx.serialization.json.JsonObject
        fun str(key: String): String? = (obj?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
        when (a.kind) {
            "backfill" -> str("entry")?.let { repo.deleteTimeEntry(it); refreshTimeWidget() }
            "rhythm" -> {
                val hid = str("habit"); val freq = str("freq") ?: "weekly"; val days = str("days") ?: ""
                habits.value.firstOrNull { it.id == hid }?.let { repo.upsertHabit(it.copy(freqType = freq, scheduleDays = days)) }
                com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
            }
            "plan" -> {
                val dues = obj?.get("dues") as? kotlinx.serialization.json.JsonObject
                dues?.forEach { (taskId, v) ->
                    val old = (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                    repo.getTask(taskId)?.let { repo.saveTask(it.copy(dueDate = if (old == null || old == 0L) null else old)) }
                }
            }
        }
        val next = com.todocompanion.app.domain.AssistantLog.markUndone(assistantLog(), a.id)
        repo.saveSettings(settings.value.copy(assistantLogJson = com.todocompanion.app.domain.AssistantLog.encode(next)))
        toast("Undone")
    }

    // ── Z5 · you over time — monthly meta-metric snapshots ────────────────────────────────────────
    fun metricSnapshots(): List<com.todocompanion.app.domain.MetricSnapshot> = com.todocompanion.app.domain.MetricSnapshots.parse(settings.value.metricSnapshotsJson)
    fun recordMonthlySnapshotIfNeeded() = viewModelScope.launch {
        val ym = java.time.YearMonth.now(zone).toString()
        val list = metricSnapshots()
        if (list.any { it.yearMonth == ym }) return@launch
        val cal = planVsActualWeek().calibration?.let { Math.round((it - 1.0) * 100).toInt() }
        val capH = trackedCapacityHours()
        val ks = bestKeystone()?.let { Math.round(it.second.lift * 100).toInt() }
        val bal = balanceBreakdown(30).firstOrNull()
        val snap = com.todocompanion.app.domain.MetricSnapshot(ym, cal, capH, ks, bal?.area ?: "", bal?.let { (it.share * 100).toInt() })
        // Only bother storing a month that actually has some signal.
        if (cal == null && capH == null && ks == null && bal == null) return@launch
        repo.saveSettings(settings.value.copy(metricSnapshotsJson = com.todocompanion.app.domain.MetricSnapshots.encode(com.todocompanion.app.domain.MetricSnapshots.upsert(list, snap))))
    }

    // ── Z7 · trust dashboard — the data that exists, all on this device ───────────────────────────
    data class DataCounts(val tasks: Int, val habits: Int, val checkins: Int, val timeEntries: Int, val activities: Int, val focus: Int)
    fun dataCounts() = DataCounts(tasks.value.size, habits.value.size, habitCheckins.value.size, timeVm.timeEntries.value.size, timeVm.timeActivities.value.size, focusSessions.value.size + timeVm.timeEntries.value.count { it.kind == "focus" })

    /** R107 — the single authoritative "what's on this device" inventory, rendered identically in both the
     *  Privacy › Trust panel and the Backup › Maintenance panel so their numbers always agree. Ordered,
     *  label → count, sourced from the same in-memory stores. */
    fun deviceInventory(): List<Pair<String, Int>> = listOf(
        "Tasks" to tasks.value.size,
        "Notes" to notes.value.size,
        "Notebooks" to notebooks.value.size,
        "Events" to events.value.size,
        "Occasions" to countdowns.value.size,
        "Habits" to habits.value.size,
        "Habit check-ins" to habitCheckins.value.size,
        "Activities" to timeVm.timeActivities.value.size,
        "Time entries" to timeVm.timeEntries.value.size,
        "Focus sessions" to (focusSessions.value.size + timeVm.timeEntries.value.count { it.kind == "focus" }),
    )

    // ── Plan A · at-rest database encryption (SQLCipher). State lives in SecureDb's own prefs (it must
    // be readable before the DB opens), not in AppSettings — so these are simple synchronous getters. ──
    fun dbEncryptionDesired(): Boolean = com.todocompanion.app.data.security.SecureDb.desiredEncrypted(appCtx)
    fun dbEncryptionActual(): Boolean = com.todocompanion.app.data.security.SecureDb.fileEncrypted(appCtx)
    fun dbEncryptionPending(): Boolean = com.todocompanion.app.data.security.SecureDb.migrationPending(appCtx)
    fun dbEncryptionError(): String = com.todocompanion.app.data.security.SecureDb.lastError(appCtx)
    /** Flip the desired at-rest encryption state. The actual migration runs on the next app start
     *  (the UI prompts for a restart); returns nothing that changes until then. */
    fun setDbEncryption(want: Boolean) = com.todocompanion.app.data.security.SecureDb.setDesiredEncrypted(appCtx, want)

    // ── Z8 · graded strength — an opt-in that gives partial days partial credit ───────────────────
    /** Per-day fractional credit for build-habit days that were attempted but fell short of the goal. */
    private fun gradedCreditFor(h: com.todocompanion.app.data.entity.HabitEntity, hc: List<com.todocompanion.app.data.entity.HabitCheckinEntity>): Map<Long, Double> {
        if (h.habitType == "break") return emptyMap()
        val target = h.targetPerDay.coerceAtLeast(1)
        val hs = com.todocompanion.app.domain.habit.HabitStats
        return hc.filter { it.status == "done" && !hs.meetsGoal(h, it.count) && it.count > 0 }
            .associate { it.epochDay to (it.count.toDouble() / target).coerceIn(0.0, 0.99) }
    }
    /** The strength score honouring the graded-credit opt-in (Z8) when it's on. */
    fun strengthOf(h: com.todocompanion.app.data.entity.HabitEntity): Int {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val hc = habitCheckins.value.filter { it.habitId == h.id }
        val (done, skip, relapse) = hs.daySets(h, hc)   // R108 audit C2
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val graded = if (settings.value.gradedStrength) gradedCreditFor(h, hc) else emptyMap()
        return hs.strength(h, done, skip, relapse, today, gradedCredit = graded)
    }
    /** Z8 preview — average strength across active build habits, binary vs graded, for the opt-in. */
    fun gradedStrengthPreview(): Pair<Int, Int>? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val active = habits.value.filter { !it.archived && !it.paused && it.habitType != "break" }
        if (active.isEmpty()) return null
        val binary = ArrayList<Int>(); val graded = ArrayList<Int>()
        active.forEach { h ->
            val hc = habitCheckins.value.filter { it.habitId == h.id }
            val (done, skip, relapse) = hs.daySets(h, hc)   // R108 audit C2
            binary += hs.strength(h, done, skip, relapse, today)
            graded += hs.strength(h, done, skip, relapse, today, gradedCredit = gradedCreditFor(h, hc))
        }
        return binary.average().toInt() to graded.average().toInt()
    }
    fun setGradedStrength(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(gradedStrength = on)) }

    // ══ PC · polish & correctness ════════════════════════════════════════════════════════════════

    // PC1 · reduced motion; PC4 · dismissible discoverability tips.
    fun setReduceMotion(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(reduceMotion = on)) }
    fun dismissTip(key: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(dismissedTips = settings.value.dismissedTips + key)) }
    fun isTipDismissed(key: String): Boolean = key in settings.value.dismissedTips

    // PC6 · reminder-reliability self-check — warn when the OS is set up to throttle our alarms.
    data class ReminderHealth(val exactAlarms: Boolean, val batteryUnrestricted: Boolean, val notifications: Boolean) {
        val ok get() = exactAlarms && batteryUnrestricted && notifications
        val issues: List<String> get() = buildList {
            if (!notifications) add("Notifications are off — reminders can't appear. Turn them on for this app.")
            if (!exactAlarms) add("Exact alarms are blocked — reminders may fire late. Allow them below.")
            if (!batteryUnrestricted) add("Battery optimisation is on — Android may delay or drop alarms. Allow unrestricted below.")
        }
    }
    fun reminderHealth(): ReminderHealth {
        val exact = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            (appCtx.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() ?: true) else true
        val battery = appCtx.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(appCtx.packageName) ?: true
        val notif = androidx.core.app.NotificationManagerCompat.from(appCtx).areNotificationsEnabled()
        return ReminderHealth(exact, battery, notif)
    }

    fun addManualTimeEntry(activityId: String, startMillis: Long, endMillis: Long, note: String = "") = viewModelScope.launch {
        if (endMillis > startMillis) repo.addManualTimeEntry(activityId, startMillis, endMillis, note)
    }
    fun updateTimeEntry(e: com.todocompanion.app.data.entity.TimeEntryEntity) = viewModelScope.launch { repo.upsertTimeEntry(e); refreshTimeWidget() }
    fun deleteTimeEntry(id: String) = viewModelScope.launch { repo.deleteTimeEntry(id); refreshTimeWidget() }
    /** U4: split a logged interval in two at [atMillis]. */
    fun splitTimeEntry(id: String, atMillis: Long) = viewModelScope.launch { repo.splitTimeEntry(id, atMillis); refreshTimeWidget() }

    // T2: start tracking time against a task (its default activity, else the generic "Tasks" bucket).
    fun startTimeTrackingForTask(task: TaskEntity) = viewModelScope.launch {
        val actId = task.defaultActivityId?.takeIf { id -> timeVm.timeActivities.value.any { it.id == id && !it.archived } }
            ?: repo.ensureTaskActivity()
        repo.startTimeTracking(actId, taskId = task.id); refreshTimeWidget()
    }
    fun setTaskDefaultActivity(taskId: String, activityId: String?) = viewModelScope.launch {
        repo.getTask(taskId)?.let { repo.saveTask(it.copy(defaultActivityId = activityId)) }
    }
    // T3: start tracking time against a habit (its linked activity, else a generic bucket).
    fun startTimeTrackingForHabit(habit: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        val actId = habit.timeActivityId?.takeIf { id -> timeVm.timeActivities.value.any { it.id == id && !it.archived } }
            ?: repo.ensureFocusActivity()
        repo.startTimeTracking(actId, habitId = habit.id); refreshTimeWidget()
    }
    fun setHabitTimeActivity(habitId: String, activityId: String?) = viewModelScope.launch {
        habits.value.firstOrNull { it.id == habitId }?.let { repo.upsertHabit(it.copy(timeActivityId = activityId)) }
    }

    /** M2: create a whole themed routine at once (one reschedule/refresh for the batch). */
    fun addHabits(habits: List<com.todocompanion.app.data.entity.HabitEntity>) = viewModelScope.launch {
        val ws = settings.value.activeWorkspaceId
        habits.forEach { repo.createHabit(it.copy(workspaceId = it.workspaceId.ifBlank { ws })) }
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        refreshHabitWidgets()
    }
    /** Soft-delete a habit to Trash (recoverable), with an Undo. History is preserved; restore is lossless. */
    fun trashHabit(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        repo.setHabitTrashed(h.id, true); refreshHabitWidgets()
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        undoEvents.tryEmit(UndoEvent(UndoKind.HABIT_TRASHED, h.id, "Habit moved to Trash", habitRestore = h))
    }
    /** Restore a trashed habit back to the active list. */
    fun restoreHabit(id: String) = viewModelScope.launch {
        repo.setHabitTrashed(id, false); refreshHabitWidgets()
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
    }
    /** Archive / unarchive a habit (kept out of the active list & analysis, never deleted), with an Undo. */
    fun setHabitArchived(h: com.todocompanion.app.data.entity.HabitEntity, archived: Boolean) = viewModelScope.launch {
        repo.setHabitArchived(h.id, archived); refreshHabitWidgets()
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        if (archived) undoEvents.tryEmit(UndoEvent(UndoKind.HABIT_ARCHIVED, h.id, "Habit archived", habitRestore = h))
    }
    /** Permanently erase a single trashed habit (Trash → Delete forever). */
    fun deleteHabit(id: String) = viewModelScope.launch {
        repo.deleteHabit(id); refreshHabitWidgets()
    }
    /** Permanently erase every trashed habit in the active workspace (Trash → Empty). */
    fun emptyHabitTrash() = viewModelScope.launch {
        repo.emptyHabitTrash(settings.value.activeWorkspaceId); refreshHabitWidgets()
    }
    // N2: reward-unlock celebration — surfaced to the Habits screen (confetti + toast) and a notification.
    val rewardCelebration = MutableStateFlow<String?>(null)
    private fun celebrateIfRewardReached(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long) {
        if (h.rewardText.isBlank() || h.rewardAtStreak <= 0 || h.habitType == "break") return
        // Only a live action *today* earns the celebration — backfilling a past day recomputes streaks but
        // must not pop the reward toast/notification (mirrors awardIfNewlyDone's today-gate).
        val t = today()
        if (epochDay != t) return
        viewModelScope.launch {
            val hs = com.todocompanion.app.domain.habit.HabitStats
            val forgiving = settings.value.forgivingStreaks
            val (done, skip, rel) = hs.daySets(h, repo.getHabitCheckinsOnce())   // R108 audit C2
            // Use the SAME streak the detail-screen reward badge shows (forgiving-aware) so "· earned!" and
            // the celebration can't disagree; fire once, on the day the streak first crosses the target.
            val streakToday = hs.displayStreak(h, done, skip, rel, t, forgiving)
            val streakYesterday = hs.displayStreak(h, done, skip, rel, t - 1, forgiving)
            if (streakToday >= h.rewardAtStreak && streakYesterday < h.rewardAtStreak) {
                com.todocompanion.app.reminders.Notifications.showReward(appCtx, h.name, h.rewardText, streakToday)
                rewardCelebration.value = h.rewardText
            }
        }
    }
    /** A day cannot be logged before the habit began — no "history" earlier than the habit itself. */
    private fun beforeStart(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long): Boolean {
        if (epochDay >= h.startEpochDay(zone)) return false
        toast("You can only log from the day “${h.name}” started.")
        return true
    }
    fun cycleHabit(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, current: Int) = viewModelScope.launch {
        if (beforeStart(h, epochDay)) return@launch
        repo.cycleCheckin(h.id, epochDay, h.targetPerDay, current, h.clickIncrement, h.extraTarget)
        refreshHabitWidgets(); celebrateIfRewardReached(h, epochDay); awardIfNewlyDone(h, epochDay, current)
    }
    /** Numeric / exact value entry for a day (also used to record a break-habit relapse amount). */
    fun setHabitValue(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, count: Int) = viewModelScope.launch {
        if (beforeStart(h, epochDay)) return@launch
        val old = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == epochDay }?.count ?: 0
        repo.setCheckinValue(h.id, epochDay, count); refreshHabitWidgets(); celebrateIfRewardReached(h, epochDay); awardIfNewlyDone(h, epochDay, old)
    }
    /** R33 F6 — the "shine": a celebratory pulse surfaced to the Habits screen when a habit is completed. */
    data class HabitShine(val name: String, val emoji: String?, val phrase: String, val colorArgb: Long?)
    val habitShine = MutableStateFlow<HabitShine?>(null)

    /** V4/V12: when a build habit crosses into "done", earn a momentum point, celebrate, and — R33 F10 —
     *  ramp the target up if the plan says consistency has held. */
    private suspend fun awardIfNewlyDone(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, oldCount: Int) {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        if (h.habitType == "break") return
        val newCount = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == epochDay }?.count ?: 0
        if (hs.meetsGoal(h, newCount) && !hs.meetsGoal(h, oldCount)) {
            // Celebration is for completing the behaviour *now* — editing a past day to "done" (backfill on
            // the month calendar) recomputes streaks/aggregates but must not pop the shine or grant a point.
            val isToday = epochDay == java.time.LocalDate.now(zone).toEpochDay()
            if (isToday) repo.awardPoints(1)
            // Wave 2 · Habit Practice Journal — if this habit has a bound journal note, append today's line.
            if (isToday) repo.appendHabitJournalEntry(h.id, epochDay)
            // R35 · reward taper — a graduated habit has eased off celebration; it runs on its own now.
            if (!h.graduated && isToday) {
                // Fogg's Tiny Habits: the celebration right after the behaviour is what wires it in —
                // an immediate hit of "shine". Prefer the user's own words; else a warm, identity-shaped line.
                val phrase = h.encouragementList().takeIf { it.isNotEmpty() }?.random()
                    ?: listOf(
                        "Yes! That's a vote for who you're becoming.",
                        "Nailed it. 💪",
                        "That's who you are now.",
                        "Done — small wins compound.",
                        "Look at you go. ✨",
                        "Kept the promise to yourself.",
                        "That's the one. 🔥",
                        "Another brick laid.",
                    ).random()
                habitShine.value = HabitShine(h.name, h.emoji, phrase, h.colorArgb)
            }
            // F10 auto ramp-up — bump the daily target once consistency holds over the step window.
            if (epochDay == java.time.LocalDate.now(zone).toEpochDay()) {
                val done = repo.getHabitCheckinsOnce().filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                com.todocompanion.app.domain.habit.HabitBuilder.rampNextTarget(h, done, epochDay)?.let { nt ->
                    repo.upsertHabit(h.copy(targetPerDay = nt, rampLastStepDay = epochDay))
                    toast("You've been consistent — ${h.name} nudged up to $nt${h.unit?.let { " $it" } ?: ""}/day")
                }
            }
        }
    }

    // ── R33 · habit-builder actions ─────────────────────────────────────────────────────────────
    /** F9 — spend a streak-freeze token to protect a specific missed day (logged as a neutral skip). */
    fun useFreeze(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long) = viewModelScope.launch {
        if (h.freezeTokens <= 0) { toast("No streak freezes left — earn one with an overachieving day."); return@launch }
        repo.setDay(h.id, epochDay, 0, "skip", "❄ streak freeze")
        repo.upsertHabit(h.copy(freezeTokens = h.freezeTokens - 1))
        toast("Streak protected ❄")
    }
    /** F12 — tap a daily pledge on a quit habit (a tiny recommitment ritual). */
    fun pledgeToday(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        repo.upsertHabit(h.copy(lastPledgeDay = today))
        toast("Pledged for today. One day at a time.")
    }
    /** F12 — (re)start the clean-time clock for a quit habit from now. */
    fun startQuitClock(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        repo.upsertHabit(h.copy(quitSinceMillis = System.currentTimeMillis()))
        toast("Clean-time started. Day one.")
    }
    /** F13 / LS10 — log an urge/craving after surfing it (or slipping), with optional HALT state and how
     *  long the urge lasted (the duration curve). A slip also records a relapse day. */
    fun logCraving(h: com.todocompanion.app.data.entity.HabitEntity, intensity: Int, trigger: String, surfed: Boolean, halt: String = "", durationSec: Int = 0) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val d = java.time.Instant.ofEpochMilli(now).atZone(zone)
        repo.upsertCraving(com.todocompanion.app.data.entity.CravingEventEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = h.id, atMillis = now,
            epochDay = d.toLocalDate().toEpochDay(), minuteOfDay = d.hour * 60 + d.minute,
            intensity = intensity.coerceIn(1, 5), trigger = trigger.trim(), surfed = surfed,
            halt = halt, durationSec = durationSec.coerceAtLeast(0), workspaceId = activeWorkspace(),
        ))
        if (!surfed) logSlip(h, trigger.ifBlank { "urge" })
        toast(if (surfed) "You rode it out 🌊 Nicely done." else "Logged. A slip isn't a relapse — back on it.")
    }
    fun deleteCraving(id: String) = viewModelScope.launch { repo.deleteCraving(id) }
    /** F16 — start a guided journey: create its habits with staggered start dates so each unlocks on its day. */
    fun startJourney(j: com.todocompanion.app.domain.habit.HabitJourneys.Journey) = viewModelScope.launch {
        if (repo.getHabitsOnce().any { it.journeyKey == j.key && !it.archived }) { toast("You're already on “${j.name}”."); return@launch }
        val today = java.time.LocalDate.now(zone)
        var order = (repo.getHabitsOnce().maxOfOrNull { it.sortOrder } ?: 0.0)
        j.steps.forEach { s ->
            order += 1.0
            val start = today.plusDays(s.dayOffset.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            repo.upsertHabit(com.todocompanion.app.data.entity.HabitEntity(
                id = java.util.UUID.randomUUID().toString(), name = s.name, emoji = s.emoji,
                targetPerDay = s.target.coerceAtLeast(1), unit = s.unit, createdAt = System.currentTimeMillis(),
                startDate = start, sortOrder = order, description = s.why, journeyKey = j.key,
                workspaceId = settings.value.activeWorkspaceId,
            ))
        }
        toast("Started “${j.name}” — step one is ready today.")
        refreshHabitWidgets()
    }

    // ── R34 · life-systems actions ────────────────────────────────────────────────────────────────
    fun setChronotype(i: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(chronotype = i.coerceIn(0, 2))) }
    fun setCalmMode(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(calmMode = on)) }
    fun addReward(text: String) = viewModelScope.launch {
        val t = text.trim(); if (t.isBlank()) return@launch
        if (t !in settings.value.rewardMenu) repo.saveSettings(settings.value.copy(rewardMenu = settings.value.rewardMenu + t))
    }
    fun removeReward(text: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(rewardMenu = settings.value.rewardMenu - text)) }

    // LS5 values → systems → habits
    fun saveValue(id: String?, name: String, emoji: String?, colorArgb: Long?, statement: String) = viewModelScope.launch {
        val existing = id?.let { vid -> coreValues.value.firstOrNull { it.id == vid } }
        val order = existing?.orderIndex ?: ((coreValues.value.maxOfOrNull { it.orderIndex } ?: 0) + 1)
        repo.upsertCoreValue(
            (existing ?: com.todocompanion.app.data.entity.CoreValueEntity(id = java.util.UUID.randomUUID().toString(), name = name, orderIndex = order, createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
                .copy(name = name.trim().ifBlank { "Value" }, emoji = emoji, colorArgb = colorArgb, statement = statement.trim())
        )
    }
    fun deleteValue(id: String) = viewModelScope.launch {
        repo.deleteCoreValue(id)
        // Detach any habits pointing at it, so no dangling reference remains.
        repo.getHabitsOnce().filter { it.valueId == id }.forEach { repo.upsertHabit(it.copy(valueId = null)) }
    }
    fun assignHabitValue(h: com.todocompanion.app.data.entity.HabitEntity, valueId: String?) = viewModelScope.launch { repo.upsertHabit(h.copy(valueId = valueId)) }

    // LS · habit scorecard
    fun addScorecardItem(text: String, sign: Int) = viewModelScope.launch {
        val t = text.trim(); if (t.isBlank()) return@launch
        val order = (scorecardItems.value.maxOfOrNull { it.orderIndex } ?: 0) + 1
        repo.upsertScorecardItem(com.todocompanion.app.data.entity.ScorecardItemEntity(java.util.UUID.randomUUID().toString(), t, sign.coerceIn(-1, 1), order, System.currentTimeMillis(), workspaceId = activeWorkspace()))
    }
    fun setScorecardSign(item: com.todocompanion.app.data.entity.ScorecardItemEntity, sign: Int) = viewModelScope.launch { repo.upsertScorecardItem(item.copy(sign = sign.coerceIn(-1, 1))) }
    fun deleteScorecardItem(id: String) = viewModelScope.launch { repo.deleteScorecardItem(id) }
    /** Turn a scorecard behaviour into a habit: a "+" becomes one to build, a "−" one to break. */
    fun scorecardToHabit(item: com.todocompanion.app.data.entity.ScorecardItemEntity) = viewModelScope.launch {
        if (item.sign == 0) { toast("Tag it good (+) or bad (−) first."); return@launch }
        val order = (repo.getHabitsOnce().maxOfOrNull { it.sortOrder } ?: 0.0) + 1
        repo.upsertHabit(com.todocompanion.app.data.entity.HabitEntity(
            id = java.util.UUID.randomUUID().toString(), name = item.text.trim(),
            habitType = if (item.sign > 0) "build" else "break",
            targetComparison = if (item.sign > 0) "atleast" else "atmost",
            targetPerDay = if (item.sign > 0) 1 else 0, sortOrder = order,
            createdAt = System.currentTimeMillis(), workspaceId = settings.value.activeWorkspaceId,
        ))
        toast(if (item.sign > 0) "Added “${item.text}” as a habit to build." else "Added “${item.text}” as a habit to break.")
        refreshHabitWidgets()
    }

    // LS7 commitment contract + witness sign-off
    fun addWitness(h: com.todocompanion.app.data.entity.HabitEntity, milestoneLabel: String, note: String) = viewModelScope.launch {
        val ref = h.refereeName.trim(); if (ref.isBlank()) { toast("Name a referee in the habit's editor first."); return@launch }
        repo.upsertWitness(com.todocompanion.app.data.entity.WitnessEventEntity(
            java.util.UUID.randomUUID().toString(), h.id, ref, milestoneLabel.trim().ifBlank { "Milestone" }, System.currentTimeMillis(), note.trim(), workspaceId = activeWorkspace()))
        toast("$ref witnessed it ✍️")
    }
    fun deleteWitness(id: String) = viewModelScope.launch { repo.deleteWitness(id) }

    // LS7 self-forfeit + akrasia horizon
    /** A derail happened — escalate the forfeit level (each repeat raises the stake). */
    fun escalateForfeit(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        repo.upsertHabit(h.copy(forfeitLevel = h.forfeitLevel + 1))
        toast("Forfeit owed" + (h.forfeitText.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + ". Level ${h.forfeitLevel + 1}.")
    }
    /** Queue a "make it easier" change — it only takes effect after a one-week akrasia horizon. */
    fun queueEase(h: com.todocompanion.app.data.entity.HabitEntity, newTarget: Int) = viewModelScope.launch {
        val applyAt = System.currentTimeMillis() + 7L * 24 * 3600 * 1000
        repo.upsertHabit(h.copy(pendingEaseMillis = applyAt, pendingEaseTarget = newTarget.coerceAtLeast(0)))
        toast("Change queued — it applies in 7 days. No easing in the heat of the moment.")
    }
    fun cancelEase(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch { repo.upsertHabit(h.copy(pendingEaseMillis = 0, pendingEaseTarget = 0)) }
    /** Apply a queued easing whose horizon has passed (called when the detail screen opens). */
    fun applyPendingEaseIfDue(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        if (h.pendingEaseMillis in 1..System.currentTimeMillis()) {
            repo.upsertHabit(h.copy(targetPerDay = h.pendingEaseTarget.coerceAtLeast(if (h.habitType == "break") 0 else 1), pendingEaseMillis = 0, pendingEaseTarget = 0))
        }
    }

    // LS2 context capture at check-in
    fun setCheckinContext(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, energy: Int, mood: Int, place: String) = viewModelScope.launch {
        repo.setCheckinContext(h.id, epochDay, energy.coerceIn(0, 5), mood.coerceIn(0, 5), place.trim())
    }

    // LS · buddy digest export / import
    fun exportBuddyDigest(name: String): String {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val digest = com.todocompanion.app.domain.habit.LifeSystems.buildDigest(name.ifBlank { "Me" }, habits.value, habitCheckins.value, today, settings.value.forgivingStreaks)
        return kotlinx.serialization.json.Json.encodeToString(com.todocompanion.app.domain.habit.LifeSystems.BuddyDigest.serializer(), digest)
    }
    fun importBuddyDigest(json: String) = viewModelScope.launch {
        val digest = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(com.todocompanion.app.domain.habit.LifeSystems.BuddyDigest.serializer(), json) }.getOrNull()
        if (digest == null) { toast("That doesn't look like a buddy digest."); return@launch }
        repo.upsertBuddy(com.todocompanion.app.data.entity.BuddySnapshotEntity(java.util.UUID.randomUUID().toString(), digest.name, System.currentTimeMillis(), json, workspaceId = activeWorkspace()))
        toast("Imported ${digest.name}'s progress 🤝")
    }
    fun deleteBuddy(id: String) = viewModelScope.launch { repo.deleteBuddy(id) }

    // LS6 save an integrity-review reflection
    fun saveIntegrityReview(kind: String, periodKey: String, note: String, statsJson: String) = viewModelScope.launch {
        repo.upsertIntegrityReview(com.todocompanion.app.data.entity.IntegrityReviewEntity(
            java.util.UUID.randomUUID().toString(), kind, periodKey, System.currentTimeMillis(), note.trim(), statsJson, workspaceId = activeWorkspace()))
        toast("Review saved to your ledger.")
    }
    fun deleteIntegrityReview(id: String) = viewModelScope.launch { repo.deleteIntegrityReview(id) }

    // ── R35 · third-wave actions ──────────────────────────────────────────────────────────────────
    fun setBookends(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(bookendsEnabled = on)) }
    fun setCompanion(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(companionEnabled = on)) }
    fun setStrengthMeter(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(strengthMeter = on)) }

    // TW-B self-tuning reminder — accept the suggested time.
    fun applyReminderDrift(h: com.todocompanion.app.data.entity.HabitEntity, minute: Int) = viewModelScope.launch {
        val others = h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }
        val typical = com.todocompanion.app.domain.habit.HabitStats.typicalDoneMinute(repo.getHabitCheckinsOnce().filter { it.habitId == h.id })
        val replaced = if (others.isEmpty()) listOf(minute) else {
            val nearest = others.minByOrNull { kotlin.math.abs(it - (typical ?: minute)) }
            (others - (nearest ?: minute) + minute).distinct().sorted()
        }
        repo.upsertHabit(h.copy(reminderTimes = replaced.joinToString(",")))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        toast("Reminder moved to ${com.todocompanion.app.domain.habit.HabitStats.minuteLabel(minute)}.")
    }

    // TW-D reward taper — graduate / un-graduate a habit that's reached automaticity.
    fun setGraduated(h: com.todocompanion.app.data.entity.HabitEntity, on: Boolean) = viewModelScope.launch {
        repo.upsertHabit(h.copy(graduated = on))
        toast(if (on) "🎓 Graduated — this one's part of you now. Prompts will ease off." else "Back to active coaching.")
    }

    // TW-F make-up ledger — repay a missed non-negotiable by completing a past expected day.
    fun logMakeUp(h: com.todocompanion.app.data.entity.HabitEntity, day: Long) = viewModelScope.launch {
        repo.setDay(h.id, day, h.targetPerDay.coerceAtLeast(1), "done", "make-up")
        refreshHabitWidgets()
        toast("Made up ${java.time.LocalDate.ofEpochDay(day)}. Debt cleared — not a failure.")
    }

    // TW-C n-of-1 experiments.
    fun startExperiment(habitId: String, outcome: String, blockLen: Int, blocks: Int) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        repo.upsertExperiment(com.todocompanion.app.data.entity.ExperimentEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = habitId, outcome = outcome,
            startDay = today, blockLenDays = blockLen.coerceIn(1, 14), blocks = blocks.coerceIn(2, 12), createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
        toast("Experiment started. Follow the on/off blocks and log your ${outcome}.")
    }
    fun endExperiment(e: com.todocompanion.app.data.entity.ExperimentEntity) = viewModelScope.launch { repo.upsertExperiment(e.copy(active = false)) }
    fun deleteExperiment(id: String) = viewModelScope.launch { repo.deleteExperiment(id) }

    // TW-D behavioral activation.
    fun addActivation(text: String, valueId: String?, day: Long) = viewModelScope.launch {
        val t = text.trim(); if (t.isBlank()) return@launch
        repo.upsertActivationItem(com.todocompanion.app.data.entity.ActivationItemEntity(
            id = java.util.UUID.randomUUID().toString(), text = t, valueId = valueId, plannedDay = day, createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
    }
    fun rateActivation(item: com.todocompanion.app.data.entity.ActivationItemEntity, pleasure: Int, mastery: Int) = viewModelScope.launch {
        repo.upsertActivationItem(item.copy(done = true, pleasure = pleasure.coerceIn(0, 5), mastery = mastery.coerceIn(0, 5)))
    }
    fun deleteActivation(id: String) = viewModelScope.launch { repo.deleteActivationItem(id) }

    // TW-E daily AM/PM bookends.
    fun saveMorningIntention(day: Long, text: String, mood: Int) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day, workspaceId = activeWorkspace())
        repo.upsertDayLog(cur.copy(amIntention = text.trim(), amMood = mood.coerceIn(0, 5), updatedAt = System.currentTimeMillis()))
    }
    fun saveEveningReflection(day: Long, text: String, mood: Int) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day, workspaceId = activeWorkspace())
        repo.upsertDayLog(cur.copy(pmReflection = text.trim(), pmMood = mood.coerceIn(0, 5), updatedAt = System.currentTimeMillis()))
    }
    // R106 — the richer daily-review reflection: overall rating, energy, highlight, gratitude, lesson.
    fun saveDayReflect(day: Long, rating: Int, energy: Int, highlight: String, gratitude: String, lesson: String) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day, workspaceId = activeWorkspace())
        repo.upsertDayLog(cur.copy(
            dayRating = rating.coerceIn(0, 5), energy = energy.coerceIn(0, 5),
            highlight = highlight.trim(), gratitude = gratitude.trim(), lesson = lesson.trim(),
            updatedAt = System.currentTimeMillis(),
        ))
        // L7 — the Living Daily Note: when you close the day, fold the recap into that day's journal note
        // (only if one already exists, so it's never intrusive). Capture in the morning, auto-filled by night.
        writeDayRecapToNote(day, createIfMissing = false)
    }

    /**
     * L7 — write (or refresh) the day's recap inside its journal note, between stable markers so re-running
     * updates in place instead of duplicating. The recap is the same self-writing digest {{today}} uses —
     * tasks done, time tracked, habits kept, felt rating — so the daily note becomes a permanent record of
     * the day: captured in the morning, completed by evening. No other note app closes this loop.
     */
    suspend fun writeDayRecapToNote(epochDay: Long, createIfMissing: Boolean) {
        val ws = activeWorkspace()
        val existing = repo.getNotesOnce().firstOrNull { !it.trashed && it.workspaceId == ws && it.kind == "journal" && it.dayEpoch == epochDay }
        if (existing == null && !createIfMissing) return
        val digest = repo.dayDigestMarkdown(epochDay).trim()
        if (digest.isBlank()) return
        val block = "<!-- kairo:recap -->\n$digest\n<!-- /kairo:recap -->"
        val base = existing?.body ?: ""
        val re = Regex("(?s)<!-- kairo:recap -->.*?<!-- /kairo:recap -->")
        val newBody = when {
            re.containsMatchIn(base) -> re.replace(base) { block }
            base.isBlank() -> block
            else -> base.trimEnd() + "\n\n" + block
        }
        val note = existing ?: com.todocompanion.app.data.entity.NoteEntity(
            id = "", kind = "journal", dayEpoch = epochDay, title = dayNoteTitle(epochDay), workspaceId = ws,
        )
        repo.upsertNote(note.copy(body = newBody))
    }

    /** L7 — manual "save today's recap to the daily note" (creates the note if needed). */
    fun saveDayRecapToNote(epochDay: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        writeDayRecapToNote(epochDay, createIfMissing = true); onDone()
    }
    // R106 — the one thing that matters tomorrow (set from the Day Review "Ready" panel).
    fun saveTomorrowFocus(day: Long, text: String) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day, workspaceId = activeWorkspace())
        repo.upsertDayLog(cur.copy(tomorrowFocus = text.trim(), updatedAt = System.currentTimeMillis()))
    }
    // Wave 2 — tomorrow's WOOP if-then: the obstacle you expect + the "if <obstacle>, then I will…"
    // implementation intention. Both optional ("" clears them). Read-modify-write, preserving every
    // other field (the one-thing focus is set separately via saveTomorrowFocus).
    fun saveTomorrowPlan(day: Long, obstacle: String, plan: String) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day, workspaceId = activeWorkspace())
        repo.upsertDayLog(cur.copy(tomorrowObstacle = obstacle.trim(), tomorrowPlan = plan.trim(), updatedAt = System.currentTimeMillis()))
    }
    // Phase B — the reflection-depth extras: three good things, the morning-intention outcome, and the
    // answer to the day's rotating prompt. Read-modify-write, preserving every other field.
    fun saveDayReflectExtras(day: Long, good1: String, good2: String, good3: String, intentionOutcome: Int, promptAnswer: String) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day, workspaceId = activeWorkspace())
        repo.upsertDayLog(cur.copy(
            good1 = good1.trim(), good2 = good2.trim(), good3 = good3.trim(),
            intentionOutcome = intentionOutcome.coerceIn(0, 3), promptAnswer = promptAnswer.trim(),
            updatedAt = System.currentTimeMillis(),
        ))
    }

    // Wave 1 — a precise emotion word alongside the 5-face pmMood (affect-labeling). Optional; "" clears it.
    // Read-modify-write, preserving every other field.
    fun saveEmotionLabel(day: Long, label: String) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day, workspaceId = activeWorkspace())
        repo.upsertDayLog(cur.copy(emotionLabel = label.trim(), updatedAt = System.currentTimeMillis()))
    }

    // Wave 1 — deliberate rollover (Bullet-Journal migration): per-task and explicit, not a silent
    // auto-carry. Carry the KEPT tasks to tomorrow (the same date bump carry-forward uses) and "let go" of
    // the rest using the app's existing won't-do / abandon (reversible via the undo it emits). No new
    // deletion path is invented; nothing happens to a task the user didn't decide on.
    fun reviewRollover(carryIds: List<String>, letGoIds: List<String>) = viewModelScope.launch {
        val tomorrowMillis = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var carried = 0
        carryIds.forEach { id ->
            val t = repo.getTask(id) ?: return@forEach
            if (!t.completed && !t.trashed) { repo.saveTask(t.copy(dueDate = tomorrowMillis, updatedAt = System.currentTimeMillis())); carried++ }
        }
        var released = 0
        letGoIds.forEach { id ->
            val t = repo.getTask(id) ?: return@forEach
            if (!t.completed && !t.trashed && !t.abandoned) { repo.setAbandoned(t, true); released++ }
        }
        toast(
            buildString {
                append("Day closed.")
                if (carried > 0) append(" Carried $carried forward.")
                if (released > 0) append(" Let go of $released.")
            },
        )
    }

    // Wave 1 — the guided Weekly Review. Persist minimally: the week's reflection + next-week focus + the
    // life areas touched, in ONE settings JSON keyed by ISO week (no new Room table). Empty fields clear it.
    fun saveWeeklyReview(
        isoWeek: String, reflection: String, nextFocus: String, areas: List<String>,
        // Track 2.4 — the chosen retrospective lens + its per-field answers. Track 2.5 — how last week's
        // focus went (0 none · 1 missed · 2 partly · 3 nailed). All in the same settings JSON, no schema.
        lens: String = "", lensAnswers: Map<String, String> = emptyMap(), focusRating: Int = 0,
    ) = viewModelScope.launch {
        if (isoWeek.isBlank()) return@launch
        val review = com.todocompanion.app.domain.WeeklyReview(
            isoWeek = isoWeek, reflection = reflection.trim(), nextFocus = nextFocus.trim(),
            areas = areas.distinct(),
            lens = lens, lensAnswers = lensAnswers.mapValues { it.value.trim() }.filterValues { it.isNotBlank() },
            focusRating = focusRating.coerceIn(0, 3),
            updatedAt = System.currentTimeMillis(),
        )
        val cur = settings.value
        repo.saveSettings(cur.copy(weeklyReviewsJson = com.todocompanion.app.domain.WeeklyReviews.upsert(cur.weeklyReviewsJson, review)))
    }

    // ── Wave 3 (feature C) · the Drucker prediction loop ─────────────────────────────────────────────
    // Log a prediction ("I expect that … will make me feel …") with a resurface date, resolve it when it
    // comes back, or drop it. The whole store is ONE settings JSON list (no schema change). See Predictions.
    fun addPrediction(expectation: String, resurfaceEpochDay: Long) = viewModelScope.launch {
        val text = expectation.trim()
        if (text.isBlank()) return@launch
        val today = today()
        val p = com.todocompanion.app.domain.Prediction(
            id = java.util.UUID.randomUUID().toString(), createdEpochDay = today,
            resurfaceEpochDay = resurfaceEpochDay.coerceAtLeast(today + 1), expectation = text,
        )
        val cur = settings.value
        repo.saveSettings(cur.copy(predictionsJson = com.todocompanion.app.domain.Predictions.upsert(cur.predictionsJson, p)))
        toast("Prediction saved — I'll bring it back on ${java.time.LocalDate.ofEpochDay(p.resurfaceEpochDay)}")
    }
    fun resolvePrediction(id: String, outcomeNote: String, matched: Int) = viewModelScope.launch {
        val today = today()
        val cur = settings.value
        repo.saveSettings(cur.copy(predictionsJson = com.todocompanion.app.domain.Predictions.resolve(cur.predictionsJson, id, outcomeNote, matched, today)))
    }
    fun removePrediction(id: String) = viewModelScope.launch {
        val cur = settings.value
        repo.saveSettings(cur.copy(predictionsJson = com.todocompanion.app.domain.Predictions.remove(cur.predictionsJson, id)))
    }

    // ── Wave 3 (feature D) · dismiss a single-day judgment-free nudge, so that observation never returns ──
    fun dismissNudge(key: String) = viewModelScope.launch {
        if (key.isBlank()) return@launch
        val cur = settings.value
        val keys = (cur.nudgeDismissedCsv.split(",").map { it.trim() }.filter { it.isNotBlank() } + key).distinct()
        repo.saveSettings(cur.copy(nudgeDismissedCsv = keys.joinToString(",")))
    }

    // Phase F — (re)schedule the evening review through the smart layer (skip-if-done + adaptive time).
    // Called from the settings flow whenever the nudge toggle, its hour, or the adaptive toggle changes.
    fun rescheduleEveningReview() = viewModelScope.launch {
        com.todocompanion.app.reminders.AlarmScheduler.scheduleEveningReviewSmart(appCtx, repo)
    }

    // Phase F — streak recovery: consume one repair token to cover a single missed day ([repairDay]).
    // A deliberate opt-in tap only; never auto-consumed. Capped per month and gated on tokens remaining,
    // so it can't be abused. Records the repaired day as a settings-side overlay (no DB day is fabricated).
    fun keepStreak(repairDay: Long) = viewModelScope.launch {
        val s = repo.settingsSnapshot()
        val period = com.todocompanion.app.domain.ReviewCadence.periodKey(today())
        val available = com.todocompanion.app.domain.ReviewCadence.tokensForPeriod(s.streakRepairTokens, s.streakRepairPeriod, period)
        if (available <= 0) return@launch
        val repaired = (s.repairedDaysCsv.split(",").mapNotNull { it.trim().toLongOrNull() } + repairDay).distinct()
        repo.saveSettings(s.copy(
            streakRepairTokens = (available - 1).coerceAtLeast(0),
            streakRepairPeriod = period,
            repairedDaysCsv = repaired.joinToString(","),
        ))
    }

    // Phase C — self-scored Daily Questions. The active question list is a single settings JSON value
    // (capped at DailyQuestions.MAX); each day's scores live on that day's DayLog.
    fun saveDailyQuestions(list: List<DailyQuestion>) = viewModelScope.launch {
        val cleaned = list.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotBlank() }.take(DailyQuestions.MAX)
        repo.saveSettings(settings.value.copy(dailyQuestionsJson = DailyQuestions.toJson(cleaned)))
    }
    // Score one question for a day (1..5). Read-modify-write the day's scores map, preserving all else.
    fun saveDailyScore(day: Long, questionId: String, score: Int) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day, workspaceId = activeWorkspace())
        val scores = DailyQuestions.parseScores(cur.dailyScoresJson).toMutableMap()
        scores[questionId] = score.coerceIn(1, 5)
        repo.upsertDayLog(cur.copy(dailyScoresJson = DailyQuestions.scoresToJson(scores), updatedAt = System.currentTimeMillis()))
    }

    // Phase E — the day's alignment: which active goals today advanced and which top values it honored.
    // One JSON blob per day on the DayLog. Read-modify-write, preserving every other field.
    fun saveDayAlignment(day: Long, movedGoalIds: List<String>, honoredValueIds: List<String>) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day, workspaceId = activeWorkspace())
        val alignment = com.todocompanion.app.domain.DayAlignment(
            movedGoalIds = movedGoalIds.distinct(),
            honoredValueIds = honoredValueIds.distinct(),
        )
        repo.upsertDayLog(cur.copy(alignmentJson = com.todocompanion.app.domain.DayAlignments.encode(alignment), updatedAt = System.currentTimeMillis()))
    }

    // ── R36 · fourth-wave actions ───────────────────────────────────────────────────────────────────
    // FW-5 New-Habit WIP limiter.
    fun setHabitWipLimit(n: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(habitWipLimit = n.coerceIn(0, 20))) }

    // FW-11 Transition detector + reset window.
    fun setTransition(label: String, startDay: Long) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(transitionLabel = label.trim(), transitionStartDay = startDay))
        if (label.isNotBlank()) toast("Transition noted. A 3-week reset window is open — a good time to re-choose your routines.")
    }
    fun clearTransition() = viewModelScope.launch { repo.saveSettings(settings.value.copy(transitionLabel = "", transitionStartDay = 0)) }

    // FW-6 Daily shutdown + carry-forward — push each still-open, due-today task to tomorrow.
    fun carryForwardTasks(taskIds: List<String>) = viewModelScope.launch {
        if (taskIds.isEmpty()) return@launch
        val tomorrowMillis = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var moved = 0
        taskIds.forEach { id ->
            val t = repo.getTask(id) ?: return@forEach
            if (!t.completed) { repo.saveTask(t.copy(dueDate = tomorrowMillis)); moved++ }
        }
        if (moved > 0) toast("Carried $moved task${if (moved == 1) "" else "s"} forward to tomorrow. Day closed.")
    }

    // FW-9 Self-escrow contingency reward.
    fun addEscrow(habitId: String?, description: String, kind: String, milestoneKind: String, milestoneValue: Int) = viewModelScope.launch {
        val d = description.trim(); if (d.isBlank()) return@launch
        repo.upsertEscrow(com.todocompanion.app.data.entity.EscrowEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = habitId, description = d, kind = kind,
            milestoneKind = milestoneKind, milestoneValue = milestoneValue.coerceAtLeast(1), createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
        toast(if (kind == "stake") "Stake locked. It's real now — reach the milestone or it's forfeit." else "Reward escrowed. Earn it at the milestone.")
    }
    fun releaseEscrow(e: com.todocompanion.app.data.entity.EscrowEntity, redeem: Boolean) = viewModelScope.launch {
        repo.upsertEscrow(e.copy(released = true, redeemed = redeem))
        toast(when {
            e.kind == "stake" && redeem -> "Stake paid. The contract held."
            e.kind == "stake" -> "Stake returned — you made it."
            redeem -> "Enjoy it — you earned this one. 🎉"
            else -> "Banked for later."
        })
    }
    fun deleteEscrow(id: String) = viewModelScope.launch { repo.deleteEscrow(id) }

    // FW-14 Personal Nudge MRT — record that an opportunity nudge (variant v) was shown for a habit today,
    // and reconcile past open impressions against whether the habit was completed.
    fun logNudgeShown(habitId: String, variant: Int, day: Long) = viewModelScope.launch {
        if (repo.nudgeForHabitDay(habitId, day) != null) return@launch   // one impression per habit per day
        repo.upsertNudgeEvent(com.todocompanion.app.data.entity.NudgeEventEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = habitId, variant = variant, epochDay = day,
            createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
    }
    /** Mark open nudge impressions from the last two weeks as acted/not, by whether the target (habit or
     *  task) was completed that day. R37: extended to task-reminder impressions (targetKind = "task"). */
    fun reconcileNudges() = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val open = repo.openNudgesSince(today - 14)
        if (open.isEmpty()) return@launch
        val checkins = repo.getHabitCheckinsOnce()
        val habits = repo.getHabitsOnce().associateBy { it.id }
        open.forEach { ev ->
            if (ev.epochDay >= today) return@forEach   // only reconcile past days
            val done = if (ev.targetKind == "task") {
                val t = repo.getTask(ev.habitId)
                t?.completed == true && t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } == ev.epochDay
            } else {
                val h = habits[ev.habitId] ?: return@forEach
                checkins.any { it.habitId == ev.habitId && it.epochDay == ev.epochDay && it.status == "done" && com.todocompanion.app.domain.habit.HabitStats.meetsGoal(h, it.count) }
            }
            if (done) repo.upsertNudgeEvent(ev.copy(acted = true))
        }
    }

    // ── R37 · habit-science ports to tasks ─────────────────────────────────────────────────────────
    fun setTaskWipLimit(n: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(taskWipLimit = n.coerceIn(0, 20))) }
    fun setReceptivityTiming(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(receptivityTiming = on)) }

    /** Port 9 — link a project/task to a core value, so "living your values" counts real work. */
    fun setTaskValue(taskId: String, valueId: String?) = viewModelScope.launch {
        repo.getTask(taskId)?.let { repo.saveTask(it.copy(valueId = valueId)) }
    }

    /** Port 6 — a self-escrow that rides on shipping a specific task (milestoneKind = "taskdone"). */
    fun addTaskEscrow(taskId: String, description: String, kind: String) = viewModelScope.launch {
        val d = description.trim(); if (d.isBlank()) return@launch
        repo.upsertEscrow(com.todocompanion.app.data.entity.EscrowEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = null, taskId = taskId, description = d, kind = kind,
            milestoneKind = "taskdone", milestoneValue = 1, createdAt = System.currentTimeMillis(), workspaceId = activeWorkspace()))
        toast(if (kind == "stake") "Stake locked on shipping this. It's real now." else "Reward escrowed — earn it by finishing this.")
    }

    /** Port 8 — fresh-start task planning: pull every stale overdue task onto today, so the week's plan
     *  starts clean. Landmarks are when re-planning sticks. */
    fun freshStartReschedule() = viewModelScope.launch {
        val zone = this@AppViewModel.zone
        val todayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val today = java.time.LocalDate.now(zone).toEpochDay()
        var moved = 0
        repo.allTasksOnce().forEach { t ->
            if (t.completed || t.trashed || t.abandoned) return@forEach
            val due = t.dueDate ?: return@forEach
            val dueDay = java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalDate().toEpochDay()
            if (dueDay < today) { repo.saveTask(t.copy(dueDate = todayStart)); moved++ }
        }
        toast(if (moved > 0) "Pulled $moved overdue task${if (moved == 1) "" else "s"} onto today — fresh start." else "Nothing overdue — you're clear.")
    }

    // ── Calendar ────────────────────────────────────────────────────────────────────────────────────
    /** R39 — make sure at least one event calendar exists (called when the unified Calendar screen opens). */
    fun ensureEventCalendar() = viewModelScope.launch { ensureDefaultCalendar() }

    private suspend fun ensureDefaultCalendar(): String {
        val existing = repo.eventCalendarsOnce()
        existing.firstOrNull { it.isDefault }?.let { return it.id }
        existing.firstOrNull()?.let { return it.id }
        val id = java.util.UUID.randomUUID().toString()
        repo.upsertEventCalendar(com.todocompanion.app.data.entity.EventCalendarEntity(
            id = id, name = "Personal", colorArgb = 0xFF4F46E5, isDefault = true, orderIndex = 0,
            workspaceId = settings.value.activeWorkspaceId, createdAt = System.currentTimeMillis()))
        return id
    }

    fun createEventCalendar(name: String, color: Long) = viewModelScope.launch {
        val n = name.trim(); if (n.isBlank()) return@launch
        repo.upsertEventCalendar(com.todocompanion.app.data.entity.EventCalendarEntity(
            id = java.util.UUID.randomUUID().toString(), name = n, colorArgb = color,
            orderIndex = repo.eventCalendarsOnce().size, workspaceId = settings.value.activeWorkspaceId, createdAt = System.currentTimeMillis()))
    }
    fun setEventCalendarVisible(c: com.todocompanion.app.data.entity.EventCalendarEntity, visible: Boolean) = viewModelScope.launch { repo.upsertEventCalendar(c.copy(visible = visible)) }
    /** Make [id] the default calendar new events land in; clears the flag on every other calendar in this
     *  workspace so exactly one is default. */
    fun setDefaultEventCalendar(id: String) = viewModelScope.launch {
        val ws = settings.value.activeWorkspaceId
        repo.eventCalendarsOnce().filter { it.workspaceId == ws }.forEach { c ->
            val shouldBe = c.id == id
            if (c.isDefault != shouldBe) repo.upsertEventCalendar(c.copy(isDefault = shouldBe))
        }
    }
    fun renameEventCalendar(c: com.todocompanion.app.data.entity.EventCalendarEntity, name: String, color: Long) = viewModelScope.launch { repo.upsertEventCalendar(c.copy(name = name.trim().ifBlank { c.name }, colorArgb = color)) }
    fun deleteEventCalendar(id: String) = viewModelScope.launch {
        val evs = repo.eventsOnce().filter { it.calendarId == id }
        evs.forEach { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, it); repo.deleteEvent(it.id) }
        repo.deleteEventCalendar(id)
    }

    /** Create or update an event, then (re)schedule its alerts. */
    fun saveEvent(existingId: String?, calendarId: String, title: String, location: String, notes: String, url: String,
                  startMillis: Long, endMillis: Long, allDay: Boolean, rrule: String, alertsMinutes: String,
                  colorArgb: Long?, floating: Boolean = false, busy: Boolean = true,
                  organizer: String = "", attendees: String = "", rsvp: String = "") = viewModelScope.launch {
        val t = title.trim().ifBlank { "Event" }
        val old = existingId?.let { repo.eventById(it) }
        old?.let { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, it) }
        val e = (old ?: com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calendarId, title = t,
            startMillis = startMillis, endMillis = endMillis, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
            .copy(calendarId = calendarId, title = t, location = location.trim(), notes = notes.trim(), url = url.trim(),
                startMillis = startMillis, endMillis = endMillis, allDay = allDay, rrule = rrule, alertsMinutes = alertsMinutes,
                colorArgb = colorArgb, floating = floating, busy = busy,
                organizer = organizer.trim(), attendees = attendees.trim(), rsvp = rsvp.trim(),
                updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
    }

    /** A2 — "the self-writing day": turn a lived, untracked stretch into a calendar event in one tap,
     *  using the default event calendar (created on demand). No alerts — it's a record of what happened. */
    fun addQuickEvent(title: String, startMillis: Long, endMillis: Long) = viewModelScope.launch {
        if (endMillis <= startMillis) return@launch
        val calId = ensureDefaultCalendar()
        saveEvent(null, calId, title.ifBlank { "Logged" }, "", "", "", startMillis, endMillis, false, "", "", null)
        toastMsg("Added to the calendar")
    }

    /** A2 (classified) — record a lived, tracked-but-uncalendared stretch as what it actually was, instead
     *  of always minting a generic "Tracked time" event. [kind]:
     *   • "event"    → a plain calendar event (a meeting, appointment, something that happened),
     *   • "task"     → an event linked to [taskId] so it reads as time spent on that task,
     *   • "activity" → leave it exactly as tracked activity time (no calendar entry is created). */
    fun backfillLivedTime(kind: String, title: String, startMillis: Long, endMillis: Long, taskId: String?) = viewModelScope.launch {
        if (endMillis <= startMillis) return@launch
        when (kind) {
            "activity" -> toastMsg("Kept as tracked time")
            "task" -> {
                val calId = ensureDefaultCalendar()
                val now = System.currentTimeMillis()
                repo.upsertEvent(com.todocompanion.app.data.entity.EventEntity(
                    id = java.util.UUID.randomUUID().toString(), calendarId = calId,
                    title = title.trim().ifBlank { "Task time" }, startMillis = startMillis, endMillis = endMillis,
                    linkedTaskId = taskId, createdAt = now, updatedAt = now))
                toastMsg("Logged against the task")
            }
            else -> {
                val calId = ensureDefaultCalendar()
                saveEvent(null, calId, title.trim().ifBlank { "Logged" }, "", "", "", startMillis, endMillis, false, "", "", null)
                toastMsg("Added to the calendar")
            }
        }
    }

    /** Phase 2 P2 — a natural-language line typed on the calendar becomes an event (or a task, if it reads
     *  like one) entirely on-device via [EventParser]. [anchorMillis] is the moment relative words like
     *  "3pm"/"tomorrow" resolve against — the day the user is viewing — so the bar is contextual. */
    fun quickAddFromCalendar(text: String, anchorMillis: Long) = viewModelScope.launch {
        val raw = text.trim(); if (raw.isEmpty()) return@launch
        val zone = runCatching { if (settings.value.timeZone.isNotBlank()) java.time.ZoneId.of(settings.value.timeZone) else java.time.ZoneId.systemDefault() }
            .getOrDefault(java.time.ZoneId.systemDefault())
        val anchorDay = java.time.Instant.ofEpochMilli(anchorMillis).atZone(zone).toLocalDate()
        val draft = com.todocompanion.app.domain.calendar.EventParser.parse(raw, anchorDay, zone)
        if (draft == null || draft.isTask) {
            // Nothing time-like, or it opens with "remind me to…"/"todo" — let the task capture path own it.
            quickAddOne(raw, QuickAddOptions())
            toast(if (draft?.isTask == true) "Task added" else "Added to Inbox")
            return@launch
        }
        val calId = ensureDefaultCalendar()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = draft.title.ifBlank { "Event" },
            location = draft.location, startMillis = draft.startMillis, endMillis = draft.endMillis, allDay = draft.allDay,
            rrule = draft.rrule, alertsMinutes = draft.alertsMinutes,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
        toast("Added “${e.title}”")
    }

    /** R56 — move an event (its whole series) to another calendar; used by the entries manager's bulk edit. */
    fun moveEventToCalendar(id: String, calendarId: String) = viewModelScope.launch {
        val e = repo.eventById(id) ?: return@launch
        if (e.calendarId == calendarId) return@launch
        repo.upsertEvent(e.copy(calendarId = calendarId, updatedAt = System.currentTimeMillis()))
    }

    /** Delete an event. scope: "series" (all), "this" (add an exdate), "following" (end the series before this day). */
    fun deleteEvent(id: String, scope: String = "series", instanceDay: Long = 0) = viewModelScope.launch {
        val e = repo.eventById(id) ?: return@launch
        when {
            e.rrule.isBlank() || scope == "series" -> { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, e); repo.deleteEvent(id) }
            scope == "this" -> {
                val ex = (e.exDates.split(",").mapNotNull { it.trim().toLongOrNull() } + instanceDay).distinct().joinToString(",")
                repo.upsertEvent(e.copy(exDates = ex, updatedAt = System.currentTimeMillis()))
            }
            scope == "following" -> repo.upsertEvent(e.copy(rrule = capUntil(e.rrule, instanceDay - 1), updatedAt = System.currentTimeMillis()))
        }
    }
    private fun capUntil(rule: String, untilDay: Long): String {
        val r = com.todocompanion.app.domain.recurrence.Recurrence.parse(rule) ?: return rule
        return com.todocompanion.app.domain.recurrence.Recurrence.encode(r.copy(untilEpochDay = untilDay, count = null))
    }

    /** FW moat — turn a task into a scheduled time block (an event linked back to the task). */
    fun blockTaskAsEvent(taskId: String, startMillis: Long, durationMin: Int) = viewModelScope.launch {
        val task = repo.getTask(taskId) ?: return@launch
        val calId = ensureDefaultCalendar()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = task.title,
            startMillis = startMillis, endMillis = startMillis + durationMin.coerceAtLeast(15) * 60000L,
            linkedTaskId = taskId, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e); toast("Blocked ${durationMin}m for “${task.title}”.")
    }

    // ── R43 · Third-horizon planner support ────────────────────────────────────────────────────────
    /** The daylight rail: store a latitude (999.0 = off) that the sunrise/sunset bands are computed from. */
    fun setDaylightLatitude(lat: Double?) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(daylightLatitude = lat?.coerceIn(-90.0, 90.0) ?: 999.0))
    }
    /** North-star allocation: set (or clear, share<=0) a target time-share for one calendar. */
    fun setNorthStarTarget(calId: String, share: Double) = viewModelScope.launch {
        val map = com.todocompanion.app.domain.calendar.ThirdHorizon.parseTargets(settings.value.northStarTargetsCsv).toMutableMap()
        if (share > 0) map[calId] = share.coerceIn(0.0, 1.0) else map.remove(calId)
        repo.saveSettings(settings.value.copy(northStarTargetsCsv = com.todocompanion.app.domain.calendar.ThirdHorizon.encodeTargets(map)))
    }
    fun clearNorthStarTargets() = viewModelScope.launch { repo.saveSettings(settings.value.copy(northStarTargetsCsv = "")) }

    /** Booked (event) minutes per epoch-day across a range — feeds the ghost week and recovery-buffer reads. */
    suspend fun bookedMinutesByDay(startDay: Long, endDay: Long): Map<Long, Long> {
        val evs = events.value
        // R60 — count scheduled tasks as booked too (unless already blocked as a linked event).
        val linked = evs.mapNotNull { it.linkedTaskId }.toSet()
        val taskBusy = com.todocompanion.app.domain.calendar.Availability.taskBusyIntervals(tasks.value, zone, linked)
        val out = HashMap<Long, Long>()
        var d = startDay
        while (d <= endDay) {
            val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, d, zone)
            val dayStart = java.time.LocalDate.ofEpochDay(d).atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = java.time.LocalDate.ofEpochDay(d + 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val taskMin = taskBusy.filter { it.second > dayStart && it.first < dayEnd }
                .sumOf { ((minOf(it.second, dayEnd) - maxOf(it.first, dayStart)) / 60000L).coerceAtLeast(0) }
            out[d] = occ.filter { !it.event.allDay }.sumOf { it.durationMin() } + taskMin
            d++
        }
        return out
    }

    /** Deadline-aware chunking: spread a task's remaining estimate across the days before its deadline,
     *  dropping a linked calendar block onto the first free slot of each chosen day. */
    fun applyDeadlineChunks(taskId: String, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val task = repo.getTask(taskId) ?: return@launch
        val est = task.estimateMin ?: return@launch
        val deadlineMs = task.deadlineDate ?: task.dueDate ?: return@launch
        val s = settings.value
        val fromDay = java.time.LocalDate.now(zone).toEpochDay()
        val deadlineDay = java.time.Instant.ofEpochMilli(deadlineMs).atZone(zone).toLocalDate().toEpochDay()
        val evs = events.value
        // R60 — scheduled tasks count as busy too, so deadline chunking doesn't overpromise free time.
        val taskBusy = com.todocompanion.app.domain.calendar.Availability.taskBusyIntervals(tasks.value, zone, evs.mapNotNull { it.linkedTaskId }.toSet() + taskId)
        // Free minutes per day = working-window length minus what's already booked.
        val freeByDay = HashMap<Long, Int>()
        var d = fromDay
        while (d <= deadlineDay) {
            val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, d, zone)
            val budget = com.todocompanion.app.domain.calendar.CalendarPlanner.dayBudget(occ, d, s.workStartHour, s.workEndHour, zone, taskBusy)
            freeByDay[d] = budget.remainingMin.coerceAtLeast(0)
            d++
        }
        val chunks = com.todocompanion.app.domain.calendar.ThirdHorizon.deadlineChunks(est, freeByDay, fromDay, deadlineDay)
        var placed = 0
        chunks.forEach { c ->
            val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(events.value, c.day, zone)
            val fromMin = if (c.day == fromDay) (java.time.LocalTime.now(zone).hour * 60 + java.time.LocalTime.now(zone).minute) else s.workStartHour * 60
            val slot = com.todocompanion.app.domain.calendar.CalendarPlanner.slideToFree(c.day, fromMin, c.minutes, occ.map { it.event }, s.workStartHour, s.workEndHour, zone)
                ?: java.time.LocalDate.ofEpochDay(c.day).atTime(s.workStartHour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
            val ev = com.todocompanion.app.data.entity.EventEntity(
                id = java.util.UUID.randomUUID().toString(), calendarId = ensureDefaultCalendar(), title = task.title,
                startMillis = slot, endMillis = slot + c.minutes * 60000L, linkedTaskId = taskId,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            repo.upsertEvent(ev); placed++
        }
        toast(if (placed > 0) "Spread “${task.title}” across $placed day${if (placed == 1) "" else "s"}." else "No free time before the deadline.")
        onDone(placed)
    }

    /** Time-debt repayment: raise a task's estimate to what it actually took, so next time it's pre-booked right. */
    fun bumpTaskEstimate(taskId: String, newEstimateMin: Int) = viewModelScope.launch {
        val t = repo.getTask(taskId) ?: return@launch
        repo.saveTask(t.copy(estimateMin = newEstimateMin.coerceAtLeast(5)))
        toast("Estimate updated to ${newEstimateMin / 60}h ${newEstimateMin % 60}m.")
    }

    /** Focus contract: arm the Focus ring for this task (open the Focus tab to begin). */
    fun armFocusForTask(taskId: String) {
        pendingFocusTaskId.value = taskId
        toast("Focus armed — open the Focus tab to start the session.")
    }

    /** Natural-language quick add on the calendar: parse → an event (or a task if it starts with todo/reminder). */
    fun quickAddCalendar(text: String, forDay: Long) = viewModelScope.launch {
        val calId = ensureDefaultCalendar()
        val draft = com.todocompanion.app.domain.calendar.EventParser.parse(text, java.time.LocalDate.ofEpochDay(forDay), zone)
        if (draft == null) {
            // No date/time recognised — make an all-day event on the focused day.
            val start = java.time.LocalDate.ofEpochDay(forDay).atStartOfDay(zone).toInstant().toEpochMilli()
            repo.upsertEvent(com.todocompanion.app.data.entity.EventEntity(
                id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = text.trim().ifBlank { "Event" },
                startMillis = start, endMillis = start + 86_399_000L, allDay = true,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
            return@launch
        }
        if (draft.isTask) {
            repo.ensureInbox()
            repo.createTask(listId = com.todocompanion.app.data.entity.ListEntity.INBOX_ID, title = draft.title, dueDate = draft.startMillis)
                .let { id -> repo.getTask(id)?.let { repo.saveTask(it.copy(isAllDay = draft.allDay)) } }
            toast("Added task “${draft.title}”.")
            return@launch
        }
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = draft.title, location = draft.location,
            startMillis = draft.startMillis, endMillis = draft.endMillis, allDay = draft.allDay, rrule = draft.rrule,
            alertsMinutes = draft.alertsMinutes, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
    }

    /** R52 — create a new event calendar and return its id (used by "import to a new calendar"). */
    suspend fun createEventCalendarReturningId(name: String): String {
        val id = java.util.UUID.randomUUID().toString()
        val colors = listOf(0xFF5B57D6, 0xFF12A594, 0xFFE5484D, 0xFFF76B15, 0xFF0EA371, 0xFFB569F5)
        val n = eventCalendars.value.size
        repo.upsertEventCalendar(com.todocompanion.app.data.entity.EventCalendarEntity(id = id, name = name.trim().ifBlank { "Calendar" },
            colorArgb = colors[n % colors.size], orderIndex = n, createdAt = System.currentTimeMillis()))
        return id
    }
    /** R52 — import into a CHOSEN calendar (null = the default). */
    fun importIcsEvents(uri: android.net.Uri, calendarId: String? = null, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val calId = calendarId ?: ensureDefaultCalendar()
        val text = withContext(Dispatchers.IO) { runCatching { readImportTextBounded(uri) }.getOrNull() }
        if (text == null) { toast("Couldn't read that file."); onDone(0); return@launch }
        val method = com.todocompanion.app.domain.calendar.EventIcs.methodOf(text)
        val evs = com.todocompanion.app.domain.calendar.EventIcs.import(text, calId, zone)
        val existing = repo.eventsOnce()
        // R53 — a cancellation removes the matching invite(s) rather than adding anything.
        if (method == "CANCEL") {
            val uids = evs.mapNotNull { it.uid.takeIf { u -> u.isNotBlank() } }.toSet()
            val cancelled = existing.filter { it.uid.isNotBlank() && it.uid in uids }
            cancelled.forEach { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, it); repo.deleteEvent(it.id) }
            toast(if (cancelled.isEmpty()) "Nothing to cancel." else "Cancelled ${cancelled.size} event${if (cancelled.size == 1) "" else "s"}.")
            onDone(cancelled.size); return@launch
        }
        // R53 — de-dupe by UID: a re-imported invite UPDATES its event in place; a higher SEQUENCE supersedes,
        // a lower/equal-but-older one is ignored. Events without a UID always add (our own exports use ids).
        val byUid = existing.filter { it.uid.isNotBlank() }.associateBy { it.uid }
        val toSave = ArrayList<com.todocompanion.app.data.entity.EventEntity>()
        var added = 0; var updated = 0
        evs.forEach { inc ->
            val prev = inc.uid.takeIf { it.isNotBlank() }?.let { byUid[it] }
            when {
                prev == null -> { toSave += inc; added++ }
                inc.sequence >= prev.sequence -> {
                    com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, prev)
                    toSave += inc.copy(id = prev.id, calendarId = prev.calendarId, rsvp = prev.rsvp, createdAt = prev.createdAt)
                    updated++
                }
                // else: an older revision than we already hold — leave ours alone.
            }
        }
        repo.upsertEvents(toSave); toSave.forEach { com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, it) }
        toast(buildString {
            if (added > 0) append("Imported $added")
            if (updated > 0) { if (added > 0) append(" · "); append("updated $updated") }
            if (added == 0 && updated == 0) append("Nothing new to import") else append(" event${if (added + updated == 1) "" else "s"}")
        } + ".")
        onDone(added + updated)
    }
    /** R52 — make a new calendar named [name] and import the .ics into it. */
    fun importIcsIntoNewCalendar(uri: android.net.Uri, name: String) = viewModelScope.launch {
        val id = createEventCalendarReturningId(name); importIcsEvents(uri, id)
    }
    /** R52 — export ONE calendar (calendarId) or, when null, every calendar combined into a single file. */
    fun exportIcsEventsTo(uri: android.net.Uri, calendarId: String? = null, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val all = repo.eventsOnce()
        val evs = if (calendarId == null) all else all.filter { it.calendarId == calendarId }
        val ics = com.todocompanion.app.domain.calendar.EventIcs.export(evs, zone)
        val ok = withContext(Dispatchers.IO) { runCatching { appCtx.contentResolver.openOutputStream(uri)?.use { it.write(ics.toByteArray()) }; true }.getOrDefault(false) }
        toast(if (ok) "Exported ${evs.size} event${if (evs.size == 1) "" else "s"} (.ics)." else "Export failed."); onDone(ok)
    }
    fun exportIcsEventsToDownloads(calendarId: String? = null, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val all = repo.eventsOnce()
        val evs = if (calendarId == null) all else all.filter { it.calendarId == calendarId }
        val ics = com.todocompanion.app.domain.calendar.EventIcs.export(evs, zone)
        val loc = withContext(Dispatchers.IO) { com.todocompanion.app.util.FileExport.saveToDownloads(appCtx, "todocompanion-calendar.ics", "text/calendar", ics.toByteArray()) }
        toast(if (loc != null) "Saved to $loc" else "Export failed."); onDone(loc)
    }

    /**
     * R52 — import birthdays from a phone-book vCard (.vcf) as yearly Occasions. Offline and
     * permission-free: the user exports contacts to a file and hands us that file (no Contacts access).
     * De-dupes against occasions that already have the same person + month/day so re-importing is safe.
     */
    fun importVcardBirthdays(uri: android.net.Uri, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) { runCatching { readImportTextBounded(uri) }.getOrNull() }
        if (text == null) { toast("Couldn't read that file."); onDone(0); return@launch }
        val parsed = com.todocompanion.app.domain.VCard.parse(text)
        if (parsed.isEmpty()) { toast("No birthdays found in that file."); onDone(0); return@launch }
        val existing = countdowns.value
        fun already(name: String, m: Int, d: Int): Boolean = existing.any { c ->
            com.todocompanion.app.domain.LifeEvent.type(c) == com.todocompanion.app.domain.LifeEvent.EventType.BIRTHDAY &&
                c.personName.equals(name, ignoreCase = true) &&
                run {
                    val od = java.time.Instant.ofEpochMilli(c.targetMillis).atZone(zone).toLocalDate()
                    od.monthValue == m && od.dayOfMonth == d
                }
        }
        var added = 0
        parsed.forEach { b ->
            if (already(b.name, b.month, b.day)) return@forEach
            val year = b.year ?: 2000 // a neutral leap year; hidden because yearKnown = false
            val date = runCatching { java.time.LocalDate.of(year, b.month, minOf(b.day, java.time.YearMonth.of(year, b.month).lengthOfMonth())) }.getOrNull() ?: return@forEach
            val millis = date.atStartOfDay(zone).toInstant().toEpochMilli()
            repo.upsertCountdown(com.todocompanion.app.data.entity.CountdownEntity(
                id = java.util.UUID.randomUUID().toString(),
                title = b.name, personName = b.name,
                targetMillis = millis, emoji = "🎂", createdAt = System.currentTimeMillis(),
                eventType = com.todocompanion.app.domain.LifeEvent.EventType.BIRTHDAY.name,
                yearly = true, yearKnown = b.year != null, category = "Contacts",
                workspaceId = activeWorkspace(),
            ))
            added++
        }
        com.todocompanion.app.widget.CountdownWidget.refresh(appCtx)
        toast(when {
            added == 0 -> "All ${parsed.size} birthday${if (parsed.size == 1) "" else "s"} were already added."
            else -> "Imported $added birthday${if (added == 1) "" else "s"}."
        })
        onDone(added)
    }

    /** R53 (Wave A) — user-triggered storage maintenance: compact + defragment the DB, refresh stats. */
    fun optimizeStorage(onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        toast("Optimising storage…")
        val before = repo.databaseSizeBytes()
        val ok = repo.optimizeStorage()
        val after = repo.databaseSizeBytes()
        val freed = (before - after).coerceAtLeast(0)
        fun human(b: Long): String = when {
            b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
            b >= 1024 -> "%.0f KB".format(b / 1024.0)
            else -> "$b B"
        }
        toast(when {
            !ok -> "Couldn't optimise storage right now."
            freed >= 1024 -> "Storage optimised — reclaimed ${human(freed)} (now ${human(after)})."
            else -> "Storage optimised — already compact (${human(after)})."
        })
        onDone(ok)
    }
    /** R54 — on-disk database size (bytes) for the storage-insight panel. */
    fun databaseSizeBytes(): Long = repo.databaseSizeBytes()

    /** R56 (Wave B / R1) — row counts computed by the database itself (COUNT aggregates), not by scanning
     * in-memory lists. Powers the maintenance "database health" readout. */
    suspend fun databaseRowCounts(): Map<String, Long> = repo.databaseRowCounts()

    /** R53 — build a METHOD:REPLY .ics carrying the event's RSVP and hand it to the OS share sheet, so a
     *  fully-offline app can still let the user reply to the organizer by whatever channel they choose. */
    fun shareRsvpReply(eventId: String) = viewModelScope.launch {
        val e = repo.eventById(eventId) ?: run { toast("Event not found."); return@launch }
        val ics = com.todocompanion.app.domain.calendar.EventIcs.exportReply(e, e.rsvp.ifBlank { "yes" }, zone = zone)
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "rsvp-reply.ics"); f.writeText(ics)
                val uri = androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/calendar"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appCtx.startActivity(android.content.Intent.createChooser(send, "Send RSVP").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    // ── R41 · the planner (auto-schedule, self-healing habits, templates, audit) ─────────────────────
    /** Greedy auto-scheduler: place flexible tasks (with estimates, not yet done) into today's free
     *  slots as linked time blocks. Returns how many blocks were created. */
    fun autoScheduleDay(day: Long, chunkMin: Int = 90, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val calId = ensureDefaultCalendar()
        val evs = repo.eventsOnce()
        // Protected windows count as busy walls; the calibration bias pads each block toward its real length.
        val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, day, zone).toMutableList()
        protectedIntervalsFor(day).forEach { (s, e) ->
            occ.add(com.todocompanion.app.domain.calendar.CalendarEngine.Occurrence(
                com.todocompanion.app.data.entity.EventEntity(id = "protected", calendarId = calId, title = "Protected",
                    startMillis = s, endMillis = e, busy = true, createdAt = 0, updatedAt = 0), s, e))
        }
        val wsTasks = repo.allTasksOnce().filter { it.workspaceId == settings.value.activeWorkspaceId }
        // R60 — a task that already has a timed slot is a wall for the auto-scheduler, and is NOT re-placed.
        val linkedIds = evs.mapNotNull { it.linkedTaskId }.toSet()
        val timedTaskBusy = com.todocompanion.app.domain.calendar.Availability.taskBusyIntervals(wsTasks, zone, linkedIds)
        val timedTaskIds = wsTasks.filter { t ->
            !t.completed && !t.trashed && !t.abandoned && !t.isAllDay && t.dueDate != null && t.id !in linkedIds &&
                java.time.Instant.ofEpochMilli(t.dueDate!!).atZone(zone).let { it.hour != 0 || it.minute != 0 }
        }.map { it.id }.toSet()
        timedTaskBusy.forEach { (s, e) ->
            occ.add(com.todocompanion.app.domain.calendar.CalendarEngine.Occurrence(
                com.todocompanion.app.data.entity.EventEntity(id = "scheduled-task", calendarId = calId, title = "",
                    startMillis = s, endMillis = e, busy = true, createdAt = 0, updatedAt = 0), s, e))
        }
        // Habits that consume time are walls too: dedicated timed habits become busy blocks tasks flow
        // around, and an ambient "habits & upkeep" reserve is held at the end of the window.
        val hcReserve = habitDayReserve(day)
        com.todocompanion.app.domain.habit.HabitTime.busyIntervals(hcReserve, day, zone).forEach { (s, e) ->
            occ.add(com.todocompanion.app.domain.calendar.CalendarEngine.Occurrence(
                com.todocompanion.app.data.entity.EventEntity(id = "habit", calendarId = calId, title = "",
                    startMillis = s, endMillis = e, busy = true, createdAt = 0, updatedAt = 0), s, e))
        }
        val ambientMin = com.todocompanion.app.domain.habit.HabitTime.ambientReserveMin(hcReserve)
        if (ambientMin > 0) {
            val dayStartMs = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
            val winEndMs = dayStartMs + settings.value.workEndHour.coerceIn(1, 24).toLong() * 3_600_000L
            val us = winEndMs - ambientMin.toLong() * 60_000L
            occ.add(com.todocompanion.app.domain.calendar.CalendarEngine.Occurrence(
                com.todocompanion.app.data.entity.EventEntity(id = "habit-upkeep", calendarId = calId, title = "",
                    startMillis = us, endMillis = winEndMs, busy = true, createdAt = 0, updatedAt = 0), us, winEndMs))
        }
        val tasks = wsTasks.filter { it.id !in timedTaskIds }
        val nowFloor = if (day == java.time.LocalDate.now(zone).toEpochDay()) System.currentTimeMillis() else null
        val bias = estimateBias.value?.medianRatio ?: 1.0
        val placements = com.todocompanion.app.domain.calendar.CalendarPlanner.autoSchedule(
            tasks, occ, day, settings.value.workStartHour, settings.value.workEndHour, chunkMin,
            fromMillis = nowFloor, biasMultiplier = bias.coerceIn(0.75, 1.5), zone = zone)
        if (placements.isEmpty()) { toast("No free slots today for your unscheduled tasks — try clearing an event or lowering estimates."); onDone(0); return@launch }
        val now = System.currentTimeMillis()
        val newEvents = placements.map { p ->
            com.todocompanion.app.data.entity.EventEntity(
                id = java.util.UUID.randomUUID().toString(), calendarId = calId,
                title = if (p.parts > 1) "${p.task.title} (${p.part}/${p.parts})" else p.task.title,
                startMillis = p.startMillis, endMillis = p.endMillis,
                linkedTaskId = p.task.id, createdAt = now, updatedAt = now)
        }
        repo.upsertEvents(newEvents)
        // Offer a one-tap undo (deletes exactly these blocks) via the app-wide snackbar instead of a plain toast.
        undoEvents.tryEmit(UndoEvent(UndoKind.EVENTS_CREATED, "",
            "Placed ${newEvents.size} block${if (newEvents.size == 1) "" else "s"} into today's gaps",
            eventIds = newEvents.map { it.id }))
        onDone(newEvents.size)
    }

    /** Self-healing habit block: drop a busy block for [habit] near its preferred time, sliding it to the
     *  nearest free slot so a new event never simply buries the window. */
    fun placeHabitBlock(habit: com.todocompanion.app.data.entity.HabitEntity, day: Long, durationMin: Int = 30, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val calId = ensureDefaultCalendar()
        val evs = repo.eventsOnce()
        val prefMin = habit.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.minOrNull()
            ?: (settings.value.workStartHour * 60)
        val start = com.todocompanion.app.domain.calendar.CalendarPlanner.slideToFree(
            day, prefMin, durationMin, evs, settings.value.workStartHour, settings.value.workEndHour, zone)
        if (start == null) { toast("No free time to defend “${habit.name}” today."); onDone(false); return@launch }
        val now = System.currentTimeMillis()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId,
            title = "${habit.emoji ?: "🔁"} ${habit.name}".trim(),
            startMillis = start, endMillis = start + durationMin * 60000L,
            colorArgb = habit.colorArgb, createdAt = now, updatedAt = now)
        repo.upsertEvent(e); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
        val hm = java.time.Instant.ofEpochMilli(start).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        toast("Protected “${habit.name}” at $hm."); onDone(true)
    }

    /** Duplicate an event (a fresh one-off copy, one day later if it recurs — the fast "again" action). */
    fun duplicateEvent(id: String) = viewModelScope.launch {
        val e = repo.eventById(id) ?: return@launch
        val now = System.currentTimeMillis()
        val copy = e.copy(id = java.util.UUID.randomUUID().toString(), rrule = "", recurrenceParentId = null,
            recurrenceDate = 0, exDates = "", createdAt = now, updatedAt = now)
        repo.upsertEvent(copy); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, copy)
        toast("Duplicated “${e.title}”.")
    }

    // Event templates (stored in settings JSON) ------------------------------------------------------
    val eventTemplates: StateFlow<List<com.todocompanion.app.domain.calendar.EventTemplate>> =
        settings.map { com.todocompanion.app.domain.calendar.EventTemplates.parse(it.eventTemplatesJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveEventTemplate(t: com.todocompanion.app.domain.calendar.EventTemplate) = viewModelScope.launch {
        val list = com.todocompanion.app.domain.calendar.EventTemplates.upsert(eventTemplates.value, t)
        repo.saveSettings(settings.value.copy(eventTemplatesJson = com.todocompanion.app.domain.calendar.EventTemplates.encode(list)))
    }
    fun deleteEventTemplate(id: String) = viewModelScope.launch {
        val list = com.todocompanion.app.domain.calendar.EventTemplates.remove(eventTemplates.value, id)
        repo.saveSettings(settings.value.copy(eventTemplatesJson = com.todocompanion.app.domain.calendar.EventTemplates.encode(list)))
    }
    /** Drop a template onto the calendar at [startMillis]. */
    fun applyEventTemplate(t: com.todocompanion.app.domain.calendar.EventTemplate, startMillis: Long) = viewModelScope.launch {
        val calId = t.calendarId.ifBlank { ensureDefaultCalendar() }
        val now = System.currentTimeMillis()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = t.title,
            location = t.location, startMillis = startMillis, endMillis = startMillis + t.durationMin.coerceAtLeast(5) * 60000L,
            colorArgb = t.colorArgb, alertsMinutes = t.alertsMinutes, busy = t.busy, createdAt = now, updatedAt = now)
        repo.upsertEvent(e); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
        toast("Added “${t.title}”.")
    }

    fun setSecondaryZone(zoneId: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(secondaryZoneId = zoneId.trim())) }

    /** Self-calibrating estimate signal: median actual/planned ratio across tasks you both estimated and
     *  tracked. Null until there are ≥3 samples. Surfaced in the task editor's estimate field. */
    val estimateBias: StateFlow<com.todocompanion.app.domain.calendar.CalendarPlanner.EstimateBias?> =
        combine(tasks, timeVm.timeEntries) { t, e -> com.todocompanion.app.domain.calendar.CalendarPlanner.estimateBias(t, e) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Remembered travel minutes per place (for the auto travel buffer). */
    val travelTimes: StateFlow<Map<String, Int>> =
        settings.map { com.todocompanion.app.domain.calendar.TravelTimes.parse(it.travelTimesJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Reserve a "leave by" travel block ending when [eventStart] begins, and remember the figure for [place]. */
    fun addTravelBuffer(eventStart: Long, minutes: Int, place: String, calendarId: String) = viewModelScope.launch {
        if (minutes <= 0) return@launch
        val calId = calendarId.ifBlank { ensureDefaultCalendar() }
        val now = System.currentTimeMillis()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId,
            title = "🚗 Travel${if (place.isNotBlank()) " to ${place.trim()}" else ""}",
            startMillis = eventStart - minutes * 60000L, endMillis = eventStart,
            alertsMinutes = "0", busy = true, createdAt = now, updatedAt = now)
        repo.upsertEvent(e); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
        val map = com.todocompanion.app.domain.calendar.TravelTimes.remember(travelTimes.value, place, minutes)
        repo.saveSettings(settings.value.copy(travelTimesJson = com.todocompanion.app.domain.calendar.TravelTimes.encode(map)))
    }

    /** The weekly time-audit / retrospective — assembled off the four local data types. */
    suspend fun buildWeeklyAudit(weekStartDay: Long): com.todocompanion.app.domain.calendar.CalendarPlanner.Audit {
        val evs = repo.eventsOnce()
        val entries = repo.timeEntriesOnce()
        val tasks = repo.allTasksOnce()
        // Habit adherence over the week: checked habit-days meeting target ÷ (habits × 7).
        val ws = settings.value.activeWorkspaceId
        // Build habits only — a quit habit has no positive daily target, so it neither pads the (habits × 7)
        // denominator nor contributes a "kept" day; paused habits are on vacation.
        val habits = repo.getHabitsOnce().filter { it.workspaceId == ws && !it.archived && !it.paused && it.habitType != "break" }
        val habitById = habits.associateBy { it.id }
        val adherence = if (habits.isEmpty()) null else {
            val checks = repo.getHabitCheckinsOnce().count { c ->
                c.epochDay in weekStartDay until weekStartDay + 7 && habitById[c.habitId]?.let { com.todocompanion.app.domain.habit.HabitStats.isSuccessDay(it, c) } == true
            }
            ((checks * 100f) / (habits.size * 7)).toInt().coerceIn(0, 100)
        }
        val audit = com.todocompanion.app.domain.calendar.CalendarPlanner.weeklyAudit(
            evs, entries, tasks, weekStartDay, settings.value.workStartHour, settings.value.workEndHour, adherence, zone)
        // Moat — the correlated retrospective: tie habit consistency to meeting-load, a link only a unified
        // events+habits store can see. Compare habit completions on busy days vs quiet days this week.
        val checkins = repo.getHabitCheckinsOnce()
        val busyDays = audit.bookedMinByDay.filter { it.value >= 180 }.keys
        val quietDays = (weekStartDay until weekStartDay + 7).filter { it !in busyDays }.toSet()
        val extra = ArrayList(audit.advice)
        if (habits.isNotEmpty() && busyDays.size >= 2 && quietDays.size >= 2) {
            fun rate(days: Set<Long>) = if (days.isEmpty()) 0f else checkins.count { c -> c.epochDay in days && habitById[c.habitId]?.let { com.todocompanion.app.domain.habit.HabitStats.isSuccessDay(it, c) } == true }.toFloat() / (habits.size * days.size)
            val busyRate = rate(busyDays); val quietRate = rate(quietDays)
            if (quietRate > 0 && busyRate < quietRate * 0.7f)
                extra.add(0, "Your habits slip on busy days — ${(busyRate * 100).toInt()}% done on heavy days vs ${(quietRate * 100).toInt()}% on lighter ones. Defend those windows next week.")
        }
        return audit.copy(advice = extra)
    }

    // ── R42 · next-horizon planner (reflow, defragment, routines, protected windows, contexts, locks) ─
    /** Protected life-windows materialised as busy intervals on [day] (walls for the scheduler). */
    private fun protectedIntervalsFor(day: Long): List<Pair<Long, Long>> {
        val dow = java.time.LocalDate.ofEpochDay(day).dayOfWeek.value
        val base = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        return com.todocompanion.app.domain.calendar.ProtectedWindows.parse(settings.value.protectedWindowsJson)
            .filter { it.appliesTo(dow) }.map { (base + it.startMin * 60000L) to (base + it.endMin * 60000L) }
    }

    /** Targeted reflow ("heal conflicts"): only task-linked blocks that now overlap a fixed event slide. */
    fun reflowDay(day: Long, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val evs = repo.eventsOnce()
        val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, day, zone)
        val fixed = occ.filter { it.event.busy && !it.event.allDay && it.event.linkedTaskId == null }.map { it.startMillis to it.endMillis } + protectedIntervalsFor(day)
        val flexible = occ.filter { it.event.linkedTaskId != null && !it.event.allDay }.map { Triple(it.event.id, it.startMillis, it.endMillis) }
        val moves = com.todocompanion.app.domain.calendar.CalendarPlanner.reflow(flexible, fixed, day, settings.value.workStartHour, settings.value.workEndHour, zone)
        applyMoves(moves); toast(if (moves.isEmpty()) "Nothing to heal — no task block clashes." else "Reflowed ${moves.size} block${if (moves.size == 1) "" else "s"} around your events."); onDone(moves.size)
    }

    /** Defragment the day: re-pack task blocks back-to-back to merge scattered gaps into focus. */
    fun defragmentDay(day: Long, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val evs = repo.eventsOnce()
        val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, day, zone)
        val fixed = occ.filter { it.event.busy && !it.event.allDay && it.event.linkedTaskId == null }.map { it.startMillis to it.endMillis } + protectedIntervalsFor(day)
        val nowFloor = if (day == java.time.LocalDate.now(zone).toEpochDay()) System.currentTimeMillis() else null
        val flexible = occ.filter { it.event.linkedTaskId != null && !it.event.allDay }
            .filter { nowFloor == null || it.startMillis >= nowFloor }.map { Triple(it.event.id, it.startMillis, it.endMillis) }
        val moves = com.todocompanion.app.domain.calendar.CalendarPlanner.defragment(flexible, fixed, day, settings.value.workStartHour, settings.value.workEndHour, zone)
        applyMoves(moves); toast(if (moves.isEmpty()) "Your day is already compact." else "Merged ${moves.size} block${if (moves.size == 1) "" else "s"} into a tighter focus window."); onDone(moves.size)
    }

    private suspend fun applyMoves(moves: List<com.todocompanion.app.domain.calendar.CalendarPlanner.Move>) {
        val now = System.currentTimeMillis()
        moves.forEach { m ->
            repo.eventById(m.id)?.let { e ->
                com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, e)
                val ne = e.copy(startMillis = m.newStart, endMillis = m.newEnd, updatedAt = now)
                repo.upsertEvent(ne); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, ne)
            }
        }
    }

    /** Paint-to-dates: duplicate an event (as one-offs) onto a set of epoch-days, keeping its time-of-day. */
    fun paintEventToDates(eventId: String, days: List<Long>) = viewModelScope.launch {
        val e = repo.eventById(eventId) ?: return@launch
        val srcDay = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate().toEpochDay()
        val dur = e.endMillis - e.startMillis
        val now = System.currentTimeMillis(); var n = 0
        days.filter { it != srcDay }.forEach { d ->
            val newStart = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone)
                .withYear(java.time.LocalDate.ofEpochDay(d).year).withDayOfYear(java.time.LocalDate.ofEpochDay(d).dayOfYear)
                .toInstant().toEpochMilli()
            val copy = e.copy(id = java.util.UUID.randomUUID().toString(), startMillis = newStart, endMillis = newStart + dur,
                rrule = "", recurrenceParentId = null, recurrenceDate = 0, exDates = "", createdAt = now, updatedAt = now)
            repo.upsertEvent(copy); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, copy); n++
        }
        toast(if (n > 0) "Copied “${e.title}” onto $n date${if (n == 1) "" else "s"}." else "No dates to copy onto.")
    }

    /** One-tap actual-logging: turn a past time block into a tracked entry (planned → actual). */
    fun logActualForBlock(eventId: String) = viewModelScope.launch {
        val e = repo.eventById(eventId) ?: return@launch
        val task = e.linkedTaskId?.let { repo.getTask(it) }
        val actId = task?.defaultActivityId?.takeIf { id -> timeVm.timeActivities.value.any { it.id == id && !it.archived } } ?: repo.ensureTaskActivity()
        repo.addManualTimeEntry(actId, e.startMillis, minOf(e.endMillis, System.currentTimeMillis()), e.title, taskId = e.linkedTaskId)
        toast("Logged “${e.title}” as tracked time.")
    }

    // Day routines (settings JSON) --------------------------------------------------------------------
    val dayRoutines: StateFlow<List<com.todocompanion.app.domain.calendar.DayRoutine>> =
        settings.map { com.todocompanion.app.domain.calendar.DayRoutines.parse(it.dayRoutinesJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Capture today's timed (non-all-day) events as a reusable routine of offsets from the working start. */
    fun saveDayRoutineFromDay(name: String, day: Long) = viewModelScope.launch {
        val n = name.trim(); if (n.isBlank()) return@launch
        val base = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).plusHours(settings.value.workStartHour.toLong()).toInstant().toEpochMilli()
        val blocks = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(repo.eventsOnce(), day, zone)
            .filter { !it.event.allDay }.sortedBy { it.startMillis }.map { o ->
                com.todocompanion.app.domain.calendar.RoutineBlock(
                    title = o.event.title, startMin = (((o.startMillis - base) / 60000L)).toInt(),
                    durationMin = o.durationMin().toInt().coerceAtLeast(5), colorArgb = o.event.colorArgb)
            }
        if (blocks.isEmpty()) { toast("No timed events today to save as a routine."); return@launch }
        val list = com.todocompanion.app.domain.calendar.DayRoutines.upsert(dayRoutines.value,
            com.todocompanion.app.domain.calendar.DayRoutine(java.util.UUID.randomUUID().toString(), n, blocks = blocks))
        repo.saveSettings(settings.value.copy(dayRoutinesJson = com.todocompanion.app.domain.calendar.DayRoutines.encode(list)))
        toast("Saved routine “$n”.")
    }
    fun applyDayRoutine(routineId: String, day: Long) = viewModelScope.launch {
        val r = dayRoutines.value.firstOrNull { it.id == routineId } ?: return@launch
        val calId = ensureDefaultCalendar()
        val base = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).plusHours(settings.value.workStartHour.toLong()).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val evs = r.blocks.map { b ->
            val s = base + b.startMin * 60000L
            com.todocompanion.app.data.entity.EventEntity(
                id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = b.title,
                startMillis = s, endMillis = s + b.durationMin * 60000L, colorArgb = b.colorArgb, createdAt = now, updatedAt = now)
        }
        repo.upsertEvents(evs); evs.forEach { com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, it) }
        toast("Laid out “${r.name}” — ${evs.size} block${if (evs.size == 1) "" else "s"}.")
    }
    fun deleteDayRoutine(id: String) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(dayRoutinesJson = com.todocompanion.app.domain.calendar.DayRoutines.encode(com.todocompanion.app.domain.calendar.DayRoutines.remove(dayRoutines.value, id))))
    }

    /** R59 (Wave 4) — import a local holiday pack for a year range as all-day events, into a dedicated
     *  "Holidays" calendar (reused if it exists). Fully offline; de-dupes by title+day. */
    fun importHolidayPack(packId: String, fromYear: Int, toYear: Int, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val pack = com.todocompanion.app.domain.calendar.Holidays.PACKS.firstOrNull { it.id == packId }
        val calName = "${pack?.emoji ?: "🎌"} Holidays"
        val calId = repo.eventCalendarsOnce().firstOrNull { it.name == calName }?.id ?: run {
            val id = java.util.UUID.randomUUID().toString()
            repo.upsertEventCalendar(com.todocompanion.app.data.entity.EventCalendarEntity(
                id = id, name = calName, colorArgb = 0xFFEF4444, workspaceId = settings.value.activeWorkspaceId, createdAt = System.currentTimeMillis()))
            id
        }
        val now = System.currentTimeMillis()
        val existingKeys = repo.eventsOnce().filter { it.calendarId == calId }.map { it.title to it.startMillis }.toSet()
        val newEvents = com.todocompanion.app.domain.calendar.Holidays.forRange(packId, fromYear, toYear).mapNotNull { h ->
            val start = h.date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = h.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            if ((h.name to start) in existingKeys) null
            else com.todocompanion.app.data.entity.EventEntity(id = java.util.UUID.randomUUID().toString(), calendarId = calId,
                title = h.name, startMillis = start, endMillis = end, allDay = true, busy = false, createdAt = now, updatedAt = now)
        }
        if (newEvents.isNotEmpty()) repo.upsertEvents(newEvents)
        toast(if (newEvents.isEmpty()) "Those holidays are already imported." else "Added ${newEvents.size} holidays.")
        onDone(newEvents.size)
    }

    // Protected windows -------------------------------------------------------------------------------
    val protectedWindows: StateFlow<List<com.todocompanion.app.domain.calendar.ProtectedWindow>> =
        settings.map { com.todocompanion.app.domain.calendar.ProtectedWindows.parse(it.protectedWindowsJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun saveProtectedWindow(name: String, startMin: Int, endMin: Int, days: List<Int>) = viewModelScope.launch {
        val n = name.trim(); if (n.isBlank() || endMin <= startMin) return@launch
        val list = com.todocompanion.app.domain.calendar.ProtectedWindows.upsert(protectedWindows.value,
            com.todocompanion.app.domain.calendar.ProtectedWindow(java.util.UUID.randomUUID().toString(), n, startMin, endMin, days))
        repo.saveSettings(settings.value.copy(protectedWindowsJson = com.todocompanion.app.domain.calendar.ProtectedWindows.encode(list)))
    }
    fun deleteProtectedWindow(id: String) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(protectedWindowsJson = com.todocompanion.app.domain.calendar.ProtectedWindows.encode(com.todocompanion.app.domain.calendar.ProtectedWindows.remove(protectedWindows.value, id))))
    }
    /** R59 (Wave 3) — merge the two protected-time systems: fold any legacy availability protectedBlocks
     *  into the canonical ProtectedWindow list (which the planner also respects), atomically, then clear
     *  the legacy field. A one-time no-op once done. */
    fun migrateProtectedBlocksToWindows() = viewModelScope.launch {
        val s = settings.value
        if (s.protectedBlocks.isBlank()) return@launch
        var list = com.todocompanion.app.domain.calendar.ProtectedWindows.parse(s.protectedWindowsJson)
        com.todocompanion.app.domain.calendar.Availability.parseProtected(s.protectedBlocks).forEach { p ->
            list = com.todocompanion.app.domain.calendar.ProtectedWindows.upsert(list,
                com.todocompanion.app.domain.calendar.ProtectedWindow(java.util.UUID.randomUUID().toString(),
                    "Protected %02d:00–%02d:00".format(p.startMin / 60, p.endMin / 60), p.startMin, p.endMin, p.days.sorted()))
        }
        repo.saveSettings(s.copy(protectedWindowsJson = com.todocompanion.app.domain.calendar.ProtectedWindows.encode(list), protectedBlocks = ""))
    }

    // Plan lock + lunar overlay -----------------------------------------------------------------------
    fun isPlanLocked(day: Long): Boolean = settings.value.planLockedDaysCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.contains(day)
    fun setPlanLocked(day: Long, locked: Boolean) = viewModelScope.launch {
        val cur = settings.value.planLockedDaysCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
        if (locked) cur.add(day) else cur.remove(day)
        repo.saveSettings(settings.value.copy(planLockedDaysCsv = cur.sorted().joinToString(",")))
        toast(if (locked) "Plan locked — auto-schedule will only fill gaps now." else "Plan unlocked.")
    }
    fun setLunarOverlay(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(lunarOverlay = on)) }

    // Context modes: a saved set of calendars; activating one shows those and hides the rest (Fantastical
    // Calendar Sets, minus the geofence). Reuses the existing per-calendar visibility — no new filtering.
    val calContexts: StateFlow<List<com.todocompanion.app.domain.calendar.CalContext>> =
        settings.map { com.todocompanion.app.domain.calendar.CalContexts.parse(it.calContextsJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun saveContext(name: String, calendarIds: List<String>) = viewModelScope.launch {
        val n = name.trim(); if (n.isBlank()) return@launch
        val list = com.todocompanion.app.domain.calendar.CalContexts.upsert(calContexts.value,
            com.todocompanion.app.domain.calendar.CalContext(java.util.UUID.randomUUID().toString(), n, calendarIds = calendarIds))
        repo.saveSettings(settings.value.copy(calContextsJson = com.todocompanion.app.domain.calendar.CalContexts.encode(list)))
    }
    fun deleteContext(id: String) = viewModelScope.launch {
        val next = if (settings.value.activeContextId == id) "" else settings.value.activeContextId
        repo.saveSettings(settings.value.copy(
            calContextsJson = com.todocompanion.app.domain.calendar.CalContexts.encode(com.todocompanion.app.domain.calendar.CalContexts.remove(calContexts.value, id)),
            activeContextId = next))
    }
    fun activateContext(id: String) = viewModelScope.launch {
        val ctx = calContexts.value.firstOrNull { it.id == id }
        if (ctx == null) { repo.saveSettings(settings.value.copy(activeContextId = "")); repo.eventCalendarsOnce().forEach { repo.upsertEventCalendar(it.copy(visible = true)) }; return@launch }
        repo.eventCalendarsOnce().forEach { repo.upsertEventCalendar(it.copy(visible = it.id in ctx.calendarIds)) }
        repo.saveSettings(settings.value.copy(activeContextId = id))
        toast("Context “${ctx.name}” — showing ${ctx.calendarIds.size} calendar${if (ctx.calendarIds.size == 1) "" else "s"}.")
    }

    fun skipHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, reason: String = "") = viewModelScope.launch {
        if (beforeStart(h, epochDay)) return@launch
        repo.skipDay(h.id, epochDay, reason); refreshHabitWidgets()
    }
    /** N6: log a break-habit slip with an optional trigger (kept in the day's note for a trigger breakdown). */
    fun logSlip(h: com.todocompanion.app.data.entity.HabitEntity, trigger: String) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val existing = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == today }
        val count = (existing?.count ?: 0) + 1
        val note = (existing?.reason?.takeIf { it.isNotBlank() }?.plus("; ") ?: "") + trigger.trim().ifBlank { "slip" }
        repo.setDay(h.id, today, count, "done", note)
        refreshHabitWidgets()
    }
    fun clearHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long) = viewModelScope.launch {
        repo.clearCheckin(h.id, epochDay); refreshHabitWidgets()
    }
    /** Write a whole day from the per-day editor: value, done/skip, and a free-text note. */
    fun setHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, count: Int, status: String, note: String) = viewModelScope.launch {
        if (beforeStart(h, epochDay)) return@launch
        repo.setDay(h.id, epochDay, count, status, note); refreshHabitWidgets()
    }
    // ---- Tier K ----
    /** K2: spend one earned freeze to protect a missed day. */
    fun spendHabitFreeze(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val ok = repo.spendFreeze(h.id, epochDay); refreshHabitWidgets(); onDone(ok)
    }
    /** K5: attach a photo to a day — the picked image is downscaled and copied into app storage. */
    fun setHabitPhoto(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, uri: Uri?) = viewModelScope.launch {
        if (uri == null) { repo.setCheckinPhoto(h.id, epochDay, null); refreshHabitWidgets(); return@launch }
        val path = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val maxDim = 1280
                val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1))
                val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
                val dir = java.io.File(appCtx.filesDir, "habit_photos").apply { mkdirs() }
                val f = java.io.File(dir, UUID.randomUUID().toString() + ".jpg")
                com.todocompanion.app.data.security.FileVault.writeEncrypted(f, out.toByteArray())   // SEC-1: at-rest encrypted
                f.absolutePath
            }.getOrNull()
        }
        if (path != null) { repo.setCheckinPhoto(h.id, epochDay, path); refreshHabitWidgets() } else toast("Couldn't read that image")
    }
    fun setHabitPaused(h: com.todocompanion.app.data.entity.HabitEntity, paused: Boolean) = viewModelScope.launch {
        repo.setHabitPaused(h.id, paused); com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo); refreshHabitWidgets()
    }
    fun pauseAllHabits(paused: Boolean) = viewModelScope.launch {
        repo.pauseAllHabits(settings.value.activeWorkspaceId, paused); com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo); refreshHabitWidgets()
    }
    private fun refreshHabitWidgets() {
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
        com.todocompanion.app.widget.HabitStatsWidget.refresh(appCtx)
        // R104 — the momentum score folds in habit strength, so keep it live on habit changes too.
        com.todocompanion.app.widget.MomentumWidget.refresh(appCtx)
    }

    // ---------- deep-work coach (H4) ----------
    data class DeepWorkStatus(val todayMin: Int, val goalMin: Int, val streakDays: Int, val best: TaskEntity?, val bestBlockMin: Int)

    /** Today's focused minutes against the daily goal, the current streak of goal-met days, and the
     *  single best task to sink a block into next (with a suggested block length from its estimate). */
    fun deepWorkStatus(): DeepWorkStatus {
        val goal = settings.value.deepWorkGoalMin.coerceAtLeast(1)
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val byDay = focusMinutesByDay()
        val todayMin = byDay[today] ?: 0
        // R83 — the streak math lives in domain/FocusStats (unit-tested).
        val streak = com.todocompanion.app.domain.FocusStats.streakDays(byDay, goal, today)
        val best = topDoNext()
        val blockMin = (best?.estimateMin ?: best?.estimateMax ?: best?.durationMin ?: 25).coerceIn(10, 90)
        return DeepWorkStatus(todayMin, goal, streak, best, blockMin)
    }

    // ---------- focus  (a MODE of time tracking, never a second system) ----------
    // Robust integration (Round 17): pressing Start in Focus does NOT open a separate stopwatch — it starts
    // a running interval on the ONE timeline tagged kind="focus". So a focus block is *tracked time* the
    // instant it begins (it shows in the running-timer bar and the calendar), Stop finalizes it (crediting
    // any linked habit through the same finalizeEntry path a manual timer uses), and EVERY focus statistic
    // below is derived from those kind="focus" intervals. There is no FocusSession double-write any more,
    // so "time tracked" and "time focused" can never diverge into two contradictory totals.
    val focusTargetMin = MutableStateFlow(25)   // countdown length in minutes; 0 = open-ended stopwatch

    // R84 — the imperative focus control lives in focus/FocusController; the VM keeps the flows above and
    // passes in the settings snapshot, activity resolver, and widget-refresh callbacks.
    private val focusCtl by lazy {
        com.todocompanion.app.focus.FocusController(
            appCtx, repo,
            settings = { settings.value },
            resolveActivity = { taskId, habitId ->
                taskId?.let { tid -> tasks.value.firstOrNull { it.id == tid }?.defaultActivityId }
                    ?: habitId?.let { hid -> habits.value.firstOrNull { it.id == hid }?.timeActivityId }
            },
            setTargetMin = { focusTargetMin.value = it },
            onRefreshTime = { refreshTimeWidget() },
            onRefreshHabits = { refreshHabitWidgets() },
        )
    }

    /** The single running focus interval, if a focus session is live right now (drives the ring). */
    val runningFocus: StateFlow<com.todocompanion.app.data.entity.TimeEntryEntity?> =
        timeVm.timeEntries.map { list -> list.firstOrNull { it.running && it.kind == "focus" } }.state(null)

    /** Focus intervals as synthetic FocusSessionEntity rows (day, minutes, taskId) computed from the one
     *  timeline, so the stats / momentum / digest screens read the SAME source as the Time reports — never
     *  a second table. Legacy persisted FocusSessions (written before this unification, when Time was off)
     *  are unioned in so old history isn't lost. A running interval is clamped to now. */
    fun focusViews(): List<com.todocompanion.app.data.entity.FocusSessionEntity> =
        com.todocompanion.app.domain.FocusStats.views(timeVm.timeEntries.value, focusSessions.value, zone, System.currentTimeMillis())
    /** Focused minutes per calendar day, from kind="focus" intervals (a running one clamped to now). */
    fun focusMinutesByDay(): Map<Long, Int> =
        com.todocompanion.app.domain.FocusStats.minutesByDay(timeVm.timeEntries.value, focusSessions.value, zone, System.currentTimeMillis())

    /** Start a focus session against [activityId] (or the task's / habit's linked activity, else a generic
     *  "Focus" activity). [remainingSec] lets Resume schedule the chime for exactly the time still left. */
    fun startFocusSession(
        activityId: String?, targetMin: Int, remainingSec: Int = targetMin * 60,
        taskId: String? = null, habitId: String? = null,
    ) = viewModelScope.launch { focusCtl.start(activityId, targetMin, remainingSec, taskId, habitId) }

    /** Stop the running focus interval (finalize + credit any linked habit) and cancel its chime. Only ever
     *  stops a kind="focus" entry, so a paused-focus Finish can never accidentally stop a manual timer. */
    fun stopFocus() = viewModelScope.launch {
        focusCtl.stop(timeVm.timeEntries.value.firstOrNull { it.running && it.kind == "focus" }?.id)
    }

    /** R81 — play the chosen focus/timer completion cue in-app (the background alarm plays it via the
     *  notification channel; this is for when the app is in the foreground when the countdown finishes). */
    fun playFocusDoneSound() = focusCtl.playDoneSound()

    // ---------- saved filters ----------
    fun createFilter(name: String) = viewModelScope.launch {
        val id = repo.createFilter(name.trim(), settings.value.activeWorkspaceId)
        currentView.value = ViewRef.FilterView(id)
    }
    fun saveFilter(f: com.todocompanion.app.data.entity.FilterEntity) = viewModelScope.launch { repo.upsertFilter(f) }
    fun deleteFilter(f: com.todocompanion.app.data.entity.FilterEntity) = viewModelScope.launch {
        repo.deleteFilter(f.id)
        if (currentView.value == ViewRef.FilterView(f.id)) currentView.value = ViewRef.Smart(SmartKind.TODAY)
    }
    fun saveList(l: ListEntity) = viewModelScope.launch { repo.saveList(l) }
    fun deleteList(id: String) = viewModelScope.launch { repo.deleteList(id) }
    // R52 — archive/restore a list; its tasks drop out of active views but are kept and restorable.
    fun setListArchived(l: ListEntity, archived: Boolean) = viewModelScope.launch { repo.saveList(l.copy(archived = archived)) }

    /** Delete a list to Trash (recoverable) instead of erasing its tasks. [moveTasksToRef]
     *  ("list:<id>"/"folder:<id>") first moves the list's tasks there, then trashes the emptied list;
     *  null keeps the tasks with the list in Trash (hidden via the container-visibility cascade). One
     *  Undo restores the list and every moved task exactly. */
    fun trashList(list: ListEntity, moveTasksToRef: String? = null) = viewModelScope.launch {
        if (list.id == ListEntity.INBOX_ID) return@launch
        val now = System.currentTimeMillis()
        val listTasks = repo.allTasksOnce().filter { it.listId == list.id }
        if (moveTasksToRef != null) moveTasksTo(listTasks.filter { it.parentId == null }, moveTasksToRef)
        repo.saveList(list.copy(trashed = true, trashedAt = now))
        undoEvents.tryEmit(UndoEvent(UndoKind.CONTAINER_TRASHED, list.id,
            if (moveTasksToRef != null) "Deleted “${list.name}”, moved its tasks" else "Deleted “${list.name}” to Trash",
            listSnapshots = listOf(list), taskSnapshots = listTasks))
    }
    fun restoreTrashedList(l: ListEntity) = viewModelScope.launch { repo.saveList(l.copy(trashed = false, trashedAt = null)) }
    fun deleteListForever(id: String) = viewModelScope.launch { repo.deleteList(id) }

    /** Convert a list into a folder, preserving its tasks in a same-named list inside it. */
    fun convertListToFolder(list: ListEntity) = viewModelScope.launch {
        val folderId = repo.createFolder(list.name, parentId = list.folderId)
        val newListId = repo.createList(list.name, folderId, list.colorArgb)
        tasks.value.filter { it.listId == list.id && it.parentId == null }.forEach { repo.moveToList(it.id, newListId) }
        repo.deleteList(list.id)
        if (currentView.value == ViewRef.ListView(list.id)) select(ViewRef.ListView(newListId))
    }

    /** Convert an empty folder into a list. Returns false (no-op) if the folder still has contents. */
    fun convertFolderToList(folder: FolderEntity): Boolean {
        val hasChildren = folders.value.any { it.parentId == folder.id } || lists.value.any { it.folderId == folder.id }
        if (hasChildren) return false
        viewModelScope.launch {
            val id = repo.createList(folder.name, folder.parentId, null)
            repo.deleteFolder(folder.id)
            select(ViewRef.ListView(id))
        }
        return true
    }
    fun moveListOrder(l: ListEntity, dir: Int) = viewModelScope.launch { repo.moveListOrder(l, dir) }
    /** Persist a drag reorder of sibling lists by writing each id's index as its sortOrder. */
    fun setListOrder(orderedIds: List<String>) = viewModelScope.launch {
        val byId = lists.value.associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { if (it.sortOrder != i.toDouble()) repo.saveList(it.copy(sortOrder = i.toDouble())) } }
    }
    /** Persist a drag reorder of sibling folders. */
    fun setFolderOrder(orderedIds: List<String>) = viewModelScope.launch {
        val byId = folders.value.associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { if (it.sortOrder != i.toDouble()) repo.saveFolder(it.copy(sortOrder = i.toDouble())) } }
    }
    fun moveFolderOrder(f: FolderEntity, dir: Int) = viewModelScope.launch { repo.moveFolderOrder(f, dir) }
    fun moveListToFolder(listId: String, folderId: String?) = viewModelScope.launch { repo.moveListToFolder(listId, folderId) }
    fun moveFolderToParent(folderId: String, parentId: String?) = viewModelScope.launch { repo.moveFolderToParent(folderId, parentId) }

    // ---------- row actions (flag / star / priority / swipes) ----------
    fun toggleStar(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(star = !t.star)) }
    /** R52 — park/un-park a task in the GTD Someday/Maybe list. Parking clears its date so it stops being
     *  "scheduled"; promoting it back just drops the flag (the user re-dates it in the review). */
    fun setSomeday(t: TaskEntity, someday: Boolean) = viewModelScope.launch {
        repo.saveTask(t.copy(someday = someday, dueDate = if (someday) null else t.dueDate, startDate = if (someday) null else t.startDate, updatedAt = System.currentTimeMillis()))
    }
    fun setPriority(t: TaskEntity, level: PriorityLevel) = viewModelScope.launch { repo.saveTask(t.copy(importance = level.importance, urgency = level.urgency)) }
    /** Drag-to-move in the Eisenhower matrix: set importance/urgency so [t] lands in quadrant [q]
     *  (0 UI, 1 NI, 2 UN, 3 NN) under the current thresholds. */
    fun setMatrixQuadrant(t: TaskEntity, q: Int, impThreshold: Int, urgThreshold: Int) = viewModelScope.launch {
        val important = q == 0 || q == 1
        val urgent = q == 0 || q == 2
        val imp = if (important) 5 else (impThreshold - 1).coerceIn(1, 5)
        val urg = if (urgent) 5 else (urgThreshold - 1).coerceIn(1, 5)
        if (t.importance != imp || t.urgency != urg) repo.saveTask(t.copy(importance = imp, urgency = urg))
    }
    fun setDuration(taskId: String, minutes: Int) = viewModelScope.launch { repo.getTask(taskId)?.let { repo.saveTask(it.copy(durationMin = minutes.coerceIn(15, 24 * 60))) } }
    /** Shift a task's start and due dates by [days] (Timeline drag-to-reschedule; preserves span). */
    fun shiftTaskDays(taskId: String, days: Int) = viewModelScope.launch {
        if (days == 0) return@launch
        repo.getTask(taskId)?.let { t ->
            val d = days * 86_400_000L
            repo.saveTask(t.copy(startDate = t.startDate?.plus(d), dueDate = t.dueDate?.plus(d)))
        }
    }
    /** Move a task's due time to [minute] of [day] (time-blocking drag on the calendar). */
    fun rescheduleToMinute(taskId: String, day: java.time.LocalDate, minute: Int) = viewModelScope.launch {
        repo.getTask(taskId)?.let { t ->
            val ms = day.atStartOfDay(zone).plusMinutes(minute.toLong().coerceIn(0, 1439)).toInstant().toEpochMilli()
            repo.saveTask(t.copy(dueDate = ms, isAllDay = false))
        }
    }
    /** C1 — reschedule ripple: move a timed task, then carry its direct dependents (tasks that declared
     *  this one a prerequisite) by the same delta, so a chain you built stays intact when its anchor
     *  slides. Only same-day, timed, still-open dependents move; [onRippled] reports how many followed. */
    fun rescheduleWithRipple(taskId: String, day: java.time.LocalDate, minute: Int, onRippled: (Int) -> Unit = {}) = viewModelScope.launch {
        val t = repo.getTask(taskId) ?: return@launch
        val oldMs = t.dueDate
        val newMs = day.atStartOfDay(zone).plusMinutes(minute.toLong().coerceIn(0, 1439)).toInstant().toEpochMilli()
        repo.saveTask(t.copy(dueDate = newMs, isAllDay = false))
        val delta = if (oldMs != null) newMs - oldMs else 0L
        if (delta == 0L || oldMs == null) { onRippled(0); return@launch }
        val anchorDate = java.time.Instant.ofEpochMilli(oldMs).atZone(zone).toLocalDate()
        val dependentIds = dependencies.value.filter { it.dependsOnTaskId == taskId }.map { it.taskId }.toSet()
        var moved = 0
        dependentIds.forEach { id ->
            val d = repo.getTask(id) ?: return@forEach
            val due = d.dueDate ?: return@forEach
            if (d.completed || d.trashed || d.abandoned || d.isAllDay) return@forEach
            if (java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalDate() != anchorDate) return@forEach
            repo.saveTask(d.copy(dueDate = due + delta)); moved++
        }
        onRippled(moved)
    }
    fun togglePin(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(pinned = !t.pinned)) }
    fun toggleNote(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(isNote = !t.isNote)) }
    fun duplicateTask(t: TaskEntity) = viewModelScope.launch {
        val nowMs = System.currentTimeMillis()
        repo.saveTask(t.copy(id = UUID.randomUUID().toString(), title = t.title + " (copy)", completed = false, completedAt = null,
            abandoned = false, trashed = false, sortOrder = t.sortOrder + 0.0001, createdAt = nowMs, updatedAt = nowMs))
    }
    /**
     * Detach the current occurrence of a recurring task so it can be edited without touching the
     * series (B5 single-instance edit). The viewed task loses its rule (becomes a one-off you're free
     * to change); a fresh copy carries the series forward to the next occurrence.
     */
    fun detachOccurrence(t: TaskEntity, onSeries: (String) -> Unit = {}) = viewModelScope.launch {
        if (t.rrule.isNullOrBlank() || t.dueDate == null) return@launch
        val (nextDue, newRule) = com.todocompanion.app.domain.recurrence.Recurrence.advance(t.rrule!!, t.dueDate!!, zone, System.currentTimeMillis())
        // This occurrence keeps its date but drops the recurrence — now a standalone task.
        repo.saveTask(t.copy(rrule = null))
        if (nextDue != null) {
            val nowMs = System.currentTimeMillis()
            val delta = nextDue - t.dueDate!!
            val seriesId = UUID.randomUUID().toString()
            repo.saveTask(t.copy(id = seriesId, dueDate = nextDue, startDate = t.startDate?.plus(delta),
                deadlineDate = t.deadlineDate?.plus(delta), rrule = newRule, completed = false, completedAt = null,
                createdAt = nowMs, updatedAt = nowMs, sortOrder = t.sortOrder + 0.0001))
            onSeries(seriesId)
        }
    }
    /** Tap-to-flag on a row: cycle through the ordered flags, then back to none (MLO-style). */
    fun cycleFlag(t: TaskEntity) = viewModelScope.launch {
        val ordered = flags.value.sortedBy { it.sortOrder }
        if (ordered.isEmpty()) return@launch
        val idx = ordered.indexOfFirst { it.id == t.flagId }
        val next = when {
            t.flagId == null -> ordered.first().id
            idx < 0 || idx == ordered.lastIndex -> null
            else -> ordered[idx + 1].id
        }
        repo.setTaskFlag(t, next)
    }
    fun setFlag(t: TaskEntity, flagId: String?) = viewModelScope.launch { repo.setTaskFlag(t, flagId) }

    // ---------- flag management ----------
    fun createFlag(name: String, colorArgb: Long, icon: String = "bookmark") = viewModelScope.launch { repo.createFlag(name, colorArgb, icon) }
    fun updateFlag(f: FlagEntity) = viewModelScope.launch {
        repo.upsertFlag(f)
        // Keep the colour cache on tasks wearing this flag in sync with the edited colour.
        tasks.value.filter { it.flagId == f.id && it.flagColorArgb != f.colorArgb }.forEach { repo.saveTask(it.copy(flagColorArgb = f.colorArgb)) }
    }
    fun deleteFlag(id: String) = viewModelScope.launch { repo.deleteFlag(id) }
    fun moveFlag(f: FlagEntity, dir: Int) = viewModelScope.launch { repo.moveFlagOrder(f, dir) }

    // ---------- templates ----------
    fun saveAsTemplate(taskId: String, name: String) = viewModelScope.launch { repo.saveAsTemplate(taskId, name) }

    /** Repeated task shapes worth turning into a template (G5). Pure logic in domain/TemplateSuggest. */
    fun suggestedTemplates(minCount: Int = 3): List<com.todocompanion.app.domain.TemplateSuggest.Suggestion> =
        com.todocompanion.app.domain.TemplateSuggest.suggest(
            tasks.value, templates.value.map { it.name.trim().lowercase() }.toSet(), minCount,
        )
    fun deleteTemplate(id: String) = viewModelScope.launch { repo.deleteTemplate(id) }
    fun renameTemplate(id: String, name: String) = viewModelScope.launch { repo.renameTemplate(id, name) }
    /** Drop a template into the current view's list (or Inbox), opening its new root if requested. */
    fun insertTemplateHere(templateId: String, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val (listId, folderId) = resolveAddTarget()
        val id = repo.instantiateTemplate(templateId, listId, folderId = folderId)
        onDone(id)
    }
    fun cyclePriority(t: TaskEntity) = viewModelScope.launch {
        val next = when (PriorityLevel.from(t.importance, t.urgency)) {
            PriorityLevel.NONE -> PriorityLevel.LOW
            PriorityLevel.LOW -> PriorityLevel.MEDIUM
            PriorityLevel.MEDIUM -> PriorityLevel.HIGH
            PriorityLevel.HIGH -> PriorityLevel.NONE
        }
        repo.saveTask(t.copy(importance = next.importance, urgency = next.urgency))
    }
    /** Apply a configured swipe action. EDIT returns false so the caller can open the editor. */
    fun applyAction(action: com.todocompanion.app.domain.SwipeAction, t: TaskEntity): Boolean {
        when (action) {
            com.todocompanion.app.domain.SwipeAction.COMPLETE -> toggleComplete(t)
            com.todocompanion.app.domain.SwipeAction.TRASH -> trash(t)
            com.todocompanion.app.domain.SwipeAction.STAR -> toggleStar(t)
            com.todocompanion.app.domain.SwipeAction.WONT_DO -> setAbandoned(t, !t.abandoned)
            com.todocompanion.app.domain.SwipeAction.CYCLE_PRIORITY -> cyclePriority(t)
            com.todocompanion.app.domain.SwipeAction.SCHEDULE_TOMORROW -> {
                val ms = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()
                save(t.copy(dueDate = ms))
            }
            com.todocompanion.app.domain.SwipeAction.SOMEDAY -> { setSomeday(t, !t.someday); toast(if (t.someday) "Back in your lists." else "Parked in Someday / Maybe.") }
            com.todocompanion.app.domain.SwipeAction.EDIT -> return false
            com.todocompanion.app.domain.SwipeAction.MOVE -> return false   // needs the move picker; caller handles
            com.todocompanion.app.domain.SwipeAction.NONE -> {}
        }
        return true
    }

    // ---------- tags / contexts ----------
    fun tagsForTask(taskId: String): List<TagEntity> {
        val ids = taskTags.value.filter { it.taskId == taskId }.map { it.tagId }.toSet()
        return tags.value.filter { it.id in ids }
    }
    fun contextsForTask(taskId: String): List<ContextEntity> {
        val ids = taskContexts.value.filter { it.taskId == taskId }.map { it.contextId }.toSet()
        return contexts.value.filter { it.id in ids }
    }
    fun setTags(taskId: String, tagIds: List<String>) = viewModelScope.launch { repo.setTaskTags(taskId, tagIds) }
    fun setContexts(taskId: String, ids: List<String>) = viewModelScope.launch { repo.setTaskContexts(taskId, ids) }
    fun createTag(name: String, parentId: String? = null) = viewModelScope.launch { repo.upsertTag(TagEntity(UUID.randomUUID().toString(), name.trim(), parentId = parentId, workspaceId = settings.value.activeWorkspaceId)) }
    /** Create a tag (or reuse one with the same name) and assign it to a note in one step (NotesNook-style). */
    fun createAndAssignNoteTag(noteId: String, name: String, currentTagIds: List<String>) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isBlank()) return@launch
        val ws = settings.value.activeWorkspaceId
        val existing = tags.value.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        if (existing == null) repo.upsertTag(TagEntity(id, clean, workspaceId = ws))
        repo.setNoteTags(noteId, (currentTagIds + id).distinct())
    }
    fun renameTag(tag: TagEntity, name: String) = viewModelScope.launch { repo.upsertTag(tag.copy(name = name.trim())) }
    fun setTagColor(tag: TagEntity, argb: Long?) = viewModelScope.launch { repo.upsertTag(tag.copy(colorArgb = argb)) }
    fun deleteTag(tag: TagEntity) = viewModelScope.launch {
        // reparent children to this tag's parent so the subtree isn't orphaned
        tags.value.filter { it.parentId == tag.id }.forEach { repo.upsertTag(it.copy(parentId = tag.parentId)) }
        repo.deleteTag(tag.id)
        if (currentView.value == ViewRef.TagView(tag.id)) select(ViewRef.Smart(SmartKind.TODAY))
    }
    fun moveTagToParent(tagId: String, parentId: String?) = viewModelScope.launch {
        tags.value.firstOrNull { it.id == tagId }?.let { repo.upsertTag(it.copy(parentId = parentId)) }
    }
    fun createContext(name: String, parentId: String? = null) = viewModelScope.launch { repo.upsertContext(ContextEntity(id = UUID.randomUUID().toString(), name = name.trim(), parentId = parentId, workspaceId = settings.value.activeWorkspaceId)) }
    /** Create a context (or reuse one with the same name) and assign it to a note in one step — the
     *  context equivalent of [createAndAssignNoteTag], so Notes matches how Tasks handle contexts. */
    fun createAndAssignNoteContext(noteId: String, name: String, currentContextIds: List<String>) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isBlank()) return@launch
        val ws = settings.value.activeWorkspaceId
        val existing = contexts.value.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        if (existing == null) repo.upsertContext(ContextEntity(id = id, name = clean, workspaceId = ws))
        repo.setNoteContexts(noteId, (currentContextIds + id).distinct())
    }
    /**
     * The dialog hands each mutator an open-time [ContextEntity] snapshot that is never refreshed, so
     * a `c.copy(...)` on it silently reverts every field another mutator changed while the dialog was
     * open. Always read-modify-write the *current* persisted row so edits merge instead of clobbering.
     */
    private fun curContext(c: ContextEntity): ContextEntity = contexts.value.firstOrNull { it.id == c.id } ?: c
    fun renameContext(c: ContextEntity, name: String) = viewModelScope.launch { repo.upsertContext(curContext(c).copy(name = name.trim())) }
    fun setContextColor(c: ContextEntity, argb: Long?) = viewModelScope.launch { repo.upsertContext(curContext(c).copy(colorArgb = argb)) }
    fun setContextActive(c: ContextEntity, active: Boolean) = viewModelScope.launch { repo.upsertContext(curContext(c).copy(active = active)) }
    fun setContextHours(c: ContextEntity, json: String?) = viewModelScope.launch { repo.upsertContext(curContext(c).copy(openHoursJson = json)) }
    fun deleteContext(c: ContextEntity) = viewModelScope.launch {
        contexts.value.filter { it.parentId == c.id }.forEach { repo.upsertContext(it.copy(parentId = c.parentId)) }
        repo.deleteContext(c.id)
        if (currentView.value == ViewRef.ContextView(c.id)) select(ViewRef.Smart(SmartKind.TODAY))
    }
    fun moveContextToParent(contextId: String, parentId: String?) = viewModelScope.launch {
        contexts.value.firstOrNull { it.id == contextId }?.let { repo.upsertContext(it.copy(parentId = parentId)) }
    }

    // ---------- dependencies ----------
    fun dependenciesFor(taskId: String): List<DependencyEntity> = dependencies.value.filter { it.taskId == taskId }
    fun addDependency(taskId: String, dependsOn: String, mode: String = "AND") = viewModelScope.launch { repo.addDependency(taskId, dependsOn, mode) }
    fun removeDependency(dep: DependencyEntity) = viewModelScope.launch { repo.removeDependency(dep) }
    /** Set the blocking mode (AND = all must finish, OR = any one unblocks) for every blocker of a task. */
    fun setDependencyMode(taskId: String, mode: String) = viewModelScope.launch {
        dependencies.value.filter { it.taskId == taskId }.forEach { repo.removeDependency(it); repo.addDependency(it.taskId, it.dependsOnTaskId, mode, it.delayDays) }
    }
    /** Set the activation delay (days after the anchor prerequisite completes) for a task's blockers. */
    fun setDependencyDelay(taskId: String, days: Int) = viewModelScope.launch {
        dependencies.value.filter { it.taskId == taskId }.forEach { repo.removeDependency(it); repo.addDependency(it.taskId, it.dependsOnTaskId, it.mode, days) }
    }
    fun setCompleteInOrder(t: TaskEntity, v: Boolean) = viewModelScope.launch { repo.saveTask(t.copy(completeInOrder = v)) }
    fun setProject(t: TaskEntity, v: Boolean) = viewModelScope.launch { repo.saveTask(t.copy(isProject = v)) }
    fun setGoal(t: TaskEntity, v: Boolean) = viewModelScope.launch { repo.saveTask(t.copy(isGoal = v)) }
    fun setReviewEvery(t: TaskEntity, days: Int?) = viewModelScope.launch { repo.saveTask(t.copy(reviewEveryDays = days)) }
    fun markReviewed(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(reviewedAt = System.currentTimeMillis())) }

    // ---------- reminders ----------
    // R77 — reminder creation/retune/cancel lives in ReminderController (context + repo + default-tier
    // provider). These wrappers keep only the viewModelScope threading, so behaviour is unchanged.
    private val reminderCtl by lazy {
        com.todocompanion.app.reminders.ReminderController(appCtx, repo) { settings.value.defaultReminderTier }
    }

    fun addAbsoluteReminder(task: TaskEntity, atMillis: Long, annoying: Boolean = false) = viewModelScope.launch { reminderCtl.addAbsolute(task, atMillis, annoying) }
    fun addRelativeReminder(task: TaskEntity, type: String, offsetMin: Int, annoying: Boolean = false) = viewModelScope.launch { reminderCtl.addRelative(task, type, offsetMin, annoying) }
    fun addExpertReminder(task: TaskEntity, type: String, offsetMin: Int = 0, repeatEveryMin: Int? = null, repeatCount: Int? = null) = viewModelScope.launch { reminderCtl.addExpert(task, type, offsetMin, repeatEveryMin, repeatCount) }
    fun addPlaceReminder(task: TaskEntity, placeName: String, onEnter: Boolean = true) = viewModelScope.launch { reminderCtl.addPlace(task, placeName, onEnter) }
    fun fireArrivalReminders(place: String) = viewModelScope.launch { reminderCtl.fireArrivals(place) }
    fun setReminderAnnoying(reminder: ReminderEntity, task: TaskEntity, on: Boolean) = viewModelScope.launch { reminderCtl.setAnnoying(reminder, task, on) }
    fun setReminderTier(reminder: ReminderEntity, task: TaskEntity, tier: Int) = viewModelScope.launch { reminderCtl.setTier(reminder, task, tier) }
    fun deleteReminder(reminder: ReminderEntity, task: TaskEntity) = viewModelScope.launch { reminderCtl.delete(reminder, task) }

    // ---------- settings ----------
    fun saveSettings(s: AppSettings) = viewModelScope.launch { repo.saveSettings(s) }

    /** Persist the daily-review SHARE config — the modular "what to include in my shared day card" model.
     *  One settings JSON value (no schema change); the Share dialog calls this as the user toggles sections. */
    fun saveDayShareConfig(config: com.todocompanion.app.domain.DayShareConfig) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(dayShareConfigJson = com.todocompanion.app.domain.DayShareConfigs.encode(config)))
    }

    /** Persist the period-review SHARE config — the modular "what to include in my shared week / month /
     *  year card" model. One settings JSON value (no schema change); the period Share dialog calls this. */
    fun savePeriodShareConfig(config: com.todocompanion.app.domain.PeriodShareConfig) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(periodShareConfigJson = com.todocompanion.app.domain.PeriodShareConfigs.encode(config)))
    }

    /** R58 — record a colour into the shared recent-colours list (most-recent first, deduped, capped at 12),
     * so every unified colour picker across the app remembers what you last used. */
    fun rememberRecentColor(argb: Long) = viewModelScope.launch {
        val cur = settings.value.recentColors.split(",").mapNotNull { it.trim().toLongOrNull() }
        val next = (listOf(argb) + cur).distinct().take(12)
        repo.saveSettings(settings.value.copy(recentColors = next.joinToString(",")))
    }
    /** Rename a smart list (null/blank clears the custom name → reverts to the built-in title). */
    fun setSmartListName(kindName: String, name: String?) = viewModelScope.launch {
        val s = settings.value
        val next = s.smartListNames.toMutableMap()
        if (name.isNullOrBlank()) next.remove(kindName) else next[kindName] = name.trim()
        repo.saveSettings(s.copy(smartListNames = next))
    }

    // ---------- sidebar favourites (pin to top) ----------
    fun isPinned(ref: String): Boolean = ref in settings.value.pinnedRefs
    fun togglePinnedRef(ref: String) = viewModelScope.launch {
        val cur = settings.value.pinnedRefs
        repo.saveSettings(settings.value.copy(pinnedRefs = if (ref in cur) cur - ref else cur + ref))
    }

    // ---------- sidebar section fold + visibility (persisted) ----------
    fun toggleSidebarSection(key: String) = viewModelScope.launch {
        val cur = settings.value.sidebarCollapsed
        repo.saveSettings(settings.value.copy(sidebarCollapsed = if (key in cur) cur - key else cur + key))
    }
    fun setSidebarSectionHidden(key: String, hidden: Boolean) = viewModelScope.launch {
        val cur = settings.value.sidebarHidden
        repo.saveSettings(settings.value.copy(sidebarHidden = if (hidden) cur + key else cur - key))
    }
    // ---------- T0: modular module config ----------
    fun setPrimaryModule(module: String) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(primaryModule = module, disabledModules = settings.value.disabledModules - module))
    }
    fun setModuleEnabled(module: String, on: Boolean) = viewModelScope.launch {
        val s = settings.value
        if (!on && module == s.primaryModule) return@launch   // the primary module is never disabled
        repo.saveSettings(s.copy(disabledModules = if (on) s.disabledModules - module else s.disabledModules + module))
    }
    /** First-run picker result: choose a primary and which others stay on. */
    fun applyModulePreset(primary: String, enabledOthers: Set<String>) = viewModelScope.launch {
        val disabled = com.todocompanion.app.domain.Modules.ALL.filter { it != primary && it !in enabledOthers }.toSet()
        repo.saveSettings(settings.value.copy(primaryModule = primary, disabledModules = disabled, onboardedModules = true))
    }
    fun markModulesOnboarded() = viewModelScope.launch { repo.saveSettings(settings.value.copy(onboardedModules = true)) }

    // ── Tier Ω · the only-we frontier — command palette, local Q&A, recap & annual report ─────────
    /** One immutable snapshot of the whole store for the Ω domain functions (all pure over it). */
    private fun omegaCtx(): com.todocompanion.app.domain.OmegaContext = com.todocompanion.app.domain.OmegaContext(
        tasks = tasks.value, habits = habits.value, checkins = habitCheckins.value,
        focus = focusSessions.value, timeEntries = timeVm.timeEntries.value, activities = timeVm.timeActivities.value,
        zone = zone, today = java.time.LocalDate.now(zone).toEpochDay(), now = System.currentTimeMillis(),
    )

    /** Ω2 — answer a data question across all three modules, entirely on-device. */
    fun answerQuery(question: String): com.todocompanion.app.domain.OmegaQuery.Answer =
        com.todocompanion.app.domain.OmegaQuery.answer(question, omegaCtx())

    /** Ω5 — the cross-module recap for any date range (inclusive epoch-days). Track 1.2 — now folds the
     *  day logs' felt state too, so the recap can say how the days felt vs the window before. */
    fun periodRecap(startDay: Long, endDay: Long, title: String, tasksOverride: List<TaskEntity>? = null): com.todocompanion.app.domain.PeriodRecap.Recap =
        com.todocompanion.app.domain.PeriodRecap.compute(startDay, endDay, title,
            omegaCtx().let { if (tasksOverride != null) it.copy(tasks = tasksOverride) else it }, dayLogs.value)

    // ── Track 1 (Unify) · shared felt-state / insight / year-spine accessors ────────────────────────
    /** Track 1.1 — the felt-state summary over an inclusive epoch-day window, for the achievement surfaces. */
    fun feltSummary(startDay: Long, endDay: Long): com.todocompanion.app.domain.FeltState.FeltSummary =
        com.todocompanion.app.domain.FeltState.summarize(dayLogs.value, startDay, endDay)

    /** Track 1.1 — cross-stream descriptive insights over a window (the mood dimension for The Record). */
    fun reviewInsightsFor(startDay: Long, endDay: Long): List<com.todocompanion.app.domain.ReviewInsights.Insight> =
        com.todocompanion.app.domain.ReviewInsights.compute(
            startDay, endDay, dayLogs.value,
            com.todocompanion.app.domain.DailyQuestions.parseQuestions(settings.value.dailyQuestionsJson),
            habits.value, habitCheckins.value, timeVm.timeEntries.value, timeVm.timeActivities.value,
            zone, System.currentTimeMillis())

    /** Track 1.3 — the unified year spine (felt state + achievement counts) over an inclusive window. */
    fun yearReviewed(startDay: Long, endDay: Long): com.todocompanion.app.domain.YearReviewed.Recap =
        com.todocompanion.app.domain.YearReviewed.compute(
            startDay, endDay, dayLogs.value, habits.value, habitCheckins.value,
            timeVm.timeEntries.value, timeVm.timeActivities.value, zone, System.currentTimeMillis(), tasks.value)

    /** Ω3 — adaptive hints suggesting a module the user would benefit from turning on. */
    fun moduleHints(): List<com.todocompanion.app.domain.ModuleHints.Hint> =
        com.todocompanion.app.domain.ModuleHints.compute(settings.value, tasks.value, habits.value)

    /** Ω4 — render the annual life report to a self-contained HTML file and hand it to the share sheet.
     *  Track 1 (Unify) — its numbers come from the one year spine ([yearReviewed] over the canonical
     *  [YearReviewed.calendarYearWindow]), so the HTML report agrees exactly with Wrapped and the
     *  "Year, reviewed" screen; LifeReport now only templates the recap into HTML. */
    fun shareAnnualReport(year: Int, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val (yStart, yEnd) = com.todocompanion.app.domain.YearReviewed.calendarYearWindow(year, today)
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val recap = yearReviewed(yStart, yEnd)
                val html = com.todocompanion.app.domain.LifeReport.buildHtml(year, recap)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "modular-year-$year.html").apply { writeText(html) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't build the report"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/html"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Your year in review").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appCtx.startActivity(chooser) }.onFailure { toast("No app to open it with") }
        onDone(true)
    }

    /** R29 Phase 5 — mint a proof-of-work receipt image from a finished item and hand it to the share sheet.
     *  Rendered locally with android.graphics; nothing leaves the device until the user picks where to send it. */
    fun shareReceipt(a: com.todocompanion.app.domain.done.Accomplishment, listName: String?, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = com.todocompanion.app.ui.util.ReceiptRenderer.render(a, listName, zone)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "receipt-${a.refId.take(8)}.png")
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't make the receipt"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Proof of work").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appCtx.startActivity(chooser) }.onFailure { toast("No app to share to") }
        onDone(true)
    }

    /** The whole accomplishment feed for the active workspace — the input to the integrity chain & impact graph. */
    fun doneFeed(): List<com.todocompanion.app.domain.done.Accomplishment> =
        com.todocompanion.app.domain.done.DoneRecord.build(tasks.value, habits.value, habitCheckins.value, timeVm.timeEntries.value, zone)

    /** R29 Phase 7 — seal the record: store the current hash-chain head so a later back-date or edit of a
     *  sealed entry is detectable (the recomputed head no longer matches). Entirely local. */
    fun sealRecord() = viewModelScope.launch {
        val seal = com.todocompanion.app.domain.done.Integrity.seal(doneFeed())
        saveSettings(settings.value.copy(integritySeal = seal.encode()))
        toast("Record sealed — ${seal.count} entries")
    }
    fun clearSeal() = viewModelScope.launch { saveSettings(settings.value.copy(integritySeal = null)) }

    // ── R32 · sealed letter to your future self (Living Record #7) ──────────────────────────────────
    /** Seal a note now to be revealed on [revealEpochDay]. The anchor hash over the body proves it wasn't
     *  edited after sealing, and we stamp the current accomplishment count so the reveal can show the delta. */
    fun sealLetter(title: String, body: String, revealEpochDay: Long) = viewModelScope.launch {
        val today = today()
        val hash = com.todocompanion.app.domain.done.Integrity.hash("${body}|$today")
        val note = com.todocompanion.app.data.entity.SealedNoteEntity(
            id = java.util.UUID.randomUUID().toString(), createdEpochDay = today,
            revealEpochDay = revealEpochDay.coerceAtLeast(today + 1), title = title.trim().ifBlank { "A letter to future me" },
            body = body.trim(), anchorHash = hash, sealedCount = doneFeed().size, workspaceId = activeWorkspace(),
        )
        repo.upsertSealedNote(note)
        // Track 3.4 — arm the local reveal notification (reuses the existing AlarmScheduler; no new permission).
        runCatching { com.todocompanion.app.reminders.AlarmScheduler.scheduleSealedLetter(appCtx, note.id, note.title, note.createdEpochDay, note.revealEpochDay, zone) }
        toast("Sealed — opens ${java.time.LocalDate.ofEpochDay(revealEpochDay)}")
    }
    fun acknowledgeLetter(n: com.todocompanion.app.data.entity.SealedNoteEntity) = viewModelScope.launch {
        repo.upsertSealedNote(n.copy(acknowledged = true))
    }
    fun deleteLetter(id: String) = viewModelScope.launch {
        // Track 3.4 — cancel any pending reveal notification before removing the letter.
        runCatching { com.todocompanion.app.reminders.AlarmScheduler.cancelSealedLetter(appCtx, id) }
        repo.deleteSealedNote(id)
    }

    /** Track 3.4 — the current accomplishment count for the active workspace, for the sealed-letter diff. */
    fun accomplishmentCount(): Int = doneFeed().size
    /** Whether a sealed note's body still matches its anchor hash (i.e. hasn't been tampered with). */
    fun letterIntact(n: com.todocompanion.app.data.entity.SealedNoteEntity): Boolean =
        com.todocompanion.app.domain.done.Integrity.hash("${n.body}|${n.createdEpochDay}") == n.anchorHash

    /** Frontier F2 — render a sealed-year certificate image for [year] and hand it to the share sheet. */
    fun shareYearCertificate(year: Int, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val ofYear = doneFeed().filter { java.time.LocalDate.ofEpochDay(it.epochDay).year == year }
        if (ofYear.isEmpty()) { toast("Nothing finished in $year yet"); onDone(false); return@launch }
        val stats = com.todocompanion.app.domain.done.DoneRecord.stats(ofYear)
        val head = com.todocompanion.app.domain.done.Integrity.headOf(ofYear.sortedBy { it.whenMillis })
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = com.todocompanion.app.ui.util.ReceiptRenderer.renderCertificate(year, stats, head, ofYear.size)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "certificate-$year.png")
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't make the certificate"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Certificate of work · $year").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appCtx.startActivity(chooser) }.onFailure { toast("No app to share to") }
        onDone(true)
    }

    /** R32 Living Record #6 — share a single milestone as a verifiable achievement card image. */
    fun shareMilestone(m: com.todocompanion.app.domain.done.LivingRecord.Milestone, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val feed = doneFeed()
        val head = com.todocompanion.app.domain.done.Integrity.headOf(feed.sortedBy { it.whenMillis })
        val payload = "TDCM|${m.key}|${feed.size}|$head"
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = com.todocompanion.app.ui.util.ReceiptRenderer.renderMilestoneCard(m.emoji, m.label, m.detail, payload)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "milestone-${m.key}.png")
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't make the card"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { appCtx.startActivity(android.content.Intent.createChooser(send, m.label).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("No app to share to") }
        onDone(true)
    }

    // Drag-reorder persistence for the drawer sections.
    fun setTagOrder(ids: List<String>) = viewModelScope.launch { repo.setTagOrder(ids) }
    fun setHabitOrder(ids: List<String>) = viewModelScope.launch { repo.setHabitOrder(ids); refreshHabitWidgets() }
    fun setContextOrder(ids: List<String>) = viewModelScope.launch { repo.setContextOrder(ids) }
    fun setFilterOrder(ids: List<String>) = viewModelScope.launch { repo.setFilterOrder(ids) }
    /** Persisted per-list Board/List layout choice. */
    fun isBoardList(listId: String): Boolean = listId in settings.value.boardLists
    fun setBoardList(listId: String, board: Boolean) = viewModelScope.launch {
        val cur = settings.value.boardLists
        repo.saveSettings(settings.value.copy(boardLists = if (board) cur + listId else cur - listId))
    }
    fun setSmartOrder(ids: List<String>) = viewModelScope.launch { repo.saveSettings(settings.value.copy(smartOrder = ids)) }
    fun setViewsOrder(ids: List<String>) = viewModelScope.launch { repo.saveSettings(settings.value.copy(viewsOrder = ids)) }

    // ---------- saved view tabs ----------
    val viewTabs: StateFlow<List<com.todocompanion.app.domain.view.ViewTab>> =
        settings.map { com.todocompanion.app.domain.view.ViewTabs.decode(it.viewTabsJson) }.state(emptyList())

    fun saveCurrentAsTab(name: String) = viewModelScope.launch {
        val tab = com.todocompanion.app.domain.view.ViewTab(
            id = UUID.randomUUID().toString(), name = name.trim().ifBlank { "View" },
            ref = com.todocompanion.app.domain.view.ViewTabs.refOf(currentView.value),
            group = groupMode.value.name, sort = sortMode.value.name,
            outline = outlineMode.value, hierarchy = filterHierarchy.value, zoom = outlineZoom.value,
        )
        val next = viewTabs.value + tab
        repo.saveSettings(settings.value.copy(viewTabsJson = com.todocompanion.app.domain.view.ViewTabs.encode(next)))
    }

    fun applyTab(tab: com.todocompanion.app.domain.view.ViewTab) {
        val v = com.todocompanion.app.domain.view.ViewTabs.viewOf(tab.ref) ?: return
        currentView.value = v
        groupMode.value = runCatching { GroupMode.valueOf(tab.group) }.getOrDefault(GroupMode.DATE)
        sortMode.value = runCatching { SortMode.valueOf(tab.sort) }.getOrDefault(SortMode.MANUAL)
        outlineMode.value = tab.outline
        filterHierarchy.value = tab.hierarchy
        outlineZoom.value = tab.zoom
    }

    fun deleteTab(id: String) = viewModelScope.launch {
        val next = viewTabs.value.filterNot { it.id == id }
        repo.saveSettings(settings.value.copy(viewTabsJson = com.todocompanion.app.domain.view.ViewTabs.encode(next)))
    }

    fun renameTab(id: String, name: String) = viewModelScope.launch {
        val next = viewTabs.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        repo.saveSettings(settings.value.copy(viewTabsJson = com.todocompanion.app.domain.view.ViewTabs.encode(next)))
    }

    // ---------- search ----------
    fun search(query: String): List<TaskEntity> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val q2 = q.removePrefix("#").removePrefix("@")
        val tagIds = tags.value.filter { it.name.lowercase().contains(q2) }.map { it.id }.toSet()
        val ctxIds = contexts.value.filter { it.name.lowercase().contains(q2) }.map { it.id }.toSet()
        val byTag = taskTags.value.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
        val byCtx = taskContexts.value.filter { it.contextId in ctxIds }.map { it.taskId }.toSet()
        return tasks.value.filter {
            !it.trashed && (it.title.lowercase().contains(q) || it.note.lowercase().contains(q) || it.id in byTag || it.id in byCtx)
        }
    }

    /**
     * R54 — scale-aware search. Small task sets use the instant in-memory scan (identical to [search]);
     * large histories use the FTS4 index (indexed title/note MATCH) so search stays fast into the
     * hundred-thousands. Tag/context matches always come from the in-memory join (those tables are small).
     * If FTS is unavailable it transparently falls back to the in-memory scan — search never fails.
     */
    suspend fun searchAsync(query: String): List<TaskEntity> {
        val q = query.trim(); if (q.isBlank()) return emptyList()
        val ql = q.lowercase(); val q2 = ql.removePrefix("#").removePrefix("@")
        val all = tasks.value
        val tagIds = tags.value.filter { it.name.lowercase().contains(q2) }.map { it.id }.toSet()
        val ctxIds = contexts.value.filter { it.name.lowercase().contains(q2) }.map { it.id }.toSet()
        val byTag = taskTags.value.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
        val byCtx = taskContexts.value.filter { it.contextId in ctxIds }.map { it.taskId }.toSet()
        // R56 — attachment filenames are searchable: a task matches if any of its attachments' names match.
        val byAttach = allAttachments.value.filter { it.fileName.lowercase().contains(ql) }.map { it.taskId }.toSet()
        // R56 — trashed tasks are INCLUDED so nothing is unfindable; the UI labels them and offers a
        // Trashed-only filter (the default "All" filter still hides them to keep results clean).
        fun inMemoryText() = all.filter { it.title.lowercase().contains(ql) || it.note.lowercase().contains(ql) }
        val textMatches = if (all.size <= 4000) inMemoryText() else {
            val ids = repo.searchTaskIds(q).toSet()
            if (ids.isEmpty()) inMemoryText() else {
                val byId = all.associateBy { it.id }
                ids.mapNotNull { byId[it] }
            }
        }
        val out = LinkedHashSet<TaskEntity>(textMatches)
        if (byTag.isNotEmpty() || byCtx.isNotEmpty() || byAttach.isNotEmpty())
            all.forEach { if (it.id in byTag || it.id in byCtx || it.id in byAttach) out += it }
        return out.toList()
    }

    /** Attachment file names matching the query, for the search UI's "📎 matched-file" hint (R56). */
    fun searchAttachmentNames(query: String): List<com.todocompanion.app.data.entity.AttachmentMeta> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        return allAttachments.value.filter { it.fileName.lowercase().contains(q) }
    }

    // Whole-app search now also reaches these first-class types (all already workspace-scoped flows).
    fun searchTimeActivities(query: String): List<com.todocompanion.app.data.entity.TimeActivityEntity> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        return timeVm.timeActivities.value.filter { !it.archived && it.name.lowercase().contains(q) }.sortedBy { it.name.lowercase() }
    }
    fun searchNotebooks(query: String): List<com.todocompanion.app.data.entity.NotebookEntity> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        return notebooks.value.filter { it.name.lowercase().contains(q) }.sortedBy { it.name.lowercase() }
    }
    /** Matching lists & folders as (id, name, isFolder) triples — tapping opens that list/folder view. */
    fun searchListsFolders(query: String): List<Triple<String, String, Boolean>> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        val f = folders.value.filter { it.name.lowercase().contains(q) }.map { Triple(it.id, it.name, true) }
        val l = lists.value.filter { it.id != ListEntity.INBOX_ID && it.name.lowercase().contains(q) }.map { Triple(it.id, it.name, false) }
        return f + l
    }
    fun searchGoals(query: String): List<com.todocompanion.app.domain.Goal> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        return goals().filter { !it.archived && (it.name.lowercase().contains(q) || it.note.lowercase().contains(q) ||
            it.area.lowercase().contains(q) || it.identity.lowercase().contains(q)) }
    }
    fun searchRoutines(query: String): List<com.todocompanion.app.domain.Routine> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        return routines().filter { it.name.lowercase().contains(q) || it.note.lowercase().contains(q) }
    }

    /** R57 — calendar events matching the query (title/place/notes), one row per series, newest first. */
    fun searchEvents(query: String): List<com.todocompanion.app.data.entity.EventEntity> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        return events.value.filter {
            it.recurrenceParentId == null &&
                (it.title.lowercase().contains(q) || it.location.lowercase().contains(q) || it.notes.lowercase().contains(q))
        }.sortedByDescending { it.startMillis }
    }

    /**
     * R57/R58 — occasions/countdowns matching the query. The real name of an occasion is its personName
     * ("Sara"), falling back to title ("Birthday") only when there's no person — so search must match
     * personName first (plus title, category and notes), and results sort by that display name.
     */
    fun searchOccasions(query: String): List<com.todocompanion.app.data.entity.CountdownEntity> {
        val q = query.trim().lowercase(); if (q.isBlank()) return emptyList()
        return countdowns.value.filter {
            it.personName.lowercase().contains(q) || it.title.lowercase().contains(q) ||
                it.category.lowercase().contains(q) || it.notes.lowercase().contains(q)
        }.sortedBy { it.personName.ifBlank { it.title }.lowercase() }
    }

    /**
     * E1/R56: search across all habits too — name, description/"why", identity, category, unit and now
     * the free-form notes. Archived habits are INCLUDED (the UI labels them) so a habit is never unfindable.
     */
    fun searchHabits(query: String): List<com.todocompanion.app.data.entity.HabitEntity> {
        val q = query.trim().lowercase().removePrefix("#").removePrefix("@")
        if (q.isBlank()) return emptyList()
        // Search over archived habits too (the row labels them "· archived") so a habit is never
        // unfindable — matches this function's contract, which `habits` (active-only) silently broke.
        return habitsWithArchived.value.filter { h ->
            h.name.lowercase().contains(q) || h.description.lowercase().contains(q) ||
                h.identity.lowercase().contains(q) || h.category.lowercase().contains(q) ||
                h.notes.lowercase().contains(q) || (h.unit?.lowercase()?.contains(q) == true)
        }.sortedBy { it.sortOrder }
    }

    // ---------- export / import ----------
    // R75 — the file I/O lives in a standalone, unit-testable BackupExporter (context + repo + zone,
    // no UI state). These wrappers keep only the threading and the UI glue (settings stamp, widget
    // refreshes, user-facing messages), so behaviour is unchanged.
    private val backup by lazy { com.todocompanion.app.data.backup.BackupExporter(appCtx, repo) { zone } }

    fun exportTo(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch { onDone(backup.exportJson(uri)) }

    // Wave O — device-independent passphrase-encrypted backup (PBKDF2 + AES-GCM). Restores on any device
    // from the passphrase alone, unlike the Keystore-bound SQLCipher DB. Fully local — no network.
    fun exportEncryptedBackup(uri: Uri, passphrase: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val jsonStr = repo.exportJson()
                val blob = com.todocompanion.app.util.PortableCrypto.encrypt(jsonStr, passphrase.toCharArray())
                appCtx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob.toByteArray()) }
                true
            }.getOrDefault(false)
        }
        onDone(ok)
    }
    fun importEncryptedBackup(uri: Uri, passphrase: String, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) { runCatching { readImportTextBounded(uri) }.getOrNull() }
        if (text == null) { onDone(false, "Couldn't read the file"); return@launch }
        val plain = com.todocompanion.app.util.PortableCrypto.decrypt(text, passphrase.toCharArray())
        if (plain == null) { onDone(false, "Wrong passphrase or damaged backup"); return@launch }
        withContext(Dispatchers.IO) { repo.importJsonMerge(plain) }
        onDone(true, "Restored from encrypted backup")
    }
    fun exportMarkdownTo(uri: Uri, includeCompleted: Boolean, onDone: (Boolean) -> Unit) = viewModelScope.launch { onDone(backup.exportMarkdown(uri, includeCompleted)) }
    fun exportCsvTo(uri: Uri, includeCompleted: Boolean, onDone: (Boolean) -> Unit) = viewModelScope.launch { onDone(backup.exportCsv(uri, includeCompleted)) }
    fun exportIcsTo(uri: Uri, includeCompleted: Boolean, onDone: (Boolean) -> Unit) = viewModelScope.launch { onDone(backup.exportIcs(uri, includeCompleted)) }
    fun exportHabitsCsvTo(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch { onDone(backup.exportHabitsCsv(uri)) }

    /**
     * SAF-free export fallback: write the chosen export straight into the public Downloads folder
     * (or the app's files dir on older devices). Used when the device has no system document picker.
     * [onDone] receives a user-facing location like "Downloads/todo-companion-backup.json", or null.
     */
    fun exportToDownloads(kind: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        val loc = backup.downloadExport(kind)
        // U10: a successful full backup stamps the "last backup" time the Momentum data-safety card reads.
        if (kind == "json" && loc != null) repo.saveSettings(settings.value.copy(lastBackupAt = System.currentTimeMillis(), lastSyncAt = System.currentTimeMillis()))
        onDone(loc)
    }
    fun importHabitsCsv(uri: Uri, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val n = backup.importHabitsCsv(uri)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
        when { n < 0 -> onDone(false, "Couldn't read that CSV — export from Loop, or our habit CSV"); n == 0 -> onDone(false, "No check-ins found in that file"); else -> onDone(true, "Imported $n habit check-ins") }
    }

    // ── CU3 · import an .ics calendar into tasks (the other half of the 2-way bridge) ──────────────
    fun importIcs(uri: Uri, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val n = backup.importIcsAsTasks(uri)
        when { n < 0 -> onDone(false, "Couldn't read that file"); n == 0 -> onDone(false, "No events found in that .ics"); else -> onDone(true, "Imported $n event${if (n == 1) "" else "s"} as tasks") }
    }

    // ── CU4 · one-tap handoff — share a full copy through the system share sheet (0 permission) ────
    fun shareBackupCopy(onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val json = repo.exportJson()
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "modular-backup-${java.time.LocalDate.now(zone)}.json").apply { writeText(json) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't prepare the copy"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Send a copy to another device").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appCtx.startActivity(chooser) }.onFailure { toast("No app to share with") }
        onDone(true)
    }

    // ── CU5 · accountability snapshot — share a goal's progress as an image card (0 permission) ────
    fun shareGoalSnapshot(g: com.todocompanion.app.domain.Goal, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val h = goalHealth(g)
        val stats = buildList {
            add("progress" to "${(h.overall * 100).toInt()}%")
            if (g.hasTasks) add("tasks" to "${h.taskDone}/${h.taskTotal}")
            if (g.hasHabit) add("streak" to "${h.habitStreak}d")
            if (g.hasBudget) add("time" to "${"%.1f".format(h.minutesTracked / 60.0)}h/${h.budgetMin / 60}h")
        }.take(4)
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.renderStatsCard("${g.emoji} ${g.name}", "Goal progress", stats)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "modular-goal.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }

    // ---------- Tier D: folder backup & account-free sync ----------
    // R84 — sync + every restore/import path lives in data.backup/RestoreManager (the most data-sensitive
    // corner: a restore overwrites the whole store). The VM keeps the trivial settings setters below and
    // thin viewModelScope wrappers; behaviour is identical.
    private val restore by lazy {
        com.todocompanion.app.data.backup.RestoreManager(
            appCtx, repo,
            settings = { settings.value },
            saveSettings = { repo.saveSettings(it) },
            listsSnapshot = { lists.value },
            displayNameOf = { displayNameOf(it) },
        )
    }
    fun setSyncFolder(uri: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(syncFolder = uri, syncEnabled = uri.isNotBlank())) }
    /** Re-arm (or cancel) the auto-backup alarm from the latest settings, so enabling/retiming takes
     *  effect immediately rather than waiting for the next app launch or boot. */
    private fun rearmAutoBackup(s: com.todocompanion.app.domain.AppSettings) {
        if (s.autoBackupEnabled && s.autoBackupFolder.ifBlank { s.syncFolder }.isNotBlank())
            com.todocompanion.app.reminders.AlarmScheduler.scheduleAutoBackup(appCtx, s.autoBackupHour, s.autoBackupIntervalDays, s.lastBackupAt, s.autoBackupDow, s.autoBackupDom)
        else com.todocompanion.app.reminders.AlarmScheduler.cancelAutoBackup(appCtx)
    }
    fun setAutoBackupFolder(uri: String) = viewModelScope.launch {
        val s = settings.value.copy(autoBackupFolder = uri, autoBackupEnabled = uri.isNotBlank()); repo.saveSettings(s); rearmAutoBackup(s)
    }
    fun setAutoBackupEnabled(on: Boolean) = viewModelScope.launch {
        val s = settings.value.copy(autoBackupEnabled = on); repo.saveSettings(s); rearmAutoBackup(s)
    }
    /** How often the automatic backup runs (days: 1 daily · 7 weekly · 30 monthly). */
    fun setAutoBackupInterval(days: Int) = viewModelScope.launch {
        val s = settings.value.copy(autoBackupIntervalDays = days.coerceIn(1, 30)); repo.saveSettings(s); rearmAutoBackup(s)
    }
    /** The hour of day (0–23) the automatic backup fires. */
    fun setAutoBackupHour(hour: Int) = viewModelScope.launch {
        val s = settings.value.copy(autoBackupHour = hour.coerceIn(0, 23)); repo.saveSettings(s); rearmAutoBackup(s)
    }
    /** For weekly backups: which ISO weekday to run on (1 = Mon … 7 = Sun). */
    fun setAutoBackupDow(dow: Int) = viewModelScope.launch {
        val s = settings.value.copy(autoBackupDow = dow.coerceIn(0, 7)); repo.saveSettings(s); rearmAutoBackup(s)
    }
    /** For monthly backups: which day-of-month to run on (1–31, clamped to the month's length). */
    fun setAutoBackupDom(dom: Int) = viewModelScope.launch {
        val s = settings.value.copy(autoBackupDom = dom.coerceIn(1, 31)); repo.saveSettings(s); rearmAutoBackup(s)
    }
    /** Toggle a smart-list helper card between folded (default) and expanded; the choice persists. */
    fun toggleSmartCard(key: String) = viewModelScope.launch {
        val cur = settings.value.smartCardsExpanded
        repo.saveSettings(settings.value.copy(smartCardsExpanded = if (key in cur) cur - key else cur + key))
    }
    fun setSyncEnabled(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(syncEnabled = on)) }
    fun markOnboarded() = viewModelScope.launch { repo.saveSettings(settings.value.copy(onboarded = true)) }
    fun replayOnboarding() = viewModelScope.launch { repo.saveSettings(settings.value.copy(onboarded = false)) }
    fun setSyncPassphrase(pass: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(syncPassphrase = pass)) }

    fun runSyncNow(onDone: (Boolean, String) -> Unit) = viewModelScope.launch { restore.runSyncNow(onDone) }
    fun runBackupNow(onDone: (Boolean) -> Unit) = viewModelScope.launch { restore.runBackupNow(onDone) }
    /** Import tasks from a Todoist/TickTick CSV or MLO OPML/.mlobak file. Returns (ok, message). */
    fun importExternal(uri: Uri, onDone: (Boolean, String) -> Unit) = viewModelScope.launch { restore.importExternal(uri, onDone) }
    fun loadSavedBackups(broad: Boolean = false, onDone: (List<com.todocompanion.app.util.FileExport.SavedFile>) -> Unit) = viewModelScope.launch { onDone(restore.loadSavedBackups(broad)) }
    fun importInboxHint(): String = restore.importInboxHint()
    fun importPastedText(text: String, onDone: (Boolean, String) -> Unit) = viewModelScope.launch { restore.importPastedText(text, onDone) }
    fun restoreSaved(s: com.todocompanion.app.util.FileExport.SavedFile, onDone: (Boolean, String) -> Unit) = viewModelScope.launch { restore.restoreSaved(s, onDone) }
    fun importFromIntent(uri: Uri, merge: Boolean = false, onDone: (Boolean, String) -> Unit) = viewModelScope.launch { restore.importFromIntent(uri, merge, onDone) }
    fun importFrom(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch { restore.importFrom(uri, onDone) }

    private fun buildOutline(all: List<TaskEntity>, startId: String? = null): List<OutlineRow> {
        val byParent = all.groupBy { it.parentId }
        val out = ArrayList<OutlineRow>(all.size)
        fun dfs(parentId: String?, depth: Int) {
            byParent[parentId]?.sortedBy { it.sortOrder }?.forEach { t ->
                val kids = byParent[t.id].orEmpty()
                out.add(OutlineRow(t, depth, kids.isNotEmpty(), t.collapsed))
                if (!t.collapsed) dfs(t.id, depth + 1)
            }
        }
        if (startId != null) {
            val root = all.firstOrNull { it.id == startId } ?: return emptyList()
            val kids = byParent[startId].orEmpty()
            out.add(OutlineRow(root, 0, kids.isNotEmpty(), root.collapsed))
            if (!root.collapsed) dfs(startId, 1)
        } else dfs(null, 0)
        return out
    }

    /** Grow an id set to include every descendant of its members (for "include subtasks" filters). */
    private fun expandWithDescendants(ids: Set<String>, all: List<TaskEntity>): Set<String> =
        ListPipeline.expandWithDescendants(ids, all)

    /** Build an outline of the [matched] tasks plus every ancestor needed to place them in the tree.
     *  Ancestors that aren't themselves matches are flagged (rendered dimmed). Ignores collapse. */
    private fun buildFilteredOutline(all: List<TaskEntity>, matched: Set<String>): List<OutlineRow> {
        if (matched.isEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        val included = HashSet<String>()
        matched.forEach { id ->
            var cur: String? = id
            while (cur != null && cur !in included && cur in byId) { included.add(cur); cur = byId[cur]?.parentId }
        }
        val inc = all.filter { it.id in included }
        val byParent = inc.groupBy { it.parentId }
        val out = ArrayList<OutlineRow>(inc.size)
        fun dfs(t: TaskEntity, depth: Int) {
            val kids = byParent[t.id].orEmpty()
            out.add(OutlineRow(t, depth, kids.isNotEmpty(), collapsed = false, matched = t.id in matched))
            kids.sortedBy { it.sortOrder }.forEach { dfs(it, depth + 1) }
        }
        // Roots = included tasks whose parent isn't part of this filtered forest.
        inc.filter { it.parentId == null || it.parentId !in included }
            .sortedWith(compareBy({ it.listId }, { it.sortOrder }))
            .forEach { dfs(it, 0) }
        return out
    }
}
