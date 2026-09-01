package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A reusable task template: a whole task subtree (note, priority, flag, recurrence, checklist,
 * tags, contexts and relative dates) frozen into JSON, ready to drop into any list. Dates are
 * stored as day-offsets so "due in 3 days" stays relative whenever the template is used.
 *
 * Part of the lossless export contract.
 */
@Serializable
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val payloadJson: String,
    val createdAt: Long,
    // R62 — the workspace this template belongs to; templates are fully isolated per workspace.
    val workspaceId: String = "default",
)

/** One node of a template subtree. Tags/contexts are stored by name so they re-resolve on use. */
@Serializable
data class TemplateTask(
    val title: String,
    val note: String = "",
    val isNote: Boolean = false,
    val importance: Int = 3,
    val urgency: Int = 3,
    val flagId: String? = null,
    val flagColorArgb: Long? = null,
    val durationMin: Int? = null,
    val estimateMin: Int? = null,
    val leadTimeMin: Int? = null,
    val completeInOrder: Boolean = false,
    val isProject: Boolean = false,
    val isGoal: Boolean = false,
    val rrule: String? = null,
    val recurrenceMode: String? = null,
    val startOffsetDays: Int? = null,   // start = instantiation day + this
    val dueOffsetDays: Int? = null,     // due   = instantiation day + this
    val tagNames: List<String> = emptyList(),
    val contextNames: List<String> = emptyList(),
    val checklist: List<String> = emptyList(),
    val children: List<TemplateTask> = emptyList(),
)
