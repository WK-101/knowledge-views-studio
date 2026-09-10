package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A first-class note (the Notes module's canonical object). A note is Markdown text with presence —
 * a title, an optional cover emoji and colour, pin/archive/trash lifecycle — and it can be woven into
 * the rest of the app: grouped under either the shared folder tree ([folderId]) OR a dedicated
 * [NotebookEntity] ([notebookId]), the choice made once in Settings (notesNotebookMode). It can also
 * link to a task ([linkedTaskId]) or event ([linkedEventId]), and — for a daily/journal note — carry
 * the day it belongs to ([dayEpoch]).
 *
 * Fully on-device. Body/title feed a raw-SQL `note_fts` index (mirroring `task_fts`); every column
 * rides the lossless JSON backup. Room's generated schema ignores the Kotlin defaults below, so the
 * v65→v66 migration creates each column with the matching affinity/nullability (no SQL DEFAULT on the
 * fresh CREATE TABLE, exactly like the time-tracking tables).
 */
@Serializable
@Entity(
    tableName = "notes",
    indices = [Index("notebookId"), Index("folderId"), Index("workspaceId"), Index("linkedTaskId")],
)
@androidx.compose.runtime.Immutable
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val body: String = "",                 // Markdown
    val notebookId: String? = null,        // grouping when notesNotebookMode == "notebooks"
    val folderId: String? = null,          // grouping when notesNotebookMode == "folderTree" (reuses FolderEntity)
    val colorArgb: Long? = null,
    val pinned: Boolean = false,
    val coverEmoji: String? = null,
    val kind: String = "note",             // note | journal | meeting
    val dayEpoch: Long? = null,            // for a journal/daily note — the day it belongs to
    val linkedTaskId: String? = null,
    val linkedEventId: String? = null,
    val sortOrder: Double = 0.0,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
)

/**
 * An optional dedicated notebook (a folder for notes) — used only when the user picks the "separate
 * notebooks" grouping in Settings. Nestable via [parentId], mirroring [FolderEntity]. When the user
 * instead reuses the folder tree, this table simply stays empty; notes are grouped by [NoteEntity.folderId].
 */
@Serializable
@Entity(tableName = "notebooks", indices = [Index("parentId"), Index("workspaceId")])
@androidx.compose.runtime.Immutable
data class NotebookEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
    val icon: String? = null,              // optional emoji
    val colorArgb: Long? = null,
    val sortOrder: Double = 0.0,
    val collapsed: Boolean = false,
    val archived: Boolean = false,
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
    val createdAt: Long = 0L,
)

/** Note ↔ Tag many-to-many (reuses the existing [TagEntity]). */
@Serializable
@Entity(tableName = "note_tags", primaryKeys = ["noteId", "tagId"], indices = [Index("tagId")])
data class NoteTagCrossRef(
    val noteId: String,
    val tagId: String,
)

/** Note ↔ Context many-to-many (reuses the existing [ContextEntity]). */
@Serializable
@Entity(tableName = "note_contexts", primaryKeys = ["noteId", "contextId"], indices = [Index("contextId")])
data class NoteContextCrossRef(
    val noteId: String,
    val contextId: String,
)

/** Lightweight projection (no body) for grid/list rendering — keeps the observed flow cheap. */
data class NoteMeta(
    val id: String,
    val title: String,
    val notebookId: String?,
    val folderId: String?,
    val colorArgb: Long?,
    val pinned: Boolean,
    val coverEmoji: String?,
    val kind: String,
    val archived: Boolean,
    val trashed: Boolean,
    val updatedAt: Long,
    val workspaceId: String,
)
