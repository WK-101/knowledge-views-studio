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

    @Query("UPDATE sources SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)

    @Query("UPDATE sources SET folder = :folder WHERE id = :id")
    suspend fun setFolder(id: String, folder: String?)

    @Query("UPDATE sources SET fullTextByDefault = :enabled WHERE id = :id")
    suspend fun setFullText(id: String, enabled: Boolean)

    @Query("UPDATE sources SET notify = :enabled WHERE id = :id")
    suspend fun setNotify(id: String, enabled: Boolean)

    @Query("UPDATE sources SET isPodcast = :enabled WHERE id = :id")
    suspend fun setPodcast(id: String, enabled: Boolean)

    @Query("SELECT DISTINCT folder FROM sources WHERE folder IS NOT NULL AND folder != '' ORDER BY folder COLLATE NOCASE")
    fun observeFolders(): Flow<List<String>>

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)
}
