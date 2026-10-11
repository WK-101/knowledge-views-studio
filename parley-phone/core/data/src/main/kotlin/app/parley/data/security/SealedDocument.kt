package app.parley.data.security

import android.content.Context
import android.content.SharedPreferences
import app.parley.common.Hex
import app.parley.common.storage.DurableFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * One small document sealed with the small-records key (so the call path can write it while the phone is locked), in
 * its own [folder] of files: the one place the stores built on it (To call, menu memory, case files) keep "unreadable
 * means refuse" and "never plain on disk" right.
 *
 * - A stored document that can't be opened right now is never taken for an empty one: the document stays unloaded,
 *   changes are refused and it is read again next time.
 * - It is never written plain: when sealing fails, a change waits in memory ([pendingWrite]) until a later write or
 *   [resealPlain] can seal it ([RecordSealing] is told).
 * - Each part ([Layout]) is its own file, written with [DurableFiles] on the IO dispatcher (synced, never through
 *   Android's preferences queue, which the main thread waits for as a call screen closes), and only when it changed:
 *   a case file's call rewrites that case, not every case. The index part is written last and lists the others, so a
 *   write cut short leaves the previous document or the new one, never a mix that loses a part.
 *
 * Earlier versions kept the whole document as one preferences value ([Legacy]). It is copied into the folder on first
 * use, and the preferences value is removed only after the folder's index is on the disk; until then the folder isn't
 * read, so a copy cut short by a crash is simply made again.
 */
class SealedDocument<S>(
    context: Context,
    private val folder: File,
    private val layout: Layout<S>,
    private val legacy: Legacy<S>? = null,
    /** Tidies a document as it is read (a change made here is written back). */
    private val clean: (S) -> S = { it },
    /** Seals text; null when sealing isn't possible right now (tests replace it). */
    sealOverride: ((String) -> String?)? = null,
) {
    /** How a document is cut into files: an index (always there, written last) and the parts it lists. */
    interface Layout<S> {
        fun empty(): S

        /** The parts of [s] by name, [INDEX] among them. */
        fun split(s: S): Map<String, String>

        /** The other parts [index] lists. */
        fun parts(index: String): List<String>

        /** The document from its index and the parts found ([parts] may lack one a write cut short never wrote). */
        fun join(index: String, parts: Map<String, String>): S
    }

    /** A document in one file. */
    class Whole<S>(private val encode: (S) -> String, private val decode: (String?) -> S) : Layout<S> {
        override fun empty(): S = decode(null)
        override fun split(s: S) = mapOf(INDEX to encode(s))
        override fun parts(index: String) = emptyList<String>()
        override fun join(index: String, parts: Map<String, String>) = decode(index)
    }

    /** The preferences value earlier versions kept the whole document in, sealed (or plain, when sealing had failed). */
    class Legacy<S>(val prefs: () -> SharedPreferences, val key: String, val decode: (String?) -> S)

    private val appContext = context.applicationContext
    private val crypto by lazy { RecordCrypto.get(appContext) }
    private val seal: (String) -> String? = sealOverride ?: { text -> crypto.sealText(text)?.takeIf { crypto.isSealed(it) } }
    private val mutex = Mutex()

    @Volatile private var loaded = false

    /** The document in memory is newer than the files (sealing or the disk failed): it waits to be written. */
    @Volatile private var unsaved = false

    /** The legacy preferences value goes once the folder holds the document. */
    private var legacyLeft = false

    /** What each part's file holds now (plain), so a write skips the unchanged ones. */
    private var written: Map<String, String> = emptyMap()

    private val _state = MutableStateFlow(layout.empty())

    /** The document as stored; empty until [load] ran. */
    val state: StateFlow<S> = _state.asStateFlow()

    /** Whether the stored document could be read. */
    val available: Boolean get() = loaded

    /** Whether a change waits to be written because sealing failed (the next write, or [resealPlain], retries). */
    val pendingWrite: Boolean get() = unsaved

    /** What a [write] did. */
    data class Write<S>(
        val state: S,
        /** The document changed (in memory; [saved] says whether it reached the disk). */
        val changed: Boolean,
        /** The document is stored as [state] (false: unreadable now, or waiting to be sealed). */
        val saved: Boolean,
    )

    /** Reads the stored document once (and writes one waiting to be); a document that can't be opened stays unloaded. */
    suspend fun load(): S = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            flushLocked()
        }
        _state.value
    }

    /**
     * Applies [f] and writes the result. Nothing is applied while the stored document can't be read (so nothing stored
     * is ever overwritten).
     */
    suspend fun write(f: (S) -> S): Write<S> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loadLocked()) return@withLock Write(_state.value, changed = false, saved = false)
            val now = _state.value
            val next = f(now)
            if (next == now) return@withLock Write(now, changed = false, saved = flushLocked())
            _state.value = next
            unsaved = true
            Write(next, changed = true, saved = flushLocked())
        }
    }

    /** [RecordSealing]'s run: a document waiting to be sealed is written. Unreadable is not plain: nothing to do then. */
    suspend fun resealPlain(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { if (!loadLocked()) true else flushLocked() }
    }

    private fun fileOf(part: String) = File(folder, part + SUFFIX)

    /** Parts read back plain (never written so by this class; a copy from elsewhere): written again, sealed. */
    private val plain = HashSet<String>()

    /** The plain text of part [name]; throws [RecordCrypto.UnreadableException] when it can't be opened now. */
    private fun open(name: String): String? {
        val file = fileOf(name)
        if (!file.isFile) return null
        val stored = file.readText()
        val text = crypto.openTextOrThrow(stored)
        if (!crypto.isSealed(stored)) {
            plain += name
            unsaved = true
        }
        return text
    }

    private fun loadLocked(): Boolean {
        if (loaded) return true
        val index = fileOf(INDEX)
        try {
            if (index.isFile) {
                val head = open(INDEX).orEmpty()
                val parts = layout.parts(head).mapNotNull { name -> open(name)?.let { name to it } }.toMap()
                written = (parts + (INDEX to head)).filterKeys { it !in plain }
                set(layout.join(head, parts))
                // The copy from preferences was made; only its removal was cut short.
                legacy?.let { l -> l.prefs().takeIf { it.contains(l.key) }?.edit()?.remove(l.key)?.commit() }
            } else {
                val l = legacy
                val stored = l?.prefs()?.getString(l.key, null)
                set(if (l == null) layout.empty() else l.decode(crypto.openTextOrThrow(stored)))
                if (stored != null) {
                    legacyLeft = true
                    unsaved = true
                }
            }
        } catch (_: RecordCrypto.UnreadableException) {
            // A Keystore hiccup or an unavailable key: what is stored must be kept as it is, never overwritten.
            unsaved = false
            return false
        }
        loaded = true
        return true
    }

    private fun set(read: S) {
        val tidy = clean(read)
        _state.value = tidy
        if (tidy != read) unsaved = true
    }

    /** Writes the document in memory when it waits to be; false when it still can't be sealed or written. */
    private fun flushLocked(): Boolean {
        if (!loaded || !unsaved) return true
        val parts = layout.split(_state.value)
        val changed = parts.filter { (name, text) -> written[name] != text || !fileOf(name).isFile }
        val sealed = changed.mapValues { (_, text) ->
            seal(text) ?: run {
                RecordSealing.markPending(appContext)
                return false
            }
        }
        // The parts first, then the index that lists them.
        val order = sealed.keys.sortedBy { it == INDEX }
        val done = HashMap(written)
        for (name in order) {
            if (!DurableFiles.writeText(fileOf(name), sealed.getValue(name))) {
                written = done
                return false
            }
            done[name] = changed.getValue(name)
        }
        // Parts the index no longer lists (a case removed) go after it.
        folder.listFiles()?.filter { it.name.endsWith(SUFFIX) && it.name.removeSuffix(SUFFIX) !in parts }?.forEach { it.delete() }
        written = parts
        plain.clear()
        unsaved = false
        if (legacyLeft) {
            legacy?.let { l -> l.prefs().edit().remove(l.key).commit() }
            legacyLeft = false
        }
        return true
    }

    companion object {
        const val INDEX = "index"
        private const val SUFFIX = ".sealed"

        /** A file name for [id]: itself when it is plain enough, else a digest of it. */
        fun partName(prefix: String, id: String): String =
            prefix + if (PLAIN_ID.matches(id)) {
                id
            } else {
                Hex.encode(MessageDigest.getInstance("SHA-256").digest(id.toByteArray())).take(MAX_ID)
            }

        private const val MAX_ID = 64
        private val PLAIN_ID = Regex("[A-Za-z0-9_-]{1,$MAX_ID}")
    }
}
