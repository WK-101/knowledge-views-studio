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

    // -- Nested tags (path-nested "parent/child" names) ------------------------

    /** Rename a tag and every descendant, rewriting the shared path prefix. E.g. renaming
     *  "tech" to "technology" also turns "tech/ai" into "technology/ai". When the new name
     *  collides with an existing tag, the two are merged (links moved, the duplicate dropped). */
    suspend fun renameSubtree(oldPath: String, newPath: String) {
        val from = oldPath.trim().trim('/')
        val to = newPath.trim().trim('/')
        if (from.isBlank() || to.isBlank() || from == to) return
        val affected = tagDao.allTags().filter { it.name == from || it.name.startsWith("$from/") }
        affected.forEach { tag ->
            val suffix = tag.name.substring(from.length) // "" for the node itself, "/child" for descendants
            val target = to + suffix
            val targetNorm = target.lowercase()
            val existing = tagDao.findByNormalized(targetNorm)
            if (existing != null && existing.id != tag.id) {
                // Merge into the tag that already owns this path.
                tagDao.moveLinks(tag.id, existing.id)
                tagDao.delete(tag.id)
            } else {
                tagDao.rename(tag.id, target, targetNorm)
            }
        }
    }

    /** Move a tag (and its descendants) under a new parent path; null lifts it to the top level. */
    suspend fun moveSubtree(path: String, newParent: String?) {
        val p = path.trim().trim('/')
        if (p.isBlank()) return
        val leaf = p.substringAfterLast('/')
        val parent = newParent?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
        // Refuse to move a tag under itself or its own descendant.
        if (parent != null && (parent == p || parent.startsWith("$p/"))) return
        val target = if (parent == null) leaf else "$parent/$leaf"
        renameSubtree(p, target)
    }

    /** Delete a tag and every tag nested beneath it. */
    suspend fun deleteSubtree(path: String) {
        val p = path.trim().trim('/')
        if (p.isBlank()) return
        tagDao.allTags().filter { it.name == p || it.name.startsWith("$p/") }.forEach { tagDao.delete(it.id) }
    }

    private suspend fun enqueue(op: String, itemId: String, tagId: String) {
        syncDao.enqueue(SyncOpEntity(id = UUID.randomUUID().toString(), op = op, itemId = itemId, fields = tagId, createdAt = clock()))
    }
}
