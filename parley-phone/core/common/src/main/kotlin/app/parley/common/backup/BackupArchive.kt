package app.parley.common.backup

import app.parley.common.record.ContactRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Parley Backup archive: a plain, deterministic ZIP (usually wrapped in the BackupCrypto envelope).
 *
 * Entry order is fixed: contacts.jsonl, contacts.vcf, calllog.jsonl, blocking.json, speeddial.json,
 * numbersim.json, settings.json, vault/<name> (sorted), journal.jsonl, photos/<sha256>.bin (sorted),
 * manifest.json (last, because it holds the SHA-256 of every other entry). All entries use the DOS
 * epoch timestamp and DEFLATE level 9, so identical content gives an identical ZIP on the same JDK.
 */

// ------------------------------------------------------------------------------------ models

/** One CallLog.Calls row. [type] / [presentation] are the platform int constants (lossless). */
@Serializable
data class CallLogRecord(
    val number: String?,
    val date: Long,
    val duration: Long,
    val type: Int,
    val presentation: Int = 1,
    val accountId: String? = null,
    val accountComponent: String? = null,
    val name: String? = null,
    val isNew: Boolean = false,
    val isRead: Boolean = true,
)

@Serializable
data class BlockRuleRecord(
    val pattern: String,
    val type: String,
    val action: String,
    val enabled: Boolean = true,
    val note: String? = null,
)

@Serializable
data class BlockedCallRecord(
    val number: String?,
    val reason: String,
    val action: String,
    val time: Long,
)

@Serializable
data class BlockingSnapshot(
    val rules: List<BlockRuleRecord> = emptyList(),
    /** BlockedNumberContract entries (the system block list). */
    val systemBlockedNumbers: List<String> = emptyList(),
    val blockedCalls: List<BlockedCallRecord> = emptyList(),
)

@Serializable
data class SpeedDialRecord(val slot: Int, val number: String, val label: String? = null)

@Serializable
data class NumberSimRecord(val matchKey: String, val phoneAccountId: String)

@Serializable
data class ManifestEntry(val name: String, val size: Long, val sha256: String)

@Serializable
data class Manifest(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val device: Map<String, String> = emptyMap(),
    val counts: Map<String, Long> = emptyMap(),
    val entries: List<ManifestEntry> = emptyList(),
) {
    fun entry(name: String): ManifestEntry? = entries.firstOrNull { it.name == name }

    /**
     * Hash of the logical content: every entry's name, size and SHA-256 plus the format version.
     * createdAt, appVersion and device info are excluded, so an unchanged phone yields the same hash
     * and the scheduler can skip the run.
     */
    fun contentHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("parley-content-v$formatVersion\n".toByteArray())
        entries.sortedBy { it.name }.forEach { md.update("${it.name}\u0000${it.size}\u0000${it.sha256}\n".toByteArray()) }
        return RecordJson.hex(md.digest())
    }
}

/** Caller-supplied metadata for [Manifest]. */
data class ArchiveMeta(
    val createdAt: Long,
    val appVersion: String,
    val device: Map<String, String> = emptyMap(),
)

/** Zip-bomb and memory limits enforced by [BackupArchiveReader]. Sizes are uncompressed bytes actually read. */
data class ArchiveLimits(
    val maxEntries: Int = 100_000,
    val maxEntryBytes: Long = 512L shl 20,
    val maxTotalBytes: Long = 4L shl 30,
    /** Cap on each entry kept in memory (manifest, small JSON sections, photos, vault blobs). */
    val maxInMemoryEntryBytes: Long = 16L shl 20,
    val maxInMemoryTotalBytes: Long = 256L shl 20,
)

object BackupArchive {
    const val FORMAT_VERSION = 1
    const val MANIFEST = "manifest.json"
    const val CONTACTS = "contacts.jsonl"
    const val VCF = "contacts.vcf"
    const val CALLLOG = "calllog.jsonl"
    const val BLOCKING = "blocking.json"
    const val SPEEDDIAL = "speeddial.json"
    const val NUMBERSIM = "numbersim.json"
    const val SETTINGS = "settings.json"
    const val JOURNAL = "journal.jsonl"
    const val PHOTO_PREFIX = "photos/"
    const val VAULT_PREFIX = "vault/"

    /** Count keys in [Manifest.counts]. */
    object Counts {
        const val CONTACTS = "contacts"
        const val PHOTOS = "photos"
        const val VCF_BYTES = "vcfBytes"
        const val CALLS = "calls"
        const val BLOCK_RULES = "blockRules"
        const val SYSTEM_BLOCKED = "systemBlocked"
        const val BLOCKED_CALLS = "blockedCalls"
        const val SPEED_DIAL = "speedDial"
        const val NUMBER_SIMS = "numberSims"
        const val SETTINGS = "settings"
        const val VAULT = "vault"
        const val JOURNAL = "journal"
    }

    internal val PHOTO_NAME = Regex("photos/[0-9a-f]{64}\\.bin")
    internal val VAULT_NAME = Regex("[A-Za-z0-9._-]{1,128}")
    internal val FIXED = setOf(CONTACTS, VCF, CALLLOG, BLOCKING, SPEEDDIAL, NUMBERSIM, SETTINGS, JOURNAL)
    internal val DOS_EPOCH: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0, 0)

    internal fun isValidVaultName(n: String) = VAULT_NAME.matches(n) && n != "." && n != ".."

    /**
     * Computes [Manifest.contentHash] for the content [build] would write, without compressing or
     * writing anything. Use the same calls as for the real backup.
     */
    fun contentHash(build: BackupArchiveWriter.() -> Unit): String {
        val w = BackupArchiveWriter.hashOnly()
        w.build()
        return w.finish().contentHash()
    }

    internal val settingsSerializer = MapSerializer(String.serializer(), String.serializer())
}

// ------------------------------------------------------------------------------------ writer

/**
 * Streams an archive to [out] (e.g. ZipOutputStream -> EncryptingOutputStream -> SAF stream).
 * Sections are optional but must be written in the canonical order (the order of the methods below),
 * each at most once. Photos seen in contacts are kept (deduplicated) in memory and written by [finish].
 * [close] finishes the archive if needed and closes [out].
 */
class BackupArchiveWriter private constructor(out: OutputStream?, private val meta: ArchiveMeta, @Suppress("UNUSED_PARAMETER") hashOnly: Boolean) : Closeable {
    constructor(out: OutputStream, meta: ArchiveMeta) : this(out, meta, false)

    private val zip: ZipOutputStream? = out?.let { ZipOutputStream(it).apply { setLevel(Deflater.BEST_COMPRESSION) } }
    private val entries = ArrayList<ManifestEntry>()
    private val counts = TreeMap<String, Long>()
    private val photos = TreeMap<String, ByteArray>()
    private var lastSection = -1
    private var manifest: Manifest? = null
    private var closed = false

    private enum class Section { CONTACTS, VCF, CALLLOG, BLOCKING, SPEEDDIAL, NUMBERSIM, SETTINGS, VAULT, JOURNAL }

    private fun enter(s: Section) {
        check(manifest == null) { "Archive already finished" }
        check(s.ordinal > lastSection) { "Section $s written out of order or twice" }
        lastSection = s.ordinal
    }

    /** Hashing/counting sink for one entry; bytes go to the zip (if any). */
    private inner class EntryOut(val name: String) : OutputStream() {
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
        var size = 0L

        init {
            zip?.putNextEntry(ZipEntry(name).apply { setTimeLocal(BackupArchive.DOS_EPOCH) })
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            md.update(b, off, len); size += len; zip?.write(b, off, len)
        }

        fun end() {
            zip?.closeEntry()
            entries += ManifestEntry(name, size, RecordJson.hex(md.digest()))
        }
    }

    private inline fun entry(name: String, body: (OutputStream) -> Unit) {
        val e = EntryOut(name)
        body(e)
        e.end()
    }

    private fun jsonBytes(s: String) = s.toByteArray(Charsets.UTF_8)

    fun writeContacts(records: Sequence<ContactRecord>) {
        enter(Section.CONTACTS)
        var n = 0L
        val keys = HashSet<String>()
        entry(BackupArchive.CONTACTS) { o ->
            for (r in records) {
                require(keys.add(r.key)) { "Duplicate contact key ${r.key}" }
                o.write(jsonBytes(RecordJson.encode(r) { h, b -> photos.putIfAbsent(h, b) }))
                o.write('\n'.code)
                n++
            }
        }
        counts[BackupArchive.Counts.CONTACTS] = n
    }

    fun writeContacts(records: Iterable<ContactRecord>) = writeContacts(records.asSequence())

    fun writeVcf(input: InputStream) {
        enter(Section.VCF)
        var n = 0L
        entry(BackupArchive.VCF) { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf); if (r < 0) break
                o.write(buf, 0, r); n += r
            }
        }
        counts[BackupArchive.Counts.VCF_BYTES] = n
    }

    fun writeVcf(bytes: ByteArray) = writeVcf(bytes.inputStream())

    fun writeCallLog(records: Sequence<CallLogRecord>) {
        enter(Section.CALLLOG)
        var n = 0L
        entry(BackupArchive.CALLLOG) { o ->
            for (r in records) {
                o.write(jsonBytes(RecordJson.json.encodeToString(CallLogRecord.serializer(), r))); o.write('\n'.code); n++
            }
        }
        counts[BackupArchive.Counts.CALLS] = n
    }

    fun writeCallLog(records: Iterable<CallLogRecord>) = writeCallLog(records.asSequence())

    fun writeBlocking(snapshot: BlockingSnapshot) {
        enter(Section.BLOCKING)
        entry(BackupArchive.BLOCKING) { it.write(jsonBytes(RecordJson.json.encodeToString(BlockingSnapshot.serializer(), snapshot))) }
        counts[BackupArchive.Counts.BLOCK_RULES] = snapshot.rules.size.toLong()
        counts[BackupArchive.Counts.SYSTEM_BLOCKED] = snapshot.systemBlockedNumbers.size.toLong()
        counts[BackupArchive.Counts.BLOCKED_CALLS] = snapshot.blockedCalls.size.toLong()
    }

    fun writeSpeedDial(entries: List<SpeedDialRecord>) {
        enter(Section.SPEEDDIAL)
        val sorted = entries.sortedBy { it.slot }
        entry(BackupArchive.SPEEDDIAL) {
            it.write(jsonBytes(RecordJson.json.encodeToString(ListSerializer(SpeedDialRecord.serializer()), sorted)))
        }
        counts[BackupArchive.Counts.SPEED_DIAL] = sorted.size.toLong()
    }

    fun writeNumberSims(entries: List<NumberSimRecord>) {
        enter(Section.NUMBERSIM)
        val sorted = entries.sortedBy { it.matchKey }
        entry(BackupArchive.NUMBERSIM) {
            it.write(jsonBytes(RecordJson.json.encodeToString(ListSerializer(NumberSimRecord.serializer()), sorted)))
        }
        counts[BackupArchive.Counts.NUMBER_SIMS] = sorted.size.toLong()
    }

    fun writeSettings(settings: Map<String, String>) {
        enter(Section.SETTINGS)
        entry(BackupArchive.SETTINGS) { it.write(jsonBytes(RecordJson.json.encodeToString(BackupArchive.settingsSerializer, settings.toSortedMap()))) }
        counts[BackupArchive.Counts.SETTINGS] = settings.size.toLong()
    }

    /** Opaque, already-encrypted vault blobs; names must match `[A-Za-z0-9._-]{1,128}`. */
    fun writeVault(blobs: Map<String, ByteArray>) {
        enter(Section.VAULT)
        blobs.keys.forEach { require(BackupArchive.isValidVaultName(it)) { "Invalid vault entry name '$it'" } }
        blobs.toSortedMap().forEach { (name, bytes) -> entry(BackupArchive.VAULT_PREFIX + name) { it.write(bytes) } }
        counts[BackupArchive.Counts.VAULT] = blobs.size.toLong()
    }

    /** Opaque change-journal lines (must not contain line breaks). */
    fun writeJournal(lines: Sequence<String>) {
        enter(Section.JOURNAL)
        var n = 0L
        entry(BackupArchive.JOURNAL) { o ->
            for (l in lines) {
                require('\n' !in l && '\r' !in l) { "Journal lines must not contain line breaks" }
                o.write(jsonBytes(l)); o.write('\n'.code); n++
            }
        }
        counts[BackupArchive.Counts.JOURNAL] = n
    }

    fun writeJournal(lines: Iterable<String>) = writeJournal(lines.asSequence())

    /** Writes photos and the manifest and finishes the ZIP (without closing the target). Idempotent. */
    fun finish(): Manifest {
        manifest?.let { return it }
        if (counts.containsKey(BackupArchive.Counts.CONTACTS)) counts[BackupArchive.Counts.PHOTOS] = photos.size.toLong()
        photos.forEach { (h, b) -> entry("${BackupArchive.PHOTO_PREFIX}$h.bin") { it.write(b) } }
        photos.clear()
        val m = Manifest(
            formatVersion = BackupArchive.FORMAT_VERSION,
            createdAt = meta.createdAt,
            appVersion = meta.appVersion,
            device = meta.device.toSortedMap(),
            counts = TreeMap(counts),
            entries = entries.toList(),
        )
        zip?.let { z ->
            z.putNextEntry(ZipEntry(BackupArchive.MANIFEST).apply { setTimeLocal(BackupArchive.DOS_EPOCH) })
            z.write(jsonBytes(RecordJson.json.encodeToString(Manifest.serializer(), m)))
            z.closeEntry()
            z.finish()
        }
        manifest = m
        return m
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            finish()
        } finally {
            zip?.close()
        }
    }

    companion object {
        /** A writer that only hashes (see [BackupArchive.contentHash]). */
        fun hashOnly(meta: ArchiveMeta = ArchiveMeta(0, "")): BackupArchiveWriter = BackupArchiveWriter(null, meta, true)
    }
}

// ------------------------------------------------------------------------------------ reader

/**
 * Validating reader. [open] makes one full pass that checks every entry's SHA-256 and size against the
 * manifest, rejects unknown/unlisted/missing/duplicate entries and enforces [ArchiveLimits]. Small
 * sections (JSON settings, block list, vault, photos) are kept from that pass; large sections
 * (contacts, call log, journal, vCard) are streamed lazily by re-opening [source] and are re-verified
 * against the manifest when the entry is fully read.
 *
 * [source] must return a fresh stream over the same plaintext ZIP each time (e.g. re-open the file and
 * wrap it in [BackupCrypto.decrypt] with an already-unwrapped key).
 */
class BackupArchiveReader private constructor(
    private val source: () -> InputStream,
    val manifest: Manifest,
    private val kept: Map<String, ByteArray>,
    private val limits: ArchiveLimits,
) {
    fun has(name: String): Boolean = manifest.entry(name) != null

    val contactCount: Long get() = manifest.counts[BackupArchive.Counts.CONTACTS] ?: 0

    /** Streams contacts with photos resolved. The sequence is only valid inside [block]. */
    fun <R> contacts(block: (Sequence<ContactRecord>) -> R): R =
        lines(BackupArchive.CONTACTS) { seq -> block(seq.map { RecordJson.decode(it, ::photo) }) }

    fun <R> callLog(block: (Sequence<CallLogRecord>) -> R): R = lines(BackupArchive.CALLLOG) { seq ->
        block(seq.map { l -> parse { RecordJson.json.decodeFromString(CallLogRecord.serializer(), l) } })
    }

    fun <R> journal(block: (Sequence<String>) -> R): R = lines(BackupArchive.JOURNAL, block)

    /** Streams the interop vCard, or returns null if the archive has none. */
    fun <R> vcf(block: (InputStream) -> R): R? = if (!has(BackupArchive.VCF)) null else stream(BackupArchive.VCF, block)

    fun blocking(): BlockingSnapshot? = kept[BackupArchive.BLOCKING]?.let { b ->
        parse { RecordJson.json.decodeFromString(BlockingSnapshot.serializer(), b.decodeToString()) }
    }

    fun speedDial(): List<SpeedDialRecord>? = kept[BackupArchive.SPEEDDIAL]?.let { b ->
        parse { RecordJson.json.decodeFromString(ListSerializer(SpeedDialRecord.serializer()), b.decodeToString()) }
    }

    fun numberSims(): List<NumberSimRecord>? = kept[BackupArchive.NUMBERSIM]?.let { b ->
        parse { RecordJson.json.decodeFromString(ListSerializer(NumberSimRecord.serializer()), b.decodeToString()) }
    }

    fun settings(): Map<String, String>? = kept[BackupArchive.SETTINGS]?.let { b ->
        parse { RecordJson.json.decodeFromString(BackupArchive.settingsSerializer, b.decodeToString()) }
    }

    fun vault(): Map<String, ByteArray> = kept.filterKeys { it.startsWith(BackupArchive.VAULT_PREFIX) }
        .mapKeys { it.key.removePrefix(BackupArchive.VAULT_PREFIX) }.mapValues { it.value.copyOf() }.toSortedMap()

    fun photo(sha256: String): ByteArray? = kept[BackupArchive.PHOTO_PREFIX + sha256 + ".bin"]?.copyOf()

    private inline fun <T> parse(f: () -> T): T = try {
        f()
    } catch (e: SerializationException) {
        throw BackupIntegrityException("Malformed backup section", e)
    } catch (e: IllegalArgumentException) {
        throw BackupIntegrityException("Malformed backup section", e)
    }

    private fun <R> lines(name: String, block: (Sequence<String>) -> R): R {
        if (!has(name)) return block(emptySequence())
        return stream(name) { input -> block(input.bufferedReader(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }) }
    }

    private fun <R> stream(name: String, block: (InputStream) -> R): R {
        val expected = manifest.entry(name) ?: throw BackupIntegrityException("No entry $name")
        ZipInputStream(source()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: throw BackupIntegrityException("Entry $name disappeared")
                if (e.name != name) continue
                val hin = VerifyingInput(zin, limits.maxEntryBytes)
                val r = block(hin)
                hin.drain()
                if (hin.size != expected.size || hin.sha256() != expected.sha256) {
                    throw BackupIntegrityException("Entry $name changed since it was verified")
                }
                return r
            }
        }
    }

    /** Hashes and counts everything read through it; never closes the zip stream. */
    private class VerifyingInput(private val inner: InputStream, private val max: Long) : InputStream() {
        private val md = MessageDigest.getInstance("SHA-256")
        var size = 0L

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) {
                md.update(b, off, n); size += n
                if (size > max) throw BackupIntegrityException("Entry exceeds size limit")
            }
            return n
        }

        fun drain() {
            val buf = ByteArray(64 * 1024)
            while (read(buf, 0, buf.size) >= 0) { /* consume */ }
        }

        fun sha256(): String = RecordJson.hex(md.digest())
        override fun close() {}
    }

    companion object {
        /** Verifies the whole archive (one pass over [source]) and returns a reader over it. */
        fun open(source: () -> InputStream, limits: ArchiveLimits = ArchiveLimits()): BackupArchiveReader {
            val seen = LinkedHashMap<String, ManifestEntry>()
            val kept = HashMap<String, ByteArray>()
            val lineCounts = HashMap<String, Long>()
            var manifestBytes: ByteArray? = null
            var total = 0L
            var inMemory = 0L
            var entryCount = 0
            try {
                ZipInputStream(source()).use { zin ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val name = e.name
                        if (++entryCount > limits.maxEntries + 1) throw BackupIntegrityException("Too many entries")
                        if (name in seen || (name == BackupArchive.MANIFEST && manifestBytes != null)) {
                            throw BackupIntegrityException("Duplicate entry $name")
                        }
                        val isManifest = name == BackupArchive.MANIFEST
                        val keep = isManifest || name in setOf(BackupArchive.BLOCKING, BackupArchive.SPEEDDIAL, BackupArchive.NUMBERSIM, BackupArchive.SETTINGS) ||
                            BackupArchive.PHOTO_NAME.matches(name) ||
                            (name.startsWith(BackupArchive.VAULT_PREFIX) && BackupArchive.isValidVaultName(name.removePrefix(BackupArchive.VAULT_PREFIX)))
                        if (!keep && name !in BackupArchive.FIXED) throw BackupIntegrityException("Unexpected entry $name")
                        val countLines = name == BackupArchive.CONTACTS || name == BackupArchive.CALLLOG || name == BackupArchive.JOURNAL
                        val md = MessageDigest.getInstance("SHA-256")
                        val bo = if (keep) ByteArrayOutputStream() else null
                        var size = 0L
                        var newlines = 0L
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            size += n; total += n
                            if (size > limits.maxEntryBytes) throw BackupIntegrityException("Entry $name exceeds size limit")
                            if (total > limits.maxTotalBytes) throw BackupIntegrityException("Backup exceeds total size limit")
                            md.update(buf, 0, n)
                            if (bo != null) {
                                inMemory += n
                                if (size > limits.maxInMemoryEntryBytes || inMemory > limits.maxInMemoryTotalBytes) {
                                    throw BackupIntegrityException("Entry $name exceeds in-memory limit")
                                }
                                bo.write(buf, 0, n)
                            }
                            if (countLines) for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) newlines++
                        }
                        if (isManifest) {
                            manifestBytes = bo!!.toByteArray()
                            continue
                        }
                        val sha = RecordJson.hex(md.digest())
                        if (BackupArchive.PHOTO_NAME.matches(name) && name != "${BackupArchive.PHOTO_PREFIX}$sha.bin") {
                            throw BackupIntegrityException("Photo $name does not match its content")
                        }
                        seen[name] = ManifestEntry(name, size, sha)
                        if (bo != null) kept[name] = bo.toByteArray()
                        if (countLines) lineCounts[name] = newlines
                    }
                }
            } catch (e: ZipException) {
                throw BackupIntegrityException("Corrupt archive", e)
            }
            val mb = manifestBytes ?: throw BackupIntegrityException("Backup has no manifest")
            val manifest = try {
                RecordJson.json.decodeFromString(Manifest.serializer(), mb.decodeToString())
            } catch (e: SerializationException) {
                throw BackupIntegrityException("Malformed manifest", e)
            } catch (e: IllegalArgumentException) {
                throw BackupIntegrityException("Malformed manifest", e)
            }
            if (manifest.formatVersion > BackupArchive.FORMAT_VERSION) {
                throw BackupIntegrityException("Backup was made by a newer version of Parley (format ${manifest.formatVersion})")
            }
            if (manifest.formatVersion < 1) throw BackupIntegrityException("Bad format version")
            val listed = manifest.entries.associateBy { it.name }
            if (listed.size != manifest.entries.size) throw BackupIntegrityException("Manifest lists an entry twice")
            for (name in seen.keys) if (name !in listed) throw BackupIntegrityException("Entry $name is not listed in the manifest")
            for ((name, m) in listed) {
                val actual = seen[name] ?: throw BackupIntegrityException("Entry $name is missing")
                if (actual.size != m.size || actual.sha256 != m.sha256) throw BackupIntegrityException("Entry $name failed its SHA-256 check")
            }
            fun checkCount(key: String, actual: Long?) {
                val claimed = manifest.counts[key]
                if (actual != null && claimed != null && claimed != actual) throw BackupIntegrityException("Manifest count for $key does not match")
            }
            checkCount(BackupArchive.Counts.CONTACTS, lineCounts[BackupArchive.CONTACTS])
            checkCount(BackupArchive.Counts.CALLS, lineCounts[BackupArchive.CALLLOG])
            checkCount(BackupArchive.Counts.JOURNAL, lineCounts[BackupArchive.JOURNAL])
            checkCount(BackupArchive.Counts.PHOTOS, seen.keys.count { it.startsWith(BackupArchive.PHOTO_PREFIX) }.toLong())
            return BackupArchiveReader(source, manifest, kept, limits)
        }

        /** Convenience for in-memory archives. */
        fun open(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits()): BackupArchiveReader =
            open({ bytes.inputStream() }, limits)
    }
}
