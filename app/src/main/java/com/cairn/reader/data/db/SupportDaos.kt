package com.cairn.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: ItemTagCrossRef)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId AND tagId = :tagId")
    suspend fun unlink(itemId: String, tagId: String)

    @Query("SELECT t.* FROM tags t JOIN item_tags it ON it.tagId = t.id WHERE it.itemId = :itemId")
    suspend fun tagsForItem(itemId: String): List<TagEntity>
}

/** A highlight joined with the article it belongs to, for the notebook and exports. */
data class HighlightWithArticle(
    val id: String,
    val itemId: String,
    val articleTitle: String,
    val articleUrl: String,
    val quote: String,
    val note: String?,
    val color: Int,
    val createdAt: Long,
)

@Dao
interface HighlightDao {
    @Upsert
    suspend fun upsert(highlight: HighlightEntity)

    @Query("SELECT * FROM highlights WHERE itemId = :itemId ORDER BY startSelector, startOffset")
    fun observeForItem(itemId: String): Flow<List<HighlightEntity>>

    @Query("UPDATE highlights SET note = :note WHERE id = :id")
    suspend fun setNote(id: String, note: String?)

    @Query("UPDATE highlights SET color = :color WHERE id = :id")
    suspend fun setColor(id: String, color: Int)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM highlights")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT h.id AS id, h.itemId AS itemId, i.title AS articleTitle, i.url AS articleUrl,
               h.quote AS quote, h.note AS note, h.color AS color, h.createdAt AS createdAt
        FROM highlights h JOIN items i ON i.id = h.itemId
        ORDER BY h.createdAt DESC
        """
    )
    fun observeAllWithArticle(): Flow<List<HighlightWithArticle>>

    @Query(
        """
        SELECT h.id AS id, h.itemId AS itemId, i.title AS articleTitle, i.url AS articleUrl,
               h.quote AS quote, h.note AS note, h.color AS color, h.createdAt AS createdAt
        FROM highlights h JOIN items i ON i.id = h.itemId
        ORDER BY i.title COLLATE NOCASE, h.startSelector, h.startOffset
        """
    )
    suspend fun allWithArticle(): List<HighlightWithArticle>

    @Query(
        """
        SELECT h.id AS id, h.itemId AS itemId, i.title AS articleTitle, i.url AS articleUrl,
               h.quote AS quote, h.note AS note, h.color AS color, h.createdAt AS createdAt
        FROM highlights h JOIN items i ON i.id = h.itemId
        WHERE h.itemId = :itemId
        ORDER BY h.startSelector, h.startOffset
        """
    )
    suspend fun forItemWithArticle(itemId: String): List<HighlightWithArticle>
}

/** A collection with the number of items filed directly in it, for the library tree. */
data class CollectionWithCount(
    val id: String,
    val name: String,
    val parentId: String?,
    val icon: String?,
    val viewMode: String?,
    val sortOrder: Int,
    val count: Int,
)

@Dao
interface CollectionDao {
    @Upsert
    suspend fun upsert(collection: CollectionEntity)

    @Query("SELECT * FROM collections ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query(
        """
        SELECT c.id AS id, c.name AS name, c.parentId AS parentId, c.icon AS icon,
               c.viewMode AS viewMode, c.sortOrder AS sortOrder,
               (SELECT COUNT(*) FROM items i WHERE i.collectionId = c.id) AS count
        FROM collections c
        ORDER BY c.sortOrder, c.name COLLATE NOCASE
        """
    )
    fun observeWithCounts(): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun get(id: String): CollectionEntity?

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE collections SET parentId = :parentId WHERE id = :id")
    suspend fun setParent(id: String, parentId: String?)

    @Query("UPDATE collections SET viewMode = :mode WHERE id = :id")
    suspend fun setViewMode(id: String, mode: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SyncDao {
    @Insert
    suspend fun enqueue(op: SyncOpEntity)

    @Query("SELECT * FROM sync_ops ORDER BY createdAt")
    suspend fun pending(): List<SyncOpEntity>

    @Query("DELETE FROM sync_ops WHERE id = :id")
    suspend fun remove(id: String)

    @Upsert
    suspend fun tombstone(tombstone: TombstoneEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE itemId = :itemId)")
    suspend fun isTombstoned(itemId: String): Boolean
}
