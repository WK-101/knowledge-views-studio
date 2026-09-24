package app.parley.data.db

import android.content.Context
import androidx.room.AutoMigration
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

/** Snapshot of a contact taken before Parley deleted, edited or merged it (30-day undo). */
@Entity(tableName = "journal")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactKey: String,
    val displayName: String,
    /** DELETE, EDIT, MERGE, SEPARATE, IMPORT, RESTORE */
    val action: String,
    val time: Long,
    /** Serialized ContactRecord (JSON, gzip). */
    val payload: ByteArray,
    val restored: Boolean = false,
)

data class JournalRow(val id: Long, val contactKey: String, val displayName: String, val action: String, val time: Long, val restored: Boolean)

/** Contacts that delete themselves after a date (plumber, delivery driver…). */
@Entity(tableName = "temporary_contacts")
data class TemporaryContactEntity(
    @PrimaryKey val lookupKey: String,
    val contactId: Long,
    val expiresAt: Long,
    val purgeHistory: Boolean,
)

/** Parley-only information about a contact that has no ContactsContract home. */
@Entity(tableName = "contact_meta")
data class ContactMetaEntity(
    @PrimaryKey val lookupKey: String,
    /** Shown on the incoming-call screen. */
    val pinnedNote: String? = null,
    /** Package of the preferred messenger for "Call with…/Message with…" (null = phone). */
    val preferredMessenger: String? = null,
    /** "Reach out every N days" nudge; null = off. */
    val reachOutDays: Int? = null,
    val lastNudgedAt: Long? = null,
)

/** Private vault contact. All personal fields are AES-GCM encrypted by the app. */
@Entity(tableName = "vault_contacts")
data class VaultContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Name + number labels, readable with the "caller ID" key even while the phone is locked. */
    val callerIdBlob: ByteArray,
    /** Everything else (full ContactRecord), needs the strong key (biometric/device credential). */
    val detailBlob: ByteArray,
    val expiresAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** HMAC of each vault phone number (E.164 / last digits) so caller ID can match without decrypting. */
@Entity(tableName = "vault_numbers", primaryKeys = ["vaultId", "hmac"])
data class VaultNumberEntity(val vaultId: Long, val hmac: String)

/** Calls with vault contacts, removed from the system call log. */
@Entity(tableName = "private_calls")
data class PrivateCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vaultId: Long,
    /** Encrypted number + details. */
    val blob: ByteArray,
    val date: Long,
    val durationSec: Long,
    val type: Int,
)

/** Notes attached to a call (from the in-call screen or history). */
@Entity(tableName = "call_notes")
data class CallNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val numberKey: String,
    val callDate: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface MetaDao {
    /** List columns only: payloads can be large and are read one at a time on restore. */
    @Query("SELECT id, contactKey, displayName, action, time, restored FROM journal WHERE time > :since ORDER BY time DESC")
    fun journal(since: Long): Flow<List<JournalRow>>

    @Query("DELETE FROM journal WHERE contactKey = :key")
    suspend fun deleteJournalFor(key: String)

    @Insert
    suspend fun addJournal(e: JournalEntity): Long

    @Query("SELECT * FROM journal WHERE id = :id")
    suspend fun journalEntry(id: Long): JournalEntity?

    @Query("UPDATE journal SET restored = 1 WHERE id = :id")
    suspend fun markRestored(id: Long)

    @Query("DELETE FROM journal WHERE time < :before")
    suspend fun pruneJournal(before: Long)

    @Query("SELECT COUNT(*) FROM journal")
    suspend fun journalCount(): Int

    @Query("SELECT * FROM temporary_contacts ORDER BY expiresAt")
    fun temporaryContacts(): Flow<List<TemporaryContactEntity>>

    @Query("SELECT * FROM temporary_contacts WHERE expiresAt <= :now")
    suspend fun expiredContacts(now: Long): List<TemporaryContactEntity>

    @Upsert
    suspend fun setTemporary(e: TemporaryContactEntity)

    @Query("DELETE FROM temporary_contacts WHERE lookupKey = :key")
    suspend fun clearTemporary(key: String)

    @Query("SELECT * FROM contact_meta WHERE lookupKey = :key")
    suspend fun meta(key: String): ContactMetaEntity?

    @Query("SELECT * FROM contact_meta")
    fun allMeta(): Flow<List<ContactMetaEntity>>

    @Upsert
    suspend fun setMeta(e: ContactMetaEntity)

    @Insert
    suspend fun addCallNote(n: CallNoteEntity): Long

    @Query("SELECT * FROM call_notes WHERE numberKey = :key ORDER BY callDate DESC")
    fun callNotes(key: String): Flow<List<CallNoteEntity>>

    @Query("SELECT * FROM call_notes ORDER BY callDate DESC")
    fun allCallNotes(): Flow<List<CallNoteEntity>>

    @Query("DELETE FROM call_notes WHERE id = :id")
    suspend fun deleteCallNote(id: Long)
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_contacts ORDER BY createdAt")
    fun contacts(): Flow<List<VaultContactEntity>>

    @Query("SELECT * FROM vault_contacts")
    suspend fun all(): List<VaultContactEntity>

    @Query("SELECT * FROM vault_contacts WHERE id = :id")
    suspend fun get(id: Long): VaultContactEntity?

    @Upsert
    suspend fun upsert(e: VaultContactEntity): Long

    @Query("DELETE FROM vault_contacts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM vault_numbers WHERE vaultId = :id")
    suspend fun clearNumbers(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addNumbers(n: List<VaultNumberEntity>)

    @Query("SELECT vaultId FROM vault_numbers WHERE hmac IN (:hmacs) LIMIT 1")
    suspend fun findByHmac(hmacs: List<String>): Long?

    @Query("SELECT * FROM private_calls ORDER BY date DESC")
    fun privateCalls(): Flow<List<PrivateCallEntity>>

    @Insert
    suspend fun addPrivateCall(c: PrivateCallEntity)

    @Query("DELETE FROM private_calls WHERE vaultId = :id")
    suspend fun deletePrivateCalls(id: Long)

    @Query("DELETE FROM private_calls WHERE id = :id")
    suspend fun deletePrivateCall(id: Long)

    @Query("SELECT * FROM vault_contacts WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun expired(now: Long): List<VaultContactEntity>
}

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

    @Query("SELECT MAX(time) FROM blocked_calls WHERE number = :number")
    suspend fun lastBlocked(number: String): Long?
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
    entities = [
        BlockRuleEntity::class, BlockedCallEntity::class, SpeedDialEntity::class, NumberSimEntity::class,
        JournalEntity::class, TemporaryContactEntity::class, ContactMetaEntity::class,
        VaultContactEntity::class, VaultNumberEntity::class, PrivateCallEntity::class, CallNoteEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockDao(): BlockDao
    abstract fun prefsDao(): PrefsDao
    abstract fun metaDao(): MetaDao
    abstract fun vaultDao(): VaultDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "parley.db").build()
    }
}
