package app.parley.data.backup

import app.parley.common.catching
import java.io.ByteArrayOutputStream
import app.parley.data.security.RecordCrypto
import android.content.Context
import app.parley.common.backup.BackupIntegrityException
import app.parley.common.backup.BlobStore
import app.parley.common.backup.ContactVersion
import app.parley.common.backup.RecordJson
import app.parley.common.backup.SnapshotClearing
import app.parley.common.backup.SnapshotDiff
import app.parley.common.backup.SnapshotIndex
import app.parley.common.backup.SnapshotKeep
import app.parley.common.backup.SnapshotLog
import app.parley.common.backup.SnapshotWriter
import app.parley.common.backup.Snapshots
import app.parley.common.memory.NumberMemory
import app.parley.common.record.Mime
import app.parley.common.record.ContactRecord
import app.parley.data.records.ContactRecordStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Content-addressed blobs (gzip, then sealed with the small-records key when [crypto] is set) in app-private storage;
 * unchanged contacts cost nothing. Blobs written before sealing are read as they are.
 */
class FileBlobStore(private val dir: File, private val crypto: RecordCrypto? = null) : BlobStore {
    init {
        dir.mkdirs()
    }

    private fun file(hash: String) = File(dir, hash.take(2)).apply { mkdirs() }.let { File(it, hash) }
    override fun has(hash: String) = file(hash).exists()
    override fun put(hash: String, bytes: ByteArray) {
        val f = file(hash)
        if (f.exists()) return
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(seal(gzip(bytes)))
        tmp.renameTo(f)
    }
    override fun get(hash: String): ByteArray? = file(hash).takeIf { it.exists() }?.let { f -> gunzip(open(f.readBytes())) }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).use { it.readBytes() }
    private fun seal(b: ByteArray) = crypto?.sealBytes(b) ?: b
    private fun open(b: ByteArray) = crypto?.openBytes(b) ?: b

    /** Seals one blob written before sealing existed; false when it already was (or sealing isn't available). */
    fun resealIfPlain(f: File): Boolean {
        val c = crypto ?: return false
        val raw = f.readBytes()
        if (c.isSealed(raw)) return false
        val sealed = c.sealBytes(raw)
        if (!c.isSealed(sealed)) return false
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(sealed)
        return tmp.renameTo(f)
    }

    fun all(): Sequence<File> = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
}

/**
 * Local "time machine": a daily incremental snapshot of all contacts (only changed contacts are
 * stored again). Lets you see what changed since any day and restore one contact to any version.
 * Private to Parley, kept for [KEEP_DAYS] days, never leaves the phone.
 *
 * The index ([SnapshotLog], `versions.bin`) keeps each contact's changes only, and is held in memory between questions
 * unless it is unusually large ([CACHE_ENTRIES]). Versions before 5.4 wrote one full index file per snapshot
 * (`index/<time>.idx`); they move into the new index the first time it is read, and are deleted once it is written.
 */
class TimeMachine(context: Context, private val records: ContactRecordStore) {
    private val root = File(context.filesDir, "timemachine")
    private val store = FileBlobStore(File(root, "blobs"), RecordCrypto.get(context))

    /** Re-seals snapshot blobs written before sealing existed; returns how many. */
    suspend fun resealOld(): Int = mutex.withLock {
        withContext(Dispatchers.IO) { store.all().count { runCatching { store.resealIfPlain(it) }.getOrDefault(false) } }
    }

    /** One full index per snapshot, as versions before 5.4 wrote them; only read to move them over. */
    private val oldIndexDir = File(root, "index")
    private val logFile = File(root, "versions.bin")

    private val mutex = Mutex()

    /** The index while it is small enough to keep; null until read, or when it is too large to hold. */
    @Volatile private var cached: SnapshotLog? = null

    /** Measured the last time the index was read or written (for the cap and for tests). */
    @Volatile var lastEntryCount: Int = 0
        private set

    /** Runs [block] on the index under the lock; [save] writes it back afterwards. */
    private suspend fun <T> withLog(save: Boolean = false, block: (SnapshotLog) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val log = load()
            val out = block(log)
            if (save) write(log)
            out
        }
    }

    private fun load(): SnapshotLog {
        cached?.let { return it }
        var log = if (logFile.isFile) {
            try {
                SnapshotLog.decode(logFile.readBytes())
            } catch (e: BackupIntegrityException) {
                // Nothing can be told from a damaged index; the next snapshot starts a new one.
                Log.w(TAG, "Snapshot index unreadable; starting again", e)
                SnapshotLog()
            }
        } else {
            SnapshotLog()
        }
        val old = oldIndexDir.listFiles().orEmpty().filter { it.name.endsWith(".idx") }
        if (old.isNotEmpty()) {
            log = moveOver(log, old)
            write(log)
            old.forEach { it.delete() }
            oldIndexDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
            oldIndexDir.delete()
        }
        keep(log)
        return log
    }

    /**
     * Adds the snapshots of the old per-snapshot files to [log], oldest first and one file at a time, then notes each
     * distinct record's photos (each record is read once, here only). A file that can't be read is skipped, as before.
     */
    private fun moveOver(log: SnapshotLog, files: List<File>): SnapshotLog {
        val after = log.timestamps.lastOrNull() ?: Long.MIN_VALUE
        files.mapNotNull { f -> f.name.removeSuffix(".idx").toLongOrNull()?.let { it to f } }.sortedBy { it.first }.forEach { (t, f) ->
            if (t <= after) return@forEach
            val idx = runCatching { SnapshotIndex.fromBytes(f.readBytes()) }.getOrNull() ?: return@forEach
            if (idx.timestamp > (log.timestamps.lastOrNull() ?: Long.MIN_VALUE)) runCatching { log.add(idx.timestamp, idx.contacts) }
        }
        for (h in log.records()) photosOf(h)?.let { log.notePhotos(h, it) }
        return log
    }

    /** The photo blobs record [hash] refers to, read without opening the photos; null when it can't be read. */
    private fun photosOf(hash: String): List<String>? = runCatching { store.get(hash)?.let { RecordJson.blobHashes(it.decodeToString()) } }.getOrNull()

    private fun write(log: SnapshotLog) {
        root.mkdirs()
        val tmp = File(logFile.path + ".tmp")
        tmp.writeBytes(log.encode())
        if (!tmp.renameTo(logFile)) { logFile.delete(); tmp.renameTo(logFile) }
        keep(log)
    }

    private fun keep(log: SnapshotLog) {
        lastEntryCount = log.entryCount
        cached = log.takeIf { lastEntryCount <= CACHE_ENTRIES }
    }

    /** When the snapshots were taken, oldest first (no record is read). */
    suspend fun snapshotTimes(): List<Long> = withLog { it.timestamps.toList() }

    /** The snapshot taken at [timestamp], whole, or null. */
    suspend fun snapshotAt(timestamp: Long): SnapshotIndex? = withLog { it.snapshot(timestamp) }

    /** Takes a snapshot if the last one is older than [minIntervalMs]. Returns true if written. */
    suspend fun snapshotIfDue(minIntervalMs: Long = 20 * 3_600_000L): Boolean = mutex.withLock { withContext(Dispatchers.IO) {
        val log = load()
        val last = log.timestamps.lastOrNull()
        val now = System.currentTimeMillis()
        if (last != null && now - last < minIntervalMs) return@withContext false
        if (last != null && now <= last) return@withContext false
        val result = SnapshotWriter(store).write(now, records.readAll(fullPhoto = false))
        val changed = log.add(now, result.index.contacts, result.photos)
        // Nothing changed: the last snapshot moves forward instead of a duplicate being added.
        if (!changed && last != null) log.retain(log.timestamps.toSet() - last)
        prune(log, now)
        write(log)
        true
    } }

    /** Removes every stored version of a contact (after it moved into the private vault). */
    suspend fun purge(key: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val log = load()
            if (log.purge(key)) {
                write(log)
                collectGarbage(log)
            }
        }
    }

    /** Drops expired snapshots; unreferenced blobs are swept only when something was dropped. */
    private fun prune(log: SnapshotLog, now: Long) {
        val all = log.timestamps
        val keep = all.filter { now - it <= KEEP_DAYS * 86_400_000L }.ifEmpty { all.takeLast(1) }
        if (keep.size == all.size) return
        log.retain(keep.toSet())
        collectGarbage(log)
    }

    /**
     * Deletes the snapshots a clear from History & undo doesn't [keep], with every blob only they used. Returns how
     * many snapshots went. With none kept, the whole store goes, including files a crash left half-written.
     */
    suspend fun clear(keep: SnapshotKeep, now: Long = System.currentTimeMillis()): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val log = load()
            val all = log.timestamps.toList()
            val drop = SnapshotClearing.toDrop(all, keep, now)
            if (drop.size == all.size) {
                // Nothing kept: no record to read for references, so everything under the store goes.
                root.listFiles().orEmpty().filter { it.name != "blobs" }.forEach { it.deleteRecursively() }
                File(root, "blobs").walkBottomUp().filter { it.isFile }.forEach { it.delete() }
                cached = null
                lastEntryCount = 0
            } else if (drop.isNotEmpty()) {
                log.retain(all.toSet() - drop.toSet())
                write(log)
                collectGarbage(log)
            }
            drop.size
        }
    }

    /** Bytes the snapshots take on disk. */
    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Deletes every blob no kept snapshot needs: from the index alone, by distinct hash, without opening any record. */
    private fun collectGarbage(log: SnapshotLog) {
        val referenced = log.referencedBlobs()
        store.all().filter { it.name !in referenced }.forEach { it.delete() }
    }

    private fun load(hash: String?): ContactRecord? = hash?.let { Snapshots.load(store, it) }

    suspend fun history(key: String): List<ContactVersion> = withContext(Dispatchers.IO) {
        withLog { it.history(key) }.map { (t, h) -> ContactVersion(t, h, load(h)) }
    }

    /** Everything that changed between the snapshot nearest to [since] and now. */
    suspend fun changesSince(since: Long): SnapshotDiff? = withContext(Dispatchers.IO) {
        val old = withLog { log -> (log.timestamps.lastOrNull { it <= since } ?: log.timestamps.firstOrNull())?.let(log::snapshot) } ?: return@withContext null
        val current = SnapshotWriter(store).write(System.currentTimeMillis(), records.readAll(fullPhoto = false)).index
        Snapshots.diff(store, old, current)
    }

    /** What changed between two stored snapshots (the sync watchdog's daily look); null when one of them is gone. */
    suspend fun diffBetween(old: Long, new: Long): SnapshotDiff? = withContext(Dispatchers.IO) {
        val (a, b) = withLog { it.snapshot(old) to it.snapshot(new) }
        if (a == null || b == null) null else Snapshots.diff(store, a, b)
    }

    /**
     * The newest stored version of each of [keys] taken at or before [atOrBefore]: what a contact looked like before
     * it vanished or lost numbers. Keys no snapshot holds (or whose blob can't be read) are left out.
     */
    suspend fun lastVersions(keys: Collection<String>, atOrBefore: Long = Long.MAX_VALUE): Map<String, ContactRecord> = withContext(Dispatchers.IO) {
        val hashes = withLog { log -> keys.distinct().mapNotNull { k -> log.lastPresent(k, atOrBefore)?.let { k to it } } }
        val out = LinkedHashMap<String, ContactRecord>()
        hashes.forEach { (k, h) -> runCatching { Snapshots.load(store, h) }.getOrNull()?.let { out[k] = it } }
        out
    }

    /** Number memory: changes with every snapshot written, dropped or purged. */
    suspend fun memoryStamp(): String = withLog { log -> log.timestamps.joinToString(",") + "|" + log.entryCount }

    /**
     * Number memory: each version of a contact the snapshots hold, with its numbers and the last snapshot that had it.
     * Each version is read once, without its photo.
     */
    suspend fun peopleForMemory(): NumberMemory.SnapshotSpans = withContext(Dispatchers.IO) {
        val (spans, newest) = withLog { it.spans() to it.timestamps.lastOrNull() }
        val versions = HashMap<String, NumberMemory.Person?>()
        val people = spans.mapNotNull { s ->
            versions.getOrPut(s.hash) {
                catching { store.get(s.hash)?.let { RecordJson.decode(it.decodeToString()) { null } } }.getOrNull()?.let { r ->
                    val numbers = r.raws.flatMap { raw -> raw.rows.filter { it.mimeType == Mime.PHONE }.mapNotNull { it["data1"] } }
                    NumberMemory.Person(r.displayName, numbers)
                }
            }?.let { NumberMemory.PersonSpan(s.key, s.lastSeen, it) }
        }
        NumberMemory.SnapshotSpans(people, newest ?: 0L)
    }

    companion object {
        private const val TAG = "TimeMachine"
        const val KEEP_DAYS = 180L

        /**
         * Versions held in memory between questions: about 60 bytes each, so a few MB. Typical address books stay well
         * under it (20,000 contacts with half of them changing over the half-year kept is 30,000); above it the index
         * is read from disk for each question instead.
         */
        const val CACHE_ENTRIES = 150_000
    }
}
