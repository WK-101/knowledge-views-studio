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
    // Notes module (v66) — first-class notes + optional notebooks + their tag/context links. Additive;
    // old backups carry empty lists, so an older file restores cleanly (no notes) and a newer file's notes
    // ride the lossless JSON round-trip.
    val notes: List<com.todocompanion.app.data.entity.NoteEntity> = emptyList(),
    val notebooks: List<com.todocompanion.app.data.entity.NotebookEntity> = emptyList(),
    val noteTags: List<com.todocompanion.app.data.entity.NoteTagCrossRef> = emptyList(),
    val noteContexts: List<com.todocompanion.app.data.entity.NoteContextCrossRef> = emptyList(),
    // Wave B (v67) — local note version-history snapshots. Additive; old backups carry an empty list.
    val noteRevisions: List<com.todocompanion.app.data.entity.NoteRevisionEntity> = emptyList(),
    // Wave C (v68) — cross-module note→entity link edges. Additive; old backups carry an empty list.
    val noteLinks: List<com.todocompanion.app.data.entity.NoteLinkEntity> = emptyList(),
    // Wave D (v69) — saved Smart Views (predicate filters). Additive; old backups carry an empty list.
    val smartViews: List<com.todocompanion.app.data.entity.SmartViewEntity> = emptyList(),
    // Wave 3 (v78) — Active-Recall flashcards. The card *content* is derivable from note bodies, but the
    // SM-2 review schedule is not, so it rides the backup to keep a restore truly lossless. Additive.
    val noteCards: List<com.todocompanion.app.data.entity.NoteCardEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "todo-companion"
        const val VERSION = 19
    }
}

object Backup {
    // R108 audit B5 — the WRITER is compact: no pretty-print whitespace and defaults omitted
    // (encodeDefaults = false). The round-trip stays EXACT because encodeDefaults only drops a field
    // when its value already equals its declared default, and every such field deserializes straight
    // back to that same default — a field without a default is always written. On a real store this
    // typically shrinks the JSON by well over half (whitespace + the many at-default columns).
    private val encoder = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    // The READER stays tolerant of BOTH shapes — older pretty backups with every field present, and new
    // compact ones with defaults omitted — and ignoreUnknownKeys covers any field dropped in a future schema.
    private val decoder = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(data: BackupFile): String = encoder.encodeToString(BackupFile.serializer(), data)
    fun decode(text: String): BackupFile = decoder.decodeFromString(BackupFile.serializer(), text)
}
