package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * The core task. Belongs to one List ([listId]); nests within that list via [parentId]
 * (MyLifeOrganized-style unlimited outline). Time fields are epoch millis.
 *
 * Every field is part of the lossless export contract.
 */
@Serializable
@Entity(
    tableName = "tasks",
    indices = [Index("parentId"), Index("listId")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val parentId: String? = null,
    val sortOrder: Double = 0.0,

    val title: String,
    val note: String = "",

    // Lifecycle
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val abandoned: Boolean = false,   // "Won't Do"
    val trashed: Boolean = false,
    val trashedAt: Long? = null,

    // Priority (importance + urgency are the stored source of truth)
    val importance: Int = 3,
    val urgency: Int = 3,

    val startDate: Long? = null,
    val dueDate: Long? = null,
    val isAllDay: Boolean = false,

    val durationMin: Int? = null,
    val estimateMin: Int? = null,
    val estimateMax: Int? = null,
    val leadTimeMin: Int? = null,

    val hideInTodoUntilStart: Boolean = true,
    val hideInTodoIfBlocked: Boolean = true,

    val star: Boolean = false,
    val starAt: Long? = null,
    val flagColorArgb: Long? = null,

    val isGoal: Boolean = false,
    val isProject: Boolean = false,
    val reviewEveryDays: Int? = null,
    val completeInOrder: Boolean = false,

    val colorArgb: Long? = null,

    val rrule: String? = null,
    val recurrenceMode: String? = null,

    val collapsed: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long,
)
