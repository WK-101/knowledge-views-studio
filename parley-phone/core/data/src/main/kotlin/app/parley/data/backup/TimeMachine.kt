package app.parley.data.backup

import java.io.ByteArrayOutputStream
import app.parley.data.security.RecordCrypto
import android.content.Context
import app.parley.common.backup.BlobStore
import app.parley.common.backup.ContactVersion
import app.parley.common.backup.RecordJson
import app.parley.common.backup.SnapshotDiff
import app.parley.common.backup.SnapshotIndex
import app.parley.common.backup.SnapshotWriter
import app.parley.common.backup.Snapshots
import app.parley.common.record.ContactRecord
import app.parley.data.records.ContactRecordStore
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
 */
class TimeMachine(context: Context, private val records: ContactRecordStore) {
    private val root = File(context.filesDir, "timemachine")
    private val store = FileBlobStore(File(root, "blobs"), RecordCrypto.get(context))

    /** Re-seals snapshot blobs written before sealing existed; returns how many. */
    suspend fun resealOld(): Int = mutex.withLock { withContext(Dispatchers.IO) { store.all().count { runCatching { store.resealIfPlain(it) }.getOrDefault(false) } } }
    private val indexDir = File(root, "index").apply { mkdirs() }

    private val mutex = Mutex()

    fun snapshots(): List<SnapshotIndex> = indexDir.listFiles().orEmpty()
        .filter { it.name.endsWith(".idx") }
        .mapNotNull { runCatching { SnapshotIndex.fromBytes(it.readBytes()) }.getOrNull() }
        .sortedBy { it.timestamp }

    /** Takes a snapshot if the last one is older than [minIntervalMs]. Returns true if written. */
    suspend fun snapshotIfDue(minIntervalMs: Long = 20 * 3_600_000L): Boolean = mutex.withLock { withContext(Dispatchers.IO) {
        val last = snapshots().lastOrNull()
        val now = System.currentTimeMillis()
        if (last != null && now - last.timestamp < minIntervalMs) return@withContext false
        val result = SnapshotWriter(store).write(now, records.readAll(fullPhoto = false))
        if (last != null && last.contacts == result.index.contacts) {
            // Nothing changed: move the last index forward instead of adding a duplicate.
            File(indexDir, "${last.timestamp}.idx").delete()
        }
        writeIndex(result.index)
        prune(now)
        true
    } }

    private fun writeIndex(index: SnapshotIndex) {
        val f = File(indexDir, "${index.timestamp}.idx")
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(index.toBytes())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Removes every stored version of a contact (after it moved into the private vault). */
    suspend fun purge(key: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            snapshots().filter { key in it.contacts }.forEach { writeIndex(it.copy(contacts = it.contacts - key)) }
            collectGarbage(snapshots())
        }
    }

    /** Drops expired snapshots; unreferenced blobs are only swept when something was dropped (it reads every record). */
    private fun prune(now: Long) {
        val all = snapshots()
        val keep = all.filter { now - it.timestamp <= KEEP_DAYS * 86_400_000L }.ifEmpty { all.takeLast(1) }
        val drop = all.filter { it !in keep }
        if (drop.isEmpty()) return
        drop.forEach { File(indexDir, "${it.timestamp}.idx").delete() }
        collectGarbage(keep)
    }

    private fun collectGarbage(keep: List<SnapshotIndex>) {
        val live = keep.flatMap { it.contacts.values }.toHashSet()
        // Photos are separate blobs referenced from records; keep any blob still referenced by a kept record.
        val referenced = HashSet<String>(live)
        keep.forEach { idx -> idx.contacts.values.forEach { h -> runCatching { Snapshots.load(store, h) }.getOrNull()?.let { r -> photoHashes(r).forEach { referenced += it } } } }
        store.all().filter { it.name !in referenced }.forEach { it.delete() }
    }

    private fun photoHashes(r: ContactRecord): List<String> =
        r.raws.flatMap { raw -> raw.rows.mapNotNull { row -> row.blob?.let { RecordJson.sha256Hex(it) } } }

    suspend fun history(key: String): List<ContactVersion> = withContext(Dispatchers.IO) { Snapshots.history(store, snapshots(), key) }

    /** Everything that changed between the snapshot nearest to [since] and now. */
    suspend fun changesSince(since: Long): SnapshotDiff? = withContext(Dispatchers.IO) {
        val all = snapshots()
        if (all.isEmpty()) return@withContext null
        val old = all.lastOrNull { it.timestamp <= since } ?: all.first()
        val current = SnapshotWriter(store).write(System.currentTimeMillis(), records.readAll(fullPhoto = false)).index
        Snapshots.diff(store, old, current)
    }

    val oldestSnapshot: Long? get() = snapshots().firstOrNull()?.timestamp

    companion object {
        const val KEEP_DAYS = 180L
    }
}
