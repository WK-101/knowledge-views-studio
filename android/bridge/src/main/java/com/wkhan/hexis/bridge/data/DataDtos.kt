package com.wkhan.hexis.bridge.data

import kotlinx.serialization.Serializable

/**
 * The typed, transport-stable shapes the `data` capability exchanges. These are a deliberate, minimal
 * projection of the core's entities — the public contract a consumer (the web UI) can rely on — not the
 * internal Room entities. The core maps entity → DTO; adding a field here is additive (tolerant decoding).
 */

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val listId: String? = null,
    val parentId: String? = null,
    val completed: Boolean = false,
    val dueDate: Long? = null,
    val startDate: Long? = null,
    val importance: Int = 2,
    val urgency: Int = 2,
    val star: Boolean = false,
    val sortOrder: Double = 0.0,
    val note: String? = null,
    val updatedAt: Long = 0L,
)

@Serializable
data class NoteDto(
    val id: String,
    val title: String = "",
    val body: String = "",
    val notebookId: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val updatedAt: Long = 0L,
)

/** A calendar event, projected for display. [calendarName] is resolved from the owning calendar. */
@Serializable
data class EventDto(
    val id: String,
    val title: String,
    val calendarId: String,
    val calendarName: String = "",
    val location: String = "",
    val notes: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val allDay: Boolean = false,
    val recurring: Boolean = false,
    val colorArgb: Long? = null,
    val linkedTaskId: String? = null,
)

/** A time-tracking entry. [endMillis] null = still running; [minutes] is the resolved duration. */
@Serializable
data class TimeEntryDto(
    val id: String,
    val activityId: String,
    val activityName: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long? = null,
    val minutes: Int = 0,
    val note: String = "",
    val kind: String = "manual",
    val running: Boolean = false,
)

/** A habit, with today's progress folded in ([doneToday] / [todayCount] against [targetPerDay]). */
@Serializable
data class HabitDto(
    val id: String,
    val name: String,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val targetPerDay: Int = 1,
    val unit: String? = null,
    val habitType: String = "build",
    val category: String = "",
    val paused: Boolean = false,
    val todayCount: Int = 0,
    val doneToday: Boolean = false,
)
