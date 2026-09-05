package com.cairn.reader.data.repo

import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.SourceDao
import com.cairn.reader.data.db.SourceEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRepository @Inject constructor(
    private val sourceDao: SourceDao,
    private val itemDao: ItemDao,
    private val blobStore: BlobStore,
) {
    fun sources(): Flow<List<SourceEntity>> = sourceDao.observeAll()
    fun folders(): Flow<List<String>> = sourceDao.observeFolders()

    suspend fun get(id: String): SourceEntity? = sourceDao.getById(id)

    suspend fun setTitle(id: String, title: String) = title.trim().takeIf { it.isNotBlank() }?.let { sourceDao.setTitle(id, it) }
    suspend fun setFolder(id: String, folder: String?) = sourceDao.setFolder(id, folder?.trim()?.ifBlank { null })
    suspend fun setFullText(id: String, enabled: Boolean) = sourceDao.setFullText(id, enabled)
    suspend fun setNotify(id: String, enabled: Boolean) = sourceDao.setNotify(id, enabled)
    suspend fun setMuted(id: String, enabled: Boolean) = sourceDao.setMuted(id, enabled)
    suspend fun setPodcast(id: String, enabled: Boolean) = sourceDao.setPodcast(id, enabled)
    suspend fun setOpenIn(id: String, mode: String) = sourceDao.setOpenIn(id, mode)
    suspend fun setMaxItems(id: String, maxItems: Int?) = sourceDao.setMaxItems(id, maxItems)

    /** Change where a feed pulls from. Normalises http→https-friendly input and resets sync state. */
    suspend fun setFeedUrl(id: String, feedUrl: String) {
        val url = feedUrl.trim()
        if (url.isBlank()) return
        sourceDao.setFeedUrl(id, url)
    }

    /**
     * Unsubscribe from a feed. Remove its own articles (blob + search index + row) so they stop
     * cluttering the Inbox, but keep anything the user explicitly kept — starred, saved, archived,
     * filed in a collection, highlighted, or a permanent offline copy — which detaches from the
     * (now-deleted) source and remains available in the Library.
     */
    suspend fun delete(id: String) {
        itemDao.unkeptIdsBySource(id).forEach { itemId ->
            val e = itemDao.getItem(itemId)
            runCatching { blobStore.deleteAllFor(itemId, e?.blobPath) }
            itemDao.deleteFts(itemId)
            itemDao.deleteItem(itemId)
        }
        sourceDao.delete(id)
    }
}
