package app.parley.data.history

import android.Manifest
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CallLog.Calls
import android.util.Log
import app.parley.common.CallEntry
import app.parley.common.PhoneNumbers
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
import app.parley.common.history.ProviderColumns
import app.parley.data.CallLogRepository
import app.parley.data.ContactsRepository
import app.parley.data.Permissions
import app.parley.data.PhoneEnv
import app.parley.data.R
import app.parley.data.StartGate
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
import org.json.JSONObject
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
    private var knownKeys: HashSet<String>? = null

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
            else -> HistoryMerge.merge(sys, arch.asSequence().take(ARCHIVE_UI_WINDOW).map { it.toEntry() }.filter { it.number !in vk || it.number.isBlank() }.toList())
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
        calls.value?.firstOrNull { !it.presentationHidden && PhoneNumbers.same(it.number, number, region) }

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
        knownKeys = null
        _kept.value = emptyMap()
        _archive.value = emptyList()
        runCatching { prefs.setArchiveReset(System.currentTimeMillis()) }
    }

    private suspend fun keys(): HashSet<String> = knownKeys ?: HashSet(dao.dedupeKeys()).also { knownKeys = it }

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
            val vk = vaultKeys.first()
            val known = keys()
            val iso = countryIso
            val now = System.currentTimeMillis()
            val fresh = ArrayList<ArchivedCallEntity>()
            for (rec in readProvider(since)) {
                val num = rec.number
                if (!num.isNullOrBlank() && num in vk) continue
                val key = crypto.mac(HistoryMerge.key(rec.toEntry(0)))
                if (!known.add(key)) continue
                fresh += entity(rec, key, iso, now)
            }
            fresh.chunked(500).forEach { dao.insert(it) }
            val purged = purgeVault(vk)
            if (full) prefs.setLastFullSync(now)
            if (fresh.isNotEmpty() || purged) reload()
            fresh.size
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
     * Called when calls with a number are deleted or purged: (number, call dates; null for every call), so stores
     * kept beside the archive (ring facts) forget them too. Set by the container.
     */
    @Volatile
    var onForget: ((number: String, dates: List<Long>?) -> Unit)? = null

    /** The archive's keyed fingerprint of [number]'s line, for small stores kept beside it (ring facts). */
    internal fun lineMac(number: String): String = personMac(number)

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
        knownKeys = null
        return true
    }

    /**
     * Every archived call, newest first, decrypted a page at a time so the whole archive is never held in memory.
     * Unreadable rows are skipped. False when the key can't be used (nothing was visited then, or not everything).
     */
    private suspend fun scanArchive(visit: (ArchivedCall) -> Unit): Boolean {
        try {
            scanPages(visit)
            return true
        } catch (e: HistoryCrypto.KeyUnavailableException) {
            Log.w(TAG, "Archive key unavailable for now", e)
        } catch (e: HistoryCrypto.KeyLostException) {
            Log.w(TAG, "Archive key lost", e)
        }
        return false
    }

    private suspend fun scanPages(visit: (ArchivedCall) -> Unit) {
        var offset = 0
        do {
            val page = dao.page(SCAN_PAGE, offset)
            for (r in page) openRow(r)?.let { visit(ArchivedCall(r.id, it)) }
            offset += page.size
        } while (page.size == SCAN_PAGE)
    }

    /** A row's call, or null when it can't be read (key problems are passed on, as by [openOrNull]). */
    private fun openRow(r: ArchivedCallEntity): CallLogRecord? = openOrNull(r.blob)?.let { runCatching { decode(it) }.getOrNull() }

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
                arrayOf(Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE, Calls.NUMBER_PRESENTATION, Calls.PHONE_ACCOUNT_ID, Calls.PHONE_ACCOUNT_COMPONENT_NAME, Calls.CACHED_NAME),
                since?.let { "${Calls.DATE} >= ?" }, since?.let { arrayOf(it.toString()) }, Calls.DATE + " DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CallLogRecord(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4), c.getString(5), c.getString(6), c.getString(7), isNew = false, isRead = true)
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
                knownKeys = null
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
            val n = dao.deleteOlderThan(now - days * DAY, dao.keepForever().map { it.personKey })
            if (n > 0) {
                knownKeys = null
                reload()
            }
        }
    }

    // ------------------------------------------------------------------ keep forever

    suspend fun isKeptForever(number: String): Boolean = withContext(Dispatchers.IO) {
        if (_archive.value == null) reload()
        personMac(number) in _kept.value
    }

    /** Keeps (or stops keeping) every call with these numbers regardless of retention. */
    suspend fun setKeepForever(numbers: List<String>, keep: Boolean) = withContext(Dispatchers.IO + NonCancellable) {
        val iso = countryIso
        val list = numbers.filter { it.isNotBlank() }.distinctBy { NumberKeys.of(it, iso) }
        if (keep) {
            dao.addKeepForever(list.map { KeepForeverEntity(personMac(it, iso), crypto.seal(it.toByteArray()), System.currentTimeMillis()) })
        } else {
            dao.removeKeepForever(list.map { personMac(it, iso) })
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
            knownKeys?.removeAll(keys.toSet())
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
     * this list leaves nothing behind for the next sync to bring back. Matched exactly ([PhoneNumbers.sameExact]):
     * the list is deleted from, and a loose match (last digits) would reach other people's calls.
     */
    suspend fun callsFor(number: String, since: Long = Long.MIN_VALUE): List<CallEntry> = withContext(Dispatchers.IO) {
        val iso = countryIso
        val system = callLog.queryForNumber(number, since)
        if (!prefs.current().archiveEnabled) return@withContext system
        val seen = system.map { HistoryMerge.key(it) }.toHashSet()
        val archived = ArrayList<CallEntry>()
        scanArchive { a ->
            val e = a.toEntry()
            val matches = e.date >= since && !e.presentationHidden && PhoneNumbers.sameExact(e.number, number, iso)
            if (matches && seen.add(HistoryMerge.key(e))) archived += e
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
                ?.use { c -> while (c.moveToNext()) if (PhoneNumbers.sameExact(c.getString(1), number, iso)) ids += c.getLong(0) }
        }
        ids.chunked(500).forEach { chunk ->
            n += runCatching { cr.delete(Calls.CONTENT_URI, "${Calls._ID} IN (${chunk.joinToString(",")})", null) }.getOrDefault(0)
        }
        mutex.withLock {
            runCatching {
                val person = personMac(number, iso)
                // Also rows filed under another form of the number.
                val other = ArrayList<Long>()
                scanArchive { if (!it.record.number.isNullOrBlank() && PhoneNumbers.sameExact(it.record.number, number, iso)) other += it.rowId }
                n += dao.deleteByPerson(person)
                other.chunked(500).forEach { dao.deleteIds(it) }
                dao.removeKeepForever(listOf(person))
                knownKeys = null
                reload()
            }
        }
        runCatching { onForget?.invoke(number, null) }
        n
    }

    suspend fun deleteForNumber(number: String): Long? = deleteRange(number, DeleteRange.ALL)

    suspend fun deleteRange(number: String, range: DeleteRange, picked: LocalDate? = null): Long? = withContext(Dispatchers.IO + NonCancellable) {
        val since = range.since(System.currentTimeMillis(), zone, picked)
        val batch = delete(callsFor(number, since))
        // Everything for this number: archive rows filed under its key that couldn't be read into the list go too.
        if (since == Long.MIN_VALUE && prefs.current().archiveEnabled) mutex.withLock {
            runCatching {
                if (dao.deleteByPerson(personMac(number)) > 0) {
                    knownKeys = null
                    reload()
                }
            }
        }
        batch
    }

    suspend fun trashBatches(): List<TrashBatch> = withContext(Dispatchers.IO) { dao.trashBatches() }

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
            val known = keys()
            val iso = countryIso
            val now = System.currentTimeMillis()
            val fresh = trashed.mapNotNull { rec ->
                val key = crypto.mac(HistoryMerge.key(rec.toEntry(0)))
                if (known.add(key)) entity(rec, key, iso, now) else null
            }
            fresh.chunked(500).forEach { dao.insert(it) }
            reload()
        }
        dao.deleteBatch(batchId)
        return maxOf(n, trashed.size.takeIf { prefs.current().archiveEnabled } ?: 0)
    }

    // ------------------------------------------------------------------ import

    /** Dry run: reads and parses the file, checks it against the whole history. Nothing is written. */
    suspend fun planImport(uri: Uri, mapping: ColumnMapping? = null, dayFirst: Boolean = true): ImportPlan = withContext(Dispatchers.IO) {
        val text = cr.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_IMPORT_BYTES) { context.getString(R.string.data_file_too_large) }
            String(bytes, Charsets.UTF_8)
        } ?: throw IllegalArgumentException(context.getString(R.string.data_file_open_failed))
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
    override suspend fun restoreLines(lines: List<CallHistoryLine>): Int = withContext(Dispatchers.IO + NonCancellable) {
        val calls = lines.mapNotNull { it.call }
        val kept = lines.mapNotNull { it.keepForever }
        if (kept.isNotEmpty()) setKeepForever(kept, true)
        if (calls.isEmpty()) return@withContext 0
        if (!prefs.current().archiveEnabled) {
            val have = readProvider(null).map { HistoryMerge.key(it.toEntry(0)) }.toHashSet()
            val values = calls.filter { HistoryMerge.key(it.toEntry(0)) !in have }.map { it.toValues() }
            return@withContext values.chunked(200).sumOf { chunk -> runCatching { cr.bulkInsert(Calls.CONTENT_URI, chunk.toTypedArray()) }.getOrDefault(0) }
        }
        mutex.withLock {
            val known = keys()
            val iso = countryIso
            val now = System.currentTimeMillis()
            val fresh = calls.mapNotNull { rec ->
                val key = crypto.mac(HistoryMerge.key(rec.toEntry(0)))
                if (known.add(key)) entity(rec, key, iso, now) else null
            }
            fresh.chunked(500).forEach { dao.insert(it) }
            if (fresh.isNotEmpty()) reload()
            fresh.size
        }
    }

    // ------------------------------------------------------------------ mapping

    private fun ArchivedCall.toEntry(): CallEntry = record.toEntry(ARCHIVE_ID_BASE + rowId)

    private fun CallLogRecord.toEntry(id: Long) = CallEntry(
        id = id,
        number = number.orEmpty(),
        cachedName = name,
        type = CallLogRepository.mapType(type),
        date = date,
        durationSec = duration,
        accountId = accountId,
        isNew = false,
        presentationHidden = presentation != Calls.PRESENTATION_ALLOWED || number.isNullOrBlank(),
    )

    private fun CallEntry.toRecord() = CallLogRecord(
        number = number, date = date, duration = durationSec, type = ProviderColumns.typeOf(type),
        presentation = if (presentationHidden) Calls.PRESENTATION_RESTRICTED else Calls.PRESENTATION_ALLOWED,
        accountId = accountId, name = cachedName, isNew = false, isRead = true,
    )

    private fun CallLogRecord.toValues() = ContentValues().apply {
        put(Calls.NUMBER, number)
        put(Calls.DATE, date)
        put(Calls.DURATION, duration)
        put(Calls.TYPE, type)
        put(Calls.NUMBER_PRESENTATION, presentation)
        put(Calls.PHONE_ACCOUNT_ID, accountId)
        put(Calls.PHONE_ACCOUNT_COMPONENT_NAME, accountComponent)
        put(Calls.CACHED_NAME, name)
        // Restored history is not news: no badge, no notification.
        put(Calls.NEW, 0)
        put(Calls.IS_READ, 1)
    }

    private fun encode(r: CallLogRecord): String = JSONObject()
        .put("n", r.number ?: JSONObject.NULL).put("d", r.date).put("s", r.duration).put("t", r.type).put("p", r.presentation)
        .put("a", r.accountId ?: JSONObject.NULL).put("c", r.accountComponent ?: JSONObject.NULL).put("m", r.name ?: JSONObject.NULL)
        .toString()

    private fun decode(s: String): CallLogRecord {
        val o = JSONObject(s)
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k)
        return CallLogRecord(str("n"), o.getLong("d"), o.optLong("s"), o.optInt("t"), o.optInt("p", 1), str("a"), str("c"), str("m"), isNew = false, isRead = true)
    }

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
            CallEntry(-p.id, p.number, p.name, CallLogRepository.mapType(p.type), p.date, p.durationSec, null, false, false)
    }
}
