package app.parley.data.backup

import app.parley.common.catching
import app.parley.common.storage.DurableFiles
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
        DurableFiles.write(f, seal(gzip(bytes)))
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
        return DurableFiles.write(f, sealed)
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
                Log.w(TAG, "Snapshot index unreadable; set aside and rebuilt from the stored versions", e)
                setAside()
                recover().also(::write)
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
        DurableFiles.write(logFile, log.encode())
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

    /**
     * A damaged index is kept beside the new one (never overwritten): a later version or a support request may still
     * read it. Its presence also stops clean-ups (see [collectGarbage]).
     */
    private fun setAside() {
        val aside = File(root, DAMAGED_PREFIX + System.currentTimeMillis())
        if (!DurableFiles.move(logFile, aside)) catching { logFile.copyTo(aside, overwrite = true) }
    }

    /**
     * An index rebuilt from the stored versions after the old one couldn't be read: each contact's versions in the
     * order they were first stored (a version's file is written when it first appears, so its time dates it), grouped
     * into snapshots by when they were written. What can't be told any more is when a contact was deleted, so every
     * contact stays present from its first version on. Versions that can't be read now are left out of the index, not
     * deleted.
     */
    private fun recover(): SnapshotLog {
        val found = store.all().mapNotNull { f ->
            val bytes = catching { store.get(f.name) }.getOrNull() ?: return@mapNotNull null
            if (RecordJson.sha256Hex(bytes) != f.name) return@mapNotNull null
            // Photos are blobs too; only records decode.
            val record = catching { RecordJson.decode(bytes.decodeToString()) { null } }.getOrNull() ?: return@mapNotNull null
            Triple(record.key, f.name, f.lastModified())
        }.sortedBy { it.third }.toList()
        val log = SnapshotLog()
        val present = sortedMapOf<String, String>()
        var i = 0
        while (i < found.size) {
            val start = found[i].third
            var j = i
            while (j < found.size && found[j].third - start <= RECOVER_GROUP_MS) present[found[j].first] = found[j++].second
            val at = found[j - 1].third
            if (at > (log.timestamps.lastOrNull() ?: Long.MIN_VALUE)) catching { log.add(at, HashMap(present)) }
            i = j
        }
        for (h in log.records()) photosOf(h)?.let { log.notePhotos(h, it) }
        Log.i(TAG, "Snapshot index rebuilt: ${log.timestamps.size} snapshot(s), ${log.entryCount} version(s)")
        return log
    }

    /**
     * Whether a damaged index was set aside less than [KEEP_DAYS] ago. Until then nothing is deleted by an index that
     * may not know every stored version; older set-aside files go (every version they knew has expired by then).
     */
    private fun recovering(now: Long = System.currentTimeMillis()): Boolean {
        var recent = false
        root.listFiles().orEmpty().filter { it.name.startsWith(DAMAGED_PREFIX) }.forEach { f ->
            if (now - f.lastModified() < KEEP_DAYS * 86_400_000L) recent = true else f.delete()
        }
        return recent
    }

    /**
     * Deletes every blob no kept snapshot needs: from the index alone, by distinct hash, without opening any record.
     * Never while the index was rebuilt after damage ([recovering]): it may not know every version still stored.
     */
    private fun collectGarbage(log: SnapshotLog) {
        if (recovering()) return
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

        /** A damaged `versions.bin`, kept aside with the time it was found. */
        private const val DAMAGED_PREFIX = "versions.bin.damaged-"

        /** Versions written within this of each other belong to one rebuilt snapshot (a daily run takes seconds). */
        private const val RECOVER_GROUP_MS = 10 * 60_000L

        /**
         * Versions held in memory between questions: about 60 bytes each, so a few MB. Typical address books stay well
         * under it (20,000 contacts with half of them changing over the half-year kept is 30,000); above it the index
         * is read from disk for each question instead.
         */
        const val CACHE_ENTRIES = 150_000
    }
}
