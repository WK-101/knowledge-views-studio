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
 * restored). A name unseen for [KEEP_DAYS] days is dropped (when the store is next read or written); deleting a
 * number's last calls forgets its names, and so does the number becoming a private contact's.
 *
 * A national number is kept under its line as the SIM the call came in on reads it ([NetworkName.line]): pass that
 * SIM's country as `simRegion` when it is known, the same way for reading as for writing.
 *
 * The callers check that the number isn't a contact, a private or an archived contact before [record]; the screens
 * check again before showing (a number saved later shows its saved name).
 */
class NetworkNameStore private constructor(context: Context, private val keys: SealedLineStore.KeySource) {
    constructor(context: Context, history: () -> CallHistory) : this(context, SealedLineStore.KeySource { SealedLineStore.HistoryKeys(history()) })

    /** With [keys] in place of the archive's (tests). */
    internal constructor(context: Context, keys: SealedLineStore.Keys) : this(context, SealedLineStore.KeySource { keys })

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val store = sealed(KEY_ROWS)

    private fun sealed(rowsKey: String) = SealedLineStore(
        prefs, rowsKey, keys, encode = NetworkName::encode, decode = NetworkName::decode, startedAt = { it.lastSeen },
        maxRows = MAX_ROWS, keepDays = KEEP_DAYS,
    )

    /** Bumped on every write, so lists re-read. */
    val version: StateFlow<Int> get() = store.version

    /**
     * [name] (already [NetworkName.clean]ed) was sent with a call from [number] at [at], on [accountId]'s SIM, whose
     * country is [region] (the number is kept under its line as that SIM reads it).
     */
    fun record(number: String, name: String, at: Long, accountId: String?, region: String?, now: Long = System.currentTimeMillis()) {
        if (number.isBlank() || name.isBlank()) return
        store.update(NetworkName.line(number, region), now) { kept -> NetworkName.record(kept, name, at, accountId, region) }
    }

    /** What the network sent for [number] (read with [simRegion], the call's SIM country, when known), newest first. */
    fun forNumber(number: String?, simRegion: String? = null, now: Long = System.currentTimeMillis()): List<NetworkNameSeen> =
        if (number.isNullOrBlank()) emptyList() else store.forNumber(NetworkName.line(number, simRegion), now)

    /** The name it sent last for [number], or null. */
    fun latest(number: String?, simRegion: String? = null): NetworkNameSeen? = NetworkName.latest(forNumber(number, simRegion))

    /**
     * A reader for a whole list (Recents, Recall): the stored rows are read once, and each number asked costs one keyed
     * fingerprint. Ask with the number's line ([NetworkName.line]) where the call's SIM is known. Names for [except]
     * numbers (private contacts) are never returned.
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

    /**
     * Forgets every name kept for [number] (its last calls went for good, or it became a private contact's): under the
     * number as the phone reads it and as each of [regions] (the SIMs' countries) reads it, since which SIM its calls
     * came in on isn't known here.
     */
    fun forget(number: String, regions: Collection<String?> = emptyList()) {
        lines(number, regions).forEach { line -> store.forget(line, null) { _, _ -> null } }
    }

    /** Whether any name is kept for [number], under any of the lines [forget] would clear. */
    fun has(number: String, regions: Collection<String?> = emptyList()): Boolean = lines(number, regions).any { store.forNumber(it).isNotEmpty() }

    /**
     * [forget], with a sealed copy kept beside deleted calls' undo [batch], so undoing the delete brings the names
     * back ([putBack]). Copies older than the undo window go.
     */
    fun setAside(number: String, regions: Collection<String?>, batch: Long, now: Long = System.currentTimeMillis()) {
        val keyed = lines(number, regions).flatMap { line ->
            val k = store.keyOf(line) ?: return@flatMap emptyList()
            store.forNumber(line, now).map { k to it }
        }
        if (keyed.isNotEmpty()) sealed(ASIDE + batch).merge(keyed)
        forget(number, regions)
        dropOldAsides(now)
    }

    /** Puts back the names [setAside] kept with [batch] (names recorded since win where newer); true when there were any. */
    fun putBack(batch: Long, now: Long = System.currentTimeMillis()): Boolean {
        val aside = sealed(ASIDE + batch)
        val rows = aside.all(now)
        rows.groupBy({ it.first }, { it.second }).forEach { (key, names) ->
            store.updateKey(key, now) { current -> names.fold(current, NetworkName::merge) }
        }
        aside.clear()
        return rows.isNotEmpty()
    }

    private fun lines(number: String, regions: Collection<String?>): List<String> =
        if (number.isBlank()) emptyList() else (listOf(number) + regions.map { NetworkName.line(number, it) }).distinct()

    private fun dropOldAsides(now: Long) {
        val old = prefs.all.keys.filter { k -> k.startsWith(ASIDE) && k.removePrefix(ASIDE).toLongOrNull()?.let { now - it > ASIDE_MS } != false }
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it) } }.apply()
    }

    fun clear() {
        val asides = prefs.all.keys.filter { it.startsWith(ASIDE) }
        store.clear()
        if (asides.isNotEmpty()) prefs.edit().apply { asides.forEach { remove(it) } }.apply()
    }

    private companion object {
        const val FILE = "parley_network_names"
        const val KEY_ROWS = "rows"
        const val MAX_ROWS = 1_000
        const val KEEP_DAYS = 400L

        /** Copies set aside with deleted calls, by undo batch: kept as long as the batch can be undone. */
        const val ASIDE = "aside_"
        const val ASIDE_MS = 31 * 86_400_000L
    }
}
