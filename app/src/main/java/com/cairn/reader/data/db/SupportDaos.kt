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

@Dao
interface HighlightDao {
    @Upsert
    suspend fun upsert(highlight: HighlightEntity)

    @Query("SELECT * FROM highlights WHERE itemId = :itemId ORDER BY startOffset")
    fun observeForItem(itemId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HighlightEntity>>

    @Query("DELETE FROM highlights WHERE id = :id")
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
