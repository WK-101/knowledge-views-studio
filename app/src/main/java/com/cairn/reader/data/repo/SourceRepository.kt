package com.cairn.reader.data.repo

import com.cairn.reader.data.db.SourceDao
import com.cairn.reader.data.db.SourceEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRepository @Inject constructor(
    private val sourceDao: SourceDao,
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

    suspend fun delete(id: String) = sourceDao.delete(id)
}
