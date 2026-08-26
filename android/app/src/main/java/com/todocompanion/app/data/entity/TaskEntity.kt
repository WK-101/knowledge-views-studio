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
    // A task captured straight into a folder (no list) carries the folder here and an empty listId.
    // Normal list tasks leave this null. Set exclusively of a real listId.
    val folderId: String? = null,
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
    // A hard deadline, distinct from the plan/due date: due = when you'll do it, deadline = when it's
    // actually due. Approaching deadlines raise the computed urgency.
    val deadlineDate: Long? = null,
    val isAllDay: Boolean = false,

    // Energy required: 1 = low, 2 = medium, 3 = high (null = unset). Powers "right now" surfacing.
    val energy: Int? = null,

    val durationMin: Int? = null,
    val estimateMin: Int? = null,
    val estimateMax: Int? = null,
    val leadTimeMin: Int? = null,

    val hideInTodoUntilStart: Boolean = true,
    val hideInTodoIfBlocked: Boolean = true,

    val star: Boolean = false,
    val starAt: Long? = null,
    val flagId: String? = null,            // the assigned FlagEntity (source of truth)
    val flagColorArgb: Long? = null,       // cache of the flag's colour, for row rendering

    val pinned: Boolean = false,
    val isNote: Boolean = false,   // a "note" is a task with no checkbox

    val progressPct: Int? = null,   // manual completion 0..100 for a leaf task (null = none / use rollup)
    val isGoal: Boolean = false,
    val isProject: Boolean = false,
    // Q2: the habit "identity/why" + reward vocabulary, on goals & projects. whyText shows when you open
    // the goal (the reason it matters); rewardText fires as a celebration when the goal is completed.
    val whyText: String = "",
    val rewardText: String = "",
    // Tier T2: the time-activity this task tracks under by default, so "Start timer" is one tap. Nullable
    // link — absent (and invisible) unless the Time module is used.
    val defaultActivityId: String? = null,
    val reviewEveryDays: Int? = null,
    val reviewedAt: Long? = null,
    val completeInOrder: Boolean = false,

    val colorArgb: Long? = null,

    val rrule: String? = null,
    val recurrenceMode: String? = null,

    val collapsed: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long,
)
