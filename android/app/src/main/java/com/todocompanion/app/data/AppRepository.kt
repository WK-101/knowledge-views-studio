package com.todocompanion.app.data

import com.todocompanion.app.data.entity.ChecklistItemEntity
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.FilterEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.FlagEntity
import com.todocompanion.app.data.entity.TemplateEntity
import com.todocompanion.app.data.entity.TemplateTask
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.SettingEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.data.entity.WorkspaceEntity
import com.todocompanion.app.data.entity.AttachmentEntity
import com.todocompanion.app.data.entity.AttachmentMeta
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
    private val attachments = db.attachmentDao()
    private val flags = db.flagDao()
    private val templates = db.templateDao()
    private val templateJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
    val allFlags: Flow<List<FlagEntity>> = flags.observeAll()
    val allTemplates: Flow<List<TemplateEntity>> = templates.observeAll()
    val allSettings: Flow<List<SettingEntity>> = settings.observeAll()
    private val habits = db.habitDao()
    val allHabits: Flow<List<HabitEntity>> = habits.observeAll()
    val allCheckins: Flow<List<HabitCheckinEntity>> = habits.observeCheckins()
    suspend fun createHabit(name: String, emoji: String?, colorArgb: Long?, target: Int, workspaceId: String): String {
        val id = uid()
        habits.upsert(HabitEntity(id = id, name = name, emoji = emoji, colorArgb = colorArgb, targetPerDay = target.coerceAtLeast(1), sortOrder = now().toDouble(), workspaceId = workspaceId, createdAt = now()))
        return id
    }
    suspend fun upsertHabit(h: HabitEntity) = habits.upsert(h)
    suspend fun deleteHabit(id: String) { habits.clearHabit(id); habits.deleteById(id) }
    /** Cycle today's progress: +1 up to target, then back to 0 (removes the check-in). */
    suspend fun cycleCheckin(habitId: String, epochDay: Long, target: Int, current: Int) {
        val next = current + 1
        if (next > target) habits.deleteCheckin(habitId, epochDay)
        else habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, next))
    }

    private val focus = db.focusDao()
    val allFocusSessions: Flow<List<FocusSessionEntity>> = focus.observeAll()
    suspend fun addFocusSession(epochDay: Long, startMillis: Long, minutes: Int, kind: String) =
        focus.upsert(FocusSessionEntity(uid(), epochDay, startMillis, minutes, kind))

    private val filters = db.filterDao()
    val allFilters: Flow<List<FilterEntity>> = filters.observeAll()
    suspend fun upsertFilter(f: FilterEntity) = filters.upsert(f)
    suspend fun deleteFilter(id: String) = filters.deleteById(id)
    suspend fun createFilter(name: String, workspaceId: String): String {
        val id = uid()
        filters.upsert(FilterEntity(id = id, name = name, sortOrder = now().toDouble(), workspaceId = workspaceId))
        return id
    }

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

    suspend fun createList(name: String, folderId: String? = null, colorArgb: Long? = null, emoji: String? = null, workspaceId: String = WorkspaceEntity.DEFAULT_ID, parentListId: String? = null): String {
        val id = uid()
        val order = lists.maxSortOrder() + 1.0
        lists.upsert(ListEntity(id = id, folderId = folderId, parentListId = parentListId, name = name, colorArgb = colorArgb, emoji = emoji, sortOrder = order, workspaceId = workspaceId))
        return id
    }

    suspend fun saveList(list: ListEntity) = lists.upsert(list)
    suspend fun getList(id: String): ListEntity? = lists.getById(id)
    /** Set (or clear, when null) a list's embedded background image (already-encoded JPEG base64). */
    suspend fun setListBackground(listId: String, base64: String?) {
        lists.getById(listId)?.let { lists.upsert(it.copy(backgroundBase64 = base64)) }
    }

    /** Delete a list and permanently remove its tasks. Child lists are re-parented up
     *  (to this list's own parent / folder root) so they aren't orphaned. */
    suspend fun deleteList(id: String) {
        if (id == ListEntity.INBOX_ID) return
        val victim = lists.getById(id)
        lists.getAll().filter { it.parentListId == id }.forEach {
            lists.upsert(it.copy(parentListId = victim?.parentListId, folderId = victim?.folderId ?: it.folderId))
        }
        tasks.getAll().filter { it.listId == id && it.parentId == null }.forEach { deleteSubtree(it.id) }
        // any orphaned tasks with this listId (safety)
        tasks.getAll().filter { it.listId == id }.forEach { tasks.deleteById(it.id) }
        lists.deleteById(id)
    }

    // ============ drawer reordering / nesting ============
    suspend fun moveListOrder(list: ListEntity, dir: Int) {
        val sibs = lists.getAll().filter { it.folderId == list.folderId && it.parentListId == list.parentListId && it.id != ListEntity.INBOX_ID && !it.archived }.sortedBy { it.sortOrder }
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
        // Moving into a folder makes the list top-level there (clears any list nesting).
        lists.getById(listId)?.let { lists.upsert(it.copy(folderId = folderId, parentListId = null, sortOrder = now().toDouble())) }
    }

    /** Nest a list under another list (or pass null to un-nest to folder root). Cycle-safe;
     *  the child adopts the parent's folder so the subtree stays in one place. */
    suspend fun setListParent(listId: String, parentListId: String?) {
        if (listId == parentListId || listId == ListEntity.INBOX_ID) return
        val all = lists.getAll()
        val list = all.firstOrNull { it.id == listId } ?: return
        // prevent cycles: parentListId must not be a descendant of listId
        val descendants = mutableSetOf(listId)
        var changed = true
        while (changed) {
            changed = false
            all.forEach { if (it.parentListId in descendants && it.id !in descendants) { descendants.add(it.id); changed = true } }
        }
        if (parentListId != null && parentListId in descendants) return
        val newFolder = if (parentListId != null) all.firstOrNull { it.id == parentListId }?.folderId else list.folderId
        lists.upsert(list.copy(parentListId = parentListId, folderId = newFolder, sortOrder = now().toDouble()))
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

    // ============ attachments ============
    /** Max size accepted PER FILE (25 MB). There is no limit on the NUMBER of attachments a
     *  task can hold. Bytes live Base64 in the DB and travel losslessly in JSON backups. Any
     *  file type is accepted (images, PDF, Office docs, epub, txt/md, etc.); the per-file cap
     *  just keeps any single file from bloating the backup. */
    val maxAttachmentBytes = 25L * 1024 * 1024
    fun attachmentMeta(taskId: String): Flow<List<AttachmentMeta>> = attachments.observeMetaForTask(taskId)
    val allAttachmentMeta: Flow<List<AttachmentMeta>> = attachments.observeAllMeta()
    fun attachmentCount(taskId: String): Flow<Int> = attachments.observeCountForTask(taskId)
    suspend fun attachmentContent(id: String): String? = attachments.contentOf(id)
    /** Store raw bytes as a task attachment. Returns false if it exceeds the size cap. */
    suspend fun addAttachment(taskId: String, fileName: String, mime: String, bytes: ByteArray): Boolean {
        if (bytes.size > maxAttachmentBytes) return false
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        attachments.upsert(
            AttachmentEntity(
                id = uid(), taskId = taskId, fileName = fileName, mime = mime,
                sizeBytes = bytes.size.toLong(), isImage = mime.startsWith("image/"),
                addedAt = now(), contentBase64 = b64,
            ),
        )
        return true
    }
    suspend fun deleteAttachment(id: String) = attachments.deleteById(id)

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

    // ============ flags ============
    suspend fun getFlagsOnce(): List<FlagEntity> = flags.getAll()
    suspend fun upsertFlag(f: FlagEntity) = flags.upsert(f)
    suspend fun createFlag(name: String, colorArgb: Long, icon: String = "flag"): String {
        val id = uid()
        flags.upsert(FlagEntity(id = id, name = name.ifBlank { "Flag" }, colorArgb = colorArgb, icon = icon, sortOrder = flags.maxSortOrder() + 1.0, createdAt = now()))
        return id
    }
    /** Delete a flag and clear it (id + colour cache) from every task that wore it. */
    suspend fun deleteFlag(id: String) {
        tasks.getAll().filter { it.flagId == id }.forEach { tasks.upsert(it.copy(flagId = null, flagColorArgb = null, updatedAt = now())) }
        flags.deleteById(id)
    }
    suspend fun moveFlagOrder(flag: FlagEntity, dir: Int) {
        val sibs = flags.getAll().sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == flag.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        flags.upsert(flag.copy(sortOrder = other.sortOrder))
        flags.upsert(other.copy(sortOrder = flag.sortOrder))
    }
    /** Assign (or clear, when [flagId] is null) a task's flag, caching the flag colour on the task. */
    suspend fun setTaskFlag(task: TaskEntity, flagId: String?) {
        val color = flagId?.let { fid -> flags.getAll().firstOrNull { it.id == fid }?.colorArgb }
        tasks.upsert(task.copy(flagId = flagId, flagColorArgb = color, updatedAt = now()))
    }
    /** Seed the default flags once, unless the user has already been given them. */
    suspend fun ensureDefaultFlags() {
        if (settings.get("flagsSeeded") == "true") return
        if (flags.getAll().isEmpty()) flags.upsertAll(FlagEntity.DEFAULTS)
        settings.put(SettingEntity("flagsSeeded", "true"))
    }

    // ============ templates ============
    suspend fun deleteTemplate(id: String) = templates.deleteById(id)
    suspend fun getTemplatesOnce(): List<TemplateEntity> = templates.getAll()
    suspend fun renameTemplate(id: String, name: String) {
        templates.getById(id)?.let { templates.upsert(it.copy(name = name.ifBlank { it.name })) }
    }

    private fun dayOffset(millis: Long?, todayStart: Long): Int? =
        millis?.let { ((it - todayStart) / 86_400_000L).toInt() }

    /** Freeze a task subtree (note, priority, flag, recurrence, checklist, tags, contexts,
     *  relative dates) into a named, reusable template. */
    suspend fun saveAsTemplate(rootTaskId: String, name: String): String? {
        val root = tasks.getById(rootTaskId) ?: return null
        val tagName = tags.getAll().associate { it.id to it.name }
        val ctxName = contexts.getAll().associate { it.id to it.name }
        val tagRefs = tags.getCrossRefs().groupBy { it.taskId }
        val ctxRefs = contexts.getCrossRefs().groupBy { it.taskId }
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        suspend fun node(t: TaskEntity): TemplateTask = TemplateTask(
            title = t.title, note = t.note, isNote = t.isNote,
            importance = t.importance, urgency = t.urgency,
            flagId = t.flagId, flagColorArgb = t.flagColorArgb,
            durationMin = t.durationMin, estimateMin = t.estimateMin, leadTimeMin = t.leadTimeMin,
            completeInOrder = t.completeInOrder, isProject = t.isProject, isGoal = t.isGoal,
            rrule = t.rrule, recurrenceMode = t.recurrenceMode,
            startOffsetDays = dayOffset(t.startDate, todayStart), dueOffsetDays = dayOffset(t.dueDate, todayStart),
            tagNames = tagRefs[t.id].orEmpty().mapNotNull { tagName[it.tagId] },
            contextNames = ctxRefs[t.id].orEmpty().mapNotNull { ctxName[it.contextId] },
            checklist = checklist.forTask(t.id).map { it.text },
            children = tasks.childrenOf(t.id).map { node(it) },
        )

        val payload = node(root)
        val id = uid()
        templates.upsert(TemplateEntity(id, name.ifBlank { root.title }, templateJson.encodeToString(TemplateTask.serializer(), payload), now()))
        return id
    }

    /** Instantiate a template into [listId] under [parentId], returning the new root task id. */
    suspend fun instantiateTemplate(templateId: String, listId: String, parentId: String? = null): String? {
        val tpl = templates.getById(templateId) ?: return null
        val payload = runCatching { templateJson.decodeFromString(TemplateTask.serializer(), tpl.payloadJson) }.getOrNull() ?: return null
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tagByName = tags.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()
        val ctxByName = contexts.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()

        suspend fun tagId(name: String): String = tagByName.getOrPut(name.lowercase()) {
            uid().also { tags.upsert(TagEntity(it, name)) }
        }
        suspend fun ctxId(name: String): String = ctxByName.getOrPut(name.lowercase()) {
            uid().also { contexts.upsert(ContextEntity(id = it, name = name)) }
        }

        suspend fun create(node: TemplateTask, parent: String?): String {
            val id = uid()
            val order = tasks.maxSortOrder(listId, parent) + 1.0
            tasks.upsert(
                TaskEntity(
                    id = id, listId = listId, parentId = parent, sortOrder = order,
                    title = node.title.ifBlank { "Untitled" }, note = node.note, isNote = node.isNote,
                    importance = node.importance, urgency = node.urgency,
                    flagId = node.flagId, flagColorArgb = node.flagColorArgb,
                    durationMin = node.durationMin, estimateMin = node.estimateMin, leadTimeMin = node.leadTimeMin,
                    completeInOrder = node.completeInOrder, isProject = node.isProject, isGoal = node.isGoal,
                    rrule = node.rrule, recurrenceMode = node.recurrenceMode,
                    startDate = node.startOffsetDays?.let { todayStart + it * 86_400_000L },
                    dueDate = node.dueOffsetDays?.let { todayStart + it * 86_400_000L },
                    createdAt = now(), updatedAt = now(),
                ),
            )
            node.checklist.forEachIndexed { i, text -> checklist.upsert(ChecklistItemEntity(id = uid(), taskId = id, sortOrder = (i + 1).toDouble(), text = text)) }
            if (node.tagNames.isNotEmpty()) tags.linkAll(node.tagNames.map { TaskTagCrossRef(id, tagId(it)) })
            if (node.contextNames.isNotEmpty()) contexts.linkAll(node.contextNames.map { TaskContextCrossRef(id, ctxId(it)) })
            node.children.forEach { create(it, id) }
            return id
        }
        return create(payload, parentId)
    }

    // ============ reminders / deps ============
    suspend fun remindersFor(taskId: String): List<ReminderEntity> = reminders.forTask(taskId)
    suspend fun upsertReminder(reminder: ReminderEntity) = reminders.upsert(reminder)
    suspend fun deleteReminder(id: String) = reminders.deleteById(id)
    suspend fun allRemindersOnce(): List<ReminderEntity> = reminders.getAll()
    suspend fun addDependency(taskId: String, dependsOn: String, mode: String = "AND", delayDays: Int = 0) =
        deps.add(DependencyEntity(taskId, dependsOn, mode, delayDays))
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
            filters = filters.getAll(),
            habits = habits.getAll(),
            habitCheckins = habits.getCheckins(),
            focusSessions = focus.getAll(),
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
            attachments = attachments.getAll(),
            flags = flags.getAll(),
            templates = templates.getAll(),
        )
    )

    suspend fun importJsonReplace(text: String) {
        val b = Backup.decode(text)
        tasks.clear(); folders.clear(); lists.clear(); checklist.clear()
        tags.clear(); tags.clearCrossRefs(); contexts.clear(); contexts.clearCrossRefs()
        reminders.clear(); deps.clear(); settings.clear(); workspaces.clear(); filters.clear()
        habits.clear(); habits.clearCheckins(); focus.clear(); attachments.clear(); flags.clear(); templates.clear()
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
        filters.upsertAll(b.filters)
        habits.upsertAll(b.habits); habits.upsertCheckins(b.habitCheckins)
        focus.upsertAll(b.focusSessions)
        attachments.upsertAll(b.attachments)
        flags.upsertAll(b.flags)
        templates.upsertAll(b.templates)
        ensureDefaultWorkspace()
        ensureInbox()
        ensureDefaultFlags()
    }

    // ============ first-run seed ============
    suspend fun ensureSeed() {
        ensureDefaultWorkspace()
        ensureInbox()
        ensureDefaultFlags()
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
