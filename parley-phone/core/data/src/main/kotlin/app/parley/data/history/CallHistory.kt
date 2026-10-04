package app.parley.data.history

import app.parley.common.catching
import app.parley.common.ExplainedFailure
import app.parley.common.security.Bounded
import app.parley.data.compactDatabase
import app.parley.data.tidyDatabase
import app.parley.common.security.LimitExceededException
import android.Manifest
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CallLog.Calls
import android.util.Log
import app.parley.common.CallEntry
import app.parley.common.backup.CallHistoryLine
import app.parley.common.backup.CallLogRecord
import app.parley.common.history.CallCsvImport
import app.parley.common.history.CallLogIndex
import app.parley.common.history.ColumnMapping
import app.parley.common.history.DeleteRange
import app.parley.common.history.HistoryFilter
import app.parley.common.history.HistoryMerge
import app.parley.common.history.ImportPlan
import app.parley.common.history.IndexContact
import app.parley.common.history.NumberKeys
import app.parley.common.history.PlanConfig
import app.parley.common.history.PlanMeter
import app.parley.common.history.PlanUsage
import app.parley.data.CallLogRepository
import app.parley.data.ContactsRepository
import app.parley.data.Permissions
import app.parley.data.PhoneEnv
import app.parley.data.R
import app.parley.data.StartGate
import app.parley.common.memory.CallTally
import app.parley.data.backup.CallHistoryBackup
import app.parley.data.changes
import app.parley.data.vault.PrivateCall
import app.parley.data.vault.VaultRepository
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.parley.common.memory.NumberMemory
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** A decrypted archive row. */
data class ArchivedCall(val rowId: Long, val record: CallLogRecord)

/**
 * Call history as data: Parley's encrypted archive of the system call log, the merged view Recents
 * reads, the shared [CallLogIndex], deletes with a 30-day undo, CSV import and per-SIM plan
 * meters.
 *
 * The archive mirrors every call-log row as soon as the log changes (content observer) and again in a daily
 * catch-up, so calls survive when the system log trims itself. It follows the retention setting except for
 * numbers kept forever, never holds calls with private (vault) contacts, and forgets calls you delete in
 * Parley. Calls deleted in other apps stay in the archive.
 */
@OptIn(FlowPreview::class)
class CallHistory(
    private val context: Context,
    private val callLog: CallLogRepository,
    private val contacts: ContactsRepository,
    private val vault: VaultRepository,
    private val scope: CoroutineScope,
    /** The archive is read, synced and followed only once the full data graph starts (see [StartGate]). */
    private val gate: StartGate,
) : CallHistoryBackup {
    val prefs = HistoryPrefs(context, scope)

    @Volatile private var dbRef: HistoryDatabase? = null
    private val db: HistoryDatabase get() = dbRef ?: synchronized(this) { dbRef ?: HistoryDatabase.create(context).also { dbRef = it } }
    private val dao: HistoryDao get() = db.dao()
    private val crypto = HistoryCrypto(context)
    private val cr = context.contentResolver
    private val mutex = Mutex()

    /**
     * Decrypted rows of the newest [ARCHIVE_UI_WINDOW] by id, so a sync only decrypts the rows it added (guarded by
     * [reloadLock]). Older rows stay sealed in the database and are read a page at a time when needed.
     */
    private val decrypted = HashMap<Long, CallLogRecord>()
    private val undecryptable = HashSet<Long>()
    private val reloadLock = Mutex()

    val countryIso: String get() = PhoneEnv.countryIso(context)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _archive = MutableStateFlow<List<ArchivedCall>?>(null)

    /**
     * The newest [ARCHIVE_UI_WINDOW] archived calls, decrypted, newest first; null until first loaded. Everything that
     * needs the whole archive (per-number lists, deletes, backup, import) pages through the database instead.
     */
    val archive: StateFlow<List<ArchivedCall>?> = _archive

    private val _kept = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Numbers whose history ignores retention: fingerprint → number. */
    val keptForever: StateFlow<Map<String, String>> = _kept

    /** Filter applied to Recents; saved filters live in [prefs]. */
    val activeFilter = MutableStateFlow(HistoryFilter())

    /** Private (vault) numbers, matched by line (F7: E.164, not the last 9 digits, so a foreign number sharing them stays). */
    private val vaultKeys = vault.contacts.map { list -> PhoneIdentity.LineSet(list.flatMap { v -> v.numbers }, countryIso) }
        .distinctUntilChanged()

    /**
     * The history Recents shows: system call log plus archived calls it no longer has, newest first. Only the
     * newest [ARCHIVE_UI_WINDOW] archived calls are merged in, so a years-long archive doesn't slow every screen;
     * [callsFor] and the backup still see all of it. Archived-only calls have ids from [ARCHIVE_ID_BASE] up.
     * Null until the call log has loaded.
     */
    val calls: StateFlow<List<CallEntry>?> = combine(
        callLog.calls, _archive, prefs.state.map { it.archiveEnabled }.distinctUntilChanged(), vaultKeys,
    ) { sys, arch, on, vk ->
        when {
            sys == null -> null
            !on || arch.isNullOrEmpty() -> sys
            else -> HistoryMerge.merge(
                sys, arch.asSequence().take(ARCHIVE_UI_WINDOW).map { it.toEntry() }.filter { it.number !in vk || it.number.isBlank() }.toList(),
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, gate.sharing, null)

    /**
     * [calls] plus the calls with private (vault) contacts, newest first: every call Parley knows of. For counts that
     * must not miss a call just because its contact is private (allowances). Private calls have negative ids.
     */
    val callsWithPrivate: StateFlow<List<CallEntry>?> = combine(calls, vault.privateCalls) { sys, priv ->
        when {
            sys == null -> null
            priv.isEmpty() -> sys
            else -> (sys + priv.map(::privateEntry)).sortedByDescending { it.date }
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, gate.sharing, null)

    /** The newest call with [number]'s line in [calls], or null (the caller's "last call" line). */
    fun lastCallWith(number: String, region: String? = countryIso): CallEntry? =
        calls.value?.firstOrNull { !it.presentationHidden && PhoneIdentity.same(it.number, number, region) }

    /** The shared index over [calls] and contacts, rebuilt off the main thread when either changes. */
    val index: StateFlow<CallLogIndex?> = combine(calls, contacts.contacts) { c, ct -> c to ct }
        .debounce(200)
        .map { (c, ct) -> c?.let { buildIndex(it, ct) } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), null)

    /** The last index and what it was built with, so new calls alone are appended rather than indexed again. */
    private class Built(val contacts: List<ContactSummary>?, val permitted: Boolean, val country: String, val zone: ZoneId, val index: CallLogIndex) {
        fun sameInputs(ct: List<ContactSummary>?, p: Boolean, c: String, z: ZoneId) = contacts === ct && permitted == p && country == c && zone == z
    }

    @Volatile private var built: Built? = null

    private fun buildIndex(calls: List<CallEntry>, ct: List<ContactSummary>?): CallLogIndex {
        // Without the permission the list is empty but that means "unknown", not "nobody is a contact".
        val permitted = ct != null && Permissions.has(context, Manifest.permission.READ_CONTACTS)
        val country = countryIso
        val z = zone
        built?.let { b ->
            if (b.sameInputs(ct, permitted, country, z)) {
                b.index.appending(calls)?.let { idx -> return idx.also { built = Built(ct, permitted, country, z, it) } }
            }
        }
        val known = if (!permitted) null else ct.orEmpty().map { IndexContact(it.id, it.lookupKey, it.displayName, it.phones.map { p -> p.number }) }
        return CallLogIndex.build(calls, known, country, z).also { built = Built(ct, permitted, country, z, it) }
    }

    /** Closes the archive database so "Delete all Parley data" can remove its file (the process restarts after). */
    internal fun closeForWipe() = synchronized(this) {
        runCatching { dbRef?.close() }
        dbRef = null
    }

    /** Waits (up to 30 s) for the first index, e.g. in a worker. */
    suspend fun awaitIndex(): CallLogIndex? = withTimeoutOrNull(30_000) { index.filterNotNull().first() }

    suspend fun awaitCalls(): List<CallEntry>? = withTimeoutOrNull(30_000) { calls.filterNotNull().first() }

    init {
        // Not in a process started for a call, a worker or a widget: decrypting the archive and a full catch-up would
        // compete with call screening. Workers sync explicitly; the rest waits for the UI or a settled call.
        scope.launch(Dispatchers.IO) {
            gate.await()
            val s = prefs.current()
            runCatching { reload() }
            if (s.archiveEnabled) {
                val stale = System.currentTimeMillis() - s.lastFullSync > TimeUnit.HOURS.toMillis(6)
                runCatching { sync(full = stale) }
            }
            cr.changes(Calls.CONTENT_URI).debounce(1500).collect {
                if (prefs.current().archiveEnabled) runCatching { sync(full = false) }
            }
        }
    }

    // ------------------------------------------------------------------ archive

    /** Opens a sealed value; a damaged row gives null, but key problems are passed on (they aren't the row's fault). */
    private fun openOrNull(blob: ByteArray): String? = try {
        String(crypto.open(blob))
    } catch (e: HistoryCrypto.KeyLostException) {
        throw e
    } catch (e: HistoryCrypto.KeyUnavailableException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Decrypts the archive into [archive], only the rows not decrypted before. When the key can't be used right
     * now, nothing changes and the next sync tries again. Only a key that is gone for good starts a new archive,
     * and even then the old database is moved aside (never deleted) and the user is told.
     */
    private suspend fun reload() = withContext(Dispatchers.IO) {
        reloadLock.withLock {
            try {
                val ids = dao.newestIds(ARCHIVE_UI_WINDOW)
                decrypted.keys.retainAll(ids.toHashSet())
                undecryptable.retainAll(ids.toHashSet())
                val missing = ids.filter { it !in decrypted && it !in undecryptable }
                missing.chunked(500).forEach { chunk ->
                    for (r in dao.byIds(chunk)) {
                        val rec = openOrNull(r.blob)?.let { runCatching { decode(it) }.getOrNull() }
                        if (rec != null) decrypted[r.id] = rec else undecryptable += r.id
                    }
                }
                _kept.value = dao.keepForever().mapNotNull { k -> openOrNull(k.blob)?.let { k.personKey to it } }.toMap()
                _archive.value = ids.mapNotNull { id -> decrypted[id]?.let { ArchivedCall(id, it) } }
            } catch (e: HistoryCrypto.KeyUnavailableException) {
                Log.w(TAG, "Archive key unavailable for now; will retry", e)
            } catch (e: HistoryCrypto.KeyLostException) {
                startOver(e)
            }
        }
    }

    /**
     * The archive key is gone for good: its rows can never be read again. The database and the wrapped key are
     * renamed (kept on the phone, not deleted), a new empty archive starts, and a notice is shown.
     */
    private suspend fun startOver(e: Exception) {
        Log.w(TAG, "Archive key lost; the old archive is kept aside and a new one starts", e)
        val suffix = "lost-" + System.currentTimeMillis()
        synchronized(this) {
            runCatching { dbRef?.close() }
            dbRef = null
            for (ext in listOf("", "-wal", "-shm", "-journal")) {
                val f = context.getDatabasePath(HistoryDatabase.NAME + ext)
                if (f.exists()) f.renameTo(File(f.parentFile, "parley-history-$suffix.db$ext"))
            }
        }
        crypto.reset(suffix)
        decrypted.clear()
        undecryptable.clear()
        _kept.value = emptyMap()
        _archive.value = emptyList()
        runCatching { prefs.setArchiveReset(System.currentTimeMillis()) }
    }

    /**
     * Archives [rows] and returns how many were new. The dedupe key is unique and inserts ignore a conflict, so a call
     * already archived is turned away by the database: no set of every key is held in memory for it.
     */
    private suspend fun insertNew(rows: List<ArchivedCallEntity>): Int =
        rows.chunked(500).sumOf { chunk -> dao.insert(chunk).count { it != -1L } }

    /**
     * Mirrors new call-log rows into the archive. [full] scans the whole log (daily catch-up); otherwise only
     * the last few days. Returns rows added.
     */
    suspend fun sync(full: Boolean): Int = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            if (!prefs.current().archiveEnabled) return@withLock 0
            if (!Permissions.has(context, Manifest.permission.READ_CALL_LOG)) return@withLock 0
            if (_archive.value == null) reload()
            // Key not usable right now: leave the archive alone and try on the next change.
            if (_archive.value == null) return@withLock 0
            val since = if (full) null else dao.newest()?.minus(TimeUnit.DAYS.toMillis(3))
            // Read from the database, not the listing: in a worker's process the listing hasn't started.
            val vk = PhoneIdentity.LineSet(vault.allNumbers(), countryIso)
            val iso = countryIso
            val now = System.currentTimeMillis()
            val fresh = ArrayList<ArchivedCallEntity>()
            val seen = HashSet<String>()
            for (rec in readProvider(since)) {
                val num = rec.number
                if (!num.isNullOrBlank() && num in vk) continue
                val key = crypto.mac(HistoryMerge.key(rec.toEntry(0)))
                if (!seen.add(key)) continue
                fresh += entity(rec, key, iso, now)
            }
            val added = insertNew(fresh)
            val purged = purgeVault(vk)
            if (full) prefs.setLastFullSync(now)
            // In a process started for a worker nothing shows the archive: the rows are in the database, and the window
            // is decrypted when the full graph starts (see init).
            val shown = gate.isOpen || _archive.subscriptionCount.value > 0
            if (shown && (added > 0 || purged)) reload()
            added
        }
    }

    private fun entity(rec: CallLogRecord, key: String, iso: String, now: Long) = ArchivedCallEntity(
        dedupeKey = key,
        personKey = personMac(rec.number.orEmpty(), iso),
        date = rec.date, durationSec = rec.duration, type = rec.type,
        blob = crypto.seal(encode(rec).toByteArray()),
        archivedAt = now,
    )

    private fun personMac(number: String, iso: String = countryIso) = crypto.mac(NumberKeys.of(number, iso))

    /**
     * [personMac] and, when an older version filed the line under another E.164 form, that one too: archive rows and
     * kept-forever numbers written before 5.4 are matched under either.
     */
    private fun personMacs(number: String, iso: String = countryIso): List<String> =
        PhoneIdentity.exactKeyForms(number, iso).ifEmpty { listOf(NumberKeys.HIDDEN) }.map(crypto::mac).distinct()

    /**
     * Called when calls with a number are deleted or purged: (number, call dates; null for every call), so stores
     * kept beside the archive (ring facts) forget them too. Set by the container.
     */
    @Volatile
    var onForget: ((number: String, dates: List<Long>?) -> Unit)? = null

    /** The archive's keyed fingerprint of [number]'s line, for small stores kept beside it (ring facts). */
    internal fun lineMac(number: String): String = personMac(number)

    /** The archive's keyed fingerprint of any [value] (personal reputation keys lines and ranges with it). */
    internal fun auxMac(value: String): String = crypto.mac(value)

    /** Seals and opens with the archive key, for small stores kept beside it (ring facts). */
    internal fun sealAux(plain: ByteArray): ByteArray = crypto.seal(plain)

    internal fun openAux(blob: ByteArray): ByteArray = crypto.open(blob)

    /** The private numbers (and region) the whole archive was last checked against; see [purgeVault]. */
    private var purgedFor: Pair<Set<String>, String>? = null

    /**
     * Calls with private contacts never stay in the archive (they live in the vault's own history). New rows are
     * filtered as they are archived, so the whole archive is read (a page at a time) only when the private numbers
     * changed since it was last checked; otherwise the newest window is enough.
     */
    private suspend fun purgeVault(vk: PhoneIdentity.LineSet): Boolean {
        val signature = vault.contacts.value.flatMap { it.numbers }.toSet() to countryIso
        if (vk.isEmpty) {
            purgedFor = signature
            return false
        }
        fun private(r: CallLogRecord) = !r.number.isNullOrBlank() && r.number in vk
        val ids = ArrayList<Long>()
        if (signature != purgedFor) {
            if (scanArchive { if (private(it.record)) ids += it.rowId }) purgedFor = signature
        } else {
            _archive.value.orEmpty().filter { private(it.record) }.mapTo(ids) { it.rowId }
        }
        if (ids.isEmpty()) return false
        ids.chunked(500).forEach { dao.deleteIds(it) }
        return true
    }

    /**
     * The private numbers, read from the vault itself, for paths that put old calls back into the archive (undo,
     * restore). Only the newest window is checked again after them, so a private call that got past this filter
     * outside it would stay for good.
     */
    private suspend fun privateLines(): PhoneIdentity.LineSet = PhoneIdentity.LineSet(vault.allNumbers(), countryIso)

    private fun isPrivate(rec: CallLogRecord, vk: PhoneIdentity.LineSet) = !rec.number.isNullOrBlank() && rec.number in vk

    /**
     * Every archived call, newest first, decrypted a page at a time so the whole archive is never held in memory.
     * Unreadable rows are skipped. False when the key can't be used (nothing was visited then, or not everything).
     */
    private suspend fun scanArchive(since: Long = Long.MIN_VALUE, visit: (ArchivedCall) -> Unit): Boolean {
        try {
            scanPages(since, visit)
            return true
        } catch (e: HistoryCrypto.KeyUnavailableException) {
            Log.w(TAG, "Archive key unavailable for now", e)
        } catch (e: HistoryCrypto.KeyLostException) {
            Log.w(TAG, "Archive key lost", e)
        }
        return false
    }

    /** Newest first, so a pass from [since] stops at the first page older than it. */
    private suspend fun scanPages(since: Long = Long.MIN_VALUE, visit: (ArchivedCall) -> Unit) {
        var page = dao.firstPage(SCAN_PAGE)
        while (page.isNotEmpty()) {
            for (r in page) if (r.date >= since) openRow(r)?.let { visit(ArchivedCall(r.id, it)) }
            val last = page.last()
            // A short page was the last one; a page reaching past [since] holds everything after it.
            page = if (page.size < SCAN_PAGE || last.date < since) emptyList() else dao.pageBefore(last.date, last.id, SCAN_PAGE)
        }
    }

    /**
     * The archived calls filed under [number]'s line, newest first: read through the person index and decrypted alone,
     * so one person's calls never cost a pass over the whole archive. [personMacs] covers every form the line was
     * filed under; the exact match then keeps another line that shares a form out.
     */
    private suspend fun personRows(number: String, iso: String): List<ArchivedCall> =
        dao.byPersons(personMacs(number, iso)).mapNotNull { r ->
            openRow(r)?.takeIf { !it.number.isNullOrBlank() && PhoneIdentity.sameExact(it.number, number, iso) }?.let { ArchivedCall(r.id, it) }
        }

    /**
     * [personRows] for a delete: also the rows filed under another region's key. A row is filed under its number as
     * read in the region of the day it was archived, which follows the SIM or the network, so after roaming or a SIM
     * swap the index alone misses some of one person's calls. A delete can't leave those behind: the archive (from
     * [since]) is read once more and matched with [PhoneIdentity.sameLineAnyRegion].
     */
    private suspend fun personRowsEverywhere(number: String, iso: String, since: Long): List<ArchivedCall> {
        val found = LinkedHashMap<Long, ArchivedCall>()
        guardKey { personRows(number, iso).forEach { found[it.rowId] = it } }
        scanArchive(since) { a -> if (a.rowId !in found && PhoneIdentity.sameLineAnyRegion(a.record.number, number, iso)) found[a.rowId] = a }
        return found.values.sortedByDescending { it.record.date }
    }

    /** A row's call, or null when it can't be read (key problems are passed on, as by [openOrNull]). */
    private fun openRow(r: ArchivedCallEntity): CallLogRecord? = openOrNull(r.blob)?.let { runCatching { decode(it) }.getOrNull() }

    /** Number memory: changes whenever calls are archived or leave the archive. */
    suspend fun memoryStamp(): String = withContext(Dispatchers.IO) {
        if (!prefs.current().archiveEnabled) "off" else "${dao.count()}:${dao.newestIds(1).firstOrNull()}"
    }

    /**
     * Number memory's summary of the archive: [previous] with only the calls archived since it was made, when nothing
     * left the archive meanwhile (the usual day); otherwise every call again. Null when the archive key can't be used
     * now (the previous memory is kept).
     */
    suspend fun tallyForMemory(previous: CallTally?, region: String?): CallTally? = withContext(Dispatchers.IO) {
        if (!prefs.current().archiveEnabled) return@withContext CallTally(mark = "off", region = region)
        val count = dao.count().toLong()
        val maxId = dao.maxId() ?: 0L
        val mark = "$count:$maxId"
        val added = ArrayList<NumberMemory.PastCall>()
        val take = { a: ArchivedCall -> a.record.number?.takeIf { it.isNotBlank() }?.let { added += NumberMemory.PastCall(it, a.record.date, a.record.name) } }
        val after = previous?.let { appendsAfter(it, region, count, maxId) }
        when {
            previous == null || after == null -> if (scanArchive { take(it) }) CallTally.empty(region).plus(added, mark) else null
            after == maxId -> previous.copy(mark = mark)
            guardKey { scanAfter(after) { take(it) } } -> previous.plus(added, mark)
            else -> null
        }
    }

    /**
     * The last row [previous] read, when everything since was only added (no call left the archive, the same region):
     * then only the rows after it are new. Null when the tally must be read again.
     */
    private suspend fun appendsAfter(previous: CallTally, region: String?, count: Long, maxId: Long): Long? {
        if (previous.region != region) return null
        val (seenCount, seenMax) = previous.mark.split(':').mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 } ?: return null
        if (seenMax > maxId) return null
        return seenMax.takeIf { count - seenCount == dao.countAfter(seenMax).toLong() }
    }

    /** Every archived call after row [after], oldest first, a page at a time. */
    private suspend fun scanAfter(after: Long, visit: (ArchivedCall) -> Unit) {
        var from = after
        do {
            val page = dao.pageAfter(from, SCAN_PAGE)
            for (r in page) openRow(r)?.let { visit(ArchivedCall(r.id, it)) }
            from = page.lastOrNull()?.id ?: from
        } while (page.size == SCAN_PAGE)
    }

    /** Runs [block], false when the archive key can't be used now (as [scanArchive]). */
    private suspend fun guardKey(block: suspend () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (e: HistoryCrypto.KeyUnavailableException) {
            Log.w(TAG, "Archive key unavailable for now", e)
        } catch (e: HistoryCrypto.KeyLostException) {
            Log.w(TAG, "Archive key lost", e)
        }
        return false
    }

    /** Every number in the archive (all of it, not only the window), e.g. for a one-off key migration. */
    suspend fun archivedNumbers(): List<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>()
        if (prefs.current().archiveEnabled) scanArchive { a -> a.record.number?.takeIf { it.isNotBlank() }?.let(out::add) }
        out.toList()
    }

    private fun readProvider(since: Long?): List<CallLogRecord> {
        val out = ArrayList<CallLogRecord>()
        try {
            cr.query(
                Calls.CONTENT_URI,
                arrayOf(
                    Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE, Calls.NUMBER_PRESENTATION, Calls.PHONE_ACCOUNT_ID,
                    Calls.PHONE_ACCOUNT_COMPONENT_NAME, Calls.CACHED_NAME, Calls.FEATURES,
                ),
                since?.let { "${Calls.DATE} >= ?" }, since?.let { arrayOf(it.toString()) }, Calls.DATE + " DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CallLogRecord(
                        c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4), c.getString(5), c.getString(6), c.getString(7),
                        isNew = false, isRead = true, features = c.getInt(8),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the call log", e)
        }
        return out
    }

    /** Turns the archive on (and fills it) or off (and deletes everything it holds). */
    suspend fun setArchiveEnabled(on: Boolean) = withContext(Dispatchers.IO + NonCancellable) {
        prefs.setArchiveEnabled(on)
        if (on) {
            sync(full = true)
        } else {
            mutex.withLock {
                dao.clear()
                reload()
            }
        }
    }

    suspend fun archiveCount(): Int = withContext(Dispatchers.IO) { dao.count() }

    /** Follows the retention setting ([days] = 0 keeps everything) except for numbers kept forever. */
    suspend fun applyRetention(days: Int) = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            dao.pruneTrash(now - TRASH_DAYS * DAY)
            if (days <= 0) return@withLock
            // Each kept number's line under its stored key and every form it is filed under now (an archive row written
            // after 5.4 may use another form than the kept-forever entry made before).
            val kept = dao.keepForever().flatMap { k -> listOf(k.personKey) + openOrNull(k.blob)?.let { personMacs(it) }.orEmpty() }.distinct()
            val n = dao.deleteOlderThan(now - days * DAY, kept)
            if (n > 0) reload()
        }
    }

    // ------------------------------------------------------------------ keep forever

    suspend fun isKeptForever(number: String): Boolean = withContext(Dispatchers.IO) {
        if (_archive.value == null) reload()
        personMacs(number).any { it in _kept.value }
    }

    /** Keeps (or stops keeping) every call with these numbers regardless of retention. */
    suspend fun setKeepForever(numbers: List<String>, keep: Boolean) = withContext(Dispatchers.IO + NonCancellable) {
        val iso = countryIso
        val list = numbers.filter { it.isNotBlank() }.distinctBy { NumberKeys.of(it, iso) }
        if (keep) {
            dao.addKeepForever(list.map { KeepForeverEntity(personMac(it, iso), crypto.seal(it.toByteArray()), System.currentTimeMillis()) })
        } else {
            dao.removeKeepForever(list.flatMap { personMacs(it, iso) })
        }
        _kept.value = dao.keepForever().mapNotNull { k -> runCatching { k.personKey to String(crypto.open(k.blob)) }.getOrNull() }.toMap()
    }

    suspend fun removeKeepForeverKeys(keys: List<String>) = withContext(Dispatchers.IO) {
        dao.removeKeepForever(keys)
        _kept.value = _kept.value - keys.toSet()
    }

    // ------------------------------------------------------------------ delete with undo

    /**
     * Deletes calls from the system log and the archive, keeping a sealed copy for 30 days.
     * Private (vault) calls (negative ids) are ignored here. Returns the undo batch id, or null if nothing
     * was deleted.
     */
    suspend fun delete(entries: List<CallEntry>): Long? = withContext(Dispatchers.IO + NonCancellable) {
        val list = entries.filter { it.id > 0 }
        if (list.isEmpty()) return@withContext null
        mutex.withLock {
            val now = System.currentTimeMillis()
            val batch = now
            // The sealed copies of archived-only calls come from their rows (they may be older than the window).
            val archivedById = dao.byIds(list.filter { isArchived(it) }.map { it.id - ARCHIVE_ID_BASE })
                .mapNotNull { r -> openRow(r)?.let { ARCHIVE_ID_BASE + r.id to ArchivedCall(r.id, it) } }
                .toMap()
            dao.trash(
                list.map { e ->
                    val rec = archivedById[e.id]?.record ?: e.toRecord()
                    TrashedCallEntity(batchId = batch, deletedAt = now, blob = crypto.seal(encode(rec).toByteArray()))
                },
            )
            val providerIds = list.map { it.id }.filter { it < ARCHIVE_ID_BASE }
            providerIds.chunked(500).forEach { chunk ->
                runCatching { cr.delete(Calls.CONTENT_URI, "${Calls._ID} IN (${chunk.joinToString(",")})", null) }
            }
            val keys = list.map { crypto.mac(HistoryMerge.key(it)) }
            keys.chunked(500).forEach { dao.deleteKeys(it) }
            reload()
            // Ring facts of deleted calls go with them.
            list.filter { !it.presentationHidden && it.number.isNotBlank() }.groupBy { it.number }
                .forEach { (n, calls) -> runCatching { onForget?.invoke(n, calls.map { it.date }) } }
            batch
        }
    }

    /**
     * Every call with [number] (any format), optionally only since [since]: all of its system call-log rows (read
     * from the provider, not the newest-3000 window Recents shows) plus every archived call, so a delete built from
     * this list leaves nothing behind for the next sync to bring back. Matched exactly ([PhoneIdentity.sameExact]):
     * the list is deleted from, and a loose match (last digits) would reach other people's calls.
     */
    suspend fun callsFor(number: String, since: Long = Long.MIN_VALUE): List<CallEntry> = callsFor(number, since, forDelete = false)

    /** [callsFor]; [forDelete] also finds archived calls filed under another region ([personRowsEverywhere]). */
    private suspend fun callsFor(number: String, since: Long, forDelete: Boolean): List<CallEntry> = withContext(Dispatchers.IO) {
        val iso = countryIso
        val system = callLog.queryForNumber(number, since)
        if (!prefs.current().archiveEnabled) return@withContext system
        val seen = system.map { HistoryMerge.key(it) }.toHashSet()
        val archived = ArrayList<CallEntry>()
        guardKey {
            val rows = if (forDelete) personRowsEverywhere(number, iso, since) else personRows(number, iso)
            for (a in rows) {
                val e = a.toEntry()
                if (e.date >= since && !e.presentationHidden && seen.add(HistoryMerge.key(e))) archived += e
            }
        }
        (system + archived).sortedByDescending { it.date }
    }

    /**
     * Removes every call with [number] from the system log and the archive with no undo copy, for automatic purges
     * (an expired temporary contact). Reads the provider and the archive directly, so it works in a process that
     * never loaded Recents. Returns calls removed.
     */
    suspend fun purgeNumber(number: String): Int = withContext(Dispatchers.IO + NonCancellable) {
        if (number.isBlank()) return@withContext 0
        val iso = countryIso
        var n = 0
        val ids = ArrayList<Long>()
        runCatching {
            // The filter URI matches loosely (the last digits); only rows that are exactly this line are deleted.
            cr.query(Uri.withAppendedPath(Calls.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(Calls._ID, Calls.NUMBER), null, null, null)
                ?.use { c -> while (c.moveToNext()) if (PhoneIdentity.sameExact(c.getString(1), number, iso)) ids += c.getLong(0) }
        }
        ids.chunked(500).forEach { chunk ->
            n += runCatching { cr.delete(Calls.CONTENT_URI, "${Calls._ID} IN (${chunk.joinToString(",")})", null) }.getOrDefault(0)
        }
        mutex.withLock {
            catching {
                val person = personMac(number, iso)
                // Rows filed under every form of the number, found through the person index (no whole-archive pass).
                val rows = ArrayList<Long>()
                personRowsEverywhere(number, iso, Long.MIN_VALUE).mapTo(rows) { it.rowId }
                rows.chunked(500).forEach { dao.deleteIds(it) }
                n += rows.size + dao.deleteByPerson(person)
                dao.removeKeepForever(listOf(person))
                reload()
            }
        }
        runCatching { onForget?.invoke(number, null) }
        n
    }

    suspend fun deleteForNumber(number: String): Long? = deleteRange(number, DeleteRange.ALL)

    suspend fun deleteRange(number: String, range: DeleteRange, picked: LocalDate? = null): Long? = withContext(Dispatchers.IO + NonCancellable) {
        val since = range.since(System.currentTimeMillis(), zone, picked)
        val batch = delete(callsFor(number, since, forDelete = true))
        // Everything for this number: archive rows filed under its key that couldn't be read into the list go too.
        if (since == Long.MIN_VALUE && prefs.current().archiveEnabled) mutex.withLock {
            catching {
                if (dao.deleteByPerson(personMac(number)) > 0) reload()
            }
        }
        batch
    }

    suspend fun trashBatches(): List<TrashBatch> = withContext(Dispatchers.IO) { dao.trashBatches() }

    /** How many deleted calls are held for undo, and their stored (sealed) size. */
    suspend fun trashUsage(): Pair<Int, Long> = withContext(Dispatchers.IO) { dao.trashCount() to dao.trashBytes() }

    /**
     * Forgets the undo copies of deleted calls (one batch, or all when [batchId] is null); the call log itself is
     * untouched. Taken under the undo lock so an undo in progress finishes first. Returns the calls forgotten.
     */
    suspend fun forgetDeleted(batchId: Long? = null): Int = withContext(Dispatchers.IO + NonCancellable) {
        undoLock.withLock {
            val n = if (batchId == null) dao.clearTrash() else dao.trashed(batchId).size.also { dao.deleteBatch(batchId) }
            if (n > 0) compactDatabase(db.openHelper)
            n
        }
    }

    /** Daily upkeep: the archive's free pages after retention and trash pruning go back to the phone. */
    internal suspend fun tidy() = withContext(Dispatchers.IO) { mutex.withLock { tidyDatabase(db.openHelper) } }

    /** Puts a deleted batch back into the system call log (and the archive). Returns calls restored. */
    suspend fun undoDelete(batchId: Long): Int = withContext(Dispatchers.IO + NonCancellable) { undoLock.withLock { undoDeleteLocked(batchId) } }

    /** One undo at a time: a second tap waits and then finds the batch gone. */
    private val undoLock = Mutex()

    private suspend fun undoDeleteLocked(batchId: Long): Int {
        val trashed = dao.trashed(batchId).mapNotNull { runCatching { decode(String(crypto.open(it.blob))) }.getOrNull() }
        if (trashed.isEmpty()) return 0
        // Idempotent. Rows the system log already has again (an earlier, interrupted undo) aren't inserted twice.
        val from = trashed.minOf { it.date } - 1000
        val present = readProvider(from).filter { it.date <= trashed.maxOf { r -> r.date } + 1000 }
        val rows = HistoryMerge.missing(trashed, present) { HistoryMerge.key(it.toEntry(0)) }
        val values = rows.map { it.toValues() }.toTypedArray()
        val n = if (values.isEmpty()) 0 else runCatching { cr.bulkInsert(Calls.CONTENT_URI, values) }.getOrDefault(0)
        if (prefs.current().archiveEnabled) mutex.withLock {
            val iso = countryIso
            val now = System.currentTimeMillis()
            val vk = privateLines()
            val fresh = trashed.mapNotNull { rec ->
                if (isPrivate(rec, vk)) return@mapNotNull null
                entity(rec, crypto.mac(HistoryMerge.key(rec.toEntry(0))), iso, now)
            }
            insertNew(fresh)
            reload()
        }
        dao.deleteBatch(batchId)
        return maxOf(n, trashed.size.takeIf { prefs.current().archiveEnabled } ?: 0)
    }

    // ------------------------------------------------------------------ import

    /** Dry run: reads and parses the file, checks it against the whole history. Nothing is written. */
    suspend fun planImport(uri: Uri, mapping: ColumnMapping? = null, dayFirst: Boolean = true): ImportPlan = withContext(Dispatchers.IO) {
        val text = cr.openInputStream(uri)?.use { input ->
            // Stops reading at the cap instead of loading the whole file first.
            val bytes = try {
                Bounded.readBytes(input, MAX_IMPORT_BYTES.toLong())
            } catch (_: LimitExceededException) {
                throw ExplainedFailure(context.getString(R.string.data_file_too_large))
            }
            String(bytes, Charsets.UTF_8)
        } ?: throw ExplainedFailure(context.getString(R.string.data_file_open_failed))
        val existing = HashSet<String>()
        readProvider(null).forEach { existing += importKey(it) }
        scanArchive { existing += importKey(it.record) }
        CallCsvImport.plan(text, existing, zone, mapping, dayFirst)
    }

    private fun importKey(r: CallLogRecord) = NumberKeys.dedupe(r.number.orEmpty(), r.date) + "|" + r.type

    /** Writes a planned import into the system call log (NEW=0, IS_READ=1) and archives it. Returns rows written. */
    suspend fun runImport(plan: ImportPlan): Int = withContext(Dispatchers.IO + NonCancellable) {
        var n = 0
        plan.toInsert.chunked(200).forEach { chunk ->
            val values = chunk.map { call ->
                ContentValues().apply {
                    call.providerValues().forEach { (k, v) ->
                        when (v) {
                            is Int -> put(k, v)
                            is Long -> put(k, v)
                            is String -> put(k, v)
                            null -> putNull(k)
                        }
                    }
                }
            }.toTypedArray()
            n += runCatching { cr.bulkInsert(Calls.CONTENT_URI, values) }.getOrDefault(0)
        }
        runCatching { sync(full = true) }
        n
    }

    // ------------------------------------------------------------------ plan meter

    val plans: StateFlow<List<PlanConfig>> = prefs.state.map { it.plans }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Current cycle usage per SIM id, for enabled plans. */
    val planUsage: StateFlow<Map<String, PlanUsage>> = combine(calls.filterNotNull(), plans) { c, p -> usage(c, p) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), emptyMap())

    private fun usage(calls: List<CallEntry>, plans: List<PlanConfig>): Map<String, PlanUsage> {
        val iso = countryIso
        val now = System.currentTimeMillis()
        return plans.filter { it.enabled && it.allowanceMinutes > 0 }.associate { p ->
            p.simId to PlanMeter.usage(p, calls, { NumberCategorizer.categorize(it, iso) }, now, zone)
        }
    }

    suspend fun savePlan(p: PlanConfig) = prefs.setPlan(p)

    suspend fun removePlan(simId: String) = prefs.removePlan(simId)

    /** Plans at or past their warning threshold that haven't been warned about in this cycle. */
    suspend fun plansToWarn(): List<PlanUsage> = withContext(Dispatchers.Default) {
        val s = prefs.current()
        if (s.plans.none { it.enabled }) return@withContext emptyList()
        val c = awaitCalls() ?: return@withContext emptyList()
        usage(c, s.plans).values.filter { it.isNear && warnKey(it) !in s.warnedCycles }
    }

    suspend fun markWarned(u: PlanUsage) = prefs.markWarned(warnKey(u))

    private fun warnKey(u: PlanUsage) = u.config.simId + "|" + u.cycleStart

    // ------------------------------------------------------------------ backup

    /** Archived calls the system log no longer has, and the "keep forever" numbers. */
    override suspend fun backupLines(): List<CallHistoryLine> = withContext(Dispatchers.IO) {
        if (_archive.value == null) reload()
        val inProvider = readProvider(null).map { HistoryMerge.key(it.toEntry(0)) }.toHashSet()
        val lines = ArrayList<CallHistoryLine>()
        val read = scanArchive { a -> if (HistoryMerge.key(a.record.toEntry(0)) !in inProvider) lines += CallHistoryLine(call = a.record) }
        // Rather fail the backup than silently leave the archive out.
        if ((!read || _archive.value == null) && prefs.current().archiveEnabled) throw IllegalStateException(context.getString(R.string.data_archive_locked))
        lines + _kept.value.values.map { CallHistoryLine(keepForever = it) }
    }

    /** Restores archived calls into the archive (or, with the archive off, into the system log). */
    override suspend fun beginRestore(): CallHistoryBackup.Restore = withContext(Dispatchers.IO) {
        if (prefs.current().archiveEnabled) ArchiveRestore(privateLines(), countryIso) else ProviderRestore()
    }

    /** Restores into the system call log (the archive is off), skipping calls it already has. */
    private inner class ProviderRestore : CallHistoryBackup.Restore {
        private var have: HashSet<String>? = null

        override suspend fun add(lines: List<CallHistoryLine>): Int = withContext(Dispatchers.IO + NonCancellable) {
            keepForever(lines)
            val calls = lines.mapNotNull { it.call }
            if (calls.isEmpty()) return@withContext 0
            // The whole log is read once per restore, not once per chunk.
            val known = have ?: readProvider(null).mapTo(HashSet()) { HistoryMerge.key(it.toEntry(0)) }.also { have = it }
            val values = calls.filter { known.add(HistoryMerge.key(it.toEntry(0))) }.map { it.toValues() }
            values.chunked(200).sumOf { chunk -> catching { cr.bulkInsert(Calls.CONTENT_URI, chunk.toTypedArray()) }.getOrDefault(0) }
        }

        override suspend fun finish() = Unit
    }

    /** Restores into the archive; the private numbers are read once, and the shown window reloaded once at the end. */
    private inner class ArchiveRestore(private val vk: PhoneIdentity.LineSet, private val iso: String) : CallHistoryBackup.Restore {
        private var added = 0

        override suspend fun add(lines: List<CallHistoryLine>): Int = withContext(Dispatchers.IO + NonCancellable) {
            keepForever(lines)
            val calls = lines.mapNotNull { it.call }
            if (calls.isEmpty()) return@withContext 0
            mutex.withLock {
                val now = System.currentTimeMillis()
                val fresh = calls.mapNotNull { rec ->
                    if (isPrivate(rec, vk)) return@mapNotNull null
                    entity(rec, crypto.mac(HistoryMerge.key(rec.toEntry(0))), iso, now)
                }
                insertNew(fresh).also { added += it }
            }
        }

        override suspend fun finish() = withContext(Dispatchers.IO + NonCancellable) {
            if (added > 0) mutex.withLock { reload() }
        }
    }

    private suspend fun keepForever(lines: List<CallHistoryLine>) {
        val kept = lines.mapNotNull { it.keepForever }
        if (kept.isNotEmpty()) setKeepForever(kept, true)
    }

    // ------------------------------------------------------------------ mapping

    private fun ArchivedCall.toEntry(): CallEntry = record.toEntry(ARCHIVE_ID_BASE + rowId)

    private fun CallLogRecord.toEntry(id: Long) = ArchivedCalls.entry(this, id)

    private fun CallEntry.toRecord() = ArchivedCalls.record(this)

    private fun CallLogRecord.toValues() = ArchivedCalls.values(this)

    private fun encode(r: CallLogRecord): String = ArchivedCalls.encode(r)

    private fun decode(s: String): CallLogRecord = ArchivedCalls.decode(s)

    companion object {
        private const val TAG = "CallHistory"

        /** Ids of archived-only calls in [calls] start here (provider ids are far smaller; vault ids are negative). */
        const val ARCHIVE_ID_BASE = 1L shl 52

        /** Archived calls merged into Recents (newest first); older ones stay reachable per number. */
        const val ARCHIVE_UI_WINDOW = 5_000

        /** Rows decrypted at a time when the whole archive is read. */
        private const val SCAN_PAGE = 500
        private const val DAY = 86_400_000L
        private const val TRASH_DAYS = 30L
        private const val MAX_IMPORT_BYTES = 20 shl 20

        fun isArchived(e: CallEntry) = e.id >= ARCHIVE_ID_BASE

        /** A private (vault) call as a history row; its id is the negated private-call id. */
        fun privateEntry(p: PrivateCall): CallEntry =
            CallEntry(-p.id, p.number, p.name, CallLogRepository.mapType(p.type), p.date, p.durationSec, null, false, false, video = p.video)
    }
}
