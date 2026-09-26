package com.wkhan.hexis.domain.port

import com.wkhan.hexis.data.entity.ChecklistItemEntity
import com.wkhan.hexis.data.entity.ContextEntity
import com.wkhan.hexis.data.entity.DependencyEntity
import com.wkhan.hexis.data.entity.FolderEntity
import com.wkhan.hexis.data.entity.ListEntity
import com.wkhan.hexis.data.entity.ReminderEntity
import com.wkhan.hexis.data.entity.SettingEntity
import com.wkhan.hexis.data.entity.TagEntity
import com.wkhan.hexis.data.entity.TaskContextCrossRef
import com.wkhan.hexis.data.entity.TaskEntity
import com.wkhan.hexis.data.entity.TaskTagCrossRef
import com.wkhan.hexis.data.entity.WorkspaceEntity
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Versioned, lossless backup envelope containing every entity. Round-trip = exact. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BackupFile(
    // EncodeDefault.ALWAYS forces these two provenance markers to be WRITTEN even though the compact
    // writer drops at-default fields — so every Hexis backup self-identifies and decode() can reject a
    // foreign file that declares a different format or a newer schema. (Pre-marker backups simply omit
    // them and are still accepted; the empty-content guard in importJsonReplace is the wipe backstop.)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val format: String = FORMAT,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val version: Int = VERSION,
    val exportedAt: Long,
    val workspaces: List<WorkspaceEntity> = emptyList(),
    val filters: List<com.wkhan.hexis.data.entity.FilterEntity> = emptyList(),
    val habits: List<com.wkhan.hexis.data.entity.HabitEntity> = emptyList(),
    val habitCheckins: List<com.wkhan.hexis.data.entity.HabitCheckinEntity> = emptyList(),
    val focusSessions: List<com.wkhan.hexis.data.entity.FocusSessionEntity> = emptyList(),
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
    val attachments: List<com.wkhan.hexis.data.entity.AttachmentEntity> = emptyList(),
    val flags: List<com.wkhan.hexis.data.entity.FlagEntity> = emptyList(),
    val templates: List<com.wkhan.hexis.data.entity.TemplateEntity> = emptyList(),
    val countdowns: List<com.wkhan.hexis.data.entity.CountdownEntity> = emptyList(),
    val activities: List<com.wkhan.hexis.data.entity.ActivityEntity> = emptyList(),
    // Tier S — time tracking. Additive; old backups simply carry empty lists.
    val timeActivities: List<com.wkhan.hexis.data.entity.TimeActivityEntity> = emptyList(),
    val timeEntries: List<com.wkhan.hexis.data.entity.TimeEntryEntity> = emptyList(),
    // R32 — sealed "letter to your future self" notes. Additive; old backups carry an empty list.
    val sealedNotes: List<com.wkhan.hexis.data.entity.SealedNoteEntity> = emptyList(),
    // R33 — habit-builder urge/craving log. Additive; old backups carry an empty list.
    val cravingEvents: List<com.wkhan.hexis.data.entity.CravingEventEntity> = emptyList(),
    // R34 — the life-systems layer's tables. Additive; old backups carry empty lists.
    val coreValues: List<com.wkhan.hexis.data.entity.CoreValueEntity> = emptyList(),
    val witnessEvents: List<com.wkhan.hexis.data.entity.WitnessEventEntity> = emptyList(),
    val scorecardItems: List<com.wkhan.hexis.data.entity.ScorecardItemEntity> = emptyList(),
    val buddySnapshots: List<com.wkhan.hexis.data.entity.BuddySnapshotEntity> = emptyList(),
    val integrityReviews: List<com.wkhan.hexis.data.entity.IntegrityReviewEntity> = emptyList(),
    // R35 — the third-wave layer's tables. Additive; old backups carry empty lists.
    val experiments: List<com.wkhan.hexis.data.entity.ExperimentEntity> = emptyList(),
    val activationItems: List<com.wkhan.hexis.data.entity.ActivationItemEntity> = emptyList(),
    val dayLogs: List<com.wkhan.hexis.data.entity.DayLogEntity> = emptyList(),
    // R36 — the fourth-wave layer's tables. Additive; old backups carry empty lists.
    val escrows: List<com.wkhan.hexis.data.entity.EscrowEntity> = emptyList(),
    val nudgeEvents: List<com.wkhan.hexis.data.entity.NudgeEventEntity> = emptyList(),
    // R37 — task time-travel history, so a restore is truly lossless. Additive; old backups carry empty.
    val revisions: List<com.wkhan.hexis.data.entity.TaskRevisionEntity> = emptyList(),
    // R38 — the dedicated-calendar layer: local calendars + events. Additive; old backups carry empty.
    val eventCalendars: List<com.wkhan.hexis.data.entity.EventCalendarEntity> = emptyList(),
    val events: List<com.wkhan.hexis.data.entity.EventEntity> = emptyList(),
    // Notes module (v66) — first-class notes + optional notebooks + their tag/context links. Additive;
    // old backups carry empty lists, so an older file restores cleanly (no notes) and a newer file's notes
    // ride the lossless JSON round-trip.
    val notes: List<com.wkhan.hexis.data.entity.NoteEntity> = emptyList(),
    val notebooks: List<com.wkhan.hexis.data.entity.NotebookEntity> = emptyList(),
    val noteTags: List<com.wkhan.hexis.data.entity.NoteTagCrossRef> = emptyList(),
    val noteContexts: List<com.wkhan.hexis.data.entity.NoteContextCrossRef> = emptyList(),
    // Wave B (v67) — local note version-history snapshots. Additive; old backups carry an empty list.
    val noteRevisions: List<com.wkhan.hexis.data.entity.NoteRevisionEntity> = emptyList(),
    // Wave C (v68) — cross-module note→entity link edges. Additive; old backups carry an empty list.
    val noteLinks: List<com.wkhan.hexis.data.entity.NoteLinkEntity> = emptyList(),
    // Wave D (v69) — saved Smart Views (predicate filters). Additive; old backups carry an empty list.
    val smartViews: List<com.wkhan.hexis.data.entity.SmartViewEntity> = emptyList(),
    // Wave 3 (v78) — Active-Recall flashcards. The card *content* is derivable from note bodies, but the
    // SM-2 review schedule is not, so it rides the backup to keep a restore truly lossless. Additive.
    val noteCards: List<com.wkhan.hexis.data.entity.NoteCardEntity> = emptyList(),
) {
    /** True when the file carries no restorable rows in any core table. importJsonReplace refuses an
     *  empty REPLACE so a foreign/partial/corrupt file (which decodes to an all-empty envelope) can't
     *  wipe a populated store. A real Hexis backup always has at least a settings row. */
    fun hasNoContent(): Boolean =
        tasks.isEmpty() && notes.isEmpty() && habits.isEmpty() && events.isEmpty() &&
            lists.isEmpty() && folders.isEmpty() && timeEntries.isEmpty() && timeActivities.isEmpty() &&
            countdowns.isEmpty() && templates.isEmpty() && workspaces.isEmpty() && filters.isEmpty() &&
            settings.isEmpty()

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
    fun decode(text: String): BackupFile {
        val root = runCatching { decoder.parseToJsonElement(text) }.getOrElse {
            throw IllegalArgumentException("Not a valid backup file — could not parse JSON.", it)
        }
        if (root !is JsonObject) throw IllegalArgumentException("Not a Hexis backup — expected a JSON object.")
        // Provenance: reject a file that explicitly declares a different format or a newer schema than we
        // can read. A file with no marker (a pre-marker backup) is tolerated; the empty-content guard in
        // importJsonReplace stops a marker-less foreign file from wiping the store.
        (root["format"] as? JsonPrimitive)?.contentOrNull?.let {
            require(it == BackupFile.FORMAT) { "Not a Hexis backup (format='$it')." }
        }
        (root["version"] as? JsonPrimitive)?.intOrNull?.let {
            require(it <= BackupFile.VERSION) { "This backup was written by a newer version of the app (v$it)." }
        }
        return decoder.decodeFromJsonElement(BackupFile.serializer(), root)
    }
}
