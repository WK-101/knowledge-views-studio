package com.wkhan.hexis.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.wkhan.hexis.data.entity.BridgeAuditEntity
import kotlinx.coroutines.flow.Flow

/** Append-and-cap access to the bridge audit log. The registry trims to a bounded recent window. */
@Dao
interface BridgeAuditDao {
    @Query("SELECT * FROM bridge_audit_log ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BridgeAuditEntity>>

    @Query("SELECT * FROM bridge_audit_log ORDER BY atMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BridgeAuditEntity>

    @Insert
    suspend fun insert(entry: BridgeAuditEntity)

    /** Keep only the most recent [keep] entries, dropping older ones. */
    @Query(
        "DELETE FROM bridge_audit_log WHERE id NOT IN " +
            "(SELECT id FROM bridge_audit_log ORDER BY atMillis DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM bridge_audit_log")
    suspend fun clear()
}
