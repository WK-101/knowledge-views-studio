package app.parley.data.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * A call mirrored from the system call log. Everything that identifies the other person (number, cached name,
 * SIM) is in [blob], AES-GCM sealed by [HistoryCrypto]. Kept in the clear: time, duration and type (needed
 * for retention and ordering) and keyed fingerprints for deduplication and "keep forever".
 */
@Entity(
    tableName = "archived_calls",
    indices = [Index(value = ["dedupeKey"], unique = true), Index("personKey"), Index("date")],
)
data class ArchivedCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** HMAC of [app.parley.common.history.HistoryMerge.key]. */
    val dedupeKey: String,
    /** HMAC of the number's E.164 key. */
    val personKey: String,
    val date: Long,
    val durationSec: Long,
    val type: Int,
    val blob: ByteArray,
    val archivedAt: Long,
)

/** A number whose archived calls ignore the retention setting. */
@Entity(tableName = "keep_forever")
data class KeepForeverEntity(
    @PrimaryKey val personKey: String,
    /** The number, sealed. */
    val blob: ByteArray,
    val createdAt: Long,
)

/** Calls deleted through Parley, kept sealed for 30 days so the delete can be undone. */
@Entity(tableName = "call_trash", indices = [Index("batchId")])
data class TrashedCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val deletedAt: Long,
    /** Sealed CallLogRecord JSON. */
    val blob: ByteArray,
)

data class TrashBatch(val batchId: Long, val deletedAt: Long, val count: Int)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM archived_calls ORDER BY date DESC")
    suspend fun all(): List<ArchivedCallEntity>

    /** Ids of the newest [limit] rows (the window kept decrypted in memory). */
    @Query("SELECT id FROM archived_calls ORDER BY date DESC, id DESC LIMIT :limit")
    suspend fun newestIds(limit: Int): List<Long>

    /** The newest [limit] rows: the first page of a whole-archive read. */
    @Query("SELECT * FROM archived_calls ORDER BY date DESC, id DESC LIMIT :limit")
    suspend fun firstPage(limit: Int): List<ArchivedCallEntity>

    /**
     * The [limit] rows after (older than) the row at ([date], [id]), newest first. Keyset paging: each page starts
     * from the date index where the last one ended, so reading the whole archive costs one pass, not a pass per page
     * as with OFFSET. The date index holds the row id too, so (date, id) is the order it is walked in.
     */
    @Query(
        "SELECT * FROM archived_calls WHERE date <= :date AND (date < :date OR id < :id) ORDER BY date DESC, id DESC LIMIT :limit",
    )
    suspend fun pageBefore(date: Long, id: Long, limit: Int): List<ArchivedCallEntity>

    /** Every row filed under one of these line fingerprints, newest first (one person's calls, through the index). */
    @Query("SELECT * FROM archived_calls WHERE personKey IN (:personKeys) ORDER BY date DESC, id DESC")
    suspend fun byPersons(personKeys: List<String>): List<ArchivedCallEntity>

    @Query("SELECT * FROM archived_calls WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ArchivedCallEntity>

    @Query("DELETE FROM archived_calls WHERE personKey = :personKey")
    suspend fun deleteByPerson(personKey: String): Int

    @Query("SELECT MAX(date) FROM archived_calls")
    suspend fun newest(): Long?

    @Query("SELECT MIN(date) FROM archived_calls")
    suspend fun oldest(): Long?

    @Query("SELECT COUNT(*) FROM archived_calls")
    suspend fun count(): Int

    @Query("SELECT MAX(id) FROM archived_calls")
    suspend fun maxId(): Long?

    @Query("SELECT COUNT(*) FROM archived_calls WHERE id > :after")
    suspend fun countAfter(after: Long): Int

    /** Rows archived after row [after], oldest first, a page at a time (number memory's incremental read). */
    @Query("SELECT * FROM archived_calls WHERE id > :after ORDER BY id LIMIT :limit")
    suspend fun pageAfter(after: Long, limit: Int): List<ArchivedCallEntity>

    /** Row ids, -1 for a call already archived (the unique dedupe key turns it away). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<ArchivedCallEntity>): List<Long>

    @Query("DELETE FROM archived_calls WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM archived_calls WHERE dedupeKey IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)

    @Query("DELETE FROM archived_calls WHERE date < :before AND personKey NOT IN (:keep)")
    suspend fun deleteOlderThan(before: Long, keep: List<String>): Int

    @Query("DELETE FROM archived_calls")
    suspend fun clear()

    @Query("SELECT * FROM keep_forever")
    suspend fun keepForever(): List<KeepForeverEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addKeepForever(e: List<KeepForeverEntity>)

    @Query("DELETE FROM keep_forever WHERE personKey IN (:keys)")
    suspend fun removeKeepForever(keys: List<String>)

    @Insert
    suspend fun trash(rows: List<TrashedCallEntity>)

    @Query("SELECT batchId, MAX(deletedAt) AS deletedAt, COUNT(*) AS count FROM call_trash GROUP BY batchId ORDER BY deletedAt DESC")
    suspend fun trashBatches(): List<TrashBatch>

    @Query("SELECT * FROM call_trash WHERE batchId = :batchId")
    suspend fun trashed(batchId: Long): List<TrashedCallEntity>

    @Query("DELETE FROM call_trash WHERE batchId = :batchId")
    suspend fun deleteBatch(batchId: Long)

    @Query("DELETE FROM call_trash WHERE deletedAt < :before")
    suspend fun pruneTrash(before: Long)

    @Query("DELETE FROM call_trash")
    suspend fun clearTrash(): Int

    @Query("SELECT COUNT(*) FROM call_trash")
    suspend fun trashCount(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(blob)), 0) FROM call_trash")
    suspend fun trashBytes(): Long
}

/** Feature-scoped database for the call-history archive (separate from [app.parley.data.db.AppDatabase]). */
@Database(
    entities = [ArchivedCallEntity::class, KeepForeverEntity::class, TrashedCallEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao

    companion object {
        const val NAME = "parley-history.db"

        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, NAME).build()
    }
}
