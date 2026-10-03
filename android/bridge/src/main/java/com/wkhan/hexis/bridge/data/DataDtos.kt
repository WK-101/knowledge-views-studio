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
