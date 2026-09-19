package com.cairn.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The shared [ItemListRow] projection + its item/state/source join, factored out of the ~19 list
 * queries that were all repeating it verbatim. Room resolves the compile-time constant concatenation
 * in each @Query, so every list query is `ITEM_LIST_SELECT + " WHERE … ORDER BY …"`. Extra joins
 * (collections, tags) are appended before the WHERE.
 */
private const val ITEM_LIST_COLUMNS = """
    SELECT i.id AS id, i.url AS url, i.title AS title, i.author AS author,
           i.siteName AS siteName, i.sourceId AS sourceId, src.title AS sourceTitle, i.excerpt AS excerpt, i.leadImage AS leadImage,
           i.publishedAt AS publishedAt, i.savedAt AS savedAt, i.readingMinutes AS readingMinutes,
           i.extractStatus AS extractStatus, i.type AS type, i.cacheStatus AS cacheStatus,
           i.simHash AS simHash,
           COALESCE(s.isRead, 0) AS isRead, COALESCE(s.isStarred, 0) AS isStarred,
           COALESCE(s.isReadLater, 0) AS isReadLater, COALESCE(s.isArchived, 0) AS isArchived
    """

/** The [ITEM_LIST_COLUMNS] projection over the standard item + state + source join. Queries that
 *  add their own joins (FTS, collections, tags) use [ITEM_LIST_COLUMNS] with a bespoke FROM instead. */
private const val ITEM_LIST_SELECT = ITEM_LIST_COLUMNS + """
    FROM items i
    LEFT JOIN item_states s ON s.itemId = i.id
    LEFT JOIN sources src ON src.id = i.sourceId
    """

/** Flat projection for list screens — joins item + mutable state + source title. */
/** Minimal projection for the home-screen list widget. */
data class WidgetRow(
    val id: String,
    val title: String,
    val source: String?,
)

/** Minimal (id, url) projection for background jobs like the broken-link watchdog. */
data class ItemIdUrl(val id: String, val url: String)

/** (id, blobPath) projection so a bulk delete can free each item's on-disk blob in one pass. */
data class ItemBlobPath(val id: String, val blobPath: String?)

data class ItemListRow(
    val id: String,
    val url: String,
    val title: String,
    val author: String?,
    val siteName: String?,
    val sourceId: String?,
    val sourceTitle: String?,
    val excerpt: String?,
    val leadImage: String?,
    val publishedAt: Long?,
    val savedAt: Long,
    val readingMinutes: Int,
    val extractStatus: String,
    val type: String,
    val cacheStatus: String?,
    val simHash: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    val isReadLater: Boolean,
    val isArchived: Boolean,
)

/** Minimal text projection for on-device semantic similarity and topic clustering. */
data class ItemText(
    val id: String,
    val title: String,
    val excerpt: String?,
    val sourceTitle: String?,
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
    val offlineCount: Int = 0,
    val readLaterCount: Int = 0,
)

@Dao
interface ItemDao {

    /** How many (non-trashed) items a feed currently has — for the per-feed verification readout. */
    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND trashedAt IS NULL")
    suspend fun countForSource(sourceId: String): Int

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


    // -- Streams ---------------------------------------------------------------

    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND COALESCE(s.isArchived, 0) = 0 AND COALESCE(s.isRead, 0) = 0
          AND (:sourceId IS NULL OR i.sourceId = :sourceId)
          AND (:folder IS NULL OR src.folder = :folder)
          AND (:sourceId IS NOT NULL OR COALESCE(src.muted, 0) = 0)
        ORDER BY i.effectiveDate DESC
        """
    )
    fun observeInbox(sourceId: String?, folder: String?): Flow<List<ItemListRow>>

    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND COALESCE(s.isArchived, 0) = 0 AND COALESCE(s.isReadLater, 0) = 1
          AND (:sourceId IS NULL OR i.sourceId = :sourceId)
          AND (:folder IS NULL OR src.folder = :folder)
        ORDER BY i.effectiveDate DESC
        """
    )
    fun observeSaved(sourceId: String?, folder: String?): Flow<List<ItemListRow>>

    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND COALESCE(s.isArchived, 0) = 0
          AND (:sourceId IS NULL OR i.sourceId = :sourceId)
          AND (:folder IS NULL OR src.folder = :folder)
          AND (:sourceId IS NOT NULL OR COALESCE(src.muted, 0) = 0)
        ORDER BY i.effectiveDate DESC
        """
    )
    fun observeAll(sourceId: String?, folder: String?): Flow<List<ItemListRow>>

    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND COALESCE(s.isArchived, 0) = 0 AND COALESCE(s.isStarred, 0) = 1
          AND (:sourceId IS NULL OR i.sourceId = :sourceId)
          AND (:folder IS NULL OR src.folder = :folder)
        ORDER BY i.effectiveDate DESC
        """
    )
    fun observeStarred(sourceId: String?, folder: String?): Flow<List<ItemListRow>>

    @Query(
        """
        SELECT COUNT(*) FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        WHERE i.trashedAt IS NULL AND COALESCE(s.isRead, 0) = 0 AND COALESCE(s.isArchived, 0) = 0
          AND (i.sourceId IS NULL OR i.sourceId NOT IN (SELECT id FROM sources WHERE muted = 1))
        """
    )
    fun observeUnreadCount(): Flow<Int>

    /** Total articles in the global "All Articles" river — non-trashed, non-archived, and excluding
     *  muted feeds — i.e. the count of what [observeAll]`(null, null)` lists. Shown on the drawer's
     *  All Articles row so the whole collection size is visible at a glance. */
    @Query(
        """
        SELECT COUNT(*) FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.trashedAt IS NULL AND COALESCE(s.isArchived, 0) = 0 AND COALESCE(src.muted, 0) = 0
        """
    )
    fun observeAllCount(): Flow<Int>

    /** Store the canonical dedup key (see UrlCanonicalizer) for one item, keeping the indexed
     *  [ItemEntity.dedupeKey] in sync (lower-cased) so backfilled rows group in the Duplicates view. */
    @Query("UPDATE items SET canonicalUrl = :canonical, dedupeKey = LOWER(:canonical) WHERE id = :id")
    suspend fun setCanonicalUrl(id: String, canonical: String)

    /** A bounded batch of items still missing a canonicalUrl, for the one-time backfill. */
    @Query("SELECT id, url FROM items WHERE canonicalUrl IS NULL LIMIT :limit")
    suspend fun itemsMissingCanonical(limit: Int): List<ItemIdUrl>

    @Query(
        """
        SELECT src.id AS sourceId, src.title AS title, src.folder AS folder,
               (SELECT COUNT(*) FROM items i
                LEFT JOIN item_states s ON s.itemId = i.id
                WHERE i.sourceId = src.id AND i.trashedAt IS NULL AND COALESCE(s.isRead, 0) = 0 AND COALESCE(s.isArchived, 0) = 0) AS unread
        FROM sources src
        ORDER BY src.sortOrder, src.title COLLATE NOCASE
        """
    )
    fun observeFeedUnread(): Flow<List<FeedUnread>>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItem(id: String): ItemEntity?

    @Query("SELECT * FROM item_states WHERE itemId = :id")
    suspend fun getState(id: String): ItemStateEntity?

    @Query("SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id WHERE i.trashedAt IS NULL AND COALESCE(s.isRead, 0) = 0 AND COALESCE(s.isArchived, 0) = 0 AND (i.sourceId IS NULL OR i.sourceId NOT IN (SELECT id FROM sources WHERE muted = 1))")
    suspend fun unreadCountOnce(): Int

    @Query("SELECT i.title FROM items i LEFT JOIN item_states s ON s.itemId = i.id WHERE i.trashedAt IS NULL AND COALESCE(s.isRead, 0) = 0 AND COALESCE(s.isArchived, 0) = 0 ORDER BY COALESCE(i.publishedAt, i.savedAt) DESC LIMIT 1")
    suspend fun latestInboxTitle(): String?

    /** The newest unread items for the home-screen list widget. */
    @Query(
        """
        SELECT i.id AS id, i.title AS title, COALESCE(src.title, i.siteName) AS source
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.trashedAt IS NULL AND COALESCE(s.isRead, 0) = 0 AND COALESCE(s.isArchived, 0) = 0
          AND (i.sourceId IS NULL OR i.sourceId NOT IN (SELECT id FROM sources WHERE muted = 1))
        ORDER BY i.effectiveDate DESC
        LIMIT :limit
        """,
    )
    suspend fun latestUnreadForWidget(limit: Int): List<WidgetRow>

    /** The newest saved (read-later) items for the widget's Saved scope. */
    @Query(
        """
        SELECT i.id AS id, i.title AS title, COALESCE(src.title, i.siteName) AS source
        FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.trashedAt IS NULL AND COALESCE(s.isReadLater, 0) = 1
        ORDER BY i.savedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun latestSavedForWidget(limit: Int): List<WidgetRow>

    @Query("SELECT * FROM items")
    suspend fun allItems(): List<ItemEntity>

    /** Just the item ids — for orphan detection, which only needs to know which ids are live
     *  (loading the whole items table just to build an id set is far more memory than that needs). */
    @Query("SELECT id FROM items")
    suspend fun allItemIds(): List<String>

    /** Curated library items (starred / archived / read-later / filed in a collection), newest first,
     *  PDFs excluded — the set worth exporting to a Markdown / Obsidian vault. */
    @Query(
        """
        SELECT i.* FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        WHERE i.trashedAt IS NULL AND i.type != 'PDF'
          AND (COALESCE(s.isStarred, 0) = 1 OR COALESCE(s.isArchived, 0) = 1
               OR COALESCE(s.isReadLater, 0) = 1 OR EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id))
        ORDER BY i.savedAt DESC
        """
    )
    suspend fun libraryItemsForExport(): List<ItemEntity>

    /** Non-PDF items with no thumbnail yet, newest first — candidates for lead-image back-fill. */
    @Query(
        """
        SELECT * FROM items
        WHERE (leadImage IS NULL OR leadImage = '') AND trashedAt IS NULL AND type != 'PDF' AND url != ''
        ORDER BY COALESCE(publishedAt, savedAt) DESC
        LIMIT :limit
        """
    )
    suspend fun itemsMissingThumbnail(limit: Int): List<ItemEntity>

    @Query("SELECT * FROM item_states")
    suspend fun allStates(): List<ItemStateEntity>

    @Query("SELECT * FROM item_collections")
    suspend fun allItemCollections(): List<ItemCollectionCrossRef>

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

    /** Mark unread items in a scope that are newer than [cutoff] (the effective sort key). */
    @Query(
        """
        UPDATE item_states SET isRead = 1, updatedAt = :ts
        WHERE COALESCE(isRead, 0) = 0 AND COALESCE(isArchived, 0) = 0
          AND itemId IN (
            SELECT i.id FROM items i LEFT JOIN sources src ON src.id = i.sourceId
            WHERE (:sourceId IS NULL OR i.sourceId = :sourceId) AND (:folder IS NULL OR src.folder = :folder)
              AND i.effectiveDate > :cutoff
          )
        """
    )
    suspend fun markReadNewerThan(sourceId: String?, folder: String?, cutoff: Long, ts: Long)

    /** Mark unread items in a scope that are older than [cutoff]. */
    @Query(
        """
        UPDATE item_states SET isRead = 1, updatedAt = :ts
        WHERE COALESCE(isRead, 0) = 0 AND COALESCE(isArchived, 0) = 0
          AND itemId IN (
            SELECT i.id FROM items i LEFT JOIN sources src ON src.id = i.sourceId
            WHERE (:sourceId IS NULL OR i.sourceId = :sourceId) AND (:folder IS NULL OR src.folder = :folder)
              AND i.effectiveDate < :cutoff
          )
        """
    )
    suspend fun markReadOlderThan(sourceId: String?, folder: String?, cutoff: Long, ts: Long)

    /** Prunable items (nothing the user engaged with) older than [cutoff], for age retention. */
    @Query(
        """
        SELECT i.id FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        WHERE COALESCE(s.isStarred, 0) = 0 AND COALESCE(s.isReadLater, 0) = 0
          AND COALESCE(s.isArchived, 0) = 0 AND NOT EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id)
          AND (i.cacheStatus IS NULL OR i.cacheStatus <> 'PERMANENT')
          AND NOT EXISTS (SELECT 1 FROM highlights h WHERE h.itemId = i.id)
          AND (:keepUnread = 0 OR COALESCE(s.isRead, 0) = 1)
          AND COALESCE(i.publishedAt, i.savedAt) < :cutoff
        """
    )
    suspend fun prunableOlderThan(cutoff: Long, keepUnread: Int): List<String>

    @Query("UPDATE item_states SET isStarred = :starred, updatedAt = :ts WHERE itemId = :id")
    suspend fun setStarred(id: String, starred: Boolean, ts: Long)

    @Query("UPDATE item_states SET isArchived = :archived, updatedAt = :ts WHERE itemId = :id")
    suspend fun setArchived(id: String, archived: Boolean, ts: Long)

    @Query("UPDATE item_states SET isReadLater = :readLater, updatedAt = :ts WHERE itemId = :id")
    suspend fun setReadLater(id: String, readLater: Boolean, ts: Long)

    // -- Batch state mutations (bulk multi-select) -----------------------------
    // One UPDATE for the whole selection instead of N single-row updates; the repository
    // pairs each with a single coalesced insert of sync-ops. Callers chunk the id list to
    // stay under SQLite's bound-variable limit.

    @Query("UPDATE item_states SET isRead = :read, updatedAt = :ts WHERE itemId IN (:ids)")
    suspend fun setReadMany(ids: List<String>, read: Boolean, ts: Long)

    @Query("UPDATE item_states SET isStarred = :starred, updatedAt = :ts WHERE itemId IN (:ids)")
    suspend fun setStarredMany(ids: List<String>, starred: Boolean, ts: Long)

    @Query("UPDATE item_states SET isArchived = :archived, updatedAt = :ts WHERE itemId IN (:ids)")
    suspend fun setArchivedMany(ids: List<String>, archived: Boolean, ts: Long)

    @Query("UPDATE item_states SET isReadLater = :readLater, updatedAt = :ts WHERE itemId IN (:ids)")
    suspend fun setReadLaterMany(ids: List<String>, readLater: Boolean, ts: Long)

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

    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId")
    suspend fun countBySource(sourceId: String): Int

    /** Items in a feed eligible for retention pruning (nothing the user engaged with),
     *  oldest first — starred / saved / archived / filed / highlighted / offline copies are kept. */
    @Query(
        """
        SELECT i.id FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        WHERE i.sourceId = :sourceId
          AND COALESCE(s.isStarred, 0) = 0 AND COALESCE(s.isReadLater, 0) = 0
          AND COALESCE(s.isArchived, 0) = 0 AND NOT EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id)
          AND (i.cacheStatus IS NULL OR i.cacheStatus <> 'PERMANENT')
          AND NOT EXISTS (SELECT 1 FROM highlights h WHERE h.itemId = i.id)
          AND (:keepUnread = 0 OR COALESCE(s.isRead, 0) = 1)
        ORDER BY COALESCE(i.publishedAt, i.savedAt) ASC
        """
    )
    suspend fun prunableOldestFirst(sourceId: String, keepUnread: Int): List<String>

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteItem(id: String)

    // -- Bulk delete (batched retention / trash / multi-select) ----------------
    // The batch equivalents of the single-item delete path (deleteFts + deleteItem + tombstone),
    // so a large prune/empty-trash runs a handful of statements instead of N×3 round-trips. FK
    // cascades still clear item_states / item_tags / item_collections / highlights on the item
    // delete, exactly as the single-row deleteItem relied on. Callers chunk the id list to stay
    // under SQLite's bound-variable limit.

    /** blobPaths for a set of items, fetched before deletion so their on-disk blobs can be freed. */
    @Query("SELECT id, blobPath FROM items WHERE id IN (:ids)")
    suspend fun blobPathsFor(ids: List<String>): List<ItemBlobPath>

    /** Bulk row delete — FK cascades clear item_states / item_tags / item_collections / highlights. */
    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteItemsMany(ids: List<String>)

    /** Bulk FTS delete mirroring [deleteFts]; item_fts is a standalone FTS4 table with no FK, so its
     *  rows must be removed explicitly. */
    @Query("DELETE FROM item_fts WHERE itemId IN (:ids)")
    suspend fun deleteFtsMany(ids: List<String>)

    /** Tombstone many ids at once, mirroring [SyncDao.tombstone]; @Upsert keeps a re-tombstone idempotent. */
    @Upsert
    suspend fun tombstoneMany(tombstones: List<TombstoneEntity>)

    /**
     * Batch equivalent of the single-item delete's DB work: remove the FTS index rows and the item
     * rows (FK cascades clear states / tags / collections / highlights) and tombstone every id so a
     * re-sync can't resurrect them — all in one transaction. On-disk blobs are freed separately by the
     * caller. [ids] must already be chunked under SQLite's bound-variable limit.
     */
    @Transaction
    suspend fun deleteItemsWithFtsAndTombstones(ids: List<String>, deletedAt: Long) {
        if (ids.isEmpty()) return
        deleteFtsMany(ids)
        deleteItemsMany(ids)
        tombstoneMany(ids.map { TombstoneEntity(itemId = it, deletedAt = deletedAt) })
    }

    /** Items of a feed that the user hasn't explicitly kept (not starred / saved / archived /
     *  filed in a collection / highlighted / permanent) — deleted when the feed is unsubscribed,
     *  so the Inbox doesn't keep showing a removed feed's articles. Kept items detach instead. */
    @Query(
        """
        SELECT i.id FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        WHERE i.sourceId = :sourceId
          AND COALESCE(s.isStarred, 0) = 0 AND COALESCE(s.isReadLater, 0) = 0
          AND COALESCE(s.isArchived, 0) = 0 AND NOT EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id)
          AND (i.cacheStatus IS NULL OR i.cacheStatus <> 'PERMANENT')
          AND NOT EXISTS (SELECT 1 FROM highlights h WHERE h.itemId = i.id)
        """
    )
    suspend fun unkeptIdsBySource(sourceId: String): List<String>

    @Query("UPDATE items SET leadImage = :leadImage WHERE id = :id")
    suspend fun setLeadImage(id: String, leadImage: String?)

    /** Point an item at a restored on-disk blob (used by full-archive restore). */
    @Query("UPDATE items SET blobPath = :blobPath WHERE id = :id")
    suspend fun setBlobPath(id: String, blobPath: String?)

    @Query("UPDATE items SET title = :title, author = COALESCE(:author, author), siteName = COALESCE(:siteName, siteName) WHERE id = :id")
    suspend fun updateMeta(id: String, title: String, author: String?, siteName: String?)

    /** Fill the original publish date (and the indexed effectiveDate sort key) from extraction, but
     *  only when the item has none yet — so a real feed date is never overwritten by a page-parsed one.
     *  Fixes saved-from-web items that otherwise sort/show by their save time. */
    @Query("UPDATE items SET publishedAt = :publishedAt, effectiveDate = :publishedAt WHERE id = :id AND publishedAt IS NULL")
    suspend fun setPublishedIfMissing(id: String, publishedAt: Long)

    /** Write enriched media metadata (YouTube video). Only non-null fields overwrite; setting a real
     *  publish date also advances the indexed effectiveDate sort key so the item sorts by air date. */
    @Query("UPDATE items SET title = COALESCE(:title, title), author = COALESCE(:author, author), publishedAt = COALESCE(:publishedAt, publishedAt), effectiveDate = COALESCE(:publishedAt, effectiveDate), durationSeconds = COALESCE(:durationSeconds, durationSeconds) WHERE id = :id")
    suspend fun updateVideoMeta(id: String, title: String?, author: String?, publishedAt: Long?, durationSeconds: Int?)

    @Query("UPDATE items SET contentSource = :source WHERE id = :id")
    suspend fun setContentSource(id: String, source: String)

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

    /** Ids of stored, non-trashed items that have an article body on disk — the set the one-time
     *  full-text re-index (Content Engine P2) walks to rebuild the FTS index at full depth. */
    @Query("SELECT id FROM items WHERE trashedAt IS NULL AND blobPath IS NOT NULL AND blobPath != ''")
    suspend fun idsWithBody(): List<String>

    /** Cross-channel dedupe (Content Engine P3): is any item already stored under this dedupe key
     *  (canonical URL, lowercased)? Lets the archive crawler skip an article the feed already brought. */
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE dedupeKey = :key)")
    suspend fun existsByDedupeKey(key: String): Boolean

    /** Content Engine P4: store the 64-bit content SimHash used for near-duplicate collapse. */
    @Query("UPDATE items SET simHash = :hash WHERE id = :id")
    suspend fun setSimHash(id: String, hash: Long)

    @Query(
        ITEM_LIST_COLUMNS + """
        FROM item_fts
        JOIN items i ON i.id = item_fts.itemId
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.trashedAt IS NULL AND item_fts MATCH :query
        ORDER BY i.effectiveDate DESC
        """
    )
    suspend fun search(query: String): List<ItemListRow>

    /** Content Engine P5 (Follow author): every non-trashed item whose byline contains [author],
     *  across the whole archive, newest first. Case-insensitive substring so "Coates" matches
     *  "Ta-Nehisi Coates". */
    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND i.author IS NOT NULL
          AND LOWER(i.author) LIKE '%' || LOWER(:author) || '%'
        ORDER BY i.effectiveDate DESC
        """
    )
    suspend fun itemsByAuthor(author: String): List<ItemListRow>

    /** Distinct non-empty bylines across the archive — powers author suggestions/autocomplete. */
    @Query("SELECT DISTINCT author FROM items WHERE author IS NOT NULL AND author != '' AND trashedAt IS NULL ORDER BY author COLLATE NOCASE LIMIT :limit")
    suspend fun distinctAuthors(limit: Int = 500): List<String>

    // -- Library scopes (Raindrop-style) --------------------------------------

    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND (COALESCE(s.isStarred, 0) = 1 OR EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id))
        ORDER BY i.savedAt DESC
        """
    )
    fun observeLibraryAll(): Flow<List<ItemListRow>>

    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND NOT EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id) AND COALESCE(s.isStarred, 0) = 1
        ORDER BY i.savedAt DESC
        """
    )
    fun observeUnsorted(): Flow<List<ItemListRow>>

    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND COALESCE(s.isArchived, 0) = 1
        ORDER BY s.updatedAt DESC, i.savedAt DESC
        """
    )
    fun observeArchived(): Flow<List<ItemListRow>>

    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND COALESCE(s.isStarred, 0) = 1 AND COALESCE(s.isArchived, 0) = 0
        ORDER BY i.savedAt DESC
        """
    )
    fun observeFavorites(): Flow<List<ItemListRow>>

    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND i.cacheStatus = 'PERMANENT'
        ORDER BY i.savedAt DESC
        """
    )
    fun observeOfflineCopies(): Flow<List<ItemListRow>>

    /** Everything readable offline: an explicit permanent copy, or an auto-cached full body on disk.
     *  Permanent (archival) copies sort first. Powers the Offline surface. */
    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND (i.cacheStatus = 'PERMANENT' OR (i.extractStatus = 'OK' AND i.blobPath IS NOT NULL))
        ORDER BY (i.cacheStatus = 'PERMANENT') DESC, i.savedAt DESC
        """
    )
    fun observeCached(): Flow<List<ItemListRow>>

    @Query("SELECT COUNT(*) FROM items WHERE trashedAt IS NULL AND (cacheStatus = 'PERMANENT' OR (extractStatus = 'OK' AND blobPath IS NOT NULL))")
    fun observeCachedCount(): Flow<Int>

    /** Candidates for an offline pack: not-yet-permanent, non-PDF items — Read Later first, then
     *  unread — newest first, so a commute grabs the things most likely to be read next. */
    @Query(
        """
        SELECT i.id FROM items i LEFT JOIN item_states s ON s.itemId = i.id
        WHERE i.trashedAt IS NULL AND i.type != 'PDF'
          AND (i.cacheStatus IS NULL OR i.cacheStatus != 'PERMANENT')
          AND (COALESCE(s.isReadLater, 0) = 1 OR COALESCE(s.isRead, 0) = 0)
        ORDER BY COALESCE(s.isReadLater, 0) DESC, COALESCE(i.publishedAt, i.savedAt) DESC
        LIMIT :limit
        """
    )
    suspend fun offlinePackCandidates(limit: Int): List<String>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id
            WHERE i.trashedAt IS NULL AND (COALESCE(s.isStarred, 0) = 1 OR EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id))) AS allCount,
          (SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id
            WHERE i.trashedAt IS NULL AND NOT EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id) AND COALESCE(s.isStarred, 0) = 1) AS unsortedCount,
          (SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id
            WHERE i.trashedAt IS NULL AND COALESCE(s.isStarred, 0) = 1 AND COALESCE(s.isArchived, 0) = 0) AS favoritesCount,
          (SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id
            WHERE i.trashedAt IS NULL AND COALESCE(s.isArchived, 0) = 1) AS archiveCount,
          (SELECT COUNT(*) FROM items i WHERE i.trashedAt IS NULL AND i.cacheStatus = 'PERMANENT') AS offlineCount,
          (SELECT COUNT(*) FROM items i LEFT JOIN item_states s ON s.itemId = i.id
            WHERE i.trashedAt IS NULL AND COALESCE(s.isReadLater, 0) = 1 AND COALESCE(s.isArchived, 0) = 0) AS readLaterCount
        """
    )
    fun observeLibraryCounts(): Flow<LibraryCounts>

    @Query(
        ITEM_LIST_COLUMNS + """
        FROM items i
        JOIN item_collections ic ON ic.itemId = i.id
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.trashedAt IS NULL AND ic.collectionId = :collectionId
        ORDER BY i.savedAt DESC
        """
    )
    fun observeCollection(collectionId: String): Flow<List<ItemListRow>>

    // -- v3.67: many-to-many collection membership + smart views + broken links -----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToCollection(ref: ItemCollectionCrossRef)

    @Query("DELETE FROM item_collections WHERE itemId = :itemId AND collectionId = :collectionId")
    suspend fun removeFromCollection(itemId: String, collectionId: String)

    @Query("DELETE FROM item_collections WHERE itemId = :itemId")
    suspend fun clearItemCollections(itemId: String)

    @Query("SELECT collectionId FROM item_collections WHERE itemId = :itemId")
    fun observeCollectionIdsFor(itemId: String): Flow<List<String>>

    @Query("SELECT collectionId FROM item_collections WHERE itemId = :itemId")
    suspend fun collectionIdsFor(itemId: String): List<String>

    @Transaction
    suspend fun setInCollection(itemId: String, collectionId: String, inIt: Boolean) {
        if (inIt) addToCollection(ItemCollectionCrossRef(itemId, collectionId))
        else removeFromCollection(itemId, collectionId)
    }

    /** Untagged library items: saved/filed but with no tags — a Raindrop-style cleanup bucket. */
    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL
          AND (COALESCE(s.isStarred, 0) = 1 OR COALESCE(s.isReadLater, 0) = 1 OR EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id))
          AND NOT EXISTS (SELECT 1 FROM item_tags t WHERE t.itemId = i.id)
        ORDER BY i.savedAt DESC
        """
    )
    fun observeUntagged(): Flow<List<ItemListRow>>

    /** Broken items: the watchdog marked their link dead. */
    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND i.linkStatus = 'BROKEN'
        ORDER BY i.savedAt DESC
        """
    )
    fun observeBroken(): Flow<List<ItemListRow>>

    /** Duplicate items: those whose canonical/plain URL is shared by more than one non-trashed item. */
    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NULL AND i.dedupeKey IN (
            SELECT dedupeKey AS k FROM items
            WHERE trashedAt IS NULL AND dedupeKey <> ''
            GROUP BY k HAVING COUNT(*) > 1
        )
        ORDER BY i.dedupeKey, i.savedAt DESC
        """
    )
    fun observeDuplicates(): Flow<List<ItemListRow>>

    @Query("SELECT COUNT(*) FROM items WHERE trashedAt IS NULL AND linkStatus = 'BROKEN'")
    fun observeBrokenCount(): Flow<Int>

    @Query("SELECT id FROM items WHERE trashedAt IS NULL AND linkStatus = 'BROKEN' LIMIT :limit")
    suspend fun brokenItemIds(limit: Int): List<String>

    /** Lightweight (id, title, excerpt, source) rows for on-device semantic similarity / clustering,
     *  newest first. Body text stays on disk; title+excerpt is enough signal and cheap to vectorize. */
    @Query(
        """
        SELECT i.id AS id, i.title AS title, i.excerpt AS excerpt, src.title AS sourceTitle
        FROM items i LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.trashedAt IS NULL
        ORDER BY COALESCE(i.publishedAt, i.savedAt) DESC LIMIT :limit
        """
    )
    suspend fun recentText(limit: Int): List<ItemText>

    @Query(
        """
        SELECT COUNT(*) FROM items i WHERE i.trashedAt IS NULL AND i.dedupeKey IN (
            SELECT dedupeKey AS k FROM items
            WHERE trashedAt IS NULL AND dedupeKey <> ''
            GROUP BY k HAVING COUNT(*) > 1
        )
        """
    )
    fun observeDuplicatesCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        WHERE i.trashedAt IS NULL
          AND (COALESCE(s.isStarred, 0) = 1 OR COALESCE(s.isReadLater, 0) = 1 OR EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id))
          AND NOT EXISTS (SELECT 1 FROM item_tags t WHERE t.itemId = i.id)
        """
    )
    fun observeUntaggedCount(): Flow<Int>

    /** Library items whose canonical/plain URL is checkable, oldest-checked first, for the watchdog. */
    @Query(
        """
        SELECT i.id, i.url FROM items i
        LEFT JOIN item_states s ON s.itemId = i.id
        WHERE i.trashedAt IS NULL AND i.type != 'PDF' AND i.url LIKE 'http%'
          AND (COALESCE(s.isStarred, 0) = 1 OR COALESCE(s.isReadLater, 0) = 1 OR EXISTS (SELECT 1 FROM item_collections ic WHERE ic.itemId = i.id) OR i.cacheStatus = 'PERMANENT')
        ORDER BY COALESCE(i.linkCheckedAt, 0) ASC
        LIMIT :limit
        """
    )
    suspend fun itemsToLinkCheck(limit: Int): List<ItemIdUrl>

    @Query("UPDATE items SET linkStatus = :status, linkCheckedAt = :ts WHERE id = :id")
    suspend fun setLinkStatus(id: String, status: String, ts: Long)

    @Query(
        ITEM_LIST_COLUMNS + """
        FROM items i
        JOIN item_tags it ON it.itemId = i.id
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.trashedAt IS NULL AND it.tagId = :tagId
        ORDER BY i.savedAt DESC
        """
    )
    fun observeByTag(tagId: String): Flow<List<ItemListRow>>

    /** Nested tags: every item tagged with [path] itself OR any descendant tag ("path/child"),
     *  deduped. Selecting a parent tag therefore shows everything filed anywhere beneath it.
     *  [prefix] must be `path || '/%'`. */
    @Query(
        ITEM_LIST_COLUMNS + """
        FROM items i
        JOIN item_tags it ON it.itemId = i.id
        JOIN tags t ON t.id = it.tagId
        LEFT JOIN item_states s ON s.itemId = i.id
        LEFT JOIN sources src ON src.id = i.sourceId
        WHERE i.trashedAt IS NULL AND (t.name = :path OR t.name LIKE :prefix ESCAPE '\')
        GROUP BY i.id
        ORDER BY i.savedAt DESC
        """
    )
    fun observeByTagPath(path: String, prefix: String): Flow<List<ItemListRow>>

    // -- Trash (soft-delete) ---------------------------------------------------

    /** Everything currently in the Trash, most-recently-trashed first. */
    @Query(
        ITEM_LIST_SELECT + """
        WHERE i.trashedAt IS NOT NULL
        ORDER BY i.trashedAt DESC
        """
    )
    fun observeTrash(): Flow<List<ItemListRow>>

    /** Live count of items in the Trash, for the nav badge. */
    @Query("SELECT COUNT(*) FROM items WHERE trashedAt IS NOT NULL")
    fun observeTrashCount(): Flow<Int>

    /** Move an item to the Trash (non-null timestamp) or restore it (null). */
    @Query("UPDATE items SET trashedAt = :ts WHERE id = :id")
    suspend fun setTrashed(id: String, ts: Long?)

    /** Trash or restore a whole selection in one UPDATE (chunked by the caller). */
    @Query("UPDATE items SET trashedAt = :ts WHERE id IN (:ids)")
    suspend fun setTrashedMany(ids: List<String>, ts: Long?)

    /** The trashed-at timestamp for an item, or null if it is not trashed. */
    @Query("SELECT trashedAt FROM items WHERE id = :id")
    suspend fun trashedAtOf(id: String): Long?

    /** IDs of items trashed before [cutoff] — the auto-purge grace window. */
    @Query("SELECT id FROM items WHERE trashedAt IS NOT NULL AND trashedAt < :cutoff")
    suspend fun trashedOlderThan(cutoff: Long): List<String>

    /** Every id currently in the Trash, for "empty trash". */
    @Query("SELECT id FROM items WHERE trashedAt IS NOT NULL")
    suspend fun allTrashedIds(): List<String>
}
