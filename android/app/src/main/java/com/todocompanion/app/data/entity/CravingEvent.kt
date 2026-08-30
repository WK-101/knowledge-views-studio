package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * R33 (Habit Builder F13/F14) — a logged URGE for a break/quit habit: the moment a craving hit, how
 * strong it was, what triggered it, and whether you rode it out (urge surfing) or gave in. Fully offline;
 * powers the private urge diary + trigger heatmap and is part of the lossless JSON backup.
 */
@Serializable
@Entity(tableName = "craving_events", indices = [Index("habitId")])
data class CravingEventEntity(
    @PrimaryKey val id: String,
    val habitId: String,
    val atMillis: Long,
    val epochDay: Long,
    val minuteOfDay: Int,           // 0..1439 — for the time-of-day trigger heatmap
    val intensity: Int,             // 1..5 how strong the urge felt
    val trigger: String = "",       // free text: "stress", "after dinner", "saw an ad"
    val surfed: Boolean = true,     // true = rode it out; false = gave in (a slip)
    val note: String = "",
)
