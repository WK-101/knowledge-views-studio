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

    suspend fun delete(id: String) = sourceDao.delete(id)
}
