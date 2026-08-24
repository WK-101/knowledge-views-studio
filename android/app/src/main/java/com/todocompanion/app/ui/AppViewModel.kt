package com.todocompanion.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.todocompanion.app.App
import com.todocompanion.app.data.entity.ChecklistItemEntity
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.nlp.QuickAddParser
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.domain.view.GroupMode
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.SortMode
import com.todocompanion.app.domain.view.TaskGroup
import com.todocompanion.app.domain.view.TaskViews
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.reminders.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

/** A flattened, indented outline row. */
data class OutlineRow(val task: TaskEntity, val depth: Int, val hasChildren: Boolean, val collapsed: Boolean)

/** Options captured by the quick-add option toolbar; override anything parsed from text. */
data class QuickAddOptions(
    val dueMillis: Long? = null,
    val hasTime: Boolean = false,
    val priority: PriorityLevel? = null,
    val listId: String? = null,
    val tagIds: List<String> = emptyList(),
    val reminderMillis: Long? = null,
)

enum class UndoKind { COMPLETED, ABANDONED, TRASHED }
data class UndoEvent(val kind: UndoKind, val taskId: String, val message: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx get() = getApplication<App>()
    private val repo get() = appCtx.repository

    /** One-shot events for the "Undo" snackbar after a completion / won't-do / trash. */
    val undoEvents = kotlinx.coroutines.flow.MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val settings: StateFlow<AppSettings> =
        repo.allSettings.map { AppSettings.fromMap(it.associate { s -> s.key to s.value }) }.state(AppSettings())

    // ---------- workspaces ----------
    val workspaces = repo.allWorkspaces.state(emptyList())
    private val activeWs: Flow<String> = settings.map { it.activeWorkspaceId }
    /** The single isolation choke point: list ids belonging to the active workspace (+ the shared Inbox). */
    private val activeListIds: Flow<Set<String>> =
        combine(repo.allLists, activeWs) { all, ws -> all.filter { it.workspaceId == ws }.map { it.id }.toSet() + ListEntity.INBOX_ID }
    private val wsTasks: Flow<List<TaskEntity>> =
        combine(repo.allTasks, activeListIds) { all, ids -> all.filter { it.listId in ids } }

    val tasks: StateFlow<List<TaskEntity>> = wsTasks.state(emptyList())
    val folders = combine(repo.allFolders, activeWs) { f, ws -> f.filter { it.workspaceId == ws } }.state(emptyList())
    val lists = combine(repo.allLists, activeWs) { l, ws -> l.filter { it.workspaceId == ws || it.id == ListEntity.INBOX_ID } }.state(emptyList())
    val tags: StateFlow<List<TagEntity>> = repo.allTags.state(emptyList())
    val contexts: StateFlow<List<ContextEntity>> = repo.allContexts.state(emptyList())
    val filters = combine(repo.allFilters, activeWs) { f, ws -> f.filter { it.workspaceId == ws } }.state(emptyList())
    val habits = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && !it.archived } }.state(emptyList())
    val habitCheckins = repo.allCheckins.state(emptyList())
    val focusSessions = repo.allFocusSessions.state(emptyList())
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

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Live task count per smart list, for the drawer. */
    val smartCounts: StateFlow<Map<SmartKind, Int>> =
        combine(wsTasks, currentView) { t, _ ->
            SmartKind.entries.associateWith { TaskViews.filterSmart(t, it, System.currentTimeMillis(), zone).size }
        }.state(emptyMap())

    private data class Cfg(val view: ViewRef, val group: GroupMode, val sort: SortMode, val prio: PriorityEngine.Config)

    private fun AppSettings.priorityConfig() = PriorityEngine.Config(
        mode = when (priorityMode) { "importance" -> PriorityEngine.Mode.IMPORTANCE; "urgency" -> PriorityEngine.Mode.URGENCY; else -> PriorityEngine.Mode.BOTH },
        dueWeight = priorityDueWeight, startWeight = priorityStartWeight, goalWeight = priorityGoalWeight, overdueBoost = priorityOverdueBoost,
    )

    val groups: StateFlow<List<TaskGroup>> =
        combine(
            wsTasks,
            combine(currentView, groupMode, sortMode, settings) { v, g, s, set -> Cfg(v, g, s, set.priorityConfig()) },
            repo.taskTagRefs,
            combine(repo.taskContextRefs, repo.allContexts, repo.allFilters) { r, c, f -> Triple(r, c, f) },
            repo.allDependencies,
        ) { all, cfg, ttRefs, tcInfo, deps ->
            val (tcRefs, ctxEntities, filterList) = tcInfo
            val now = System.currentTimeMillis()
            val filtered = when (val v = cfg.view) {
                is ViewRef.Smart -> {
                    val base = TaskViews.filterSmart(all, v.kind, now, zone)
                    if (v.kind == SmartKind.DO_NEXT) rankDoNext(base, all, now, cfg.prio, deps, tcRefs, ctxEntities) else base
                }
                is ViewRef.FilterView -> {
                    val q = com.todocompanion.app.domain.view.Filters.parse(filterList.firstOrNull { it.id == v.filterId }?.queryJson)
                    val tagsByTask = ttRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
                    val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
                    all.filter { com.todocompanion.app.domain.view.Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, zone) }
                }
                is ViewRef.ListView -> all.filter { !it.trashed && !it.completed && !it.abandoned && it.listId == v.listId }
                is ViewRef.TagView -> {
                    val ids = ttRefs.filter { it.tagId == v.tagId }.map { it.taskId }.toSet()
                    all.filter { it.id in ids && !it.trashed && !it.completed && !it.abandoned }
                }
                is ViewRef.ContextView -> {
                    val ids = tcRefs.filter { it.contextId == v.contextId }.map { it.taskId }.toSet()
                    all.filter { it.id in ids && !it.trashed && !it.completed && !it.abandoned }
                }
            }
            val sorted = if ((cfg.view as? ViewRef.Smart)?.kind == SmartKind.DO_NEXT) filtered
            else TaskViews.sort(filtered, cfg.sort)
            TaskViews.group(sorted, if ((cfg.view as? ViewRef.Smart)?.kind == SmartKind.DO_NEXT) GroupMode.NONE else cfg.group, now, zone)
        }.state(emptyList())

    val outlineRows: StateFlow<List<OutlineRow>> =
        combine(wsTasks, currentView) { all, v ->
            val listId = (v as? ViewRef.ListView)?.listId ?: return@combine emptyList()
            buildOutline(all.filter { it.listId == listId && !it.trashed })
        }.state(emptyList())

    private fun rankDoNext(
        base: List<TaskEntity>, all: List<TaskEntity>, now: Long, cfg: PriorityEngine.Config,
        deps: List<DependencyEntity>, tcRefs: List<com.todocompanion.app.data.entity.TaskContextCrossRef>, ctxs: List<ContextEntity>,
    ): List<TaskEntity> {
        val byParent = all.groupBy { it.parentId }
        val blocked = PriorityEngine.computeBlocked(deps, all.associateBy { it.id })
        // Context availability (open-hours), evaluated once for now.
        val dt = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val dow = dt.dayOfWeek.value; val minute = dt.hour * 60 + dt.minute
        val availById = ctxs.associate { it.id to com.todocompanion.app.domain.context.ContextAvailability.isAvailable(it, dow, minute) }
        val ctxByTask = tcRefs.groupBy { it.taskId }
        return PriorityEngine.doNext(
            all = base,
            now = now,
            blocked = blocked,
            hasIncompleteChild = { id -> byParent[id].orEmpty().any { !it.completed && !it.trashed && !it.abandoned } },
            contextAvailable = { id ->
                val ids = ctxByTask[id].orEmpty().map { it.contextId }
                ids.isEmpty() || ids.any { availById[it] == true }
            },
            cfg = cfg,
        ).map { it.task }
    }

    fun observeTask(id: String): Flow<TaskEntity?> = repo.observeTask(id)

    // ---------- navigation ----------
    fun select(view: ViewRef) {
        currentView.value = view
        groupMode.value = if (view is ViewRef.ListView) GroupMode.NONE else GroupMode.DATE
        outlineMode.value = false
    }

    fun currentTitle(): String = when (val v = currentView.value) {
        is ViewRef.Smart -> v.kind.title
        is ViewRef.ListView -> lists.value.firstOrNull { it.id == v.listId }?.name ?: "List"
        is ViewRef.TagView -> "#" + (tags.value.firstOrNull { it.id == v.tagId }?.name ?: "tag")
        is ViewRef.ContextView -> "@" + (contexts.value.firstOrNull { it.id == v.contextId }?.name ?: "context")
        is ViewRef.FilterView -> filters.value.firstOrNull { it.id == v.filterId }?.name ?: "Filter"
    }

    fun canOutline(): Boolean = currentView.value is ViewRef.ListView

    private fun targetListForAdd(): String =
        (currentView.value as? ViewRef.ListView)?.listId ?: ListEntity.INBOX_ID

    // ---------- quick add ----------
    fun submitQuickAdd(text: String, opts: QuickAddOptions) = viewModelScope.launch {
        val parsed = QuickAddParser.parse(text)
        if (parsed.title.isBlank() && parsed.tags.isEmpty()) return@launch
        val due = opts.dueMillis ?: parsed.dateTime?.atZone(zone)?.toInstant()?.toEpochMilli()
        val level = opts.priority ?: parsed.priority
        val imp = level?.importance ?: 3
        val urg = level?.urgency ?: 3
        // ~list resolves to an existing list by name (case-insensitive); otherwise fall back.
        val listId = opts.listId
            ?: parsed.list?.let { name -> lists.value.firstOrNull { !it.archived && it.name.equals(name, ignoreCase = true) }?.id }
            ?: targetListForAdd()
        val id = repo.createTask(listId, parsed.title.ifBlank { "Untitled" }, importance = imp, urgency = urg, dueDate = due)

        val tagIds = opts.tagIds.toMutableList()
        if (parsed.tags.isNotEmpty()) {
            val existing = repo.getTagsOnce().associateBy { it.name.lowercase() }
            parsed.tags.forEach { name ->
                tagIds += existing[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertTag(TagEntity(it, name)) }
            }
        }
        if (tagIds.isNotEmpty()) repo.setTaskTags(id, tagIds.distinct())

        // @contexts resolve to existing contexts by name, creating any that are new.
        if (parsed.contexts.isNotEmpty()) {
            val existingCtx = repo.getContextsOnce().associateBy { it.name.lowercase() }
            val ctxIds = parsed.contexts.map { name ->
                existingCtx[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertContext(ContextEntity(id = it, name = name)) }
            }
            repo.setTaskContexts(id, ctxIds.distinct())
        }

        val reminderAt = opts.reminderMillis ?: (if (parsed.hasTime && due != null) due else null)
        if (reminderAt != null) {
            val r = ReminderEntity(UUID.randomUUID().toString(), taskId = id, type = "absolute", atTime = reminderAt)
            repo.upsertReminder(r)
            repo.getTask(id)?.let { AlarmScheduler.schedule(appCtx, r, it) }
        }
    }

    // ---------- task actions ----------
    fun addTask(listId: String, parentId: String? = null, title: String = "New task") =
        viewModelScope.launch { repo.createTask(listId, title, parentId = parentId) }
    fun toggleComplete(t: TaskEntity) = viewModelScope.launch {
        // Completing a repeating task rolls it forward to the next occurrence instead of closing it
        // — unless its recurrence has ended (until-date reached or count exhausted).
        val (nextDue, newRule) = if (!t.completed && !t.rrule.isNullOrBlank() && t.dueDate != null)
            com.todocompanion.app.domain.recurrence.Recurrence.advance(t.rrule!!, t.dueDate!!, zone) else null to null
        if (nextDue != null) {
            val delta = nextDue - t.dueDate!!
            repo.saveTask(t.copy(dueDate = nextDue, startDate = t.startDate?.plus(delta), rrule = newRule, completed = false, completedAt = null))
            val updated = repo.getTask(t.id)
            reminders.value.filter { it.taskId == t.id && it.atTime != null }.forEach { r ->
                val nr = r.copy(atTime = r.atTime!! + delta)
                repo.upsertReminder(nr)
                updated?.let { AlarmScheduler.schedule(appCtx, nr, it) }
            }
        } else {
            repo.setCompleted(t, !t.completed)
            if (!t.completed) undoEvents.tryEmit(UndoEvent(UndoKind.COMPLETED, t.id, "Completed “${t.title.take(30)}”"))
        }
    }
    fun setAbandoned(t: TaskEntity, v: Boolean) = viewModelScope.launch {
        repo.setAbandoned(t, v)
        if (v) undoEvents.tryEmit(UndoEvent(UndoKind.ABANDONED, t.id, "Marked won't do"))
    }
    fun toggleCollapsed(t: TaskEntity) = viewModelScope.launch { repo.setCollapsed(t, !t.collapsed) }
    fun trash(t: TaskEntity) = viewModelScope.launch {
        repo.setTrashed(t.id, true)
        undoEvents.tryEmit(UndoEvent(UndoKind.TRASHED, t.id, "Moved to Trash"))
    }
    fun undo(e: UndoEvent) = viewModelScope.launch {
        when (e.kind) {
            UndoKind.COMPLETED -> repo.getTask(e.taskId)?.let { repo.setCompleted(it, false) }
            UndoKind.ABANDONED -> repo.getTask(e.taskId)?.let { repo.setAbandoned(it, false) }
            UndoKind.TRASHED -> repo.setTrashed(e.taskId, false)
        }
    }
    fun restore(t: TaskEntity) = viewModelScope.launch { repo.setTrashed(t.id, false) }
    fun deleteForever(t: TaskEntity) = viewModelScope.launch { repo.deleteSubtree(t.id) }
    fun emptyTrash() = viewModelScope.launch { repo.emptyTrash() }
    fun indent(t: TaskEntity) = viewModelScope.launch { repo.indent(t) }
    fun outdent(t: TaskEntity) = viewModelScope.launch { repo.outdent(t) }
    fun moveUp(t: TaskEntity) = viewModelScope.launch { repo.moveUp(t) }
    fun moveDown(t: TaskEntity) = viewModelScope.launch { repo.moveDown(t) }
    fun moveToList(t: TaskEntity, listId: String) = viewModelScope.launch { repo.moveToList(t.id, listId) }
    fun save(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t) }
    /** Persist a manual drag reorder by writing each id's index as its sortOrder. */
    fun setManualOrder(orderedIds: List<String>) = viewModelScope.launch {
        val byId = tasks.value.associateBy { it.id }
        orderedIds.forEachIndexed { i, id ->
            byId[id]?.let { if (it.sortOrder != i.toDouble()) repo.saveTask(it.copy(sortOrder = i.toDouble())) }
        }
    }

    // ---------- checklist ----------
    fun checklistFor(taskId: String) = checklist.value.filter { it.taskId == taskId }.sortedBy { it.sortOrder }
    fun addChecklistItem(taskId: String, text: String) = viewModelScope.launch { repo.addChecklistItem(taskId, text) }
    fun toggleChecklist(item: ChecklistItemEntity) = viewModelScope.launch { repo.saveChecklistItem(item.copy(checked = !item.checked)) }
    fun deleteChecklistItem(id: String) = viewModelScope.launch { repo.deleteChecklistItem(id) }

    // ---------- folders / lists ----------
    fun createFolder(name: String, parentId: String? = null) = viewModelScope.launch { repo.createFolder(name, parentId, settings.value.activeWorkspaceId) }
    fun renameFolder(f: FolderEntity, name: String) = viewModelScope.launch { repo.saveFolder(f.copy(name = name)) }
    fun setFolderIcon(f: FolderEntity, icon: String?) = viewModelScope.launch { repo.saveFolder(f.copy(icon = icon)) }
    fun toggleFolder(f: FolderEntity) = viewModelScope.launch { repo.saveFolder(f.copy(collapsed = !f.collapsed)) }
    fun deleteFolder(id: String) = viewModelScope.launch { repo.deleteFolder(id) }
    fun createList(name: String, folderId: String?, colorArgb: Long?) = viewModelScope.launch { repo.createList(name, folderId, colorArgb, workspaceId = settings.value.activeWorkspaceId) }

    // ---------- workspaces ----------
    fun createWorkspace(name: String) = viewModelScope.launch {
        val id = repo.createWorkspace(name.trim())
        repo.saveSettings(settings.value.copy(activeWorkspaceId = id))
        currentView.value = ViewRef.Smart(SmartKind.TODAY)
    }
    fun switchWorkspace(id: String) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(activeWorkspaceId = id))
        currentView.value = ViewRef.Smart(SmartKind.TODAY)
    }
    fun renameWorkspace(w: com.todocompanion.app.data.entity.WorkspaceEntity, name: String) = viewModelScope.launch { repo.upsertWorkspace(w.copy(name = name.trim())) }
    fun deleteWorkspace(id: String) = viewModelScope.launch {
        repo.deleteWorkspace(id)
        if (settings.value.activeWorkspaceId == id) repo.saveSettings(settings.value.copy(activeWorkspaceId = com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID))
    }

    // ---------- habits ----------
    fun createHabit(name: String, emoji: String?, colorArgb: Long?, target: Int) = viewModelScope.launch {
        repo.createHabit(name.trim(), emoji, colorArgb, target, settings.value.activeWorkspaceId)
    }
    fun saveHabit(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch { repo.upsertHabit(h) }
    fun deleteHabit(id: String) = viewModelScope.launch { repo.deleteHabit(id) }
    fun cycleHabit(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, current: Int) = viewModelScope.launch {
        repo.cycleCheckin(h.id, epochDay, h.targetPerDay, current)
    }

    // ---------- focus ----------
    fun recordFocus(startMillis: Long, minutes: Int, kind: String) = viewModelScope.launch {
        if (minutes <= 0) return@launch
        val day = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate().toEpochDay()
        repo.addFocusSession(day, startMillis, minutes, kind)
    }

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
    fun setPriority(t: TaskEntity, level: PriorityLevel) = viewModelScope.launch { repo.saveTask(t.copy(importance = level.importance, urgency = level.urgency)) }
    fun togglePin(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(pinned = !t.pinned)) }
    fun toggleNote(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(isNote = !t.isNote)) }
    fun duplicateTask(t: TaskEntity) = viewModelScope.launch {
        val nowMs = System.currentTimeMillis()
        repo.saveTask(t.copy(id = UUID.randomUUID().toString(), title = t.title + " (copy)", completed = false, completedAt = null,
            abandoned = false, trashed = false, sortOrder = t.sortOrder + 0.0001, createdAt = nowMs, updatedAt = nowMs))
    }
    fun cycleFlag(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(flagColorArgb = com.todocompanion.app.ui.components.nextFlagColor(t.flagColorArgb))) }
    fun setFlag(t: TaskEntity, argb: Long?) = viewModelScope.launch { repo.saveTask(t.copy(flagColorArgb = argb)) }
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
            com.todocompanion.app.domain.SwipeAction.EDIT -> return false
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
    fun createTag(name: String, parentId: String? = null) = viewModelScope.launch { repo.upsertTag(TagEntity(UUID.randomUUID().toString(), name.trim(), parentId = parentId)) }
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
    fun createContext(name: String, parentId: String? = null) = viewModelScope.launch { repo.upsertContext(ContextEntity(id = UUID.randomUUID().toString(), name = name.trim(), parentId = parentId)) }
    fun renameContext(c: ContextEntity, name: String) = viewModelScope.launch { repo.upsertContext(c.copy(name = name.trim())) }
    fun setContextColor(c: ContextEntity, argb: Long?) = viewModelScope.launch { repo.upsertContext(c.copy(colorArgb = argb)) }
    fun setContextActive(c: ContextEntity, active: Boolean) = viewModelScope.launch { repo.upsertContext(c.copy(active = active)) }
    fun setContextHours(c: ContextEntity, json: String?) = viewModelScope.launch { repo.upsertContext(c.copy(openHoursJson = json)) }
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

    // ---------- reminders ----------
    fun addAbsoluteReminder(task: TaskEntity, atMillis: Long) = viewModelScope.launch {
        val r = ReminderEntity(UUID.randomUUID().toString(), taskId = task.id, type = "absolute", atTime = atMillis)
        repo.upsertReminder(r)
        AlarmScheduler.schedule(appCtx, r, task)
    }
    fun deleteReminder(reminder: ReminderEntity, task: TaskEntity) = viewModelScope.launch {
        repo.deleteReminder(reminder.id)
        AlarmScheduler.cancel(appCtx, reminder, task)
    }

    // ---------- settings ----------
    fun saveSettings(s: AppSettings) = viewModelScope.launch { repo.saveSettings(s) }

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

    // ---------- export / import ----------
    fun exportTo(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val json = repo.exportJson()
            appCtx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }.isSuccess
        onDone(ok)
    }
    fun importFrom(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val text = appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching
            repo.importJsonReplace(text)
            AlarmScheduler.rescheduleAll(appCtx, repo)
        }.isSuccess
        onDone(ok)
    }

    private fun buildOutline(all: List<TaskEntity>): List<OutlineRow> {
        val byParent = all.groupBy { it.parentId }
        val out = ArrayList<OutlineRow>(all.size)
        fun dfs(parentId: String?, depth: Int) {
            byParent[parentId]?.sortedBy { it.sortOrder }?.forEach { t ->
                val kids = byParent[t.id].orEmpty()
                out.add(OutlineRow(t, depth, kids.isNotEmpty(), t.collapsed))
                if (!t.collapsed) dfs(t.id, depth + 1)
            }
        }
        dfs(null, 0)
        return out
    }
}
