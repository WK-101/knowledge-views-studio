package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A separate space (MLO-style) — its own folders, lists and tasks. */
@Serializable
@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Double = 0.0,
) {
    companion object {
        const val DEFAULT_ID = "default"
    }
}

/** A folder groups lists (and other folders) in the sidebar. Nestable via [parentId]. */
@Serializable
@Entity(tableName = "folders", indices = [Index("parentId")])
@androidx.compose.runtime.Immutable
data class FolderEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
    val icon: String? = null,   // optional emoji
    val sortOrder: Double = 0.0,
    val collapsed: Boolean = false,
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
    val description: String = "",   // optional free-text note, shown atop the folder's tasks
    val archived: Boolean = false,  // R52 — stow an inactive folder (and its lists) without deleting
)

/** A list / project: the primary container that holds a task outline.
 *  Lists can be nested under other lists via [parentListId] (in addition to living in a folder). */
@Serializable
@Entity(tableName = "lists", indices = [Index("folderId"), Index("parentListId")])
@androidx.compose.runtime.Immutable
data class ListEntity(
    @PrimaryKey val id: String,
    val folderId: String? = null,
    val parentListId: String? = null,
    val name: String,
    val colorArgb: Long? = null,
    val emoji: String? = null,
    val sortOrder: Double = 0.0,
    val viewMode: String = "list", // "list" | "outline"
    val archived: Boolean = false,
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
    val backgroundBase64: String? = null,   // optional embedded background image (JPEG), shown faintly behind the list
    val description: String = "",   // optional free-text note, shown atop the list's tasks
) {
    companion object {
        /** Well-known id of the default Inbox list. */
        const val INBOX_ID = "inbox"
    }
}

/** A flat checklist item under a task (quick ticks, distinct from subtasks). */
@Serializable
@Entity(tableName = "checklist_items", indices = [Index("taskId")])
data class ChecklistItemEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val sortOrder: Double = 0.0,
    val text: String,
    val checked: Boolean = false,
)
