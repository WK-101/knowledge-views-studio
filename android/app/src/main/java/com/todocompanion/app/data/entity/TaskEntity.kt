package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * The core task. Self-referencing tree via [parentId] gives unlimited nesting
 * (MyLifeOrganized-style outline). All time fields are epoch millis (UTC).
 *
 * Every field here is part of the lossless export contract — do not drop fields
 * without a format version bump.
 */
@Serializable
@Entity(tableName = "tasks", indices = [Index("parentId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val sortOrder: Double = 0.0,

    val title: String,
    val note: String = "",

    val completed: Boolean = false,
    val completedAt: Long? = null,

    // Priority source of truth. Simple 4-level UI maps onto these; advanced UI sets them directly.
    val importance: Int = 3,   // 1..5
    val urgency: Int = 3,      // 1..5

    val startDate: Long? = null,
    val dueDate: Long? = null,

    val durationMin: Int? = null,
    val estimateMin: Int? = null,
    val estimateMax: Int? = null,
    val leadTimeMin: Int? = null,

    // Gating flags for the computed To-Do (Do Next) list.
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

    // Recurrence (engine lands in Phase 2; stored now for round-trip fidelity).
    val rrule: String? = null,
    val recurrenceMode: String? = null, // "fromDue" | "fromCompletion"

    val collapsed: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long,
)
