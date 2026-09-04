package com.cairn.reader.data.repo

import com.cairn.reader.data.db.ItemTagCrossRef
import com.cairn.reader.data.db.TagDao
import com.cairn.reader.data.db.TagEntity
import com.cairn.reader.data.db.TagWithCount
import com.cairn.reader.data.db.SyncDao
import com.cairn.reader.data.db.SyncOpEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Flat, many-to-many tags (the Raindrop model's cross-cutting axis). Tags are matched
 *  case-insensitively by a normalized name so "AI" and "ai" are the same tag. */
@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao,
    private val syncDao: SyncDao,
) {
    private val clock: () -> Long = { System.currentTimeMillis() }

    fun allWithCounts(): Flow<List<TagWithCount>> = tagDao.observeAllWithCounts()
    fun tagsForItem(itemId: String): Flow<List<TagEntity>> = tagDao.observeTagsForItem(itemId)

    /** Attach a tag by name to an item, creating the tag if it doesn't exist. */
    suspend fun addToItem(itemId: String, rawName: String) {
        val name = rawName.trim()
        if (name.isBlank()) return
        val normalized = name.lowercase()
        val existing = tagDao.findByNormalized(normalized)
        val tagId = existing?.id ?: UUID.randomUUID().toString().also {
            tagDao.upsert(TagEntity(id = it, name = name, normalizedName = normalized))
        }
        tagDao.link(ItemTagCrossRef(itemId = itemId, tagId = tagId))
        enqueue("addTag", itemId, tagId)
    }

    suspend fun removeFromItem(itemId: String, tagId: String) {
        tagDao.unlink(itemId, tagId)
        enqueue("removeTag", itemId, tagId)
    }

    suspend fun rename(id: String, name: String) = tagDao.rename(id, name.trim(), name.trim().lowercase())
    suspend fun delete(id: String) = tagDao.delete(id)

    private suspend fun enqueue(op: String, itemId: String, tagId: String) {
        syncDao.enqueue(SyncOpEntity(id = UUID.randomUUID().toString(), op = op, itemId = itemId, fields = tagId, createdAt = clock()))
    }
}
