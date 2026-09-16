package com.cairn.reader.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Upsert
    suspend fun upsert(transcript: TranscriptEntity)

    @Query("SELECT * FROM transcripts WHERE itemId = :itemId")
    suspend fun get(itemId: String): TranscriptEntity?

    /** Live "is the whole transcript saved?" flag, for the toggle in the transcript screen. */
    @Query("SELECT EXISTS(SELECT 1 FROM transcripts WHERE itemId = :itemId)")
    fun observeSaved(itemId: String): Flow<Boolean>

    @Query("DELETE FROM transcripts WHERE itemId = :itemId")
    suspend fun delete(itemId: String)

    @Query("SELECT COUNT(*) FROM transcripts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM transcripts")
    suspend fun all(): List<TranscriptEntity>
}
