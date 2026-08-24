package com.todocompanion.app.data

import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.SettingEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.port.Backup
import com.todocompanion.app.domain.port.BackupFile
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Single source of truth over Room. All reads are reactive Flows; all writes are suspend. */
class AppRepository(private val db: AppDatabase) {

    private val tasks = db.taskDao()
    private val tags = db.tagDao()
    private val contexts = db.contextDao()
    private val reminders = db.reminderDao()
    private val deps = db.dependencyDao()
    private val settings = db.settingDao()

    // ----- reactive reads -----
    val allTasks: Flow<List<TaskEntity>> = tasks.observeAll()
    val allTags: Flow<List<TagEntity>> = tags.observeAll()
    val allContexts: Flow<List<ContextEntity>> = contexts.observeAll()
    val taskTagRefs: Flow<List<TaskTagCrossRef>> = tags.observeCrossRefs()
    val taskContextRefs: Flow<List<TaskContextCrossRef>> = contexts.observeCrossRefs()
    val allReminders: Flow<List<ReminderEntity>> = reminders.observeAll()
    val allDependencies: Flow<List<DependencyEntity>> = deps.observeAll()
    val allSettings: Flow<List<SettingEntity>> = settings.observeAll()

    fun observeTask(id: String): Flow<TaskEntity?> = tasks.observeById(id)

    suspend fun getTask(id: String): TaskEntity? = tasks.getById(id)

    // ----- task mutations -----
    private fun now() = System.currentTimeMillis()

    suspend fun createTask(
        title: String,
        parentId: String? = null,
        importance: Int = 3,
        urgency: Int = 3,
        dueDate: Long? = null,
        startDate: Long? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val order = tasks.maxSortOrder(parentId) + 1.0
        tasks.upsert(
            TaskEntity(
                id = id,
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
        tasks.upsert(task.copy(completed = completed, completedAt = if (completed) now() else null, updatedAt = now()))

    suspend fun setCollapsed(task: TaskEntity, collapsed: Boolean) =
        tasks.upsert(task.copy(collapsed = collapsed, updatedAt = now()))

    /** Delete a task and its whole subtree, plus links/reminders/deps. */
    suspend fun deleteSubtree(rootId: String) {
        val toDelete = mutableListOf(rootId)
        var frontier = listOf(rootId)
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < 10_000) {
            val next = frontier.flatMap { tasks.childrenOf(it).map { c -> c.id } }
            toDelete.addAll(next)
            frontier = next
        }
        for (id in toDelete) {
            tags.unlinkAllForTask(id)
            contexts.unlinkAllForTask(id)
            reminders.deleteForTask(id)
            deps.removeAllInvolving(id)
            tasks.deleteById(id)
        }
    }

    /** Make [task] a child of its immediate previous sibling. */
    suspend fun indent(task: TaskEntity) {
        val siblings = childrenOfParent(task.parentId)
        val idx = siblings.indexOfFirst { it.id == task.id }
        if (idx <= 0) return
        val newParent = siblings[idx - 1]
        val order = tasks.maxSortOrder(newParent.id) + 1.0
        tasks.upsert(task.copy(parentId = newParent.id, sortOrder = order, updatedAt = now()))
    }

    /** Move [task] up to become a sibling of its parent, just after it. */
    suspend fun outdent(task: TaskEntity) {
        val parentId = task.parentId ?: return
        val parent = tasks.getById(parentId) ?: return
        val order = parent.sortOrder + 0.5
        tasks.upsert(task.copy(parentId = parent.parentId, sortOrder = order, updatedAt = now()))
        renormalize(parent.parentId)
    }

    suspend fun moveUp(task: TaskEntity) = swapWithNeighbor(task, -1)
    suspend fun moveDown(task: TaskEntity) = swapWithNeighbor(task, +1)

    private suspend fun swapWithNeighbor(task: TaskEntity, dir: Int) {
        val siblings = childrenOfParent(task.parentId)
        val idx = siblings.indexOfFirst { it.id == task.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= siblings.size) return
        val other = siblings[j]
        tasks.upsert(task.copy(sortOrder = other.sortOrder, updatedAt = now()))
        tasks.upsert(other.copy(sortOrder = task.sortOrder, updatedAt = now()))
    }

    private suspend fun childrenOfParent(parentId: String?): List<TaskEntity> =
        if (parentId == null) tasks.childrenOfRoot() else tasks.childrenOf(parentId)

    private suspend fun renormalize(parentId: String?) {
        val list = childrenOfParent(parentId).sortedBy { it.sortOrder }
        list.forEachIndexed { i, t ->
            val target = (i + 1).toDouble()
            if (t.sortOrder != target) tasks.upsert(t.copy(sortOrder = target))
        }
    }

    // ----- tags / contexts -----
    suspend fun getTagsOnce(): List<TagEntity> = tags.getAll()
    suspend fun getContextsOnce(): List<ContextEntity> = contexts.getAll()
    suspend fun upsertTag(tag: TagEntity) = tags.upsert(tag)
    suspend fun setTaskTags(taskId: String, tagIds: List<String>) {
        tags.unlinkAllForTask(taskId)
        tags.linkAll(tagIds.map { TaskTagCrossRef(taskId, it) })
    }

    suspend fun upsertContext(context: ContextEntity) = contexts.upsert(context)
    suspend fun setTaskContexts(taskId: String, contextIds: List<String>) {
        contexts.unlinkAllForTask(taskId)
        contexts.linkAll(contextIds.map { TaskContextCrossRef(taskId, it) })
    }

    // ----- reminders -----
    suspend fun remindersFor(taskId: String): List<ReminderEntity> = reminders.forTask(taskId)
    suspend fun upsertReminder(reminder: ReminderEntity) = reminders.upsert(reminder)
    suspend fun deleteReminder(id: String) = reminders.deleteById(id)
    suspend fun allRemindersOnce(): List<ReminderEntity> = reminders.getAll()

    // ----- dependencies -----
    suspend fun addDependency(taskId: String, dependsOn: String, mode: String = "AND") =
        deps.add(DependencyEntity(taskId, dependsOn, mode))
    suspend fun removeDependency(dep: DependencyEntity) = deps.remove(dep)

    // ----- settings -----
    suspend fun settingsSnapshot(): AppSettings =
        AppSettings.fromMap(settings.getAll().associate { it.key to it.value })

    suspend fun saveSettings(s: AppSettings) =
        settings.putAll(s.toMap().map { SettingEntity(it.key, it.value) })

    // ----- export / import -----
    suspend fun exportJson(): String {
        val backup = BackupFile(
            exportedAt = now(),
            tasks = tasks.getAll(),
            tags = tags.getAll(),
            taskTags = tags.getCrossRefs(),
            contexts = contexts.getAll(),
            taskContexts = contexts.getCrossRefs(),
            reminders = reminders.getAll(),
            dependencies = deps.getAll(),
            settings = settings.getAll(),
        )
        return Backup.encode(backup)
    }

    /** Replace the entire database with the contents of a backup file. */
    suspend fun importJsonReplace(text: String) {
        val b = Backup.decode(text)
        tasks.clear(); tags.clear(); tags.clearCrossRefs()
        contexts.clear(); contexts.clearCrossRefs()
        reminders.clear(); deps.clear(); settings.clear()
        tasks.upsertAll(b.tasks)
        tags.upsertAll(b.tags); tags.linkAll(b.taskTags)
        contexts.upsertAll(b.contexts); contexts.linkAll(b.taskContexts)
        reminders.upsertAll(b.reminders)
        deps.addAll(b.dependencies)
        settings.putAll(b.settings)
    }
}
