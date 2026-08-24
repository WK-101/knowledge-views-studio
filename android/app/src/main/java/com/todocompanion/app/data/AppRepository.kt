package com.todocompanion.app.data

import com.todocompanion.app.data.entity.ChecklistItemEntity
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.SettingEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.data.entity.WorkspaceEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.port.Backup
import com.todocompanion.app.domain.port.BackupFile
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Single source of truth over Room. Reads are reactive Flows; writes are suspend. */
class AppRepository(private val db: AppDatabase) {

    private val tasks = db.taskDao()
    private val folders = db.folderDao()
    private val lists = db.listDao()
    private val checklist = db.checklistDao()
    private val tags = db.tagDao()
    private val contexts = db.contextDao()
    private val reminders = db.reminderDao()
    private val deps = db.dependencyDao()
    private val settings = db.settingDao()

    // ----- reactive reads -----
    val allTasks: Flow<List<TaskEntity>> = tasks.observeAll()
    val allFolders: Flow<List<FolderEntity>> = folders.observeAll()
    val allLists: Flow<List<ListEntity>> = lists.observeAll()
    val allChecklist: Flow<List<ChecklistItemEntity>> = checklist.observeAll()
    val allTags: Flow<List<TagEntity>> = tags.observeAll()
    val allContexts: Flow<List<ContextEntity>> = contexts.observeAll()
    val taskTagRefs: Flow<List<TaskTagCrossRef>> = tags.observeCrossRefs()
    val taskContextRefs: Flow<List<TaskContextCrossRef>> = contexts.observeCrossRefs()
    val allReminders: Flow<List<ReminderEntity>> = reminders.observeAll()
    val allDependencies: Flow<List<DependencyEntity>> = deps.observeAll()
    val allSettings: Flow<List<SettingEntity>> = settings.observeAll()

    fun observeTask(id: String): Flow<TaskEntity?> = tasks.observeById(id)
    suspend fun getTask(id: String): TaskEntity? = tasks.getById(id)
    suspend fun allTasksOnce(): List<TaskEntity> = tasks.getAll()
    suspend fun setCompletedById(id: String, completed: Boolean) {
        tasks.getById(id)?.let { setCompleted(it, completed) }
    }

    private fun now() = System.currentTimeMillis()
    private fun uid() = UUID.randomUUID().toString()

    // ============ tasks ============
    suspend fun createTask(
        listId: String,
        title: String,
        parentId: String? = null,
        importance: Int = 3,
        urgency: Int = 3,
        dueDate: Long? = null,
        startDate: Long? = null,
    ): String {
        val id = uid()
        val order = tasks.maxSortOrder(listId, parentId) + 1.0
        tasks.upsert(
            TaskEntity(
                id = id,
                listId = listId,
                parentId = parentId,
                sortOrder = order,
                title = title.ifBlank { "Untitled" },
                importance = importance,
                urgency = urgency,
                dueDate = dueDate,
                startDate = startDate,
                createdAt = now(),
                updatedAt = now(),
            )
        )
        return id
    }

    suspend fun saveTask(task: TaskEntity) = tasks.upsert(task.copy(updatedAt = now()))

    suspend fun setCompleted(task: TaskEntity, completed: Boolean) =
        tasks.upsert(task.copy(completed = completed, completedAt = if (completed) now() else null, abandoned = false, updatedAt = now()))

    suspend fun setAbandoned(task: TaskEntity, abandoned: Boolean) =
        tasks.upsert(task.copy(abandoned = abandoned, completed = false, updatedAt = now()))

    suspend fun setCollapsed(task: TaskEntity, collapsed: Boolean) =
        tasks.upsert(task.copy(collapsed = collapsed, updatedAt = now()))

    private suspend fun subtreeIds(rootId: String): List<String> {
        val out = mutableListOf(rootId)
        var frontier = listOf(rootId)
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < 10_000) {
            val next = frontier.flatMap { tasks.childrenOf(it).map { c -> c.id } }
            out.addAll(next)
            frontier = next
        }
        return out
    }

    /** Move a task (and subtree) to Trash, or restore it. */
    suspend fun setTrashed(rootId: String, trashed: Boolean) {
        val ids = subtreeIds(rootId)
        for (id in ids) {
            val t = tasks.getById(id) ?: continue
            tasks.upsert(t.copy(trashed = trashed, trashedAt = if (trashed) now() else null, updatedAt = now()))
        }
    }

    /** Permanently delete a task and its subtree. */
    suspend fun deleteSubtree(rootId: String) {
        for (id in subtreeIds(rootId)) {
            tags.unlinkAllForTask(id)
            contexts.unlinkAllForTask(id)
            reminders.deleteForTask(id)
            deps.removeAllInvolving(id)
            checklist.deleteForTask(id)
            tasks.deleteById(id)
        }
    }

    suspend fun emptyTrash() {
        tasks.getAll().filter { it.trashed }.forEach { deleteSubtree(it.id) }
    }

    private suspend fun siblingsIn(listId: String, parentId: String?): List<TaskEntity> =
        tasks.childrenIn(listId, parentId)

    suspend fun indent(task: TaskEntity) {
        val sibs = siblingsIn(task.listId, task.parentId)
        val idx = sibs.indexOfFirst { it.id == task.id }
        if (idx <= 0) return
        val newParent = sibs[idx - 1]
        val order = tasks.maxSortOrder(task.listId, newParent.id) + 1.0
        tasks.upsert(task.copy(parentId = newParent.id, sortOrder = order, updatedAt = now()))
    }

    suspend fun outdent(task: TaskEntity) {
        val parentId = task.parentId ?: return
        val parent = tasks.getById(parentId) ?: return
        tasks.upsert(task.copy(parentId = parent.parentId, sortOrder = parent.sortOrder + 0.5, updatedAt = now()))
        renormalize(task.listId, parent.parentId)
    }

    suspend fun moveUp(task: TaskEntity) = swap(task, -1)
    suspend fun moveDown(task: TaskEntity) = swap(task, +1)
    private suspend fun swap(task: TaskEntity, dir: Int) {
        val sibs = siblingsIn(task.listId, task.parentId)
        val idx = sibs.indexOfFirst { it.id == task.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        tasks.upsert(task.copy(sortOrder = other.sortOrder, updatedAt = now()))
        tasks.upsert(other.copy(sortOrder = task.sortOrder, updatedAt = now()))
    }

    private suspend fun renormalize(listId: String, parentId: String?) {
        siblingsIn(listId, parentId).sortedBy { it.sortOrder }.forEachIndexed { i, t ->
            val target = (i + 1).toDouble()
            if (t.sortOrder != target) tasks.upsert(t.copy(sortOrder = target))
        }
    }

    /** Move a task and its whole subtree to another list; the root becomes a top-level task there. */
    suspend fun moveToList(rootId: String, newListId: String) {
        val ids = subtreeIds(rootId)
        val rootOrder = tasks.maxSortOrder(newListId, null) + 1.0
        for (id in ids) {
            val t = tasks.getById(id) ?: continue
            if (id == rootId) {
                tasks.upsert(t.copy(listId = newListId, parentId = null, sortOrder = rootOrder, updatedAt = now()))
            } else {
                tasks.upsert(t.copy(listId = newListId, updatedAt = now()))
            }
        }
    }

    // ============ workspaces ============
    val allWorkspaces: Flow<List<WorkspaceEntity>> = db.workspaceDao().observeAll()
    private val workspaces = db.workspaceDao()
    suspend fun ensureDefaultWorkspace() {
        if (workspaces.getAll().none { it.id == WorkspaceEntity.DEFAULT_ID }) {
            workspaces.upsert(WorkspaceEntity(WorkspaceEntity.DEFAULT_ID, "Personal", 0.0))
        }
    }
    suspend fun upsertWorkspace(w: WorkspaceEntity) = workspaces.upsert(w)
    suspend fun createWorkspace(name: String): String {
        val id = uid()
        workspaces.upsert(WorkspaceEntity(id, name, now().toDouble()))
        return id
    }
    /** Delete a workspace, reassigning its folders/lists (and thus tasks) to the default space. */
    suspend fun deleteWorkspace(id: String) {
        if (id == WorkspaceEntity.DEFAULT_ID) return
        folders.getAll().filter { it.workspaceId == id }.forEach { folders.upsert(it.copy(workspaceId = WorkspaceEntity.DEFAULT_ID)) }
        lists.getAll().filter { it.workspaceId == id }.forEach { lists.upsert(it.copy(workspaceId = WorkspaceEntity.DEFAULT_ID)) }
        workspaces.deleteById(id)
    }

    // ============ folders ============
    suspend fun createFolder(name: String, parentId: String? = null, workspaceId: String = WorkspaceEntity.DEFAULT_ID): String {
        val id = uid()
        folders.upsert(FolderEntity(id = id, parentId = parentId, name = name, sortOrder = now().toDouble(), workspaceId = workspaceId))
        return id
    }

    suspend fun saveFolder(folder: FolderEntity) = folders.upsert(folder)

    /** Delete a folder; its lists and child folders move up to its parent. */
    suspend fun deleteFolder(id: String) {
        val f = folders.getAll().firstOrNull { it.id == id } ?: return
        lists.getAll().filter { it.folderId == id }.forEach { lists.upsert(it.copy(folderId = f.parentId)) }
        folders.getAll().filter { it.parentId == id }.forEach { folders.upsert(it.copy(parentId = f.parentId)) }
        folders.deleteById(id)
    }

    // ============ lists ============
    suspend fun ensureInbox() {
        if (lists.getById(ListEntity.INBOX_ID) == null) {
            lists.upsert(ListEntity(id = ListEntity.INBOX_ID, name = "Inbox", sortOrder = 0.0))
        }
    }

    suspend fun createList(name: String, folderId: String? = null, colorArgb: Long? = null, emoji: String? = null, workspaceId: String = WorkspaceEntity.DEFAULT_ID): String {
        val id = uid()
        val order = lists.maxSortOrder() + 1.0
        lists.upsert(ListEntity(id = id, folderId = folderId, name = name, colorArgb = colorArgb, emoji = emoji, sortOrder = order, workspaceId = workspaceId))
        return id
    }

    suspend fun saveList(list: ListEntity) = lists.upsert(list)
    suspend fun getList(id: String): ListEntity? = lists.getById(id)

    /** Delete a list and permanently remove its tasks. */
    suspend fun deleteList(id: String) {
        if (id == ListEntity.INBOX_ID) return
        tasks.getAll().filter { it.listId == id && it.parentId == null }.forEach { deleteSubtree(it.id) }
        // any orphaned tasks with this listId (safety)
        tasks.getAll().filter { it.listId == id }.forEach { tasks.deleteById(it.id) }
        lists.deleteById(id)
    }

    // ============ drawer reordering / nesting ============
    suspend fun moveListOrder(list: ListEntity, dir: Int) {
        val sibs = lists.getAll().filter { it.folderId == list.folderId && it.id != ListEntity.INBOX_ID && !it.archived }.sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == list.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        lists.upsert(list.copy(sortOrder = other.sortOrder))
        lists.upsert(other.copy(sortOrder = list.sortOrder))
    }

    suspend fun moveFolderOrder(folder: FolderEntity, dir: Int) {
        val sibs = folders.getAll().filter { it.parentId == folder.parentId }.sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == folder.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        folders.upsert(folder.copy(sortOrder = other.sortOrder))
        folders.upsert(other.copy(sortOrder = folder.sortOrder))
    }

    suspend fun moveListToFolder(listId: String, folderId: String?) {
        lists.getById(listId)?.let { lists.upsert(it.copy(folderId = folderId, sortOrder = now().toDouble())) }
    }

    suspend fun moveFolderToParent(folderId: String, parentId: String?) {
        if (folderId == parentId) return
        // prevent cycles: parentId must not be a descendant of folderId
        val all = folders.getAll()
        val descendants = mutableSetOf(folderId)
        var changed = true
        while (changed) {
            changed = false
            all.forEach { if (it.parentId in descendants && it.id !in descendants) { descendants.add(it.id); changed = true } }
        }
        if (parentId != null && parentId in descendants) return
        all.firstOrNull { it.id == folderId }?.let { folders.upsert(it.copy(parentId = parentId, sortOrder = now().toDouble())) }
    }

    // ============ checklist ============
    suspend fun checklistFor(taskId: String): List<ChecklistItemEntity> = checklist.forTask(taskId)
    suspend fun addChecklistItem(taskId: String, text: String) {
        val order = checklist.maxSortOrder(taskId) + 1.0
        checklist.upsert(ChecklistItemEntity(id = uid(), taskId = taskId, sortOrder = order, text = text))
    }
    suspend fun saveChecklistItem(item: ChecklistItemEntity) = checklist.upsert(item)
    suspend fun deleteChecklistItem(id: String) = checklist.deleteById(id)

    // ============ tags / contexts ============
    suspend fun getTagsOnce(): List<TagEntity> = tags.getAll()
    suspend fun getContextsOnce(): List<ContextEntity> = contexts.getAll()
    suspend fun upsertTag(tag: TagEntity) = tags.upsert(tag)
    suspend fun deleteTag(id: String) = tags.deleteById(id)
    suspend fun setTaskTags(taskId: String, tagIds: List<String>) {
        tags.unlinkAllForTask(taskId)
        tags.linkAll(tagIds.map { TaskTagCrossRef(taskId, it) })
    }
    suspend fun upsertContext(context: ContextEntity) = contexts.upsert(context)
    suspend fun deleteContext(id: String) = contexts.deleteById(id)
    suspend fun setTaskContexts(taskId: String, contextIds: List<String>) {
        contexts.unlinkAllForTask(taskId)
        contexts.linkAll(contextIds.map { TaskContextCrossRef(taskId, it) })
    }

    // ============ reminders / deps ============
    suspend fun remindersFor(taskId: String): List<ReminderEntity> = reminders.forTask(taskId)
    suspend fun upsertReminder(reminder: ReminderEntity) = reminders.upsert(reminder)
    suspend fun deleteReminder(id: String) = reminders.deleteById(id)
    suspend fun allRemindersOnce(): List<ReminderEntity> = reminders.getAll()
    suspend fun addDependency(taskId: String, dependsOn: String, mode: String = "AND") =
        deps.add(DependencyEntity(taskId, dependsOn, mode))
    suspend fun removeDependency(dep: DependencyEntity) = deps.remove(dep)

    // ============ settings ============
    suspend fun settingsSnapshot(): AppSettings =
        AppSettings.fromMap(settings.getAll().associate { it.key to it.value })
    suspend fun saveSettings(s: AppSettings) =
        settings.putAll(s.toMap().map { SettingEntity(it.key, it.value) })

    // ============ export / import ============
    suspend fun exportJson(): String = Backup.encode(
        BackupFile(
            exportedAt = now(),
            workspaces = workspaces.getAll(),
            folders = folders.getAll(),
            lists = lists.getAll(),
            tasks = tasks.getAll(),
            checklist = checklist.getAll(),
            tags = tags.getAll(),
            taskTags = tags.getCrossRefs(),
            contexts = contexts.getAll(),
            taskContexts = contexts.getCrossRefs(),
            reminders = reminders.getAll(),
            dependencies = deps.getAll(),
            settings = settings.getAll(),
        )
    )

    suspend fun importJsonReplace(text: String) {
        val b = Backup.decode(text)
        tasks.clear(); folders.clear(); lists.clear(); checklist.clear()
        tags.clear(); tags.clearCrossRefs(); contexts.clear(); contexts.clearCrossRefs()
        reminders.clear(); deps.clear(); settings.clear(); workspaces.clear()
        folders.upsertAll(b.folders)
        lists.upsertAll(b.lists)
        tasks.upsertAll(b.tasks)
        checklist.upsertAll(b.checklist)
        tags.upsertAll(b.tags); tags.linkAll(b.taskTags)
        contexts.upsertAll(b.contexts); contexts.linkAll(b.taskContexts)
        reminders.upsertAll(b.reminders)
        deps.addAll(b.dependencies)
        settings.putAll(b.settings)
        workspaces.upsertAll(b.workspaces)
        ensureDefaultWorkspace()
        ensureInbox()
    }

    // ============ first-run seed ============
    suspend fun ensureSeed() {
        ensureDefaultWorkspace()
        ensureInbox()
        if (tasks.getAll().isNotEmpty()) return
        val work = createFolder("Work")
        val personal = createFolder("Personal")
        val quarterly = createList("Quarterly Report", folderId = work, colorArgb = 0xFFE5484D)
        val admin = createList("Admin", folderId = work, colorArgb = 0xFFF59E0B)
        val home = createList("Home", folderId = personal, colorArgb = 0xFF3E7BFA)

        createTask(ListEntity.INBOX_ID, "Try quick-add: \"pay rent tomorrow 5pm !! #home\"")
        createTask(ListEntity.INBOX_ID, "Everything is offline, private, and free")
        val report = createTask(quarterly, "Draft summary for board deck", importance = 5, urgency = 4)
        createTask(quarterly, "Collect figures", parentId = report, importance = 4)
        createTask(quarterly, "Write exec overview", parentId = report)
        createTask(admin, "File expense receipts", importance = 3, urgency = 4)
        createTask(home, "Water the plants", importance = 2)
        createTask(home, "Book dentist", importance = 3, urgency = 4)
    }
}
