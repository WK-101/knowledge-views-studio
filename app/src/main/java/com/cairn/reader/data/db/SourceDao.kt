package com.cairn.reader.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources ORDER BY sortOrder, title COLLATE NOCASE")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE feedUrl = :feedUrl")
    suspend fun getByFeedUrl(feedUrl: String): SourceEntity?

    @Query("UPDATE sources SET etag = :etag, lastModified = :lastModified, lastSyncedAt = :syncedAt, consecutiveErrors = 0 WHERE id = :id")
    suspend fun markSynced(id: String, etag: String?, lastModified: String?, syncedAt: Long)

    @Query("UPDATE sources SET consecutiveErrors = consecutiveErrors + 1, retryAfter = :retryAfter WHERE id = :id")
    suspend fun markError(id: String, retryAfter: Long?)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)
}
