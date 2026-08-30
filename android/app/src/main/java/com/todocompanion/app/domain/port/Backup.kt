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
import com.todocompanion.app.data.entity.WorkspaceEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned, lossless backup envelope containing every entity. Round-trip = exact. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long,
    val workspaces: List<WorkspaceEntity> = emptyList(),
    val filters: List<com.todocompanion.app.data.entity.FilterEntity> = emptyList(),
    val habits: List<com.todocompanion.app.data.entity.HabitEntity> = emptyList(),
    val habitCheckins: List<com.todocompanion.app.data.entity.HabitCheckinEntity> = emptyList(),
    val focusSessions: List<com.todocompanion.app.data.entity.FocusSessionEntity> = emptyList(),
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
    val attachments: List<com.todocompanion.app.data.entity.AttachmentEntity> = emptyList(),
    val flags: List<com.todocompanion.app.data.entity.FlagEntity> = emptyList(),
    val templates: List<com.todocompanion.app.data.entity.TemplateEntity> = emptyList(),
    val countdowns: List<com.todocompanion.app.data.entity.CountdownEntity> = emptyList(),
    val activities: List<com.todocompanion.app.data.entity.ActivityEntity> = emptyList(),
    // Tier S — time tracking. Additive; old backups simply carry empty lists.
    val timeActivities: List<com.todocompanion.app.data.entity.TimeActivityEntity> = emptyList(),
    val timeEntries: List<com.todocompanion.app.data.entity.TimeEntryEntity> = emptyList(),
    // R32 — sealed "letter to your future self" notes. Additive; old backups carry an empty list.
    val sealedNotes: List<com.todocompanion.app.data.entity.SealedNoteEntity> = emptyList(),
    // R33 — habit-builder urge/craving log. Additive; old backups carry an empty list.
    val cravingEvents: List<com.todocompanion.app.data.entity.CravingEventEntity> = emptyList(),
    // R34 — the life-systems layer's tables. Additive; old backups carry empty lists.
    val coreValues: List<com.todocompanion.app.data.entity.CoreValueEntity> = emptyList(),
    val witnessEvents: List<com.todocompanion.app.data.entity.WitnessEventEntity> = emptyList(),
    val scorecardItems: List<com.todocompanion.app.data.entity.ScorecardItemEntity> = emptyList(),
    val buddySnapshots: List<com.todocompanion.app.data.entity.BuddySnapshotEntity> = emptyList(),
    val integrityReviews: List<com.todocompanion.app.data.entity.IntegrityReviewEntity> = emptyList(),
    // R35 — the third-wave layer's tables. Additive; old backups carry empty lists.
    val experiments: List<com.todocompanion.app.data.entity.ExperimentEntity> = emptyList(),
    val activationItems: List<com.todocompanion.app.data.entity.ActivationItemEntity> = emptyList(),
    val dayLogs: List<com.todocompanion.app.data.entity.DayLogEntity> = emptyList(),
    // R36 — the fourth-wave layer's tables. Additive; old backups carry empty lists.
    val escrows: List<com.todocompanion.app.data.entity.EscrowEntity> = emptyList(),
    val nudgeEvents: List<com.todocompanion.app.data.entity.NudgeEventEntity> = emptyList(),
    // R37 — task time-travel history, so a restore is truly lossless. Additive; old backups carry empty.
    val revisions: List<com.todocompanion.app.data.entity.TaskRevisionEntity> = emptyList(),
    // R38 — the dedicated-calendar layer: local calendars + events. Additive; old backups carry empty.
    val eventCalendars: List<com.todocompanion.app.data.entity.EventCalendarEntity> = emptyList(),
    val events: List<com.todocompanion.app.data.entity.EventEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "todo-companion"
        const val VERSION = 15
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
