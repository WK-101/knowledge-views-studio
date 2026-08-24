package com.todocompanion.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.todocompanion.app.App
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.nlp.QuickAddParser
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.reminders.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

/** A flattened, indented outline row for the tree UI. */
data class OutlineRow(
    val task: TaskEntity,
    val depth: Int,
    val hasChildren: Boolean,
    val collapsed: Boolean,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx get() = getApplication<App>()
    private val repo get() = appCtx.repository

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val settings: StateFlow<AppSettings> =
        repo.allSettings.map { list -> AppSettings.fromMap(list.associate { it.key to it.value }) }
            .state(AppSettings())

    val tasks: StateFlow<List<TaskEntity>> = repo.allTasks.state(emptyList())
    val tags: StateFlow<List<TagEntity>> = repo.allTags.state(emptyList())
    val contexts: StateFlow<List<ContextEntity>> = repo.allContexts.state(emptyList())
    val taskTags = repo.taskTagRefs.state(emptyList())
    val taskContexts = repo.taskContextRefs.state(emptyList())
    val reminders = repo.allReminders.state(emptyList())

    val outline: StateFlow<List<OutlineRow>> =
        repo.allTasks.map { buildOutline(it) }.state(emptyList())

    val doNext: StateFlow<List<PriorityEngine.Ranked>> =
        combine(repo.allTasks, repo.allDependencies, repo.taskContextRefs, repo.allContexts) { t, d, tc, c ->
            val now = System.currentTimeMillis()
            val byParent = t.groupBy { it.parentId }
            val byId = t.associateBy { it.id }
            val blocked = PriorityEngine.computeBlocked(d, byId)
            val ctxByTask = tc.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId } }
            val ctxById = c.associateBy { it.id }
            PriorityEngine.doNext(
                all = t,
                now = now,
                blocked = blocked,
                hasIncompleteChild = { id -> byParent[id].orEmpty().any { !it.completed } },
                contextAvailable = { id ->
                    val cs = ctxByTask[id].orEmpty()
                    cs.isEmpty() || cs.any { ctxById[it]?.active == true }
                },
            )
        }.state(emptyList())

    fun observeTask(id: String): Flow<TaskEntity?> = repo.observeTask(id)

    fun tagsForTask(taskId: String): List<TagEntity> {
        val ids = taskTags.value.filter { it.taskId == taskId }.map { it.tagId }.toSet()
        return tags.value.filter { it.id in ids }
    }

    fun contextsForTask(taskId: String): List<ContextEntity> {
        val ids = taskContexts.value.filter { it.taskId == taskId }.map { it.contextId }.toSet()
        return contexts.value.filter { it.id in ids }
    }

    // ---------- actions ----------
    fun quickAdd(text: String, parentId: String? = null) = viewModelScope.launch {
        val parsed = QuickAddParser.parse(text)
        if (parsed.title.isBlank() && parsed.tags.isEmpty()) return@launch
        val imp = parsed.priority?.importance ?: 3
        val urg = parsed.priority?.urgency ?: 3
        val due = parsed.dateTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        val id = repo.createTask(
            title = parsed.title.ifBlank { "Untitled" },
            parentId = parentId,
            importance = imp,
            urgency = urg,
            dueDate = due,
        )
        if (parsed.tags.isNotEmpty()) {
            val existing = repo.getTagsOnce().associateBy { it.name.lowercase() }
            val ids = parsed.tags.map { name ->
                existing[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { newId ->
                    repo.upsertTag(TagEntity(newId, name))
                }
            }
            repo.setTaskTags(id, ids)
        }
        if (parsed.contexts.isNotEmpty()) {
            val existing = repo.getContextsOnce().associateBy { it.name.lowercase() }
            val ids = parsed.contexts.map { name ->
                existing[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { newId ->
                    repo.upsertContext(ContextEntity(id = newId, name = name))
                }
            }
            repo.setTaskContexts(id, ids)
        }
        if (parsed.hasTime && due != null) {
            val reminder = ReminderEntity(UUID.randomUUID().toString(), taskId = id, type = "absolute", atTime = due)
            repo.upsertReminder(reminder)
            repo.getTask(id)?.let { AlarmScheduler.schedule(appCtx, reminder, it) }
        }
    }

    fun addChild(parentId: String?) = viewModelScope.launch { repo.createTask("New task", parentId = parentId) }
    fun toggleComplete(task: TaskEntity) = viewModelScope.launch { repo.setCompleted(task, !task.completed) }
    fun toggleCollapsed(task: TaskEntity) = viewModelScope.launch { repo.setCollapsed(task, !task.collapsed) }
    fun indent(task: TaskEntity) = viewModelScope.launch { repo.indent(task) }
    fun outdent(task: TaskEntity) = viewModelScope.launch { repo.outdent(task) }
    fun moveUp(task: TaskEntity) = viewModelScope.launch { repo.moveUp(task) }
    fun moveDown(task: TaskEntity) = viewModelScope.launch { repo.moveDown(task) }
    fun delete(task: TaskEntity) = viewModelScope.launch { repo.deleteSubtree(task.id) }
    fun save(task: TaskEntity) = viewModelScope.launch { repo.saveTask(task) }

    fun setTags(taskId: String, tagIds: List<String>) = viewModelScope.launch { repo.setTaskTags(taskId, tagIds) }
    fun setContexts(taskId: String, ids: List<String>) = viewModelScope.launch { repo.setTaskContexts(taskId, ids) }
    fun createTag(name: String) = viewModelScope.launch { repo.upsertTag(TagEntity(UUID.randomUUID().toString(), name)) }
    fun createContext(name: String) = viewModelScope.launch { repo.upsertContext(ContextEntity(id = UUID.randomUUID().toString(), name = name)) }

    fun saveSettings(s: AppSettings) = viewModelScope.launch { repo.saveSettings(s) }

    // reminders on a task
    fun remindersFor(taskId: String, cb: (List<ReminderEntity>) -> Unit) = viewModelScope.launch {
        cb(repo.remindersFor(taskId))
    }

    fun addAbsoluteReminder(task: TaskEntity, atMillis: Long) = viewModelScope.launch {
        val r = ReminderEntity(UUID.randomUUID().toString(), taskId = task.id, type = "absolute", atTime = atMillis)
        repo.upsertReminder(r)
        AlarmScheduler.schedule(appCtx, r, task)
    }

    fun deleteReminder(reminder: ReminderEntity, task: TaskEntity) = viewModelScope.launch {
        repo.deleteReminder(reminder.id)
        AlarmScheduler.cancel(appCtx, reminder, task)
    }

    // export / import
    fun exportTo(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val json = repo.exportJson()
            appCtx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }.isSuccess
        onDone(ok)
    }

    fun importFrom(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val text = appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@runCatching Unit
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
