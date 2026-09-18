package com.cairn.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Queue operations for the deep-archive crawl frontier (Content Engine P3). */
@Dao
interface CrawlFrontierDao {

    /** Add discovered URLs. IGNORE on conflict so re-enumeration never resets an already-FETCHED row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(rows: List<CrawlFrontierEntity>)

    /** The next batch to fetch, newest-first (most recent content backfills first). */
    @Query("SELECT * FROM crawl_frontier WHERE state = 'PENDING' ORDER BY lastmod DESC, addedAt ASC LIMIT :limit")
    suspend fun nextPending(limit: Int): List<CrawlFrontierEntity>

    @Query("UPDATE crawl_frontier SET state = :state, attempts = attempts + 1 WHERE sourceId = :sourceId AND url = :url")
    suspend fun mark(sourceId: String, url: String, state: String)

    @Query("SELECT COUNT(*) FROM crawl_frontier WHERE sourceId = :sourceId AND state = 'PENDING'")
    suspend fun pendingCount(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM crawl_frontier WHERE state = 'PENDING'")
    suspend fun totalPending(): Int

    @Query("SELECT COUNT(*) FROM crawl_frontier WHERE sourceId = :sourceId")
    suspend fun totalForSource(sourceId: String): Int

    /** Distinct sources that still have work queued — the worker walks these to finalize state. */
    @Query("SELECT DISTINCT sourceId FROM crawl_frontier WHERE state = 'PENDING'")
    suspend fun sourcesWithPending(): List<String>

    @Query("DELETE FROM crawl_frontier WHERE sourceId = :sourceId")
    suspend fun clearForSource(sourceId: String)

    @Query("SELECT COUNT(*) FROM crawl_frontier WHERE state = 'PENDING'")
    fun observePending(): Flow<Int>
}
