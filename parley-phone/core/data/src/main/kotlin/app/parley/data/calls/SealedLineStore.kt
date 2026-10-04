package app.parley.data.calls

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import app.parley.data.history.CallHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Facts per call, per line, kept in app-private preferences and never readable at rest: rows are keyed by the
 * call-history archive's keyed fingerprint of the line (never the number), each row's facts are sealed with the archive
 * key, and the newest [maxRows] of the last [keepDays] days are kept. Nothing leaves the phone.
 *
 * Stored as one line per row, "<key>\t<sealed facts JSON, Base64>", so each row opens on its own: a damaged line is
 * dropped, and one the key can't open right now is kept as it is and never written over.
 */
class SealedLineStore<T>(
    context: Context,
    file: String,
    private val rowsKey: String,
    private val keySource: KeySource,
    private val encode: (T) -> String,
    private val decode: (String) -> T?,
    private val startedAt: (T) -> Long,
    private val maxRows: Int = 400,
    private val keepDays: Long = 60,
) {
    /** Where the keys come from, asked on each use (the archive opens lazily, and may not be ready). */
    fun interface KeySource {
        fun keys(): Keys
    }

    /** The archive's keyed fingerprint and its key (tests use their own). */
    interface Keys {
        fun lineMac(number: String): String
        fun seal(plain: ByteArray): ByteArray
        fun open(blob: ByteArray): ByteArray
    }

    /** The call-history archive's keys. */
    class HistoryKeys(private val h: CallHistory) : Keys {
        override fun lineMac(number: String) = h.lineMac(number)
        override fun seal(plain: ByteArray) = h.sealAux(plain)
        override fun open(blob: ByteArray) = h.openAux(blob)
    }

    private fun keys(): Keys = keySource.keys()

    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    private data class Row<T>(val key: String, val facts: T)

    /** The rows that opened, and the stored lines that couldn't be opened right now (kept as they are). */
    private class Loaded<T>(val rows: List<Row<T>>, val unread: List<String>)

    private var cache: Loaded<T>? = null

    /** Bumped on every write, so screens re-read. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** The line key of [number] (null when the key can't be used now); a hidden number has its own. */
    fun keyOf(number: String?): String? = if (number.isNullOrBlank()) HIDDEN else runCatching { MAC + keys().lineMac(number) }.getOrNull()

    /**
     * The stored rows, or null when the key can't be used right now. Only a fully read list is cached: a row that
     * couldn't be opened (a Keystore hiccup) is tried again next time, and every write keeps it as it is.
     */
    @Synchronized
    private fun loaded(): Loaded<T>? {
        cache?.let { return it }
        val l = read(prefs.getString(rowsKey, null)) ?: return null
        if (l.unread.isEmpty()) cache = l
        return l
    }

    private fun rows(): List<Row<T>> = loaded()?.rows.orEmpty()

    @Synchronized
    fun add(number: String?, facts: T, now: Long = System.currentTimeMillis()) {
        // Without the key nothing is stored (never in plain text).
        val k = keyOf(number) ?: return
        val l = loaded() ?: return
        val next = (listOf(Row(k, facts)) + l.rows.filterNot { it.key == k && startedAt(it.facts) == startedAt(facts) })
            .filter { now - startedAt(it.facts) < keepDays * DAY_MS }
            .take(maxRows)
        store(next, l.unread)
    }

    /** Adds rows already keyed (moved from an older format); false when they couldn't be sealed. */
    @Synchronized
    fun merge(keyed: List<Pair<String, T>>): Boolean {
        val l = loaded() ?: return false
        return store((l.rows + keyed.map { Row(it.first, it.second) }).sortedByDescending { startedAt(it.facts) }.take(maxRows), l.unread)
    }

    /** Facts for [number], newest first. */
    fun forNumber(number: String?): List<T> {
        val k = keyOf(number) ?: return emptyList()
        return rows().filter { it.key == k }.map { it.facts }.sortedByDescending(startedAt)
    }

    /** Every row as (line key, facts), newest first. */
    fun all(): List<Pair<String, T>> = rows().map { it.key to it.facts }.sortedByDescending { startedAt(it.second) }

    /**
     * Forgets [number]'s facts: those of the calls at [dates] (call-log dates, matched by [near]), or all of them when
     * [dates] is null, unreadable rows included (their key is readable; only their facts are sealed).
     */
    @Synchronized
    fun forget(number: String, dates: List<Long>?, near: (List<T>, Long) -> T?) {
        val k = keyOf(number) ?: return
        val l = loaded() ?: return
        val unread = if (dates == null) l.unread.filterNot { it.substringBefore('\t') == k } else l.unread
        val mine = l.rows.filter { it.key == k }.map { it.facts }
        val drop = if (dates == null) mine.toSet() else dates.mapNotNull { d -> near(mine, d) }.toSet()
        if (drop.isEmpty() && unread.size == l.unread.size) return
        store(l.rows.filterNot { it.key == k && it.facts in drop }, unread)
    }

    /** Forgets everything, and the [also] keys of older formats. */
    @Synchronized
    fun clear(vararg also: String) {
        cache = Loaded(emptyList(), emptyList())
        prefs.edit().remove(rowsKey).apply { also.forEach { remove(it) } }.apply()
        _version.value++
    }

    private fun store(next: List<Row<T>>, unread: List<String>): Boolean {
        val text = runCatching { write(next) }.getOrNull() ?: return false
        cache = if (unread.isEmpty()) Loaded(next, emptyList()) else null
        prefs.edit().putString(rowsKey, (listOf(text).filter { it.isNotEmpty() } + unread).joinToString("\n")).apply()
        _version.value++
        return true
    }

    private fun write(rows: List<Row<T>>): String {
        val h = keys()
        return rows.joinToString("\n") { r -> r.key + "\t" + Base64.encodeToString(h.seal(encode(r.facts).toByteArray()), Base64.NO_WRAP) }
    }

    private fun read(text: String?): Loaded<T>? {
        if (text.isNullOrEmpty()) return Loaded(emptyList(), emptyList())
        val h = runCatching { keys() }.getOrNull() ?: return null
        val rows = ArrayList<Row<T>>()
        val unread = ArrayList<String>()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            val blob = if (tab <= 0) null else runCatching { Base64.decode(line.substring(tab + 1), Base64.NO_WRAP) }.getOrNull()
            val opened = blob?.let { runCatching { String(h.open(it)) } }
            when {
                opened == null -> Unit
                opened.isFailure -> unread += line
                else -> decode(opened.getOrThrow())?.let { rows += Row(line.substring(0, tab), it) }
            }
        }
        return Loaded(rows, unread)
    }

    companion object {
        const val HIDDEN = "hidden"
        const val MAC = "m:"
        private const val DAY_MS = 86_400_000L
    }
}
