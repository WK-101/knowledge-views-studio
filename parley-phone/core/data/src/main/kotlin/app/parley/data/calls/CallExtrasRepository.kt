package app.parley.data.calls

import android.content.Context
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calls.RingExplainer
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

    /** [durable]: on disk before this returns (Situations switching), not later. */
    @Synchronized
    fun update(durable: Boolean = false, transform: (CallExtrasConfig) -> CallExtrasConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit().putString(KEY, CallExtrasConfig.encode(next)).let { if (durable) it.commit() else it.apply() }
    }

    private companion object {
        const val FILE = "parley_call_extras"
        const val KEY = "config_v1"
    }
}

/**
 * Ring-side facts per incoming call. In app-private storage, like the screening trace; the newest 400 of
 * the last 60 days are kept. Nothing leaves the phone, and nothing is readable at rest: rows are keyed by
 * the call-history archive's keyed fingerprint of the line (never the number) and the facts (Bluetooth device names
 * among them) are sealed with the archive key. Deleting or purging calls forgets their facts
 * ([app.parley.data.history.CallHistory.onForget]). Kept in a [SealedLineStore].
 */
class RingFactsStore(context: Context, private val history: () -> CallHistory) {
    private val store = SealedLineStore(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE), KEY_ROWS, { SealedLineStore.HistoryKeys(history()) },
        encode = { RingFactsCodec.encode(listOf(it)) }, decode = { RingFactsCodec.decode(it).firstOrNull() }, startedAt = { it.startedAt },
    )

    @Volatile private var migrated = false

    /** Bumped on every write, so screens re-read. */
    val version: StateFlow<Int> get() = store.version

    /**
     * Rows from before they were keyed and sealed (plaintext line keys): re-keyed when they were E.164, else dropped.
     * Serialised: the call path and a screen can both be first, and two merges would add every old row twice.
     */
    @Synchronized
    private fun migrate() {
        if (migrated) return
        migrated = true
        val old = store.prefs.getString(KEY_ROWS_V1, null) ?: return
        val mac = runCatching { history() }.getOrNull() ?: return
        val moved = old.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val k = line.substring(0, tab)
            val facts = RingFactsCodec.decode(line.substring(tab + 1)).firstOrNull() ?: return@mapNotNull null
            val key = when {
                k == SealedLineStore.HIDDEN -> SealedLineStore.HIDDEN
                k.startsWith("+") -> runCatching { SealedLineStore.MAC + mac.lineMac(k) }.getOrNull()
                else -> null
            } ?: return@mapNotNull null
            key to facts
        }.toList()
        if (store.merge(moved)) store.prefs.edit().remove(KEY_ROWS_V1).apply()
    }

    fun add(number: String?, facts: RingFacts, now: Long = System.currentTimeMillis()) {
        migrate()
        store.add(number, facts, now)
    }

    /** Facts for [number], newest first. */
    fun forNumber(number: String?): List<RingFacts> {
        migrate()
        return store.forNumber(number)
    }

    /** The facts of the call from [number] that rang at about [time] (a call-log date), or null. */
    fun near(number: String?, time: Long): RingFacts? = RingExplainer.matchFor(forNumber(number), time)

    /**
     * Forgets [number]'s facts: those of the calls at [dates] (call-log dates), or all of them when [dates] is null
     * (the number's history was purged).
     */
    fun forget(number: String, dates: List<Long>? = null) {
        migrate()
        store.forget(number, dates, RingExplainer::matchFor)
    }

    fun clear() = store.clear(KEY_ROWS_V1)

    private companion object {
        const val FILE = "parley_ring_facts"
        const val KEY_ROWS_V1 = "rows_v1"
        const val KEY_ROWS = "rows_v2"
    }
}
