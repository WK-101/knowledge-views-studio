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

    suspend fun setFolder(id: String, folder: String?) = sourceDao.setFolder(id, folder?.trim()?.ifBlank { null })
    suspend fun setFullText(id: String, enabled: Boolean) = sourceDao.setFullText(id, enabled)
    suspend fun setNotify(id: String, enabled: Boolean) = sourceDao.setNotify(id, enabled)

    suspend fun delete(id: String) = sourceDao.delete(id)
}
