package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A habit to build (TickTick-style): checked off per day, with streaks. */
@Serializable
@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val targetPerDay: Int = 1,
    val unit: String? = null,            // e.g. "glasses", "pages", "min" — shown with the count
    val scheduleDays: String = "",       // day-of-week values 1..7 (Mon..Sun), comma-separated; "" = every day
    val reminderTimes: String = "",      // minutes-from-midnight, comma-separated (e.g. "540,1080" = 9:00, 18:00)
    val sortOrder: Double = 0.0,
    val archived: Boolean = false,
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
    val createdAt: Long,
)

/** One day's progress for a habit. [epochDay] is the local date; [count] is completions that day. */
@Serializable
@Entity(tableName = "habit_checkins", primaryKeys = ["habitId", "epochDay"], indices = [Index("habitId")])
data class HabitCheckinEntity(
    val habitId: String,
    val epochDay: Long,
    val count: Int = 1,
)

/** A completed focus (Pomodoro / stopwatch) session, for the focus tab + statistics. */
@Serializable
@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val epochDay: Long,
    val startMillis: Long,
    val minutes: Int,
    val kind: String = "pomo",
    val taskId: String? = null,   // the task this focus session was spent on, if any
)
