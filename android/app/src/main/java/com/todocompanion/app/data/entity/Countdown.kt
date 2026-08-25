package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A countdown to (or up from) an important date — TickTick-style. Shows "N days left" at a glance
 * and can be pinned to a home-screen widget. Entirely offline; part of the lossless backup.
 */
@Serializable
@Entity(tableName = "countdowns")
data class CountdownEntity(
    @PrimaryKey val id: String,
    val title: String,
    val targetMillis: Long,      // the date being counted to (or from, if in the past)
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val pinned: Boolean = false, // surfaced on the widget
    val sortOrder: Double = 0.0,
    val createdAt: Long,
)
