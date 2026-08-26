package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Tier S — time tracking. A [TimeActivityEntity] is a named thing you spend time on ("Deep work",
 * "Reading", "Exercise"); a [TimeEntryEntity] is one recorded interval of it. Inspired by
 * Simple Time Tracker (Razeeman, GPL-3.0) but rebuilt from scratch for this app's offline store —
 * no code copied, no network, no account.
 *
 * Design discipline: exactly one timer runs at a time (a running entry has endMillis == null), which
 * keeps the model, the UI and the daily-total maths honest. Entries can also be added or edited
 * retroactively. Both tables serialise into the lossless JSON backup.
 */
@Serializable
@Entity(tableName = "time_activities")
data class TimeActivityEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val archived: Boolean = false,
    val sortOrder: Double = 0.0,
    val createdAt: Long,
    // Tier T4: an optional per-activity time goal, computed as a fold over intervals (never a stored
    // counter). 0 = no goal. goalDays is a CSV of ISO weekdays (1=Mon..7=Sun) the goal applies to;
    // "" = every day. Progress is summed minutes tracked to this activity on a goal day.
    val goalMinutesPerDay: Int = 0,
    val goalDays: String = "",
)

@Serializable
@Entity(tableName = "time_entries")
data class TimeEntryEntity(
    @PrimaryKey val id: String,
    val activityId: String,
    val startMillis: Long,
    val endMillis: Long? = null,   // null ⇒ currently running
    val note: String = "",
    // Optional links back into the two halves of the store — track time against a task or a habit.
    val taskId: String? = null,
    val habitId: String? = null,
    val createdAt: Long = 0,
    // Tier T1 (invariant I1): where this interval originated. "manual" (the tracker) or "focus" (mirrored
    // from a Focus/Pomodoro session so the tracker timeline is the single source of truth for "time").
    val kind: String = "manual",
) {
    val running: Boolean get() = endMillis == null
    /** Elapsed minutes, clamped to a floor of 0. For a running entry, pass [nowMillis]. */
    fun minutes(nowMillis: Long): Int = (((endMillis ?: nowMillis) - startMillis) / 60_000L).toInt().coerceAtLeast(0)
}
