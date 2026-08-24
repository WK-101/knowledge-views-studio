package com.todocompanion.app.domain.port

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned, lossless backup envelope containing every entity. Round-trip = exact. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long,
    val folders: List<FolderEntity> = emptyList(),
    val lists: List<ListEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val checklist: List<ChecklistItemEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val taskTags: List<TaskTagCrossRef> = emptyList(),
    val contexts: List<ContextEntity> = emptyList(),
    val taskContexts: List<TaskContextCrossRef> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
    val dependencies: List<DependencyEntity> = emptyList(),
    val settings: List<SettingEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "todo-companion"
        const val VERSION = 2
    }
}

object Backup {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(data: BackupFile): String = json.encodeToString(BackupFile.serializer(), data)
    fun decode(text: String): BackupFile = json.decodeFromString(BackupFile.serializer(), text)
}
