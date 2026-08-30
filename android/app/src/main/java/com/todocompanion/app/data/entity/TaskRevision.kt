package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A point-in-time snapshot of a task's meaningful fields — the backbone of "task time-travel"
 * (H5). Recorded sparsely (deduped by content signature, throttled) as you edit, so you can look
 * back at how a task read hours or days ago and restore any earlier version. On-device only;
 * capped per task so history never grows without bound. R37: included in the lossless backup so a
 * restore preserves your edit history too.
 */
@Serializable
@Entity(tableName = "task_revisions", indices = [Index("taskId")])
data class TaskRevisionEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val at: Long,
    // The full TaskEntity serialized as JSON at the moment of capture.
    val snapshotJson: String,
    // A short human label of what stands out in this version (title, or "Completed", etc.).
    val label: String = "",
)
