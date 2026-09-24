package app.parley.data.history

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
import app.parley.data.backup.CallHistoryBackup
import app.parley.data.changes
import app.parley.data.vault.VaultRepository
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
 * Call history as data: Parley's encrypted archive of the system call log (H1), the merged view Recents
 * reads, the shared [CallLogIndex] (H9), deletes with a 30-day undo (K10), CSV import (H8) and per-SIM plan
 * meters (T8).
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
) : CallHistoryBackup {
    val prefs = HistoryPrefs(context, scope)
    @Volatile private var dbRef: HistoryDatabase? = null
    private val db: HistoryDatabase get() = dbRef ?: synchronized(this) { dbRef ?: HistoryDatabase.create(context).also { dbRef = it } }
    private val dao: HistoryDao get() = db.dao()
    private val crypto = HistoryCrypto(context)
    private val cr = context.contentResolver
    private val mutex = Mutex()
    private var knownKeys: HashSet<String>? = null

    /** Decrypted rows by id, so a sync only decrypts the rows it added (guarded by [reloadLock]). */
    private val decrypted = HashMap<Long, CallLogRecord>()
    private val undecryptable = HashSet<Long>()
    private val reloadLock = Mutex()

    val countryIso: String get() = PhoneEnv.countryIso(context)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _archive = MutableStateFlow<List<ArchivedCall>?>(null)

    /** Decrypted archive, newest first; null until first loaded. */
    val archive: StateFlow<List<ArchivedCall>?> = _archive

    private val _kept = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Numbers whose history ignores retention: fingerprint → number. */
    val keptForever: StateFlow<Map<String, String>> = _kept

    /** Filter applied to Recents (H4); saved filters live in [prefs]. */
    val activeFilter = MutableStateFlow(HistoryFilter())

    /** Private (vault) numbers, matched by line (F7: E.164, not the last 9 digits, so a foreign number sharing them stays). */
    private val vaultKeys = vault.contacts.map { list -> PhoneNumbers.LineSet(list.flatMap { v -> v.numbers }, countryIso) }
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
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    /** The shared index over [calls] and contacts, rebuilt off the main thread when either changes. */
    val index: StateFlow<CallLogIndex?> = combine(calls, contacts.contacts) { c, ct -> c to ct }
        .debounce(200)
        .map { (c, ct) -> c?.let { buildIndex(it, ct) } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), null)

    private fun buildIndex(calls: List<CallEntry>, ct: List<app.parley.common.ContactSummary>?): CallLogIndex {
        // Without the permission the list is empty but that means "unknown", not "nobody is a contact".
        val known = if (ct == null || !Permissions.has(context, android.Manifest.permission.READ_CONTACTS)) null
        else ct.map { IndexContact(it.id, it.lookupKey, it.displayName, it.phones.map { p -> p.number }) }
        return CallLogIndex.build(calls, known, countryIso, zone)
    }

    /** Waits (up to 30 s) for the first index, e.g. in a worker. */
    suspend fun awaitIndex(): CallLogIndex? = withTimeoutOrNull(30_000) { index.filterNotNull().first() }

    suspend fun awaitCalls(): List<CallEntry>? = withTimeoutOrNull(30_000) { calls.filterNotNull().first() }

    init {
        scope.launch(Dispatchers.IO) {
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
                val ids = dao.idsNewestFirst()
                decrypted.keys.retainAll(ids.toHashSet())
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
                if (f.exists()) f.renameTo(java.io.File(f.parentFile, "parley-history-$suffix.db$ext"))
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
            if (!Permissions.has(context, android.Manifest.permission.READ_CALL_LOG)) return@withLock 0
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

    /** Calls with private contacts never stay in the archive (they live in the vault's own history). */
    private suspend fun purgeVault(vk: PhoneNumbers.LineSet): Boolean {
        if (vk.isEmpty) return false
        val ids = _archive.value.orEmpty().filter { !it.record.number.isNullOrBlank() && it.record.number in vk }.map { it.rowId }
        if (ids.isEmpty()) return false
        ids.chunked(500).forEach { dao.deleteIds(it) }
        knownKeys = null
        return true
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
        val list = numbers.filter { it.isNotBlank() }.distinctBy { NumberKeys.of(it, countryIso) }
        if (keep) {
            dao.addKeepForever(list.map { KeepForeverEntity(personMac(it), crypto.seal(it.toByteArray()), System.currentTimeMillis()) })
        } else {
            dao.removeKeepForever(list.map { personMac(it) })
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
            val archivedById = _archive.value.orEmpty().associateBy { ARCHIVE_ID_BASE + it.rowId }
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
            batch
        }
    }

    /** Every call with [number] (any format), optionally only since [since], including archived calls Recents doesn't show. */
    fun callsFor(number: String, since: Long = Long.MIN_VALUE): List<CallEntry> {
        val iso = countryIso
        fun matches(e: CallEntry) = e.date >= since && !e.presentationHidden && PhoneNumbers.same(e.number, number, iso)
        val shown = calls.value.orEmpty().filter(::matches)
        if (!prefs.state.value.archiveEnabled) return shown
        val seen = shown.map { HistoryMerge.key(it) }.toHashSet()
        val older = _archive.value.orEmpty().asSequence().drop(ARCHIVE_UI_WINDOW).map { it.toEntry() }
            .filter { matches(it) && seen.add(HistoryMerge.key(it)) }.toList()
        return shown + older
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
            cr.query(Uri.withAppendedPath(Calls.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(Calls._ID), null, null, null)
                ?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        }
        ids.chunked(500).forEach { chunk ->
            n += runCatching { cr.delete(Calls.CONTENT_URI, "${Calls._ID} IN (${chunk.joinToString(",")})", null) }.getOrDefault(0)
        }
        mutex.withLock {
            runCatching {
                if (_archive.value == null) reload()
                val person = personMac(number, iso)
                // Also rows filed under another form of the number.
                val other = _archive.value.orEmpty().filter { !it.record.number.isNullOrBlank() && PhoneNumbers.same(it.record.number, number, iso) }.map { it.rowId }
                n += dao.deleteByPerson(person)
                other.chunked(500).forEach { dao.deleteIds(it) }
                dao.removeKeepForever(listOf(person))
                knownKeys = null
                reload()
            }
        }
        n
    }

    suspend fun deleteForNumber(number: String): Long? = delete(callsFor(number))

    suspend fun deleteRange(number: String, range: DeleteRange, picked: java.time.LocalDate? = null): Long? =
        delete(callsFor(number, range.since(System.currentTimeMillis(), zone, picked)))

    suspend fun trashBatches(): List<TrashBatch> = withContext(Dispatchers.IO) { dao.trashBatches() }

    /** Puts a deleted batch back into the system call log (and the archive). Returns calls restored. */
    suspend fun undoDelete(batchId: Long): Int = withContext(Dispatchers.IO + NonCancellable) { undoLock.withLock { undoDeleteLocked(batchId) } }

    /** One undo at a time: a second tap waits and then finds the batch gone (F21). */
    private val undoLock = Mutex()

    private suspend fun undoDeleteLocked(batchId: Long): Int {
        val trashed = dao.trashed(batchId).mapNotNull { runCatching { decode(String(crypto.open(it.blob))) }.getOrNull() }
        if (trashed.isEmpty()) return 0
        // F21: idempotent. Rows the system log already has again (an earlier, interrupted undo) aren't inserted twice.
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

    // ------------------------------------------------------------------ import (H8)

    /** Dry run: reads and parses the file, checks it against the whole history. Nothing is written. */
    suspend fun planImport(uri: Uri, mapping: ColumnMapping? = null, dayFirst: Boolean = true): ImportPlan = withContext(Dispatchers.IO) {
        val text = cr.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_IMPORT_BYTES) { "The file is larger than 20 MB" }
            String(bytes, Charsets.UTF_8)
        } ?: throw IllegalArgumentException("Couldn't open the file")
        val existing = HashSet<String>()
        readProvider(null).forEach { existing += importKey(it) }
        _archive.value.orEmpty().forEach { existing += importKey(it.record) }
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

    // ------------------------------------------------------------------ plan meter (T8)

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
        // Rather fail the backup than silently leave the archive out.
        if (_archive.value == null && prefs.current().archiveEnabled) throw IllegalStateException("Parley's call archive can't be unlocked right now. Try again later.")
        val inProvider = readProvider(null).map { HistoryMerge.key(it.toEntry(0)) }.toHashSet()
        _archive.value.orEmpty().map { it.record }.filter { HistoryMerge.key(it.toEntry(0)) !in inProvider }.map { CallHistoryLine(call = it) } +
            _kept.value.values.map { CallHistoryLine(keepForever = it) }
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
        private const val DAY = 86_400_000L
        private const val TRASH_DAYS = 30L
        private const val MAX_IMPORT_BYTES = 20 shl 20

        fun isArchived(e: CallEntry) = e.id >= ARCHIVE_ID_BASE
    }
}
