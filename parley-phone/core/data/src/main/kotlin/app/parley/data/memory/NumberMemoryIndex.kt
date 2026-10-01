package app.parley.data.memory

import android.util.Log
import app.parley.common.VaultNumberKeys
import app.parley.common.memory.MemoryHint
import app.parley.common.memory.NumberMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * The number-memory index (I1): keyed hash of a number → small sealed hints, so "who is this?" is answered from
 * memory while a call rings, and no list of plain numbers is kept anywhere. The numbers are hashed with a Keystore key
 * ([Keys.key], like the vault's number fingerprints) and each hint is sealed on its own ([Keys.seal]): only the hints
 * of the number asked about are ever opened.
 *
 * Each [Source] is rebuilt only when its fingerprint changed (incremental); a source that can't be read now keeps its
 * previous hints. The file is device-local (`no_backup/number_memory`): it is rebuilt from the stores it indexes, so a
 * backup has nothing to carry, and "Delete all Parley data" removes it with the key.
 */
class NumberMemoryIndex(private val dir: File, private val keys: Keys) {
    /** The keyed hash and the sealing; a fake in tests. */
    interface Keys {
        /** A keyed hash of [input], as hex. */
        fun key(input: String): String

        /** [plain] sealed; throws when it can't be sealed now (nothing is ever stored plain). */
        fun seal(plain: ByteArray): ByteArray

        /** Throws when [sealed] can't be opened now. */
        fun open(sealed: ByteArray): ByteArray
    }

    /** One store the index reads ("journal", "snapshots", …). */
    interface Source {
        val id: String

        /** Changes whenever what [read] would return changes; null when it can't be told cheaply (always read). */
        suspend fun fingerprint(): String?

        /** Every number with its hint, or null when the store can't be read now (its previous hints are kept). */
        suspend fun read(): List<NumberMemory.Entry>?
    }

    private class Part(val fingerprint: String, val rows: List<Pair<String, ByteArray>>)

    private val file = File(dir, FILE)
    private val mutex = Mutex()

    /** keyed hash → sealed hints, with the file's stamp it was read at. */
    @Volatile private var cache: Pair<Long, Map<String, List<ByteArray>>>? = null

    /** What a rebuild did, for logs and tests. */
    data class Stats(val read: List<String>, val kept: List<String>, val rows: Int)

    /**
     * Brings the index up to date with [sources] (numbers read with [region]). Sources whose fingerprint didn't
     * change aren't read again; a source that fails keeps what it had. Nothing is written when sealing fails.
     */
    suspend fun rebuild(sources: List<Source>, region: String?): Stats = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Rows hashed with another key (the Keystore was reset) can't be found any more: start over.
            val probe = keys.key(PROBE)
            val old = readParts().takeIf { it.first == probe }?.second.orEmpty()
            val next = LinkedHashMap<String, Part>()
            val read = ArrayList<String>()
            val kept = ArrayList<String>()
            val hashes = HashMap<String, String>()
            for (s in sources) {
                val prev = old[s.id]
                val fresh = freshPart(s, prev, region, hashes)
                (fresh ?: prev)?.let { next[s.id] = it }
                if (fresh != null && fresh !== prev) read += s.id else kept += s.id
            }
            writeParts(probe, next)
            cache = null
            Stats(read, kept, next.values.sumOf { it.rows.size })
        }
    }

    /**
     * [s]'s part: [prev] when its fingerprint didn't change, null when it can't be read now (the caller keeps [prev]),
     * else its hints, sealed one by one under the keyed hashes of their numbers ([hashes] caches them).
     */
    private suspend fun freshPart(s: Source, prev: Part?, region: String?, hashes: HashMap<String, String>): Part? {
        val fp = guard(s.id) { s.fingerprint() }
        if (fp != null && prev != null && prev.fingerprint == fp) return prev
        val entries = guard(s.id) { s.read() } ?: return null
        val rows = ArrayList<Pair<String, ByteArray>>()
        for (e in entries) {
            val sealed = keys.seal(NumberMemory.encode(e.hint).toByteArray(Charsets.UTF_8))
            VaultNumberKeys.stored(e.number, region).forEach { input -> rows += hashes.getOrPut(input) { keys.key(input) } to sealed }
        }
        return Part(fp.orEmpty(), rows)
    }

    /**
     * Everything remembered about [number] (read with [region]): the exact line first, then the last-digits fallback
     * for numbers stored without a country. Empty when there is no index yet or it can't be read now (fail open).
     */
    @Suppress("TooGenericExceptionCaught") // Fail open: whatever goes wrong, the call screen just shows no line.
    suspend fun lookup(number: String, region: String?): List<MemoryHint> = withContext(Dispatchers.IO) {
        try {
            val map = loaded()
            if (map.isEmpty() || number.isBlank()) return@withContext emptyList()
            VaultNumberKeys.lookup(number, region).flatMap { input -> map[keys.key(input)].orEmpty() }
                .mapNotNull { sealed -> runCatching { NumberMemory.decode(String(keys.open(sealed), Charsets.UTF_8)) }.getOrNull() }
                .distinct()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Number memory unavailable: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /** Reads the file into memory once, and again only when it changed (a rebuild, a wipe). */
    private fun loaded(): Map<String, List<ByteArray>> {
        val stamp = if (file.isFile) file.lastModified() xor file.length() else 0L
        cache?.takeIf { it.first == stamp }?.let { return it.second }
        val map = HashMap<String, MutableList<ByteArray>>()
        val (probe, parts) = readParts()
        // Only hash with the key when there is something to find (a phone without memory never makes the key).
        if (parts.isNotEmpty() && probe == keys.key(PROBE)) parts.values.forEach { p -> p.rows.forEach { (k, v) -> map.getOrPut(k) { ArrayList(1) } += v } }
        cache = stamp to map
        return map
    }

    /** The key probe the file was written with, and its parts. */
    private fun readParts(): Pair<String, Map<String, Part>> {
        if (!file.isFile) return "" to emptyMap()
        return try {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC) return "" to emptyMap()
                val probe = inp.readUTF()
                val parts = LinkedHashMap<String, Part>()
                repeat(inp.readInt()) {
                    val id = inp.readUTF()
                    val fp = inp.readUTF()
                    val n = inp.readInt()
                    val rows = ArrayList<Pair<String, ByteArray>>(n)
                    repeat(n) {
                        val k = inp.readUTF()
                        val bytes = ByteArray(inp.readInt().coerceIn(0, MAX_ROW))
                        inp.readFully(bytes)
                        rows += k to bytes
                    }
                    parts[id] = Part(fp, rows)
                }
                probe to parts
            }
        } catch (e: IOException) {
            // A damaged file is rebuilt from the stores on the next run.
            Log.w(TAG, "Number memory unreadable: ${e.javaClass.simpleName}")
            "" to emptyMap()
        }
    }

    private fun writeParts(probe: String, parts: Map<String, Part>) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(probe)
            out.writeInt(parts.size)
            parts.forEach { (id, p) ->
                out.writeUTF(id)
                out.writeUTF(p.fingerprint)
                out.writeInt(p.rows.size)
                p.rows.forEach { (k, v) ->
                    out.writeUTF(k)
                    out.writeInt(v.size)
                    out.write(v)
                }
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** When the index was last written (0: never). */
    fun builtAt(): Long = file.lastModified()

    /** Forgets the index (in memory and on disk). */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            cache = null
        }
    }

    @Suppress("TooGenericExceptionCaught") // One store failing (a key, a damaged row) must not stop the others.
    private suspend fun <T> guard(id: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Number memory source $id unavailable: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "NumberMemory"
        private const val FILE = "index.bin"
        private const val MAGIC = 0x504E4D31 // "PNM1"
        private const val MAX_ROW = 64 * 1024

        /** Hashed with the key and stored in the file, so rows made with an older key are never trusted. */
        private const val PROBE = "parley-number-memory-probe"
    }
}
