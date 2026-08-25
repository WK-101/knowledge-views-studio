package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A file attached to a task. The bytes live in the database as Base64 so that a
 * JSON backup remains fully lossless (an attachment is just another entity) and
 * nothing ever leaves the device. Kept small by an import cap in the repository.
 */
@Serializable
@Entity(tableName = "attachments", indices = [Index("taskId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val isImage: Boolean,
    val addedAt: Long,
    // Bytes are stored EITHER inline as Base64 (legacy / imported) OR in an app-private file at
    // [filePath] (F4 — keeps the DB and sync snapshots lean). Exactly one is populated.
    val contentBase64: String,
    val filePath: String? = null,
)

/** Lightweight projection (no bytes) for list rendering — keeps the observed flow cheap. */
data class AttachmentMeta(
    val id: String,
    val taskId: String,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val isImage: Boolean,
    val addedAt: Long,
)
