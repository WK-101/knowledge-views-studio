package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A folder groups lists (and other folders) in the sidebar. Nestable via [parentId]. */
@Serializable
@Entity(tableName = "folders", indices = [Index("parentId")])
data class FolderEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
    val sortOrder: Double = 0.0,
    val collapsed: Boolean = false,
)

/** A list / project: the primary container that holds a task outline. */
@Serializable
@Entity(tableName = "lists", indices = [Index("folderId")])
data class ListEntity(
    @PrimaryKey val id: String,
    val folderId: String? = null,
    val name: String,
    val colorArgb: Long? = null,
    val emoji: String? = null,
    val sortOrder: Double = 0.0,
    val viewMode: String = "list", // "list" | "outline"
    val archived: Boolean = false,
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
