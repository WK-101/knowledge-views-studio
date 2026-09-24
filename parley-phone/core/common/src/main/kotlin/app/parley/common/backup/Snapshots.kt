package app.parley.common.backup

import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Content-addressed storage for the contacts "time machine". Keys are lower-case hex SHA-256 of the
 * bytes. Implementations may be a directory, a Room table or an archive section.
 */
interface BlobStore {
    fun has(hash: String): Boolean
    fun put(hash: String, bytes: ByteArray)
    fun get(hash: String): ByteArray?
}

class InMemoryBlobStore : BlobStore {
    private val map = ConcurrentHashMap<String, ByteArray>()
    override fun has(hash: String) = map.containsKey(hash)
    override fun put(hash: String, bytes: ByteArray) { map.putIfAbsent(hash, bytes.copyOf()) }
    override fun get(hash: String): ByteArray? = map[hash]?.copyOf()
    val size: Int get() = map.size
    val keys: Set<String> get() = map.keys.toSet()
}

/** One point in time: contact key -> hash of its canonical JSON blob. */
@Serializable
data class SnapshotIndex(val timestamp: Long, val contacts: Map<String, String>) {
    fun toBytes(): ByteArray = RecordJson.json.encodeToString(serializer(), copy(contacts = contacts.toSortedMap())).toByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): SnapshotIndex = try {
            RecordJson.json.decodeFromString(serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw BackupIntegrityException("Malformed snapshot index", e)
        } catch (e: IllegalArgumentException) {
            throw BackupIntegrityException("Malformed snapshot index", e)
        }
    }
}

data class SnapshotResult(val index: SnapshotIndex, val newBlobs: Int, val reusedBlobs: Int)

/**
 * Writes incremental snapshots: each contact's canonical JSON (and each photo) is stored once under
 * its SHA-256, so an unchanged contact costs one index entry.
 */
class SnapshotWriter(private val store: BlobStore) {
    fun write(timestamp: Long, records: Sequence<ContactRecord>): SnapshotResult {
        val map = sortedMapOf<String, String>()
        var fresh = 0
        var reused = 0
        fun putBlob(hash: String, bytes: ByteArray) {
            if (store.has(hash)) reused++ else { store.put(hash, bytes); fresh++ }
        }
        for (r in records) {
            val json = RecordJson.encode(r) { h, b -> putBlob(h, b) }.toByteArray(Charsets.UTF_8)
            val h = RecordJson.sha256Hex(json)
            putBlob(h, json)
            require(map.put(r.key, h) == null) { "Duplicate contact key ${r.key}" }
        }
        return SnapshotResult(SnapshotIndex(timestamp, map), fresh, reused)
    }

    fun write(timestamp: Long, records: Iterable<ContactRecord>) = write(timestamp, records.asSequence())
}

/** Contact-level field changes plus a DataRow diff (rows compared by [DataRow.canonicalKey] and flags). */
data class ContactChange(
    val key: String,
    val before: ContactRecord,
    val after: ContactRecord,
    /** Names of changed top-level fields: displayName, starred, customRingtone, sendToVoicemail, accounts. */
    val fields: List<String>,
    val addedRows: List<DataRow>,
    val removedRows: List<DataRow>,
)

data class SnapshotDiff(
    val added: List<ContactRecord>,
    val removed: List<ContactRecord>,
    val changed: List<ContactChange>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/** A distinct version of one contact; [record] null means the contact didn't exist at [timestamp]. */
data class ContactVersion(val timestamp: Long, val hash: String?, val record: ContactRecord?)

object Snapshots {
    /** Loads and verifies a contact blob (photos resolved from the store). */
    fun load(store: BlobStore, hash: String): ContactRecord {
        val bytes = store.get(hash) ?: throw BackupIntegrityException("Missing snapshot blob $hash")
        if (RecordJson.sha256Hex(bytes) != hash) throw BackupIntegrityException("Snapshot blob $hash is corrupted")
        return RecordJson.decode(bytes.decodeToString()) { store.get(it) }
    }

    fun diff(store: BlobStore, old: SnapshotIndex, new: SnapshotIndex): SnapshotDiff {
        val added = new.contacts.keys.filter { it !in old.contacts }.sorted().map { load(store, new.contacts.getValue(it)) }
        val removed = old.contacts.keys.filter { it !in new.contacts }.sorted().map { load(store, old.contacts.getValue(it)) }
        val changed = old.contacts.keys.filter { k -> new.contacts[k]?.let { it != old.contacts[k] } == true }.sorted().map { k ->
            diffRecords(load(store, old.contacts.getValue(k)), load(store, new.contacts.getValue(k)))
        }
        return SnapshotDiff(added, removed, changed)
    }

    fun diffRecords(before: ContactRecord, after: ContactRecord): ContactChange {
        val fields = buildList {
            if (before.displayName != after.displayName) add("displayName")
            if (before.starred != after.starred) add("starred")
            if (before.customRingtone != after.customRingtone) add("customRingtone")
            if (before.sendToVoicemail != after.sendToVoicemail) add("sendToVoicemail")
            val acc = { c: ContactRecord -> c.raws.map { listOf(it.accountType, it.accountName, it.dataSet, it.sourceId) }.toSet() }
            if (acc(before) != acc(after)) add("accounts")
        }
        // Multiset diff over (canonicalKey, flags, blob hash) so reordering isn't a change.
        fun id(r: DataRow) = r.canonicalKey + "|" + r.isPrimary + r.isSuperPrimary + "|" + (r.blob?.let(RecordJson::sha256Hex) ?: "")
        val beforeRows = before.raws.flatMap { it.rows }
        val afterRows = after.raws.flatMap { it.rows }
        fun minus(a: List<DataRow>, b: List<DataRow>): List<DataRow> {
            val counts = HashMap<String, Int>()
            b.forEach { counts[id(it)] = (counts[id(it)] ?: 0) + 1 }
            return a.filter { r ->
                val k = id(r); val n = counts[k] ?: 0
                if (n > 0) { counts[k] = n - 1; false } else true
            }
        }
        return ContactChange(before.key, before, after, fields, minus(afterRows, beforeRows), minus(beforeRows, afterRows))
    }

    /**
     * Versions of [contactKey] across [snapshots] (any order), oldest first; consecutive snapshots with
     * the same content are collapsed. Absences after the first appearance are reported as deletions.
     */
    fun history(store: BlobStore, snapshots: List<SnapshotIndex>, contactKey: String): List<ContactVersion> {
        val out = ArrayList<ContactVersion>()
        var last: String? = null
        var seen = false
        for (s in snapshots.sortedBy { it.timestamp }) {
            val h = s.contacts[contactKey]
            if (h == null && !seen) continue
            seen = true
            if (out.isNotEmpty() && h == last) continue
            out += ContactVersion(s.timestamp, h, h?.let { load(store, it) })
            last = h
        }
        return out
    }
}
