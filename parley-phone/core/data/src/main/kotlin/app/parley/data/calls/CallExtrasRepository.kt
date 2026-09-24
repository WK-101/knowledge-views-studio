package app.parley.data.calls

import android.content.Context
import android.util.Base64
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calls.RingFacts
import app.parley.common.calls.RingFactsCodec
import app.parley.data.history.CallHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Calls switches added in v3.1 (proximity sensor, pocket-dial guard, missed-call re-alert). A small JSON
 * document in its own preferences file, read once and kept in memory, so the call path never waits on disk.
 */
class CallExtrasRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(CallExtrasConfig.decode(prefs.getString(KEY, null)))
    val config: StateFlow<CallExtrasConfig> = _config.asStateFlow()

    @Synchronized
    fun update(transform: (CallExtrasConfig) -> CallExtrasConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit().putString(KEY, CallExtrasConfig.encode(next)).apply()
    }

    private companion object {
        const val FILE = "parley_call_extras"
        const val KEY = "config_v1"
    }
}

/**
 * Ring-side facts per incoming call (V9). In app-private storage, like the screening trace; the newest [MAX_ROWS] of
 * the last [KEEP_DAYS] days are kept. Nothing leaves the phone, and nothing is readable at rest: rows are keyed by
 * the call-history archive's keyed fingerprint of the line (never the number) and the facts (Bluetooth device names
 * among them) are sealed with the archive key. Deleting or purging calls forgets their facts
 * ([app.parley.data.history.CallHistory.onForget]); a row that can't be opened is dropped.
 */
class RingFactsStore(private val context: Context, private val history: () -> CallHistory) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private data class Row(val key: String, val facts: RingFacts)

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    private var loaded = false

    /** Bumped on every write, so screens re-read. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** The row key of [number]: the archive's fingerprint of its line, or null when the key can't be used now. */
    private fun key(number: String?): String? =
        if (number.isNullOrBlank()) HIDDEN else runCatching { MAC + history().lineMac(number) }.getOrNull()

    @Synchronized
    private fun rows(): List<Row> {
        if (!loaded) {
            loaded = true
            _rows.value = decode(prefs.getString(KEY_ROWS, null))
            migrate()
        }
        return _rows.value
    }

    /** Rows from before they were keyed and sealed (plaintext line keys): re-keyed when they were E.164, else dropped. */
    private fun migrate() {
        val old = prefs.getString(KEY_ROWS_V1, null) ?: return
        val mac = runCatching { history() }.getOrNull() ?: return
        val moved = old.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val k = line.substring(0, tab)
            val facts = RingFactsCodec.decode(line.substring(tab + 1)).firstOrNull() ?: return@mapNotNull null
            val key = when {
                k == HIDDEN -> HIDDEN
                k.startsWith("+") -> runCatching { MAC + mac.lineMac(k) }.getOrNull()
                else -> null
            } ?: return@mapNotNull null
            Row(key, facts)
        }.toList()
        val next = (_rows.value + moved).sortedByDescending { it.facts.startedAt }.take(MAX_ROWS)
        if (store(next)) prefs.edit().remove(KEY_ROWS_V1).apply()
    }

    @Synchronized
    fun add(number: String?, facts: RingFacts, now: Long = System.currentTimeMillis()) {
        // Without the key nothing is stored (never in plain text).
        val k = key(number) ?: return
        val next = (listOf(Row(k, facts)) + rows().filterNot { it.key == k && it.facts.startedAt == facts.startedAt })
            .filter { now - it.facts.startedAt < KEEP_DAYS * 86_400_000L }
            .take(MAX_ROWS)
        store(next)
    }

    /** Facts for [number], newest first. */
    fun forNumber(number: String?): List<RingFacts> {
        val k = key(number) ?: return emptyList()
        return rows().filter { it.key == k }.map { it.facts }.sortedByDescending { it.startedAt }
    }

    /** The facts of the call from [number] that rang at about [time] (a call-log date), or null. */
    fun near(number: String?, time: Long): RingFacts? = app.parley.common.calls.RingExplainer.matchFor(forNumber(number), time)

    /**
     * Forgets [number]'s facts: those of the calls at [dates] (call-log dates), or all of them when [dates] is null
     * (the number's history was purged).
     */
    @Synchronized
    fun forget(number: String, dates: List<Long>? = null) {
        val k = key(number) ?: return
        val mine = rows().filter { it.key == k }
        if (mine.isEmpty()) return
        val drop = if (dates == null) mine.map { it.facts }.toSet()
        else dates.mapNotNull { d -> app.parley.common.calls.RingExplainer.matchFor(mine.map { it.facts }, d) }.toSet()
        if (drop.isEmpty()) return
        store(rows().filterNot { it.key == k && it.facts in drop })
    }

    @Synchronized
    fun clear() {
        _rows.value = emptyList()
        prefs.edit().remove(KEY_ROWS).remove(KEY_ROWS_V1).apply()
        _version.value++
    }

    private fun store(next: List<Row>): Boolean {
        val text = runCatching { encode(next) }.getOrNull() ?: return false
        _rows.value = next
        prefs.edit().putString(KEY_ROWS, text).apply()
        _version.value++
        return true
    }

    // One line per row: "<key>\t<sealed facts JSON, Base64>", so each row opens on its own.
    private fun encode(rows: List<Row>): String {
        val h = history()
        return rows.joinToString("\n") { r ->
            r.key + "\t" + Base64.encodeToString(h.sealAux(RingFactsCodec.encode(listOf(r.facts)).toByteArray()), Base64.NO_WRAP)
        }
    }

    private fun decode(text: String?): List<Row> {
        if (text.isNullOrEmpty()) return emptyList()
        val h = runCatching { history() }.getOrNull() ?: return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val json = runCatching { String(h.openAux(Base64.decode(line.substring(tab + 1), Base64.NO_WRAP))) }.getOrNull() ?: return@mapNotNull null
            RingFactsCodec.decode(json).firstOrNull()?.let { Row(line.substring(0, tab), it) }
        }.toList()
    }

    private companion object {
        const val FILE = "parley_ring_facts"
        const val KEY_ROWS_V1 = "rows_v1"
        const val KEY_ROWS = "rows_v2"
        const val HIDDEN = "hidden"
        const val MAC = "m:"
        const val MAX_ROWS = 400
        const val KEEP_DAYS = 60L
    }
}
