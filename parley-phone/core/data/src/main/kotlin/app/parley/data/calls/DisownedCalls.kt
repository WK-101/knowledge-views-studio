package app.parley.data.calls

import android.content.Context
import app.parley.data.history.CallHistory

/**
 * "It wasn't them": calls from a saved organisation's line that you said weren't really them (a spoofed caller ID).
 * "This number never calls you" leaves those calls out of the history it reads, so the scam call it was there to catch
 * doesn't switch it off for the next one ([app.parley.common.calls.NeverCallsYou.withoutDisowned]). Kept per line in a
 * [SealedLineStore] like the call facts: when you said it, nothing else. They go with the line's calls.
 */
class DisownedCalls private constructor(context: Context, keys: SealedLineStore.KeySource) {
    constructor(context: Context, history: () -> CallHistory) : this(context, SealedLineStore.KeySource { SealedLineStore.HistoryKeys(history()) })

    /** With [keys] in place of the archive's (tests). */
    internal constructor(context: Context, keys: SealedLineStore.Keys) : this(context, SealedLineStore.KeySource { keys })

    private val store = SealedLineStore(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE), KEY_ROWS, keys,
        encode = { it.toString() }, decode = { it.toLongOrNull() }, startedAt = { it },
        // As long as calls are kept: a mark matters for as long as the call it is about.
        maxRows = 400, keepDays = KEEP_DAYS,
    )

    /** [number]'s latest call wasn't them; said at [at]. */
    fun disown(number: String, at: Long = System.currentTimeMillis()) = store.add(number, at, at)

    /** When "It wasn't them" was said about [number]'s calls, newest first. */
    fun forNumber(number: String): List<Long> = store.forNumber(number)

    /** Every call with [number] went: so do its marks. */
    fun forget(number: String) = store.forget(number, null) { _, _ -> null }

    fun clear() = store.clear()

    private companion object {
        const val FILE = "parley_disowned_calls"
        const val KEY_ROWS = "rows_v1"
        const val KEEP_DAYS = 3_650L
    }
}
