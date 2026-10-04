package app.parley.data.calls

import android.content.Context
import app.parley.common.calls.CallQualityCodec
import app.parley.common.calls.CallQualityFacts
import app.parley.data.history.CallHistory
import kotlinx.coroutines.flow.StateFlow

/**
 * Quality facts per call (SIM, Wi-Fi calling, HD voice, why it ended, how long, the caller's subject), for the number
 * history now and a call quality diary later. Kept in a [SealedLineStore] like [RingFactsStore];
 * deleting or purging calls forgets theirs.
 */
class CallQualityStore private constructor(context: Context, keys: SealedLineStore.KeySource) {
    constructor(context: Context, history: () -> CallHistory) : this(context, SealedLineStore.KeySource { SealedLineStore.HistoryKeys(history()) })

    /** With [keys] in place of the archive's (tests). */
    internal constructor(context: Context, keys: SealedLineStore.Keys) : this(context, SealedLineStore.KeySource { keys })

    private val store = SealedLineStore(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE), KEY_ROWS, keys,
        encode = { CallQualityCodec.encode(listOf(it)) }, decode = { CallQualityCodec.decode(it).firstOrNull() }, startedAt = { it.startedAt },
    )

    /** Bumped on every write, so screens re-read. */
    val version: StateFlow<Int> get() = store.version

    fun add(number: String?, facts: CallQualityFacts, now: Long = System.currentTimeMillis()) = store.add(number, facts, now)

    /** Facts for [number], newest first. */
    fun forNumber(number: String?): List<CallQualityFacts> = store.forNumber(number)

    /**
     * Every row as (line key, facts), newest first, for the quality diary (I8). The key is the keyed fingerprint of the
     * line, never the number: [keyOf] gives a known number's key to match it.
     */
    fun all(): List<Pair<String, CallQualityFacts>> = store.all()

    /** The line key [all] uses for [number] (null when the key can't be used now); a hidden number has its own. */
    fun keyOf(number: String?): String? = store.keyOf(number)

    /** Forgets [number]'s facts: those of the calls at [dates] (call-log dates), or all when [dates] is null. */
    fun forget(number: String, dates: List<Long>? = null) = store.forget(number, dates, CallQualityCodec::near)

    fun clear() = store.clear()

    private companion object {
        const val FILE = "parley_call_quality"
        const val KEY_ROWS = "rows_v1"
    }
}
