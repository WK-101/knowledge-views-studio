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
    // R52 — indices on the columns the app filters/sorts by most, so queries stay fast as the table grows
    // into the tens of thousands over years of use (see the scale plan). All additive.
    indices = [
        Index("parentId"), Index("listId"), Index("folderId"), Index("workspaceId"),
        Index("completed"), Index("trashed"), Index("someday"), Index("dueDate"),
        // R57 (Wave B / index audit) — composites for the hottest WHERE combinations (workspace scoping,
        // completed/trash counts). Additive; SQLite picks them for the DB-side aggregates.
        Index("workspaceId", "trashed"), Index("completed", "trashed"),
    ],
)
@androidx.compose.runtime.Immutable
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
    // R52 — GTD Someday/Maybe: parked, uncommitted work. Kept OUT of Today/Do-Next/Next-7/Scheduled,
    // overdue and workload; surfaced only in its own Someday list and the weekly review.
    val someday: Boolean = false,

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

    // The Done Record (R27): additive completion metadata that turns a finished task from a dead archive
    // line into a record worth reading back. All optional, off by default, and part of the lossless export.
    val outcomeNote: String? = null,   // what came of it / the impact — the brag-doc line
    val winFlag: Boolean = false,      // "this was a win" — feeds the Trophy case + brag doc
    val learnedNote: String? = null,   // a lesson captured on finishing — the learnings log
    val praiseQuote: String? = null,   // a compliment / thank-you pinned to this work
    val mood: Int? = null,             // how it felt to finish: 1=rough … 5=great (null = unrated)
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

    // R37 — habit-science ports to tasks. valueId links a project/task to a core value ("living your
    // values" counts real work). deferCount/lastDeferDay drive the "never defer twice" deferral-chain
    // counter: how many days running this task's due date has been pushed forward.
    val valueId: String? = null,
    val deferCount: Int = 0,
    val lastDeferDay: Long = 0,

    // R28 #3 — the workspace that owns this task's trash. Workspaces share only the Inbox; everything else
    // (including the Trash) is independent, so a trashed task is scoped to the workspace it was deleted in
    // rather than leaking across all of them via the shared Inbox. Backfilled from the task's list/folder.
    val workspaceId: String = "default",

    val createdAt: Long,
    val updatedAt: Long,
)
