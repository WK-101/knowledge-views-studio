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
    // Wave B (v67): a favourite star (independent of pin), a read-only lock, and richer trash bookkeeping
    // (deletedAt drives the auto-empty-trash sweep; deletedBy distinguishes user vs. app vs. expiry).
    val favorite: Boolean = false,
    val readonly: Boolean = false,
    val deletedAt: Long? = null,
    val deletedBy: String? = null,      // user | app | expired
)

/**
 * Wave B (v67) — a local version snapshot of a note, captured per editing session. Fully on-device,
 * no network. [charDelta] is the cheap change magnitude vs. the previous snapshot (Standard Notes'
 * session-history model); the editor's history timeline shows it and restores any snapshot. Pruned to
 * the user's "keep versions" setting so storage stays bounded.
 */
@Serializable
@Entity(tableName = "note_revisions", indices = [Index("noteId")])
@androidx.compose.runtime.Immutable
data class NoteRevisionEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val createdAt: Long,
    val title: String,
    val body: String,
    val charDelta: Int,
)

/**
 * Wave C — the connective-tissue edge that makes Notes a first-class citizen of the whole app. A
 * `[[wiki-link]]` in a note body is materialized (on save) into one row here, resolved to whatever the
 * title names: another note, a task, a habit, or an event. This is the "limited incremental cross-ref"
 * (per the data-model decision) — scoped to note→entity edges, not a fully generic relations graph.
 * It powers backlink panels on tasks/habits/events ("Notes about this") and the on-device life graph.
 * [targetId] is "" when the title matches nothing yet (an unresolved link, ready to become a new note).
 */
@Serializable
@Entity(
    tableName = "note_links",
    primaryKeys = ["noteId", "targetTitle"],
    indices = [Index("targetType", "targetId"), Index("noteId")],
)
data class NoteLinkEntity(
    val noteId: String,
    val targetTitle: String,
    val targetType: String,   // note | task | habit | event
    val targetId: String,
)

/**
 * Wave D — a Smart View: a saved, dynamic filter over notes (Standard Notes' predicate model). The
 * filter itself is a [com.todocompanion.app.domain.NotePredicate] serialized into [predicateJson]; the
 * app evaluates it on the fly, so a view always reflects the current notes.
 */
@Serializable
@Entity(tableName = "smart_views", indices = [Index("workspaceId")])
@androidx.compose.runtime.Immutable
data class SmartViewEntity(
    @PrimaryKey val id: String,
    val title: String,
    val icon: String? = null,
    val predicateJson: String,
    val sortOrder: Double = 0.0,
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
    val createdAt: Long = 0L,
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
