package com.cairn.reader.data.repo

import com.cairn.reader.data.db.CollectionDao
import com.cairn.reader.data.db.CollectionEntity
import com.cairn.reader.data.db.CollectionWithCount
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.SyncDao
import com.cairn.reader.data.db.SyncOpEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collections are the single "home" for a saved item (the Raindrop model): an item
 * lives in at most one collection, and tags carry the many-to-many axis. Deleting a
 * collection promotes its children to the top level and returns its items to Unsorted
 * rather than deleting them.
 */
@Singleton
class CollectionRepository @Inject constructor(
    private val collectionDao: CollectionDao,
    private val itemDao: ItemDao,
    private val syncDao: SyncDao,
) {
    private val clock: () -> Long = { System.currentTimeMillis() }

    fun collections(): Flow<List<CollectionWithCount>> = collectionDao.observeWithCounts()

    suspend fun create(name: String, parentId: String? = null): String {
        val id = UUID.randomUUID().toString()
        collectionDao.upsert(
            CollectionEntity(id = id, name = name.trim(), parentId = parentId, sortOrder = clock().toInt()),
        )
        return id
    }

    suspend fun rename(id: String, name: String) = collectionDao.rename(id, name.trim())
    suspend fun setParent(id: String, parentId: String?) = collectionDao.setParent(id, parentId)
    suspend fun setViewMode(id: String, mode: String) = collectionDao.setViewMode(id, mode)

    suspend fun delete(id: String) {
        collectionDao.promoteChildren(id)
        itemDao.clearCollection(id)
        collectionDao.delete(id)
    }

    /** File an item into a collection (or null to remove it from every collection → Unsorted).
     *  Additive: filing into a new collection keeps existing memberships (items can live in many). */
    suspend fun moveItem(itemId: String, collectionId: String?) {
        if (collectionId == null) {
            itemDao.clearItemCollections(itemId)
            itemDao.setCollection(itemId, null)
        } else {
            itemDao.setInCollection(itemId, collectionId, true)
        }
        syncDao.enqueue(
            SyncOpEntity(id = UUID.randomUUID().toString(), op = "moveToCollection", itemId = itemId, fields = collectionId, createdAt = clock()),
        )
    }

    /** Whether an item is currently filed in a given collection. */
    fun collectionsFor(itemId: String): kotlinx.coroutines.flow.Flow<List<String>> = itemDao.observeCollectionIdsFor(itemId)

    /** Toggle one collection membership for an item (many-to-many). */
    suspend fun setInCollection(itemId: String, collectionId: String, inIt: Boolean) {
        itemDao.setInCollection(itemId, collectionId, inIt)
        syncDao.enqueue(
            SyncOpEntity(id = UUID.randomUUID().toString(), op = "moveToCollection", itemId = itemId, fields = collectionId, createdAt = clock()),
        )
    }
}
