package com.cairn.reader.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    /** Enabled rules in evaluation order — used by the engine on each new item. */
    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun enabledRules(): List<RuleEntity>

    @Query("SELECT * FROM rules ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: String): RuleEntity?

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RuleEntity)

    @Update
    suspend fun update(rule: RuleEntity)

    @Delete
    suspend fun delete(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}
