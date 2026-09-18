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

    @Query("UPDATE sources SET hubUrl = :hubUrl WHERE id = :id")
    suspend fun setHubUrl(id: String, hubUrl: String?)

    /** Records the newest item date seen at the last successful parse (feed freshness verification).
     *  Only advances the stamp — never moves it backwards on a partial/stale re-fetch. */
    @Query("UPDATE sources SET latestItemAt = :latestItemAt WHERE id = :id AND (latestItemAt IS NULL OR latestItemAt < :latestItemAt)")
    suspend fun setLatestItemAt(id: String, latestItemAt: Long)

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

    @Query("UPDATE sources SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: String, muted: Boolean)

    @Query("UPDATE sources SET syncPaused = :paused WHERE id = :id")
    suspend fun setSyncPaused(id: String, paused: Boolean)

    @Query("UPDATE sources SET isPodcast = :enabled WHERE id = :id")
    suspend fun setPodcast(id: String, enabled: Boolean)

    @Query("UPDATE sources SET openIn = :mode WHERE id = :id")
    suspend fun setOpenIn(id: String, mode: String)

    @Query("UPDATE sources SET maxItems = :maxItems WHERE id = :id")
    suspend fun setMaxItems(id: String, maxItems: Int?)

    // ---- v21: Content Engine per-source knobs ----
    @Query("UPDATE sources SET acquisitionMode = :mode WHERE id = :id")
    suspend fun setAcquisitionMode(id: String, mode: String)

    @Query("UPDATE sources SET depthMode = :mode WHERE id = :id")
    suspend fun setDepthMode(id: String, mode: String)

    @Query("UPDATE sources SET offlineTier = :tier WHERE id = :id")
    suspend fun setOfflineTier(id: String, tier: String)

    /** Set backfill progress/state (the archive crawler owns these; UI reads them for progress). */
    @Query("UPDATE sources SET backfillState = :state, backfillCursor = :cursor, backfillDiscovered = :discovered, backfillDone = :done WHERE id = :id")
    suspend fun setBackfill(id: String, state: String, cursor: String?, discovered: Int, done: Int)

    @Query("UPDATE sources SET backfillState = :state WHERE id = :id")
    suspend fun setBackfillState(id: String, state: String)

    @Query("UPDATE sources SET contentHash = :hash, lastSyncedAt = :syncedAt, consecutiveErrors = 0 WHERE id = :id")
    suspend fun setContentHash(id: String, hash: String, syncedAt: Long)

    // Changing the feed URL resets sync bookkeeping so the new source is fetched fresh next sync.
    @Query("UPDATE sources SET feedUrl = :feedUrl, etag = NULL, lastModified = NULL, consecutiveErrors = 0, retryAfter = NULL WHERE id = :id")
    suspend fun setFeedUrl(id: String, feedUrl: String)

    @Query("SELECT DISTINCT folder FROM sources WHERE folder IS NOT NULL AND folder != '' ORDER BY folder COLLATE NOCASE")
    fun observeFolders(): Flow<List<String>>

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)
}
