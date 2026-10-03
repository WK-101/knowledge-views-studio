package app.parley.common.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.UTFDataFormatException

/**
 * The time machine's index: the days snapshots were taken, and for each contact only the snapshots where it changed
 * (its record hash then, or that it was gone). A snapshot is the newest version of every contact at or before it, so
 * an unchanged contact costs nothing per day, and a question about one contact or two days never reads the others'
 * days. It replaces one full `lookup key → hash` map per snapshot (`SnapshotIndex` files), which cost about 150 bytes
 * per contact per day on disk and had to be parsed in full, all 180 of them, for any question.
 *
 * Also kept: which photo blobs each record refers to, so unused blobs are found without opening a single record.
 *
 * Not thread-safe; the time machine changes it under its lock.
 */
class SnapshotLog private constructor(
    private val times: MutableList<Long>,
    private val versions: HashMap<String, MutableList<Version>>,
    private val photos: HashMap<Digest, List<Digest>>,
) {
    constructor() : this(ArrayList(), HashMap(), HashMap())

    /** A SHA-256 as four longs: a quarter of the memory of its hex string, compared by value. */
    data class Digest(val a: Long, val b: Long, val c: Long, val d: Long) : Comparable<Digest> {
        fun hex(): String = buildString(HEX_LENGTH) { listOf(a, b, c, d).forEach { append(java.lang.Long.toHexString(it).padStart(16, '0')) } }

        override fun compareTo(other: Digest): Int = compareValuesBy(this, other, { it.a }, { it.b }, { it.c }, { it.d })

        companion object {
            fun of(hex: String): Digest {
                if (hex.length != HEX_LENGTH || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) throw BackupIntegrityException("Not a record hash")
                fun part(i: Int) = java.lang.Long.parseUnsignedLong(hex.substring(i * 16, i * 16 + 16), 16)
                return Digest(part(0), part(1), part(2), part(3))
            }
        }
    }

    /** From [at] on, the contact is the record [hash], or gone when it is null. */
    private class Version(val at: Long, val hash: Digest?)

    /** The snapshot times, oldest first. */
    val timestamps: List<Long> get() = times

    /** Stored versions (the measure of the index's size in memory). */
    val entryCount: Int get() = versions.values.sumOf { it.size }

    /**
     * Adds the snapshot [contacts] (key → record hash) taken at [timestamp], later than every one before; [photos]
     * names each new record's photo blobs. Returns whether any contact changed since the previous snapshot.
     */
    fun add(timestamp: Long, contacts: Map<String, String>, photos: Map<String, List<String>> = emptyMap()): Boolean {
        require(times.isEmpty() || timestamp > times.last()) { "Snapshots are added in time order" }
        // Every hash is checked before anything changes, so a damaged snapshot leaves the index as it was.
        val parsed = contacts.mapValues { Digest.of(it.value) }
        val photoDigests = photos.filterValues { it.isNotEmpty() }.entries.associate { (k, v) -> Digest.of(k) to v.map(Digest::of) }
        times += timestamp
        var changed = false
        for ((key, h) in parsed) {
            val list = versions.getOrPut(key) { ArrayList(1) }
            if (list.lastOrNull()?.hash != h) {
                list += Version(timestamp, h)
                changed = true
            }
        }
        for ((key, list) in versions) {
            if (key !in contacts && list.last().hash != null) {
                list += Version(timestamp, null)
                changed = true
            }
        }
        photoDigests.forEach { (record, blobs) -> this.photos.putIfAbsent(record, blobs) }
        return changed
    }

    /** The snapshot taken at [timestamp], whole; null when there is none at that time. */
    fun snapshot(timestamp: Long): SnapshotIndex? {
        if (timestamp !in times) return null
        val map = HashMap<String, String>()
        for ((key, list) in versions) at(list, timestamp)?.let { map[key] = it.hex() }
        return SnapshotIndex(timestamp, map)
    }

    /** The versions of [key], oldest first: when each began, and its record hash (null: the contact was gone). */
    fun history(key: String): List<Pair<Long, String?>> = versions[key].orEmpty().map { it.at to it.hash?.hex() }

    /** The newest record of [key] taken at or before [atOrBefore]; a contact that was gone then gives its last one before. */
    fun lastPresent(key: String, atOrBefore: Long = Long.MAX_VALUE): String? =
        versions[key]?.lastOrNull { it.at <= atOrBefore && it.hash != null }?.hash?.hex()

    /**
     * Each stored version of a contact that existed, with the last snapshot that still had it: what number memory
     * reads ("was saved as … until 12 March"). A version still current ends at the newest snapshot.
     */
    fun spans(): List<Span> {
        val out = ArrayList<Span>()
        for ((key, list) in versions) {
            list.forEachIndexed { i, v ->
                val hash = v.hash ?: return@forEachIndexed
                val next = list.getOrNull(i + 1)?.at
                val last = if (next == null) times.last() else times.lastOrNull { it < next } ?: return@forEachIndexed
                out += Span(key, hash.hex(), last)
            }
        }
        return out
    }

    data class Span(val key: String, val hash: String, val lastSeen: Long)

    /**
     * Keeps only the snapshots at [keep]. Each kept snapshot stays exactly as it was: a version that began on a dropped
     * day now begins on the next kept one, and a contact's versions that no kept snapshot shows go.
     */
    fun retain(keep: Set<Long>) {
        val kept = times.filter { it in keep }
        if (kept.size == times.size) return
        times.clear()
        times += kept
        val it = versions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val next = remapped(e.value, kept)
            if (next.isEmpty()) it.remove() else e.setValue(next)
        }
        val live = versions.values.flatMapTo(HashSet()) { list -> list.mapNotNull { v -> v.hash } }
        photos.keys.retainAll(live)
    }

    /** [list] as the [kept] snapshots show it: no version shown by none, no repeat, no leading "gone". */
    private fun remapped(list: List<Version>, kept: List<Long>): MutableList<Version> {
        val next = ArrayList<Version>(list.size)
        for (v in list) {
            // The first kept snapshot at or after it: the one that now shows this version (if it is still the newest).
            val at = firstAtOrAfter(kept, v.at)
            if (at != null) {
                if (next.lastOrNull()?.at == at) next.removeAt(next.lastIndex)
                if (next.lastOrNull()?.hash != v.hash) next += Version(at, v.hash)
            }
        }
        while (next.isNotEmpty() && next.first().hash == null) next.removeAt(0)
        return next
    }

    /** Forgets every version of [key] (it moved into the private vault). True when there was any. */
    fun purge(key: String): Boolean {
        val gone = versions.remove(key) ?: return false
        val live = versions.values.flatMapTo(HashSet()) { list -> list.mapNotNull { v -> v.hash } }
        gone.forEach { v -> v.hash?.takeIf { it !in live }?.let(photos::remove) }
        return true
    }

    /** Every blob a kept snapshot needs: each distinct record, and the photos those records refer to. */
    fun referencedBlobs(): Set<String> {
        val records = versions.values.flatMapTo(HashSet()) { list -> list.mapNotNull { it.hash } }
        return records.flatMapTo(HashSet()) { h -> listOf(h.hex()) + photos[h].orEmpty().map { it.hex() } }
    }

    /** Distinct records the snapshots hold. */
    fun records(): Set<String> = versions.values.flatMapTo(HashSet()) { list -> list.mapNotNull { it.hash?.hex() } }

    /** Notes the photo blobs of [record], one indexed before photos were noted (read once when the old files move over). */
    fun notePhotos(record: String, blobs: List<String>) {
        if (blobs.isNotEmpty()) photos[Digest.of(record)] = blobs.map(Digest::of)
    }

    /** The bytes stored on disk: a format of its own, read back by [decode]. Keys and records are written sorted. */
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream(64 + entryCount * 48)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(FORMAT)
            out.writeInt(times.size)
            times.forEach(out::writeLong)
            out.writeInt(versions.size)
            for (key in versions.keys.sorted()) writeVersions(out, key, versions.getValue(key))
            out.writeInt(photos.size)
            for (record in photos.keys.sorted()) {
                val blobs = photos.getValue(record)
                writeDigest(out, record)
                out.writeInt(blobs.size)
                blobs.forEach { writeDigest(out, it) }
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x50544D4C // "PTML"
        private const val FORMAT = 1
        private const val HEX_LENGTH = 64

        /** A key, a time or a count beyond these means the file is damaged, not that memory should be spent on it. */
        private const val MAX_COUNT = 10_000_000

        private fun firstAtOrAfter(sorted: List<Long>, t: Long): Long? {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < t) lo = mid + 1 else hi = mid
            }
            return sorted.getOrNull(lo)
        }

        /** The version of a contact shown by the snapshot at [t]: its newest at or before [t]. */
        private fun at(list: List<Version>, t: Long): Digest? {
            var found: Version? = null
            for (v in list) if (v.at <= t) found = v else break
            return found?.hash
        }

        private fun writeVersions(out: DataOutputStream, key: String, list: List<Version>) {
            out.writeUTF(key)
            out.writeInt(list.size)
            for (v in list) {
                out.writeLong(v.at)
                out.writeBoolean(v.hash != null)
                v.hash?.let { writeDigest(out, it) }
            }
        }

        private fun writeDigest(out: DataOutputStream, d: Digest) {
            out.writeLong(d.a)
            out.writeLong(d.b)
            out.writeLong(d.c)
            out.writeLong(d.d)
        }

        private fun readDigest(inp: DataInputStream) = Digest(inp.readLong(), inp.readLong(), inp.readLong(), inp.readLong())

        private fun count(inp: DataInputStream): Int = inp.readInt().also { if (it !in 0..MAX_COUNT) throw BackupIntegrityException("Damaged snapshot index") }

        /** Reads [encode]'s bytes; throws [BackupIntegrityException] when they are damaged. */
        fun decode(bytes: ByteArray): SnapshotLog = try {
            DataInputStream(bytes.inputStream()).use { inp ->
                if (inp.readInt() != MAGIC || inp.readByte().toInt() != FORMAT) throw BackupIntegrityException("Not a snapshot index")
                val times = ArrayList<Long>()
                repeat(count(inp)) { times += inp.readLong() }
                val versions = HashMap<String, MutableList<Version>>()
                repeat(count(inp)) {
                    val key = inp.readUTF()
                    val list = ArrayList<Version>()
                    repeat(count(inp)) {
                        val at = inp.readLong()
                        list += Version(at, if (inp.readBoolean()) readDigest(inp) else null)
                    }
                    versions[key] = list
                }
                val photos = HashMap<Digest, List<Digest>>()
                repeat(count(inp)) {
                    val record = readDigest(inp)
                    photos[record] = List(count(inp)) { readDigest(inp) }
                }
                if (inp.read() != -1) throw BackupIntegrityException("Damaged snapshot index")
                SnapshotLog(times, versions, photos)
            }
        } catch (e: EOFException) {
            throw BackupIntegrityException("Damaged snapshot index", e)
        } catch (e: UTFDataFormatException) {
            throw BackupIntegrityException("Damaged snapshot index", e)
        } catch (e: BackupIntegrityException) {
            throw e
        } catch (e: IOException) {
            throw BackupIntegrityException("Damaged snapshot index", e)
        }

        /** The index the per-snapshot files ([SnapshotIndex], oldest first) describe; each is read once and let go. */
        fun fromIndexes(indexes: Sequence<SnapshotIndex>): SnapshotLog {
            val log = SnapshotLog()
            for (idx in indexes) log.add(idx.timestamp, idx.contacts)
            return log
        }
    }
}
