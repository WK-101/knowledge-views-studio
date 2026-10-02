package app.parley.data.calls

import android.content.Context
import android.util.Base64
import app.parley.common.calls.CallQualityCodec
import app.parley.common.calls.CallQualityFacts
import app.parley.data.history.CallHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quality facts per call (SIM, Wi-Fi calling, HD voice, why it ended, how long, the caller's subject), for the number
 * history now and a call quality diary later. Kept like [RingFactsStore]: rows are keyed by the call-history archive's
 * keyed fingerprint of the line (never the number), the facts are sealed with the archive key, the newest [MAX_ROWS] of
 * the last [KEEP_DAYS] days are kept, and deleting or purging calls forgets theirs. Nothing leaves the phone.
 */
class CallQualityStore private constructor(context: Context, private val keySource: KeySource) {
    constructor(context: Context, history: () -> CallHistory) : this(context, KeySource { HistoryKeys(history()) })

    /** With [keys] in place of the archive's (tests). */
    internal constructor(context: Context, keys: Keys) : this(context, KeySource { keys })

    private fun interface KeySource {
        fun keys(): Keys
    }

    private fun keys(): Keys = keySource.keys()

    /** The archive's keyed fingerprint and its key (tests use their own). */
    internal interface Keys {
        fun lineMac(number: String): String
        fun seal(plain: ByteArray): ByteArray
        fun open(blob: ByteArray): ByteArray
    }

    private class HistoryKeys(private val h: CallHistory) : Keys {
        override fun lineMac(number: String) = h.lineMac(number)
        override fun seal(plain: ByteArray) = h.sealAux(plain)
        override fun open(blob: ByteArray) = h.openAux(blob)
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private data class Row(val key: String, val facts: CallQualityFacts)

    /** The rows that opened, and the stored lines that couldn't be opened right now (kept as they are). */
    private class Loaded(val rows: List<Row>, val unread: List<String>)

    private var cache: Loaded? = null

    /** Bumped on every write, so screens re-read. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun key(number: String?): String? =
        if (number.isNullOrBlank()) HIDDEN else runCatching { MAC + keys().lineMac(number) }.getOrNull()

    /**
     * The stored rows, or null when the key can't be used right now. Only a fully read list is cached: a row that
     * couldn't be opened (a Keystore hiccup) is tried again next time, and every write keeps it as it is.
     */
    @Synchronized
    private fun loaded(): Loaded? {
        cache?.let { return it }
        val l = decode(prefs.getString(KEY_ROWS, null)) ?: return null
        if (l.unread.isEmpty()) cache = l
        return l
    }

    private fun rows(): List<Row> = loaded()?.rows.orEmpty()

    @Synchronized
    fun add(number: String?, facts: CallQualityFacts, now: Long = System.currentTimeMillis()) {
        // Without the key nothing is stored (never in plain text).
        val k = key(number) ?: return
        // Rows that can't be read now are never written over.
        val l = loaded() ?: return
        val next = (listOf(Row(k, facts)) + l.rows.filterNot { it.key == k && it.facts.startedAt == facts.startedAt })
            .filter { now - it.facts.startedAt < KEEP_DAYS * 86_400_000L }
            .take(MAX_ROWS)
        store(next, l.unread)
    }

    /** Facts for [number], newest first. */
    fun forNumber(number: String?): List<CallQualityFacts> {
        val k = key(number) ?: return emptyList()
        return rows().filter { it.key == k }.map { it.facts }.sortedByDescending { it.startedAt }
    }

    /**
     * Every row as (line key, facts), newest first, for the quality diary (I8). The key is the keyed fingerprint of the
     * line, never the number: [keyOf] gives a known number's key to match it.
     */
    fun all(): List<Pair<String, CallQualityFacts>> = rows().map { it.key to it.facts }.sortedByDescending { it.second.startedAt }

    /** The line key [all] uses for [number] (null when the key can't be used now); a hidden number has its own. */
    fun keyOf(number: String?): String? = key(number)

    /** Forgets [number]'s facts: those of the calls at [dates] (call-log dates), or all when [dates] is null. */
    @Synchronized
    fun forget(number: String, dates: List<Long>? = null) {
        val k = key(number) ?: return
        val l = loaded() ?: return
        // All of theirs: an unreadable row of theirs goes too (its key is readable; only its facts are sealed).
        val unread = if (dates == null) l.unread.filterNot { it.substringBefore('\t') == k } else l.unread
        val mine = l.rows.filter { it.key == k }
        val drop = if (dates == null) mine.map { it.facts }.toSet()
        else dates.mapNotNull { d -> CallQualityCodec.near(mine.map { it.facts }, d) }.toSet()
        if (drop.isEmpty() && unread.size == l.unread.size) return
        store(l.rows.filterNot { it.key == k && it.facts in drop }, unread)
    }

    @Synchronized
    fun clear() {
        cache = Loaded(emptyList(), emptyList())
        prefs.edit().remove(KEY_ROWS).apply()
        _version.value++
    }

    private fun store(next: List<Row>, unread: List<String>): Boolean {
        val text = runCatching { encode(next) }.getOrNull() ?: return false
        cache = if (unread.isEmpty()) Loaded(next, emptyList()) else null
        prefs.edit().putString(KEY_ROWS, (listOf(text).filter { it.isNotEmpty() } + unread).joinToString("\n")).apply()
        _version.value++
        return true
    }

    // One line per row: "<key>\t<sealed facts JSON, Base64>", so each row opens on its own.
    private fun encode(rows: List<Row>): String {
        val h = keys()
        return rows.joinToString("\n") { r ->
            r.key + "\t" + Base64.encodeToString(h.seal(CallQualityCodec.encode(listOf(r.facts)).toByteArray()), Base64.NO_WRAP)
        }
    }

    private fun decode(text: String?): Loaded? {
        if (text.isNullOrEmpty()) return Loaded(emptyList(), emptyList())
        val h = runCatching { keys() }.getOrNull() ?: return null
        val rows = ArrayList<Row>()
        val unread = ArrayList<String>()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            val blob = if (tab <= 0) null else runCatching { Base64.decode(line.substring(tab + 1), Base64.NO_WRAP) }.getOrNull()
            // A damaged line is dropped; one the key can't open right now is kept as it is.
            val opened = blob?.let { runCatching { String(h.open(it)) } }
            when {
                opened == null -> Unit
                opened.isFailure -> unread += line
                else -> CallQualityCodec.decode(opened.getOrThrow()).firstOrNull()?.let { rows += Row(line.substring(0, tab), it) }
            }
        }
        return Loaded(rows, unread)
    }

    private companion object {
        const val FILE = "parley_call_quality"
        const val KEY_ROWS = "rows_v1"
        const val HIDDEN = "hidden"
        const val MAC = "m:"
        const val MAX_ROWS = 400
        const val KEEP_DAYS = 60L
    }
}
