package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Lightweight grouping label (TickTick-style). Nestable via [parentId]. */
@Serializable
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long? = null,
    val parentId: String? = null,
    val sortOrder: Double = 0.0,
    // Tags are scoped to a workspace so switching workspaces hides the other space's labels.
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
)

/** Task ↔ Tag many-to-many. */
@Serializable
@Entity(tableName = "task_tags", primaryKeys = ["taskId", "tagId"], indices = [Index("tagId")])
data class TaskTagCrossRef(
    val taskId: String,
    val tagId: String,
)

/**
 * GTD context (MLO-style): availability-aware. A task only surfaces in the
 * Do-Next list when its context is "available" (active + within open-hours /
 * geofence). Hierarchical via [parentId].
 */
@Serializable
@Entity(tableName = "contexts")
data class ContextEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
    val icon: String? = null,
    val colorArgb: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusM: Double? = null,
    val openHoursJson: String? = null, // reserved for Phase 2 availability windows
    val active: Boolean = true,
    val sortOrder: Double = 0.0,
    // Contexts are scoped to a workspace so switching workspaces hides the other space's contexts.
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
)

/** Task ↔ Context many-to-many. */
@Serializable
@Entity(tableName = "task_contexts", primaryKeys = ["taskId", "contextId"], indices = [Index("contextId")])
data class TaskContextCrossRef(
    val taskId: String,
    val contextId: String,
)

/** A reminder attached to a task. Time-based in Phase 1; location fields reserved for Phase 2. */
@Serializable
@Entity(tableName = "reminders", indices = [Index("taskId")])
data class ReminderEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val type: String = "absolute", // absolute | relativeToDue | relativeToStart | location
    val atTime: Long? = null,       // for absolute reminders (epoch millis)
    val offsetMin: Int? = null,     // for relative reminders (minutes before)
    val contextId: String? = null,  // optional context this location reminder borrows coords from
    // Location reminders: fire on entering/leaving a radius around a point. All local (framework
    // proximity alerts) — no Play Services, no INTERNET.
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusM: Double? = null,
    val placeName: String? = null,
    val onEnter: Boolean = true,    // true = arrive, false = leave
    val annoying: Boolean = false,
    val escalate: Boolean = false,  // keep re-alerting until acknowledged
    val tone: String? = null,
)

/** Task → task dependency. A task blocked by an incomplete predecessor drops out of Do-Next. */
@Serializable
@Entity(tableName = "dependencies", primaryKeys = ["taskId", "dependsOnTaskId"], indices = [Index("dependsOnTaskId")])
data class DependencyEntity(
    val taskId: String,
    val dependsOnTaskId: String,
    val mode: String = "AND", // AND | OR
    val delayDays: Int = 0,   // activate this many days after the anchor prerequisite completes
)

/** Key/value app settings, persisted locally. */
@Serializable
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** A saved custom filter (smart list): a name + a serialized [FilterQuery] in [queryJson]. */
@Serializable
@Entity(tableName = "filters")
data class FilterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Double = 0.0,
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
    val queryJson: String = "",
    val colorArgb: Long? = null,
)
