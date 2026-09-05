package com.cairn.reader.data.db

import androidx.room.Dao
import androidx.room.Query

/** A source's engagement/health snapshot for the feed-hygiene report. */
data class SourceHealth(
    val id: String,
    val title: String,
    val itemCount: Int,
    val readCount: Int,
    val errors: Int,
    val lastSyncedAt: Long?,
)

/** A source's total engagement weight (reads + stars + saves), for focus scoring. */
data class SourceEngagement(val sourceId: String, val weight: Int)

/** A read/starred/saved item's title, for building the on-device interest profile. */
data class EngagedTitle(val title: String)

/**
 * Read-only aggregate queries powering the private, on-device Insights surface (reading analytics +
 * feed-hygiene report) and the passive Focus scorer. Nothing here leaves the device.
 */
@Dao
interface InsightsDao {

    @Query("SELECT COUNT(*) FROM item_states WHERE isRead = 1")
    suspend fun readCount(): Int

    @Query("SELECT COUNT(*) FROM item_states WHERE isStarred = 1")
    suspend fun starredCount(): Int

    @Query("SELECT COUNT(*) FROM item_states WHERE isReadLater = 1")
    suspend fun savedCount(): Int

    /** Total minutes of reading across everything marked read (each item's estimated length). */
    @Query("SELECT COALESCE(SUM(i.readingMinutes), 0) FROM items i JOIN item_states s ON s.itemId = i.id WHERE s.isRead = 1")
    suspend fun readMinutesSum(): Int

    /** Articles opened (last read) since a timestamp — for "this week" and streak-style stats. */
    @Query("SELECT COUNT(*) FROM item_states WHERE lastReadAt IS NOT NULL AND lastReadAt >= :since")
    suspend fun readSince(since: Long): Int

    /** Distinct calendar-day timestamps you read on, newest first — for a reading streak. */
    @Query("SELECT DISTINCT lastReadAt FROM item_states WHERE lastReadAt IS NOT NULL ORDER BY lastReadAt DESC LIMIT 400")
    suspend fun readTimestamps(): List<Long>

    /** Top sources by number of read articles. */
    @Query(
        """
        SELECT src.title AS title, COUNT(*) AS c
        FROM items i JOIN item_states s ON s.itemId = i.id JOIN sources src ON src.id = i.sourceId
        WHERE s.isRead = 1
        GROUP BY i.sourceId ORDER BY c DESC LIMIT :limit
        """
    )
    suspend fun topReadSources(limit: Int): List<TitleCount>

    /** Per-source health: how many items, how many read, error streak, last sync. */
    @Query(
        """
        SELECT src.id AS id, src.title AS title,
               (SELECT COUNT(*) FROM items WHERE sourceId = src.id AND trashedAt IS NULL) AS itemCount,
               (SELECT COUNT(*) FROM items i2 JOIN item_states s2 ON s2.itemId = i2.id WHERE i2.sourceId = src.id AND s2.isRead = 1) AS readCount,
               src.consecutiveErrors AS errors, src.lastSyncedAt AS lastSyncedAt
        FROM sources src ORDER BY src.title COLLATE NOCASE ASC
        """
    )
    suspend fun sourceHealth(): List<SourceHealth>

    /** Engagement weight per source (a read/star/save each counts once). */
    @Query(
        """
        SELECT i.sourceId AS sourceId,
               SUM((s.isRead = 1) + (s.isStarred = 1) + (s.isReadLater = 1)) AS weight
        FROM items i JOIN item_states s ON s.itemId = i.id
        WHERE i.sourceId IS NOT NULL AND (s.isRead = 1 OR s.isStarred = 1 OR s.isReadLater = 1)
        GROUP BY i.sourceId
        """
    )
    suspend fun sourceEngagement(): List<SourceEngagement>

    /** Titles of engaged (read/starred/saved) items, newest first — the interest-term corpus. */
    @Query(
        """
        SELECT i.title AS title FROM items i JOIN item_states s ON s.itemId = i.id
        WHERE (s.isRead = 1 OR s.isStarred = 1 OR s.isReadLater = 1)
        ORDER BY s.updatedAt DESC LIMIT :limit
        """
    )
    suspend fun engagedTitles(limit: Int): List<EngagedTitle>
}

/** A generic (title, count) projection for "top" lists. */
data class TitleCount(val title: String, val c: Int)
