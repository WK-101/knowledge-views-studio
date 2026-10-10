package app.parley.data.backup

import app.parley.common.catching
import app.parley.data.applyInBatches
import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.util.Log
import androidx.core.content.ContextCompat
import app.parley.common.backup.BackupIntegrityException
import app.parley.common.backup.SnapshotDiff
import app.parley.common.backup.SyncWatchMemory
import app.parley.common.backup.SyncWatchdog
import app.parley.common.backup.WatchAccounts
import app.parley.common.backup.WatchEvent
import app.parley.common.backup.WatchKind
import app.parley.common.people.AccountFindingKind
import app.parley.common.people.AccountKey
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A contact that lost numbers: who, and the numbers its last good version had. */
data class LostNumbers(val key: String, val contactId: Long, val name: String, val rows: List<DataRow>)

/**
 * The sync watchdog's device side: after the daily snapshot, compares it with the last one the watchdog saw
 * and the accounts with how they were, keeps what it said in [memory], and brings contacts back ("Restore from
 * snapshot") with an undo. Everything stays on the phone; nothing is read but the snapshots, the journal and the
 * accounts' sync switches (READ_SYNC_SETTINGS).
 */
class SyncWatch(private val c: DataContainer) {
    private val context: Context get() = c.appContext
    private val prefs = c.appContext.getSharedPreferences("sync_watch", Context.MODE_PRIVATE)
    private val _memory = MutableStateFlow(SyncWatchMemory.decode(prefs.getString(KEY, null)))

    /** Its memory; [SyncWatchMemory.pending] are the cards shown in the Contact health check. */
    val memory: StateFlow<SyncWatchMemory> = _memory
    private val lock = Mutex()

    private fun save(m: SyncWatchMemory) {
        prefs.edit().putString(KEY, m.encode()).apply()
        _memory.value = m
    }

    /**
     * The daily look; returns the events never said before (to notify about). Run after the day's snapshot. Without
     * the contacts permission the snapshots can't be trusted (they would look empty), so nothing is checked.
     */
    suspend fun run(now: Long = System.currentTimeMillis()): List<WatchEvent> = lock.withLock {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val snaps = c.timeMachine.snapshotTimes()
        val newest = snaps.lastOrNull() ?: return emptyList()
        val m = _memory.value
        val accounts = accountsNow()
        // The snapshot the last run ended on (or, on the very first run, the one before the newest).
        val old = if (m.baseline > 0) snaps.lastOrNull { it <= m.baseline } else snaps.getOrNull(snaps.size - 2)
        val diff = if (old != null && old < newest) {
            try {
                c.timeMachine.diffBetween(old, newest)
            } catch (e: BackupIntegrityException) {
                Log.w(TAG, "Snapshot unreadable; skipped today", e)
                null
            }
        } else {
            null
        }
        val since = old ?: newest
        // Parley's own deletes, edits and merges since then are the user's doing (with an hour's slack, so one made just
        // before the baseline snapshot, while it was being written, still counts).
        val userKeys = if (diff == null) {
            emptySet()
        } else {
            runCatching { c.meta.journal(since - HOUR).first().map { it.contactKey }.toSet() }.getOrDefault(emptySet())
        }
        val found = SyncWatchdog.check(
            SyncWatchdog.Input(
                since = since, now = now, diff = diff ?: SnapshotDiff(emptyList(), emptyList(), emptyList()),
                userKeys = userKeys, acknowledged = m.acknowledged.keys,
                before = m.accounts?.toWatch(), after = accounts,
            ),
        )
        val (next, fresh) = m.afterRun(found, accounts, newest, now)
        save(expireReturned(next))
        fresh
    }

    /** [m] without the cards whose contacts have all come back by themselves (sync recovered). */
    private suspend fun expireReturned(m: SyncWatchMemory): SyncWatchMemory {
        val cards = m.pending.filter { it.restorable && it.kind != WatchKind.NUMBERS_LOST }
        if (cards.isEmpty()) return m
        val now = withContext(Dispatchers.IO) { addressBook() } ?: return m
        var out = m
        for (e in cards) {
            val all = runCatching { c.timeMachine.lastVersions(e.keys).values.toList() }.getOrNull() ?: continue
            if (all.isNotEmpty() && SyncWatchdog.stillGone(all, now).isEmpty()) out = out.expire(e)
        }
        return out
    }

    /** "It was me": the card goes and these contacts are never reported again. */
    fun dismiss(e: WatchEvent) {
        save(_memory.value.dismiss(e, System.currentTimeMillis()))
    }

    /** Every contact of [e] came back by itself: its card goes (they stay remembered as said). */
    fun expire(e: WatchEvent) {
        save(_memory.value.expire(e))
    }

    /** A restore was undone: its card shows again. */
    fun reopen(e: WatchEvent) {
        save(_memory.value.reopen(e))
    }

    fun pending(fingerprint: String): WatchEvent? = _memory.value.pending.firstOrNull { it.fingerprint == fingerprint }

    /** What a card can still bring back: the contacts still gone, and how many came back by themselves. */
    data class Vanished(val records: List<ContactRecord>, val cameBack: Int)

    /**
     * The last version of each vanished contact of [e], taken before it went (name order), without those that came back
     * since (re-checked against the address book now, so a restore never duplicates them). When all came back, the
     * card goes.
     */
    suspend fun vanished(e: WatchEvent): Vanished = withContext(Dispatchers.IO) {
        val all = c.timeMachine.lastVersions(e.keys).values.toList()
        val gone = stillGone(all) ?: all
        if (all.isNotEmpty() && gone.isEmpty()) save(_memory.value.expire(e))
        Vanished(gone.sortedBy { it.displayName.lowercase() }, all.size - gone.size)
    }

    /** Of [records], those not in the address book now; null when it can't be read (nothing is dropped then). */
    private fun stillGone(records: List<ContactRecord>): List<ContactRecord>? {
        if (records.isEmpty()) return records
        return addressBook()?.let { SyncWatchdog.stillGone(records, it) }
    }

    /** Every contact as it is now (no photos), or null when the address book can't be read. */
    private fun addressBook(): List<ContactRecord>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching { c.records.readAll(fullPhoto = false).toList() }.onFailure { Log.w(TAG, "Couldn't read contacts", it) }.getOrNull()
    }

    /** The contacts of a NUMBERS_LOST event that still exist and still miss numbers, with what they had. */
    suspend fun lostNumbers(e: WatchEvent): List<LostNumbers> = withContext(Dispatchers.IO) {
        if (e.kind != WatchKind.NUMBERS_LOST) return@withContext emptyList()
        val before = c.timeMachine.lastVersions(e.keys, atOrBefore = e.since)
        before.mapNotNull { (key, old) ->
            val id = contactIdFor(key) ?: return@mapNotNull null
            val current = c.records.read(id, fullPhoto = false) ?: return@mapNotNull null
            val rows = SyncWatchdog.lostNumbers(old, current)
            if (rows.isEmpty()) null else LostNumbers(key, id, current.displayName, rows)
        }.sortedBy { it.name.lowercase() }
    }

    /** What a restore wrote: the raw contact ids (for undo), and how many it skipped because they had come back. */
    data class Restored(val rawIds: List<Long>, val restored: Int, val cameBack: Int)

    /**
     * Brings [records] back as new contacts in their accounts. The address book is read again first: a contact that came
     * back since the screen opened is skipped, never written twice. Without a way to check (no permission, an error),
     * nothing is written.
     */
    suspend fun restore(records: List<ContactRecord>): Restored = withContext(Dispatchers.IO) {
        val gone = stillGone(records) ?: return@withContext Restored(emptyList(), 0, 0)
        val ids = if (gone.isEmpty()) emptyList() else c.records.insertAll(gone, target = null).flatMap { it.rawIds }
        c.contacts.refresh()
        Restored(ids, gone.size, records.size - gone.size)
    }

    /** Undo of [restore]: those raw contacts go again (journaled, so it stays in History & undo). */
    suspend fun undoRestore(rawIds: List<Long>) = withContext(Dispatchers.IO) {
        if (rawIds.isNotEmpty()) c.contacts.deleteRaws(rawIds)
        c.contacts.refresh()
    }

    /** Adds the lost numbers back to each contact's editable raw contact; returns the data rows written (for undo). */
    suspend fun restoreNumbers(items: List<LostNumbers>): List<Long> = withContext(Dispatchers.IO) {
        val out = ArrayList<Long>()
        for (item in items) {
            val raw = c.contacts.writableRaws(item.contactId).let { (edit, all) -> edit ?: all.firstOrNull() } ?: continue
            val ops = ArrayList<ContentProviderOperation>()
            for (row in item.rows) {
                val v = ContentValues()
                v.put(Data.RAW_CONTACT_ID, raw)
                v.put(Data.MIMETYPE, row.mimeType)
                // data1..data14 as stored (number, type, label…); flags and photo bytes don't apply to a number.
                for (i in 1..14) row["data$i"]?.let { v.put("data$i", it) }
                ops += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValues(v).build()
            }
            runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }
                .onFailure { Log.w(TAG, "Couldn't add numbers back", it) }
                .getOrNull()?.forEach { r -> r.uri?.let { out += ContentUris.parseId(it) } }
        }
        c.contacts.refresh()
        out
    }

    /** Undo of [restoreNumbers]: the numbers it added go again. */
    suspend fun undoNumbers(dataIds: List<Long>) = withContext(Dispatchers.IO) {
        val ops = dataIds.map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, it)) }
        catching { context.contentResolver.applyInBatches(ops) }
        c.contacts.refresh()
    }

    private fun contactIdFor(key: String): Long? = runCatching {
        ContactsContract.Contacts.lookupContact(context.contentResolver, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key))
            ?.let { ContentUris.parseId(it) }
    }.getOrNull()

    private suspend fun accountsNow(): WatchAccounts {
        val r = c.people.accounts.report()
        val key = { a: app.parley.data.AccountRef -> AccountKey(a.type, a.name) }
        return WatchAccounts(
            signedIn = r.signedIn.map { key(it.first) }.toSet(),
            counts = r.owning.associate { key(it.first) to it.second },
            syncOff = r.findings.filter { it.kind == AccountFindingKind.SYNC_OFF }.mapNotNull { it.account }.toSet(),
            masterSyncOn = r.findings.none { it.kind == AccountFindingKind.MASTER_SYNC_OFF },
            syncKnown = r.syncKnown,
        )
    }

    private companion object {
        const val TAG = "SyncWatch"
        const val KEY = "memory"
        const val HOUR = 3_600_000L
    }
}
