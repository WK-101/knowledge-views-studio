package app.parley.data.calls

import android.content.Context
import app.parley.common.calls.NetworkName
import app.parley.common.calls.NetworkNameSeen
import app.parley.data.history.CallHistory
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The names the network sent with calls from numbers that aren't saved (see [NetworkName]): the latest and up to two
 * before it per number, with when, the SIM and the country. The call log has no column for them, so Parley keeps
 * them beside its call-history archive, in a [SealedLineStore] like ring facts: keyed by the archive's fingerprint of
 * the line, sealed with its key, on this phone only (not in backups, like number memory, which is rebuilt rather than
 * restored). A name unseen for [KEEP_DAYS] days is dropped; deleting a number's calls forgets its names.
 *
 * The callers check that the number isn't a contact, a private or an archived contact before [record]; the screens
 * check again before showing (a number saved later shows its saved name).
 */
class NetworkNameStore private constructor(context: Context, keys: SealedLineStore.KeySource) {
    constructor(context: Context, history: () -> CallHistory) : this(context, SealedLineStore.KeySource { SealedLineStore.HistoryKeys(history()) })

    /** With [keys] in place of the archive's (tests). */
    internal constructor(context: Context, keys: SealedLineStore.Keys) : this(context, SealedLineStore.KeySource { keys })

    private val store = SealedLineStore(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE), KEY_ROWS, keys,
        encode = NetworkName::encode, decode = NetworkName::decode, startedAt = { it.lastSeen },
        maxRows = MAX_ROWS, keepDays = KEEP_DAYS,
    )

    /** Bumped on every write, so lists re-read. */
    val version: StateFlow<Int> get() = store.version

    /** [name] (already [NetworkName.clean]ed) was sent with a call from [number] at [at]. */
    fun record(number: String, name: String, at: Long, accountId: String?, region: String?, now: Long = System.currentTimeMillis()) {
        if (number.isBlank() || name.isBlank()) return
        store.update(number, now) { kept -> NetworkName.record(kept, name, at, accountId, region) }
    }

    /** What the network sent for [number], newest first. */
    fun forNumber(number: String?): List<NetworkNameSeen> = if (number.isNullOrBlank()) emptyList() else store.forNumber(number)

    /** The name it sent last for [number], or null. */
    fun latest(number: String?): NetworkNameSeen? = NetworkName.latest(forNumber(number))

    /**
     * A reader for a whole list (Recents, Recall): the stored rows are read once, and each number asked costs one keyed
     * fingerprint. Names for [except] numbers (private contacts) are never returned.
     */
    fun reader(except: (String) -> Boolean = { false }): (String) -> List<NetworkNameSeen> {
        val byKey = store.all().groupBy({ it.first }, { it.second })
        if (byKey.isEmpty()) return { emptyList() }
        // Each number's fingerprint is taken once per reader: lists regroup on every keystroke of a search.
        val asked = ConcurrentHashMap<String, List<NetworkNameSeen>>()
        return { number ->
            if (number.isBlank() || except(number)) {
                emptyList()
            } else {
                asked.getOrPut(number) { store.keyOf(number)?.let { byKey[it] }.orEmpty().sortedByDescending { it.lastSeen } }
            }
        }
    }

    /** Forgets every name kept for [number] (its calls were deleted, or it became a private contact). */
    fun forget(number: String) = store.forget(number, null) { _, _ -> null }

    fun clear() = store.clear()

    private companion object {
        const val FILE = "parley_network_names"
        const val KEY_ROWS = "rows"
        const val MAX_ROWS = 1_000
        const val KEEP_DAYS = 400L
    }
}
