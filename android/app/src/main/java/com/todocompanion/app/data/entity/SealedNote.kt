package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * R32 — a "letter to your future self". Written now, sealed with a tamper-evident hash of its own words,
 * and revealed on a chosen future date beside a diff of everything you actually accomplished since. Fully
 * offline; part of the lossless JSON backup.
 */
@Serializable
@Entity(tableName = "sealed_notes")
data class SealedNoteEntity(
    @PrimaryKey val id: String,
    val createdEpochDay: Long,       // when it was sealed
    val revealEpochDay: Long,        // when it becomes readable + the "since then" diff is drawn
    val title: String,
    val body: String,
    val anchorHash: String,          // sha-256 of "body|createdEpochDay" — proves it wasn't edited after sealing
    val sealedCount: Int,            // number of accomplishments at seal time, for the since-then delta
    val acknowledged: Boolean = false, // the user has opened it after reveal (so it stops nagging)
)
