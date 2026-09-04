package com.cairn.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Flat projection for list screens — joins item + mutable state + source title. */
data class ItemListRow(
    val id: String,
    val url: String,
    val title: String,
    val author: String?,
    val siteName: String?,
    val sourceTitle: String?,
    val excerpt: String?,
    val leadImage: String?,
    val publishedAt: Long?,
    val savedAt: Long,
    val readingMinutes: Int,
    val extractStatus: String,
    val type: String,
    val isRead: Boolean,
    val isStarred: Boolean,
    val isReadLater: Boolean,
    val isArchived: Boolean,
)

/** A feed with its current unread count, for the navigation drawer. */
data class FeedUnread(
    val sourceId: String,
    val title: String,
    val folder: String?,
    val unread: Int,
)

/** Live counts for the library's system scopes (Raindrop shows a count on each). */
data class LibraryCounts(
    val allCount: Int,
    val unsortedCount: Int,
    val favoritesCount: Int,
    val archiveCount: Int,
)

@Dao
interface ItemDao {

    @Upsert
    suspend fun upsertItem(item: ItemEntity)

    @Upsert
    suspend fun upsertState(state: ItemStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStateIfAbsent(state: ItemStateEntity)

    @Transaction
    suspend fun insertItemWithState(item: ItemEntity, now: Long) {
        upsertItem(item)
        insertStateIfAbsent(ItemStateEntity(itemId = item.id, updatedAt = now))
    }

    @Query("SELECT COUNT(*) FROM items WHERE guid = :guid AND sourceId = :sourceId")
    suspend fun countByGuid(guid: String, sourceId: String): Int

    // -- Streams ---------------------------------------------------------------

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE COALESCE(s.isArchived, 0) = 0 AND COALESCE(s.isRead, 0) = 0
          AND (:sourceId IS NULL OR i.sourceId = :sourceId)
          AND (:folder IS NULL OR src.folder = :folder)
        ORDER BY COALESCE(i.publishedAt, i.savedAt) DESC
        """
    )
    fun observeInbox(sourceId: String?, folder: String?): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE COALESCE(s.isStarred, 0) = 1 OR COALESCE(s.isArchived, 0) = 1 OR COALESCE(s.isReadLater, 0) = 1
        ORDER BY i.savedAt DESC
        """
    )
    fun observeLibrary(): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE COALESCE(s.isArchived, 0) = 0 AND COALESCE(s.isReadLater, 0) = 1
          AND (:sourceId IS NULL OR i.sourceId = :sourceId)
          AND (:folder IS NULL OR src.folder = :folder)
        ORDER BY COALESCE(i.publishedAt, i.savedAt) DESC
        """
    )
    fun observeSaved(sourceId: String?, folder: String?): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE COALESCE(s.isArchived, 0) = 0
          AND (:sourceId IS NULL OR i.sourceId = :sourceId)
          AND (:folder IS NULL OR src.folder = :folder)
        ORDER BY COALESCE(i.publishedAt, i.savedAt) DESC
        """
    )
    fun observeAll(sourceId: String?, folder: String?): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE COALESCE(s.isArchived, 0) = 0 AND COALESCE(s.isStarred, 0) = 1
          AND (:sourceId IS NULL OR i.sourceId = :sourceId)
          AND (:folder IS NULL OR src.folder = :folder)
        ORDER BY COALESCE(i.publishedAt, i.savedAt) DESC
        """
    )
    fun observeStarred(sourceId: String?, folder: String?): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT COUNT(*) FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        WHERE COALESCE(s.isRead, 0) = 0 AND COALESCE(s.isArchived, 0) = 0
        """
    )
    fun observeUnreadCount(): Flow<Int>

    @Query(
        """
        SELECT src.id AS sourceId, src.title AS title, src.folder AS folder,
               (SELECT COUNT(*) FROM items i
                LEFT JOIN item_states s ON s.itemId = i.id
                WHERE i.sourceId = src.id AND COALESCE(s.isRead, 0) = 0 AND COALESCE(s.isArchived, 0) = 0) AS unread
        FROM sources src
        ORDER BY src.sortOrder, src.title COLLATE NOCASE
        """
    )
    fun observeFeedUnread(): Flow<List<FeedUnread>>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItem(id: String): ItemEntity?

    @Query("SELECT * FROM item_states WHERE itemId = :id")
    suspend fun getState(id: String): ItemStateEntity?

    @Query("SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id WHERE COALESCE(s.isRead, 0) = 0 AND COALESCE(s.isArchived, 0) = 0")
    suspend fun unreadCountOnce(): Int

    @Query("SELECT i.title FROM items i LEFT JOIN item_states s ON s.itemId = i.id WHERE COALESCE(s.isRead, 0) = 0 AND COALESCE(s.isArchived, 0) = 0 ORDER BY COALESCE(i.publishedAt, i.savedAt) DESC LIMIT 1")
    suspend fun latestInboxTitle(): String?

    @Query("SELECT * FROM items")
    suspend fun allItems(): List<ItemEntity>

    @Query("SELECT * FROM item_states")
    suspend fun allStates(): List<ItemStateEntity>

    // -- Mutations -------------------------------------------------------------

    @Query("UPDATE item_states SET isRead = :read, updatedAt = :ts WHERE itemId = :id")
    suspend fun setRead(id: String, read: Boolean, ts: Long)

    /** Mark every unread, non-archived item in a scope (all / one feed / one folder) as read. */
    @Query(
        """
        UPDATE item_states SET isRead = 1, updatedAt = :ts
        WHERE COALESCE(isRead, 0) = 0 AND COALESCE(isArchived, 0) = 0
          AND itemId IN (
            SELECT i.id FROM items i
            LEFT JOIN sources src ON src.id = i.sourceId
            WHERE (:sourceId IS NULL OR i.sourceId = :sourceId)
              AND (:folder IS NULL OR src.folder = :folder)
          )
        """
    )
    suspend fun markScopeRead(sourceId: String?, folder: String?, ts: Long)

    @Query("UPDATE item_states SET isStarred = :starred, updatedAt = :ts WHERE itemId = :id")
    suspend fun setStarred(id: String, starred: Boolean, ts: Long)

    @Query("UPDATE item_states SET isArchived = :archived, updatedAt = :ts WHERE itemId = :id")
    suspend fun setArchived(id: String, archived: Boolean, ts: Long)

    @Query("UPDATE item_states SET isReadLater = :readLater, updatedAt = :ts WHERE itemId = :id")
    suspend fun setReadLater(id: String, readLater: Boolean, ts: Long)

    @Query("UPDATE item_states SET readProgress = :progress, lastReadAt = :ts, updatedAt = :ts WHERE itemId = :id")
    suspend fun setProgress(id: String, progress: Float, ts: Long)

    /** Update only the extraction status — used to record a failed fetch without
     *  discarding the feed content we already have. */
    @Query("UPDATE items SET extractStatus = :status WHERE id = :id")
    suspend fun setExtractStatus(id: String, status: String)

    @Query("UPDATE items SET type = :type WHERE id = :id")
    suspend fun setType(id: String, type: String)

    @Query("UPDATE items SET cacheStatus = :status WHERE id = :id")
    suspend fun setCacheStatus(id: String, status: String?)

    @Query("UPDATE items SET leadImage = :leadImage WHERE id = :id")
    suspend fun setLeadImage(id: String, leadImage: String?)

    @Query("UPDATE items SET collectionId = :collectionId WHERE id = :id")
    suspend fun setCollection(id: String, collectionId: String?)

    @Query("UPDATE items SET collectionId = NULL WHERE collectionId = :collectionId")
    suspend fun clearCollection(collectionId: String)

    @Query("UPDATE items SET title = :title, author = COALESCE(:author, author), siteName = COALESCE(:siteName, siteName) WHERE id = :id")
    suspend fun updateMeta(id: String, title: String, author: String?, siteName: String?)

    @Query("UPDATE items SET blobPath = :blobPath, excerpt = :excerpt, wordCount = :wordCount, readingMinutes = :minutes, leadImage = COALESCE(:leadImage, leadImage), extractStatus = :status, contentSource = :contentSource WHERE id = :id")
    suspend fun setExtracted(
        id: String,
        blobPath: String?,
        excerpt: String?,
        wordCount: Int,
        minutes: Int,
        leadImage: String?,
        status: String,
        contentSource: String,
    )

    // -- Full-text search ------------------------------------------------------

    @Insert
    suspend fun insertFts(fts: ItemFtsEntity)

    @Query("DELETE FROM item_fts WHERE itemId = :id")
    suspend fun deleteFts(id: String)

    @Transaction
    suspend fun indexItem(fts: ItemFtsEntity) {
        deleteFts(fts.itemId)
        insertFts(fts)
    }

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM item_fts
        JOIN items i ON i.id = item_fts.itemId
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE item_fts MATCH :query
        ORDER BY COALESCE(i.publishedAt, i.savedAt) DESC
        """
    )
    suspend fun search(query: String): List<ItemListRow>

    // -- Library scopes (Raindrop-style) --------------------------------------

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE COALESCE(s.isReadLater, 0) = 1 OR COALESCE(s.isStarred, 0) = 1 OR i.collectionId IS NOT NULL
        ORDER BY i.savedAt DESC
        """
    )
    fun observeLibraryAll(): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.collectionId IS NULL AND (COALESCE(s.isReadLater, 0) = 1 OR COALESCE(s.isStarred, 0) = 1)
        ORDER BY i.savedAt DESC
        """
    )
    fun observeUnsorted(): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE COALESCE(s.isArchived, 0) = 1
        ORDER BY s.updatedAt DESC, i.savedAt DESC
        """
    )
    fun observeArchived(): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE COALESCE(s.isStarred, 0) = 1 AND COALESCE(s.isArchived, 0) = 0
        ORDER BY i.savedAt DESC
        """
    )
    fun observeFavorites(): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id
            WHERE COALESCE(s.isReadLater, 0) = 1 OR COALESCE(s.isStarred, 0) = 1 OR i.collectionId IS NOT NULL) AS allCount,
          (SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id
            WHERE i.collectionId IS NULL AND (COALESCE(s.isReadLater, 0) = 1 OR COALESCE(s.isStarred, 0) = 1)) AS unsortedCount,
          (SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id
            WHERE COALESCE(s.isStarred, 0) = 1 AND COALESCE(s.isArchived, 0) = 0) AS favoritesCount,
          (SELECT COUNT(*) FROM item_states s WHERE COALESCE(s.isArchived, 0) = 1) AS archiveCount
        """
    )
    fun observeLibraryCounts(): Flow<LibraryCounts>

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.collectionId = :collectionId
        ORDER BY i.savedAt DESC
        """
    )
    fun observeCollection(collectionId: String): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
               i.siteName AS siteName, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
               i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
               i.extractStatus AS extractStatus, i.type AS type,
               COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
               COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
        FROM items i
        JOIN item_tags it ON it.itemId = i.id
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE it.tagId = :tagId
        ORDER BY i.savedAt DESC
        """
    )
    fun observeByTag(tagId: String): Flow<List<ItemListRow>>
}
