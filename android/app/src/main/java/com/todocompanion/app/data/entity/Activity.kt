package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One entry in a task's private, on-device activity log — a lightweight audit trail
 * (created / completed / rescheduled / moved …). Never leaves the device; part of the
 * lossless backup so history survives an export/import round-trip.
 */
@Serializable
@Entity(tableName = "task_activity", indices = [Index("taskId")])
data class ActivityEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val type: String,        // created | completed | reopened | rescheduled | moved | trashed | restored | wontdo
    val at: Long,
    val detail: String? = null,
)
