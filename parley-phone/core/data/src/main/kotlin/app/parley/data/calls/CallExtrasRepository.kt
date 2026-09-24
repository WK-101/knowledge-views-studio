package app.parley.data.calls

import android.content.Context
import app.parley.common.PhoneNumbers
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calls.RingFacts
import app.parley.common.calls.RingFactsCodec
import app.parley.data.PhoneEnv
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
 * Ring-side facts per incoming call (V9), keyed by line like the ring records. In app-private storage, like the
 * screening trace; the newest [MAX_ROWS] of the last [KEEP_DAYS] days are kept. Nothing leaves the phone.
 */
class RingFactsStore(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private data class Row(val key: String, val facts: RingFacts)

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    private var loaded = false

    /** Bumped on every write, so screens re-read. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun key(number: String): String = PhoneNumbers.lineKey(number, PhoneEnv.countryIso(context))

    @Synchronized
    private fun rows(): List<Row> {
        if (!loaded) {
            loaded = true
            _rows.value = decode(prefs.getString(KEY_ROWS, null))
        }
        return _rows.value
    }

    @Synchronized
    fun add(number: String?, facts: RingFacts, now: Long = System.currentTimeMillis()) {
        val k = if (number.isNullOrBlank()) HIDDEN else key(number)
        val next = (listOf(Row(k, facts)) + rows().filterNot { it.key == k && it.facts.startedAt == facts.startedAt })
            .filter { now - it.facts.startedAt < KEEP_DAYS * 86_400_000L }
            .take(MAX_ROWS)
        _rows.value = next
        prefs.edit().putString(KEY_ROWS, encode(next)).apply()
        _version.value++
    }

    /** Facts for [number], newest first. */
    fun forNumber(number: String?): List<RingFacts> {
        val k = if (number.isNullOrBlank()) HIDDEN else key(number)
        return rows().filter { it.key == k }.map { it.facts }.sortedByDescending { it.startedAt }
    }

    /** The facts of the call from [number] that rang at about [time] (a call-log date), or null. */
    fun near(number: String?, time: Long): RingFacts? = app.parley.common.calls.RingExplainer.matchFor(forNumber(number), time)

    @Synchronized
    fun clear() {
        _rows.value = emptyList()
        prefs.edit().remove(KEY_ROWS).apply()
        _version.value++
    }

    // One line per row: "<key>\t<facts JSON>", so each row decodes on its own.
    private fun encode(rows: List<Row>): String = rows.joinToString("\n") { it.key + "\t" + RingFactsCodec.encode(listOf(it.facts)) }

    private fun decode(text: String?): List<Row> = text.orEmpty().lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        RingFactsCodec.decode(line.substring(tab + 1)).firstOrNull()?.let { Row(line.substring(0, tab), it) }
    }.toList()

    private companion object {
        const val FILE = "parley_ring_facts"
        const val KEY_ROWS = "rows_v1"
        const val HIDDEN = "hidden"
        const val MAX_ROWS = 400
        const val KEEP_DAYS = 60L
    }
}
