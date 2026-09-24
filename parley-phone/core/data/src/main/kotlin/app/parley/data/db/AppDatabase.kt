package app.parley.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "block_rules")
data class BlockRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val type: String,
    val action: String,
    val enabled: Boolean = true,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "blocked_calls")
data class BlockedCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String?,
    val reason: String,
    val action: String,
    val time: Long,
)

@Entity(tableName = "speed_dial")
data class SpeedDialEntity(
    @PrimaryKey val key: Int,
    val number: String,
    val label: String?,
)

/** Remembered SIM per number (keyed by the last digits of the number). */
@Entity(tableName = "number_sim")
data class NumberSimEntity(
    @PrimaryKey val matchKey: String,
    val phoneAccountId: String,
)

@Dao
interface BlockDao {
    @Query("SELECT * FROM block_rules ORDER BY createdAt DESC")
    fun rules(): Flow<List<BlockRuleEntity>>

    @Query("SELECT * FROM block_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<BlockRuleEntity>

    @Upsert
    suspend fun upsertRule(rule: BlockRuleEntity): Long

    @Query("DELETE FROM block_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Insert
    suspend fun logBlocked(call: BlockedCallEntity)

    @Query("SELECT * FROM blocked_calls ORDER BY time DESC LIMIT 500")
    fun blockedCalls(): Flow<List<BlockedCallEntity>>

    @Query("DELETE FROM blocked_calls")
    suspend fun clearBlocked()
}

@Dao
interface PrefsDao {
    @Query("SELECT * FROM speed_dial ORDER BY `key`")
    fun speedDials(): Flow<List<SpeedDialEntity>>

    @Query("SELECT * FROM speed_dial WHERE `key` = :key")
    suspend fun speedDial(key: Int): SpeedDialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSpeedDial(e: SpeedDialEntity)

    @Query("DELETE FROM speed_dial WHERE `key` = :key")
    suspend fun clearSpeedDial(key: Int)

    @Query("SELECT * FROM number_sim WHERE matchKey = :key")
    suspend fun simFor(key: String): NumberSimEntity?

    @Query("SELECT * FROM number_sim")
    fun allSims(): Flow<List<NumberSimEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSim(e: NumberSimEntity)

    @Query("DELETE FROM number_sim WHERE matchKey = :key")
    suspend fun clearSim(key: String)
}

@Database(
    entities = [BlockRuleEntity::class, BlockedCallEntity::class, SpeedDialEntity::class, NumberSimEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockDao(): BlockDao
    abstract fun prefsDao(): PrefsDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "parley.db").build()
    }
}
