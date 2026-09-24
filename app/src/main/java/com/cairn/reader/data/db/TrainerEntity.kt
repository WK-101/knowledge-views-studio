package com.cairn.reader.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A single "intelligence" trainer signal (NewsBlur-style): the user thumbs a facet of a story up
 * (focus) or down (hide). Each facet — an author, a tag, a feed, or a title keyword — carries at most
 * one sentiment, so [kind] + [value] is unique. Scoring an item sums the sentiments of the facets it
 * matches (see `TrainingScorer`): a positive sum is a Focus story, negative is Hidden. Entirely
 * on-device; nothing about what you like or mute ever leaves the phone.
 */
@Entity(
    tableName = "trainers",
    indices = [Index(value = ["kind", "value"], unique = true)],
)
data class TrainerEntity(
    @PrimaryKey val id: String,
    /** AUTHOR | TAG | FEED | TITLE. */
    val kind: String,
    /** The match value: lower-cased text for AUTHOR/TAG/TITLE, the raw sourceId for FEED. */
    val value: String,
    /** +1 = like (focus), -1 = dislike (hide). */
    val sentiment: Int,
    val createdAt: Long,
)

@Dao
interface TrainerDao {
    @Query("SELECT * FROM trainers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TrainerEntity>>

    @Query("SELECT * FROM trainers")
    suspend fun getAll(): List<TrainerEntity>

    @Upsert
    suspend fun upsert(trainer: TrainerEntity)

    @Query("DELETE FROM trainers WHERE kind = :kind AND value = :value")
    suspend fun remove(kind: String, value: String)

    @Query("SELECT sentiment FROM trainers WHERE kind = :kind AND value = :value LIMIT 1")
    suspend fun sentimentFor(kind: String, value: String): Int?

    @Query("SELECT COUNT(*) FROM trainers")
    fun observeCount(): Flow<Int>
}
