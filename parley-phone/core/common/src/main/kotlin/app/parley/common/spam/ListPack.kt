package app.parley.common.spam

import java.util.Locale
import app.parley.common.CountryCodes
import app.parley.common.PhoneNumbers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * `.parleylist` spam-list pack (B4): a zip holding
 * - `manifest.json` ([PackManifest]), which carries the SHA-256 of the other files;
 * - `numbers.bin`: sorted records of 10 bytes: big-endian uint64 E.164 digits, category byte, score byte;
 * - `ranges.txt`: one prefix per line, `+33162 1 80` (prefix, category, score), `#` comments;
 * - `manifest.sig` (optional): Ed25519 signature of the manifest bytes by [PackManifest.publicKey].
 *
 * Packs without a signature are accepted when the hashes match, and shown as "unsigned".
 */
@Serializable
data class PackManifest(
    val format: Int = 1,
    val id: String,
    val name: String,
    val publisher: String = "",
    val source: String = "",
    val licence: String = "",
    /** Monotonic version, e.g. 20260901. Newer versions replace older ones. */
    val version: Long = 1,
    /** Creation time, epoch ms. */
    val created: Long = 0,
    /** After this many days without an update the pack is flagged as stale. */
    val ttlDays: Int = 30,
    val regions: List<String> = emptyList(),
    /** Category id (as string) → name. */
    val categories: Map<String, String> = emptyMap(),
    val entries: Int = 0,
    val ranges: Int = 0,
    /** Base64 Ed25519 public key of the publisher (optional). */
    val publicKey: String? = null,
    /** File name → lowercase hex SHA-256. */
    val sha256: Map<String, String> = emptyMap(),
)

data class PackRange(val prefix: String, val category: Int, val score: Int)

enum class SignatureStatus { SIGNED, UNSIGNED }

/** A pack that passed every integrity check. */
class ParsedPack(
    val manifest: PackManifest,
    val manifestBytes: ByteArray,
    val numbers: ByteArray,
    val ranges: List<PackRange>,
    val rangesText: String,
    val signature: SignatureStatus,
    /** Publisher key fingerprint when signed. */
    val fingerprint: String?,
)

class PackException(message: String) : Exception(message)

object ListPack {
    const val EXTENSION = "parleylist"
    const val RECORD = 10
    const val MANIFEST = "manifest.json"
    const val NUMBERS = "numbers.bin"
    const val RANGES = "ranges.txt"
    const val SIGNATURE = "manifest.sig"
    private const val MAX_ENTRY = 128L * 1024 * 1024

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private val ID = Regex("[A-Za-z0-9._-]{1,80}")

    /** Pack ids: letters, digits, '.', '_' and '-', no path separators, and never only dots ("." or ".."). */
    fun isValidId(id: String): Boolean = ID.matches(id) && id.any { it != '.' }

    /** Directory name a pack is stored under: derived from, never equal to, the id (so no id can escape the pack folder). */
    fun storageName(id: String): String = "pack_" + sha256Hex(id.toByteArray(Charsets.UTF_8))

    /** A stored manifest, or null when it can't be read. */
    fun readManifest(bytes: ByteArray): PackManifest? = runCatching { json.decodeFromString(PackManifest.serializer(), bytes.decodeToString()) }.getOrNull()

    /** The publisher key a signed pack names, decoded (full 32 bytes), or null. */
    fun publicKeyBytes(p: PackManifest): ByteArray? = p.publicKey?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }

    /** Whether [candidate] was signed by the same publisher key as the installed pack ([installedKey], Base64). Full key compare. */
    fun sameKey(installedKey: String?, candidate: PackManifest): Boolean {
        val a = installedKey?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        val b = publicKeyBytes(candidate) ?: return false
        return a.size == 32 && MessageDigest.isEqual(a, b)
    }

    /** Reads and fully verifies a pack. Throws [PackException] with a user-readable reason. */
    fun parse(zip: ByteArray): ParsedPack {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (name !in setOf(MANIFEST, NUMBERS, RANGES, SIGNATURE)) continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY) throw PackException("The list is too large")
                    out.write(buf, 0, n)
                }
                files[name] = out.toByteArray()
            }
        }
        val manifestBytes = files[MANIFEST] ?: throw PackException("Not a Parley list: manifest.json is missing")
        val manifest = try {
            json.decodeFromString(PackManifest.serializer(), manifestBytes.decodeToString())
        } catch (e: Exception) {
            throw PackException("The list's manifest can't be read")
        }
        if (manifest.format != 1) throw PackException("This list needs a newer version of Parley")
        if (!isValidId(manifest.id)) throw PackException("The list has an invalid id")
        val numbers = files[NUMBERS] ?: ByteArray(0)
        val rangesBytes = files[RANGES] ?: ByteArray(0)
        for ((name, bytes) in listOf(NUMBERS to numbers, RANGES to rangesBytes)) {
            val expected = manifest.sha256[name]
            if (expected == null) {
                if (bytes.isNotEmpty()) throw PackException("The list's manifest doesn't cover $name")
            } else if (!expected.equals(sha256Hex(bytes), ignoreCase = true)) {
                throw PackException("The list is damaged ($name doesn't match its checksum)")
            }
        }
        if (numbers.size % RECORD != 0) throw PackException("The list is damaged (numbers.bin has a partial record)")
        if (!isSorted(numbers)) throw PackException("The list is damaged (numbers are not sorted)")
        val sig = files[SIGNATURE]
        var status = SignatureStatus.UNSIGNED
        var fingerprint: String? = null
        if (sig != null) {
            val key = manifest.publicKey?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                ?: throw PackException("The list is signed but names no key")
            if (!Ed25519.verify(key, manifestBytes, sig)) throw PackException("The list's signature is not valid")
            status = SignatureStatus.SIGNED
            fingerprint = Ed25519.fingerprint(key)
        }
        val text = rangesBytes.decodeToString()
        return ParsedPack(manifest, manifestBytes, numbers, parseRanges(text), text, status, fingerprint)
    }

    fun parseRanges(text: String): List<PackRange> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val p = line.split(Regex("[\\s,;]+"))
            val prefix = p[0].filter { it.isDigit() || it == '+' }
            if (!prefix.startsWith("+") || prefix.length < 3) return@mapNotNull null
            PackRange(prefix, p.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 255) ?: 0, p.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 100) ?: 70)
        }
        .toList()

    private fun isSorted(b: ByteArray): Boolean {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        var prev = -1L
        var i = 0
        while (i < b.size) {
            val v = buf.getLong(i)
            if (v <= prev) return false
            prev = v
            i += RECORD
        }
        return true
    }

    /** E.164 "+33612345678" → 33612345678, or null when it isn't a plausible number. */
    fun key(e164: String): Long? {
        if (!e164.startsWith("+")) return null
        val d = e164.substring(1)
        if (d.length !in 6..15 || !d.all { it.isDigit() }) return null
        return d.toLongOrNull()
    }
}

/** Builds packs offline (tools, tests, and "share my rules"). */
class PackBuilder(private val base: PackManifest) {
    private val numbers = java.util.TreeMap<Long, Pair<Int, Int>>()
    private val ranges = LinkedHashMap<String, PackRange>()

    /** Adds a number (any format; national numbers use [countryIso]). Returns false when it isn't a usable number. */
    fun addNumber(number: String, category: Int, score: Int, countryIso: String? = null): Boolean {
        val e = PhoneNumbers.toE164(number, countryIso) ?: return false
        val k = ListPack.key(e) ?: return false
        val old = numbers[k]
        // Keep the strongest evidence when a number appears twice.
        if (old == null || score > old.second) numbers[k] = category.coerceIn(0, 255) to score.coerceIn(0, 100)
        return true
    }

    fun addRange(prefix: String, category: Int, score: Int): Boolean {
        val p = prefix.filter { it.isDigit() || it == '+' }
        if (!p.startsWith("+") || p.length < 3 || CountryCodes.callingCodeOf(p) == null) return false
        ranges[p] = PackRange(p, category.coerceIn(0, 255), score.coerceIn(0, 100))
        return true
    }

    val size: Int get() = numbers.size + ranges.size

    fun numbersBytes(): ByteArray {
        val buf = ByteBuffer.allocate(numbers.size * ListPack.RECORD).order(ByteOrder.BIG_ENDIAN)
        numbers.forEach { (k, v) ->
            buf.putLong(k)
            buf.put(v.first.toByte())
            buf.put(v.second.toByte())
        }
        return buf.array()
    }

    fun rangesText(): String = ranges.values.joinToString("\n") { "${it.prefix} ${it.category} ${it.score}" } + if (ranges.isEmpty()) "" else "\n"

    /** The zip bytes; signed when [secretKey] is given. */
    fun build(secretKey: ByteArray? = null, now: Long = System.currentTimeMillis()): ByteArray {
        val nums = numbersBytes()
        val rangesBytes = rangesText().encodeToByteArray()
        val manifest = base.copy(
            created = if (base.created == 0L) now else base.created,
            entries = numbers.size,
            ranges = ranges.size,
            publicKey = secretKey?.let { java.util.Base64.getEncoder().encodeToString(Ed25519.publicKey(it)) },
            sha256 = mapOf(ListPack.NUMBERS to ListPack.sha256Hex(nums), ListPack.RANGES to ListPack.sha256Hex(rangesBytes)),
        )
        val manifestBytes = ListPack.json.encodeToString(PackManifest.serializer(), manifest).encodeToByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name).apply { time = 0 })
                z.write(bytes)
                z.closeEntry()
            }
            put(ListPack.MANIFEST, manifestBytes)
            put(ListPack.NUMBERS, nums)
            put(ListPack.RANGES, rangesBytes)
            if (secretKey != null) put(ListPack.SIGNATURE, Ed25519.sign(secretKey, manifestBytes))
        }
        return out.toByteArray()
    }
}

/** A single lookup result inside one pack. */
data class PackMatch(val category: Int, val score: Int, val range: Boolean)

/**
 * Lookup over a pack's `numbers.bin` (memory-mapped on the phone, a heap buffer in tests) and its ranges.
 * Binary search: ~20 probes for a million numbers, well under a millisecond.
 */
class PackIndex(private val numbers: ByteBuffer, ranges: List<PackRange>) {
    private val count = numbers.capacity() / ListPack.RECORD
    private val rangesByLength = ranges.sortedByDescending { it.prefix.length }

    val size: Int get() = count

    fun find(key: Long): PackMatch? {
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val at = mid * ListPack.RECORD
            val v = numbers.getLong(at)
            when {
                v < key -> lo = mid + 1
                v > key -> hi = mid - 1
                else -> return PackMatch(numbers.get(at + 8).toInt() and 0xFF, numbers.get(at + 9).toInt() and 0xFF, false)
            }
        }
        return null
    }

    fun findRange(e164: String): PackMatch? =
        rangesByLength.firstOrNull { e164.startsWith(it.prefix) }?.let { PackMatch(it.category, it.score, true) }

    /**
     * Looks a caller up in every form it may be listed under: its E.164 form for the SIM country, the raw
     * digits when the network sent an international number without '+', and each part of a forwarded "A&B".
     */
    fun lookup(number: String, countryIso: String?, useRanges: Boolean): PackMatch? {
        for (e in forms(number, countryIso)) {
            ListPack.key(e)?.let { k -> find(k)?.let { return it } }
        }
        if (useRanges) for (e in forms(number, countryIso)) findRange(e)?.let { return it }
        return null
    }

    companion object {
        fun forms(number: String, countryIso: String?): List<String> {
            val out = LinkedHashSet<String>()
            for (part in PhoneNumbers.forwardedParts(number)) {
                val e = PhoneNumbers.toE164(part, countryIso)
                if (e != null) out += e
                val clean = PhoneNumbers.clean(part)
                if (clean.startsWith("+")) {
                    out += PhoneNumbers.canonicalE164(clean)
                } else if (e == null && clean.length in 8..15 && !clean.startsWith("0")) {
                    // Couldn't interpret it for the SIM country: try it as international digits without '+'.
                    out += PhoneNumbers.canonicalE164("+$clean")
                }
            }
            return out.toList()
        }

        fun of(parsed: ParsedPack) = PackIndex(ByteBuffer.wrap(parsed.numbers).order(ByteOrder.BIG_ENDIAN), parsed.ranges)
    }
}
