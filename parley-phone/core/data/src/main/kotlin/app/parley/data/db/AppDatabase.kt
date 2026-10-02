package app.parley.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
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
import androidx.room.Update
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
    /** BLOCK or ALLOW (v3). */
    @ColumnInfo(defaultValue = "BLOCK") val kind: String = "BLOCK",
    /** Phone-account id the rule is limited to. */
    val simId: String? = null,
    /** Encoded [app.parley.common.Schedule], null = always. */
    val schedule: String? = null,
    @ColumnInfo(defaultValue = "DEFAULT") val notify: String = "DEFAULT",
    val ringtone: String? = null,
    /** Temporary rules ("Allow for 24 h"). */
    val expiresAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val hitCount: Int = 0,
    val lastHitAt: Long? = null,
    /** Label title for label rules. */
    val label: String? = null,
)

/**
 * Screened calls: every blocked call, plus unknown callers that were let through (so "Why did this ring?" can
 * show the stored decision trace). The table keeps its v1 name.
 */
@Entity(tableName = "blocked_calls", indices = [Index(value = ["number", "time"]), Index(value = ["time"])])
data class BlockedCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String?,
    val reason: String,
    val action: String,
    val time: Long,
    /** True for calls that were let through (v3). */
    @ColumnInfo(defaultValue = "0") val allowed: Boolean = false,
    /** Decision trace, JSON (see [app.parley.common.TraceCodec]). */
    val trace: String? = null,
    /** One-line verdict ("Blocked by rule 'Telemarketing' · 7 calls"). */
    val verdict: String? = null,
    /** VerdictKind name. */
    val verdictKind: String? = null,
    val ruleId: Long? = null,
    val packId: String? = null,
    @ColumnInfo(defaultValue = "0") val failedOpen: Boolean = false,
    val callerName: String? = null,
    val simId: String? = null,
)

/**
 * How long an incoming call rang before it was answered or given up (for the one-ring "wangiri" guard). [numberKey]
 * is [app.parley.common.PhoneIdentity.key]; rows from before the phone-key migration may still hold the last digits.
 */
@Entity(tableName = "call_rings", indices = [Index(value = ["numberKey"])])
data class CallRingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val numberKey: String,
    val startedAt: Long,
    val ringMs: Long,
    val answered: Boolean,
)

@Entity(tableName = "speed_dial")
data class SpeedDialEntity(
    @PrimaryKey val key: Int,
    val number: String,
    val label: String?,
)

/**
 * Remembered SIM per number. The column keeps its first name, but holds [app.parley.common.PhoneIdentity.key] (rows
 * from before the phone-key migration may still hold the last digits, which lookups still read).
 */
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

/**
 * Contacts that delete themselves after a date (plumber, delivery driver…). Create and change them through
 * [app.parley.data.TemporaryContacts] (creating) and [app.parley.data.people.TemporaryContactStore], never directly.
 */
@Entity(tableName = "temporary_contacts")
data class TemporaryContactEntity(
    @PrimaryKey val lookupKey: String,
    val contactId: Long,
    val expiresAt: Long,
    val purgeHistory: Boolean,
    /**
     * The raw contacts that make up the temporary contact, comma-separated (v4). Only these are ever deleted; null
     * for entries made before v4, which are only deleted while they are still a single raw contact.
     */
    val rawIds: String? = null,
    /** Name at the time it was made temporary, for the "expired" notice (v4). */
    val name: String? = null,
    /** "Keep this contact?" was already asked after an edit (v4). */
    @ColumnInfo(defaultValue = "0") val keepAsked: Boolean = false,
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
    /** Last known contact id, so the key can be re-resolved after it changes (v4, F8). */
    val contactId: Long? = null,
    /** Relation name -> related contact's lookup key ([app.parley.common.people.RelationLinks], v4, F23). */
    val relationLinks: String? = null,
    /** Circle rhythm, JSON ([app.parley.common.circle.KeepRhythm]); null = every [reachOutDays] days (v5). */
    val rhythm: String? = null,
    /** Life events remembered yearly, one [app.parley.common.circle.YearlyEvents] key per line (v6). */
    val yearlyEvents: String? = null,
)

/**
 * A contact that isn't a phone call (met, messaged, video call, other), keyed to the contact's lookup key like
 * contact_meta (re-keyed by [app.parley.data.people.ContactKeys]). The note is sealed with the Keystore key Parley
 * uses for small private records ([app.parley.data.circle.InteractionStore]); nothing personal is stored in clear.
 * [dedupeKey] is unique, so a "Log this?" accepted twice or a restore run twice records one entry (v5).
 */
@Entity(tableName = "interactions", indices = [Index(value = ["dedupeKey"], unique = true), Index(value = ["lookupKey", "time"])])
data class InteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lookupKey: String,
    /** Last known contact id, like contact_meta.contactId. */
    val contactId: Long? = null,
    /** [app.parley.common.circle.InteractionType] name. */
    val type: String,
    /** [app.parley.common.circle.InteractionChannel] name when logged from a launch; null when added by hand. */
    val channel: String? = null,
    /** When it happened (edits keep it unless the user changes it). */
    val time: Long,
    /** Sealed note, or null. */
    val noteBlob: ByteArray? = null,
    val dedupeKey: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** When and how you were in touch, without opening any note. */
data class InteractionTouchRow(val lookupKey: String, val time: Long, val dedupeKey: String)

/** A stored interaction key and its last known contact id. */
data class InteractionKeyRow(val lookupKey: String, val contactId: Long?)

/** Newest interaction per contact, for the Circle list. */
data class LastInteractionRow(val lookupKey: String, val time: Long, val type: String)

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

/**
 * A vault row without its sealed details: what listings, caller ID and the call path read. The details (up to hundreds
 * of kB for a contact moved in with its photo) are left in the database.
 */
data class VaultCallerRow(val id: Long, val callerIdBlob: ByteArray, val expiresAt: Long?, val createdAt: Long) {
    override fun equals(other: Any?): Boolean =
        other is VaultCallerRow && id == other.id && expiresAt == other.expiresAt && createdAt == other.createdAt &&
            callerIdBlob.contentEquals(other.callerIdBlob)

    override fun hashCode(): Int = id.hashCode() * 31 + callerIdBlob.contentHashCode()
}

/** HMAC of each vault phone number (E.164 / last digits) so caller ID can match without decrypting. */
@Entity(tableName = "vault_numbers", primaryKeys = ["vaultId", "hmac"], indices = [Index(value = ["hmac"])])
data class VaultNumberEntity(val vaultId: Long, val hmac: String)

/**
 * Calls with vault contacts, removed from the system call log. [dedupeKey] ("vaultId|date|type") is unique, so a
 * sweep that runs twice (the provider delete failed after the insert) or a restore run twice stores each call once;
 * rows from before v7 have none.
 */
@Entity(tableName = "private_calls", indices = [Index(value = ["dedupeKey"], unique = true)])
data class PrivateCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vaultId: Long,
    /** Encrypted number + details. */
    val blob: ByteArray,
    val date: Long,
    val durationSec: Long,
    val type: Int,
    val dedupeKey: String? = null,
) {
    companion object {
        fun dedupeKey(vaultId: Long, date: Long, type: Int) = "$vaultId|$date|$type"
    }
}

/**
 * Notes attached to a call (from the in-call screen or history). [numberKey] is [app.parley.common.PhoneIdentity.key];
 * notes from before the phone-key migration may still hold the last digits (read with
 * [app.parley.common.PhoneIdentity.lookupKeys]).
 */
@Entity(tableName = "call_notes", indices = [Index(value = ["numberKey"])])
data class CallNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val numberKey: String,
    val callDate: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One connected call, written by the call path when the call ends: the usage ledger that call-time allowances count
 * (supervision included). Unlike the system call log, nobody else can clear or trim it, and it has the calls with
 * private contacts too. Kept for [app.parley.data.calltime.CallUsageLedger.KEEP_DAYS] days.
 */
@Entity(tableName = "call_usage", indices = [Index(value = ["startedAt"])])
data class CallUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Connect time (wall clock). */
    val startedAt: Long,
    val durationSec: Long,
    val incoming: Boolean,
    /** [app.parley.common.PhoneIdentity.key] of the other party; null for a withheld number. */
    val lineKey: String?,
    /** Lookup key of the contact at the time of the call, when it was one. */
    val contactKey: String?,
    /** Phone-account (SIM) id. */
    val accountId: String?,
)

@Dao
interface UsageDao {
    @Insert
    suspend fun add(e: CallUsageEntity): Long

    @Query("SELECT * FROM call_usage WHERE startedAt >= :since ORDER BY startedAt DESC")
    suspend fun since(since: Long): List<CallUsageEntity>

    @Query("DELETE FROM call_usage WHERE startedAt < :before")
    suspend fun prune(before: Long)

    @Query("SELECT COUNT(*) FROM call_usage")
    suspend fun count(): Int
}

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

    /** Stored size of the undo copies, for History & undo's storage summary. */
    @Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM journal")
    suspend fun journalBytes(): Long

    @Query("DELETE FROM journal WHERE id = :id")
    suspend fun deleteJournalEntry(id: Long): Int

    @Query("DELETE FROM journal")
    suspend fun clearJournal(): Int

    @Query("SELECT * FROM temporary_contacts ORDER BY expiresAt")
    fun temporaryContacts(): Flow<List<TemporaryContactEntity>>

    @Query("SELECT * FROM temporary_contacts WHERE expiresAt <= :now")
    suspend fun expiredContacts(now: Long): List<TemporaryContactEntity>

    @Upsert
    suspend fun setTemporary(e: TemporaryContactEntity)

    @Query("DELETE FROM temporary_contacts WHERE lookupKey = :key")
    suspend fun clearTemporary(key: String)

    @Query("SELECT * FROM temporary_contacts")
    suspend fun allTemporary(): List<TemporaryContactEntity>

    @Query("SELECT * FROM temporary_contacts WHERE lookupKey = :key")
    suspend fun temporary(key: String): TemporaryContactEntity?

    @Query("SELECT * FROM contact_meta WHERE lookupKey = :key")
    suspend fun meta(key: String): ContactMetaEntity?

    /** One person's row, for a screen that shows it (instead of watching the whole table). */
    @Query("SELECT * FROM contact_meta WHERE lookupKey = :key")
    fun metaFlow(key: String): Flow<ContactMetaEntity?>

    @Query("SELECT * FROM temporary_contacts WHERE lookupKey = :key")
    fun temporaryFlow(key: String): Flow<TemporaryContactEntity?>

    /** Creates the row if missing, so the targeted updates below have one to change. */
    @Query("INSERT OR IGNORE INTO contact_meta (lookupKey, contactId) VALUES (:key, :contactId)")
    suspend fun ensureMeta(key: String, contactId: Long)

    @Query("UPDATE contact_meta SET pinnedNote = :note, contactId = :contactId WHERE lookupKey = :key")
    suspend fun setPinnedNote(key: String, contactId: Long, note: String?)

    @Query("UPDATE contact_meta SET preferredMessenger = :value, contactId = :contactId WHERE lookupKey = :key")
    suspend fun setPreferredMessenger(key: String, contactId: Long, value: String?)

    @Query("SELECT * FROM contact_meta")
    fun allMeta(): Flow<List<ContactMetaEntity>>

    @Query("SELECT * FROM contact_meta")
    suspend fun allMetaNow(): List<ContactMetaEntity>

    /** Restore: only the fields a backup carries, without touching the Circle's own columns. */
    @Query("UPDATE contact_meta SET pinnedNote = :note, preferredMessenger = :messenger, relationLinks = :links, lastNudgedAt = COALESCE(:nudged, lastNudgedAt) WHERE lookupKey = :key")
    suspend fun setPersonalMeta(key: String, note: String?, messenger: String?, links: String?, nudged: Long?): Int

    @Upsert
    suspend fun setMeta(e: ContactMetaEntity)

    @Query("DELETE FROM contact_meta WHERE lookupKey = :key")
    suspend fun deleteMeta(key: String)

    @Query("UPDATE contact_meta SET contactId = :id WHERE lookupKey = :key")
    suspend fun setMetaContactId(key: String, id: Long?)

    @Query("UPDATE contact_meta SET relationLinks = :links WHERE lookupKey = :key")
    suspend fun setRelationLinks(key: String, links: String?)

    /** Targeted writes, so a row read a while ago never overwrites newer edits (pinned note, a re-key). */
    @Query("UPDATE contact_meta SET lastNudgedAt = :at WHERE lookupKey = :key")
    suspend fun setLastNudgedAt(key: String, at: Long?)

    @Query("UPDATE contact_meta SET rhythm = :rhythm WHERE lookupKey = :key")
    suspend fun setMetaRhythm(key: String, rhythm: String?)

    @Insert
    suspend fun addCallNote(n: CallNoteEntity): Long

    @Query("SELECT * FROM call_notes WHERE numberKey = :key ORDER BY callDate DESC")
    fun callNotes(key: String): Flow<List<CallNoteEntity>>

    /** Notes stored under any of [keys] (a line's key and its older last-digits key). */
    @Query("SELECT * FROM call_notes WHERE numberKey IN (:keys) ORDER BY callDate DESC")
    fun callNotesAny(keys: List<String>): Flow<List<CallNoteEntity>>

    @Query("SELECT * FROM call_notes ORDER BY callDate DESC")
    suspend fun allCallNotesNow(): List<CallNoteEntity>

    @Query("SELECT DISTINCT numberKey FROM call_notes")
    suspend fun callNoteKeys(): List<String>

    @Query("UPDATE call_notes SET numberKey = :to WHERE numberKey = :from")
    suspend fun rekeyCallNotes(from: String, to: String)

    @Query("SELECT COUNT(*) FROM call_notes WHERE numberKey = :key AND callDate = :callDate AND text = :text")
    suspend fun countCallNote(key: String, callDate: Long, text: String): Int

    @Query("SELECT * FROM call_notes ORDER BY callDate DESC")
    fun allCallNotes(): Flow<List<CallNoteEntity>>

    @Query("DELETE FROM call_notes WHERE id = :id")
    suspend fun deleteCallNote(id: Long)

    /** The call notes of a person's numbers, newest first. */
    @Query("SELECT * FROM call_notes WHERE numberKey IN (:keys) ORDER BY callDate DESC")
    suspend fun callNotesNow(keys: List<String>): List<CallNoteEntity>

    /** A promise ticked off rewrites its line. */
    @Query("SELECT * FROM call_notes WHERE id = :id")
    suspend fun callNote(id: Long): CallNoteEntity?

    @Query("UPDATE call_notes SET text = :text WHERE id = :id")
    suspend fun setCallNoteText(id: Long, text: String)

    /** Journal ids, for re-sealing older payloads one at a time. */
    @Query("SELECT id FROM journal")
    suspend fun journalIds(): List<Long>

    // Re-sealing older plain values: each write applies only if the value is still the one read (no lost edit).
    @Query("UPDATE journal SET payload = :payload WHERE id = :id AND payload = :old")
    suspend fun resealJournalPayload(id: Long, old: ByteArray, payload: ByteArray)

    @Query("UPDATE contact_meta SET pinnedNote = :note WHERE lookupKey = :key AND pinnedNote = :old")
    suspend fun resealPinnedNote(key: String, old: String, note: String)

    @Query("UPDATE call_notes SET text = :text WHERE id = :id AND text = :old")
    suspend fun resealCallNote(id: Long, old: String, text: String)
}

@Dao
interface InteractionDao {
    @Query("SELECT * FROM interactions WHERE lookupKey = :key ORDER BY time DESC")
    fun forKey(key: String): Flow<List<InteractionEntity>>

    @Query("SELECT * FROM interactions WHERE lookupKey = :key ORDER BY time DESC")
    suspend fun forKeyNow(key: String): List<InteractionEntity>

    @Query("SELECT * FROM interactions ORDER BY time DESC")
    suspend fun all(): List<InteractionEntity>

    /**
     * The newest interaction of each contact (SQLite returns the bare `type` of the row holding MAX(time)). Emits
     * whenever any interaction changes, for lists that show "last in touch".
     */
    @Query("SELECT lookupKey, MAX(time) AS time, type FROM interactions GROUP BY lookupKey")
    fun latestPerKey(): Flow<List<LastInteractionRow>>

    @Query("SELECT lookupKey, time, type FROM interactions WHERE lookupKey = :key ORDER BY time DESC LIMIT 1")
    suspend fun latestFor(key: String): LastInteractionRow?

    @Query("SELECT time FROM interactions WHERE lookupKey = :key")
    suspend fun timesFor(key: String): List<Long>

    @Query("SELECT lookupKey, time, dedupeKey FROM interactions WHERE time >= :since")
    suspend fun touchesSince(since: Long): List<InteractionTouchRow>

    /** Emits on every change (for the Circle widget). */
    @Query("SELECT COUNT(*) FROM interactions")
    fun countFlow(): Flow<Int>

    @Query("SELECT * FROM interactions WHERE id = :id")
    suspend fun get(id: Long): InteractionEntity?

    /** Returns -1 when the dedupe key already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: InteractionEntity): Long

    @Update
    suspend fun update(e: InteractionEntity)

    @Query("DELETE FROM interactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM interactions WHERE dedupeKey = :key")
    suspend fun deleteByDedupe(key: String)

    /** Each stored key with the newest contact id recorded for it (so [app.parley.data.people.ContactKeys] can follow ids). */
    @Query("SELECT lookupKey, MAX(contactId) AS contactId FROM interactions GROUP BY lookupKey")
    suspend fun keys(): List<InteractionKeyRow>

    @Query("UPDATE interactions SET lookupKey = :to, contactId = COALESCE(:toId, contactId) WHERE lookupKey = :from")
    suspend fun rekey(from: String, to: String, toId: Long?)

    @Query("DELETE FROM interactions WHERE lookupKey = :key")
    suspend fun deleteFor(key: String)

    @Query("DELETE FROM interactions")
    suspend fun clear()
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_contacts ORDER BY createdAt")
    fun contacts(): Flow<List<VaultContactEntity>>

    @Query("SELECT * FROM vault_contacts")
    suspend fun all(): List<VaultContactEntity>

    @Query("SELECT * FROM vault_contacts WHERE id = :id")
    suspend fun get(id: Long): VaultContactEntity?

    @Query("SELECT id, callerIdBlob, expiresAt, createdAt FROM vault_contacts ORDER BY createdAt")
    fun callerRows(): Flow<List<VaultCallerRow>>

    @Query("SELECT id, callerIdBlob, expiresAt, createdAt FROM vault_contacts")
    suspend fun callerRowsNow(): List<VaultCallerRow>

    @Query("SELECT id, callerIdBlob, expiresAt, createdAt FROM vault_contacts WHERE id = :id")
    suspend fun callerRow(id: Long): VaultCallerRow?

    /** Replaces the sealed details only if they are still [old] (a save or a re-seal meanwhile wins). Rows changed. */
    @Query("UPDATE vault_contacts SET detailBlob = :blob WHERE id = :id AND detailBlob = :old")
    suspend fun replaceDetailBlob(id: Long, old: ByteArray, blob: ByteArray): Int

    @Upsert
    suspend fun upsert(e: VaultContactEntity): Long

    @Query("DELETE FROM vault_contacts WHERE id = :id")
    suspend fun delete(id: Long)

    /** Targeted writes, so a row read a while ago never brings back an older blob or expiry. */
    @Query("UPDATE vault_contacts SET detailBlob = :blob WHERE id = :id")
    suspend fun setDetailBlob(id: Long, blob: ByteArray)

    @Query("UPDATE vault_contacts SET expiresAt = :expiresAt WHERE id = :id")
    suspend fun setExpiry(id: Long, expiresAt: Long?)

    /** Only the caller-ID copy: a star, a label, a ringtone set without unlocking never rewrites the sealed details. */
    @Query("UPDATE vault_contacts SET callerIdBlob = :blob WHERE id = :id")
    suspend fun setCallerIdBlob(id: Long, blob: ByteArray)

    @Query("DELETE FROM vault_numbers WHERE vaultId = :id")
    suspend fun clearNumbers(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addNumbers(n: List<VaultNumberEntity>)

    @Query("SELECT vaultId FROM vault_numbers WHERE hmac IN (:hmacs) LIMIT 1")
    suspend fun findByHmac(hmacs: List<String>): Long?

    /** Every vault entry with one of these number fingerprints (F15: the caller picks a deterministic winner). */
    @Query("SELECT DISTINCT vaultId FROM vault_numbers WHERE hmac IN (:hmacs)")
    suspend fun idsByHmac(hmacs: List<String>): List<Long>

    @Query("SELECT * FROM private_calls ORDER BY date DESC")
    fun privateCalls(): Flow<List<PrivateCallEntity>>

    @Query("SELECT * FROM private_calls ORDER BY date DESC")
    suspend fun allPrivateCalls(): List<PrivateCallEntity>

    /** Returns -1 when the same call (see [PrivateCallEntity.dedupeKey]) is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPrivateCall(c: PrivateCallEntity): Long

    @Query("SELECT COUNT(*) FROM private_calls WHERE vaultId = :vaultId AND date = :date AND type = :type")
    suspend fun countPrivateCall(vaultId: Long, date: Long, type: Int): Int

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

    /** Re-sealing an older plain caller name (applies only if it is still the one read). */
    @Query("UPDATE blocked_calls SET callerName = :name WHERE id = :id AND callerName = :old")
    suspend fun resealCallerName(id: Long, old: String, name: String)

    @Query("SELECT * FROM blocked_calls WHERE allowed = 0 ORDER BY time DESC LIMIT 500")
    fun blockedCalls(): Flow<List<BlockedCallEntity>>

    /** Every stopped call since [since], uncapped (the weekly line counts them all, L5). */
    @Query("SELECT * FROM blocked_calls WHERE allowed = 0 AND time >= :since ORDER BY time DESC")
    suspend fun blockedCallsSince(since: Long): List<BlockedCallEntity>

    @Query("DELETE FROM blocked_calls")
    suspend fun clearBlocked()

    @Query("SELECT MAX(time) FROM blocked_calls WHERE number = :number AND allowed = 0")
    suspend fun lastBlocked(number: String): Long?

    @Query("SELECT * FROM block_rules")
    suspend fun allRules(): List<BlockRuleEntity>

    @Insert
    suspend fun insertRules(rules: List<BlockRuleEntity>)

    @Query("UPDATE block_rules SET hitCount = hitCount + 1, lastHitAt = :time WHERE id = :id")
    suspend fun recordHit(id: Long, time: Long)

    @Query("DELETE FROM block_rules WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpiredRules(now: Long): Int

    @Insert
    suspend fun logScreened(call: BlockedCallEntity): Long

    @Query("SELECT * FROM blocked_calls ORDER BY time DESC LIMIT 1000")
    fun screenedCalls(): Flow<List<BlockedCallEntity>>

    @Query("SELECT * FROM blocked_calls WHERE time >= :since ORDER BY time DESC")
    suspend fun screenedSince(since: Long): List<BlockedCallEntity>

    @Query("SELECT time FROM blocked_calls WHERE allowed = 0 AND number IN (:numbers) AND time >= :since ORDER BY time DESC")
    suspend fun blockedTimes(numbers: List<String>, since: Long): List<Long>

    @Query("DELETE FROM blocked_calls WHERE id = :id")
    suspend fun deleteScreened(id: Long)

    @Query("DELETE FROM blocked_calls WHERE allowed = 1 AND time < :before")
    suspend fun pruneAllowed(before: Long)

    /** Blocked-call history older than [before], and anything beyond the newest [keep] rows. */
    @Query(
        "DELETE FROM blocked_calls WHERE allowed = 0 AND (time < :before OR id NOT IN " +
            "(SELECT id FROM blocked_calls WHERE allowed = 0 ORDER BY time DESC LIMIT :keep))",
    )
    suspend fun pruneBlocked(before: Long, keep: Int)

    @Insert
    suspend fun addRing(r: CallRingEntity)

    @Query("SELECT * FROM call_rings WHERE startedAt >= :since ORDER BY startedAt DESC")
    fun rings(since: Long): Flow<List<CallRingEntity>>

    @Query("SELECT * FROM call_rings WHERE numberKey = :key ORDER BY startedAt DESC LIMIT 5")
    suspend fun ringsFor(key: String): List<CallRingEntity>

    @Query("SELECT DISTINCT numberKey FROM call_rings")
    suspend fun ringKeys(): List<String>

    @Query("UPDATE call_rings SET numberKey = :to WHERE numberKey = :from")
    suspend fun rekeyRings(from: String, to: String)

    @Query("SELECT * FROM blocked_calls WHERE allowed = 0 ORDER BY time DESC LIMIT 500")
    suspend fun blockedCallsNow(): List<BlockedCallEntity>

    @Query("SELECT COUNT(*) FROM blocked_calls WHERE time = :time AND allowed = 0 AND ((number IS NULL AND :number IS NULL) OR number = :number)")
    suspend fun countBlocked(number: String?, time: Long): Int

    @Query("DELETE FROM call_rings WHERE startedAt < :before")
    suspend fun pruneRings(before: Long)
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

    @Query("SELECT * FROM number_sim")
    suspend fun allSimsNow(): List<NumberSimEntity>

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
        CallRingEntity::class, InteractionEntity::class, CallUsageEntity::class,
    ],
    version = 8,
    exportSchema = true,
    // v3: allow rules, schedules, SIM, hit counters, decision traces, ring lengths (blocking roadmap).
    // v4: temporary contacts remember their raw contact ids; contact metadata remembers the contact id and relation
    //     links (round-4 data-safety fixes F2, F8, F23). Added columns only, all nullable or defaulted.
    // v5: interactions and the Circle rhythm column in contact metadata. A new table and a nullable
    //     column: nothing existing changes.
    // v7: the call-usage ledger (a new table), a nullable dedupe column with a unique index for private calls (old
    //     rows keep NULL, which never conflicts), and indexes on the number columns that are looked up. Additive only.
    //     Stored number keys move to PhoneIdentity.key afterwards, in the app (PhoneKeyMigrator): that needs the
    //     phone's contacts and calls, which a schema migration can't read.
    // v8: an index on vault_numbers.hmac (caller ID looks private numbers up by it while the phone rings). Additive.
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockDao(): BlockDao
    abstract fun prefsDao(): PrefsDao
    abstract fun metaDao(): MetaDao
    abstract fun vaultDao(): VaultDao
    abstract fun interactionDao(): InteractionDao
    abstract fun usageDao(): UsageDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "parley.db").build()
    }
}
