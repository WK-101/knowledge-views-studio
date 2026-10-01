package app.parley.common.backup

import app.parley.common.people.AccountKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
data class StoredAccount(
    val type: String? = null,
    val name: String? = null,
    val count: Int = 0,
    val signedIn: Boolean = false,
    val syncOff: Boolean = false,
)

/** [WatchAccounts] as stored between runs. */
@Serializable
data class StoredAccounts(val accounts: List<StoredAccount> = emptyList(), val masterSyncOn: Boolean = true, val syncKnown: Boolean = true) {
    fun toWatch(): WatchAccounts = WatchAccounts(
        signedIn = accounts.filter { it.signedIn }.map { AccountKey(it.type, it.name) }.toSet(),
        counts = accounts.associate { AccountKey(it.type, it.name) to it.count },
        syncOff = accounts.filter { it.syncOff }.map { AccountKey(it.type, it.name) }.toSet(),
        masterSyncOn = masterSyncOn,
        syncKnown = syncKnown,
    )

    companion object {
        fun of(w: WatchAccounts): StoredAccounts = StoredAccounts(
            (w.counts.keys + w.signedIn + w.syncOff).distinct().sortedBy { "${it.type}|${it.name}" }.map {
                StoredAccount(it.type, it.name, w.counts[it] ?: 0, it in w.signedIn, it in w.syncOff)
            },
            w.masterSyncOn, w.syncKnown,
        )
    }
}

/**
 * What the sync watchdog remembers between its daily runs: the baseline snapshot and accounts it compares against,
 * the events it has said (never twice), the contacts already reported or dismissed, and the events still shown as a
 * card until "Restore from snapshot" or "It was me".
 */
@Serializable
data class SyncWatchMemory(
    val baseline: Long = 0,
    val accounts: StoredAccounts? = null,
    val said: List<String> = emptyList(),
    val acknowledged: Map<String, Long> = emptyMap(),
    val pending: List<WatchEvent> = emptyList(),
) {
    /**
     * After a run that found [found] against the accounts [now] and the newest snapshot [newest]: the new memory and
     * the events to notify about (only ones never said before).
     */
    fun afterRun(found: List<WatchEvent>, now: WatchAccounts, newest: Long, at: Long): Pair<SyncWatchMemory, List<WatchEvent>> {
        val fresh = SyncWatchdog.fresh(found, said.toSet())
        val ack = SyncWatchdog.pruneAcknowledged(acknowledged, at, KEEP_MS) + fresh.flatMap { e -> e.keys.map { it to at } }
        val next = copy(
            baseline = newest,
            accounts = StoredAccounts.of(now),
            said = (said + fresh.map { it.fingerprint }).takeLast(MAX_SAID),
            acknowledged = ack,
            pending = (pending.filter { at - it.at <= PENDING_MS } + fresh).takeLast(MAX_PENDING),
        )
        return next to fresh
    }

    /** "It was me" (or the event was dealt with): the card goes; its contacts are never reported again. */
    fun dismiss(e: WatchEvent, at: Long): SyncWatchMemory =
        copy(pending = pending.filter { it.fingerprint != e.fingerprint }, acknowledged = acknowledged + e.keys.map { it to at })

    /** A restore was undone: the card comes back (its contacts stay acknowledged, so nothing is notified again). */
    fun reopen(e: WatchEvent): SyncWatchMemory =
        if (pending.any { it.fingerprint == e.fingerprint }) this else copy(pending = (pending + e).takeLast(MAX_PENDING))

    fun encode(): String = RecordJson.json.encodeToString(serializer(), this)

    companion object {
        /** Contacts already said are remembered as long as snapshots are kept (180 days). */
        const val KEEP_MS = 181L * 24 * 60 * 60 * 1000

        /** A card nobody answered goes quietly after 30 days rather than lingering for good. */
        const val PENDING_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_SAID = 200
        const val MAX_PENDING = 10

        /** The stored memory, or a fresh one when there is none or it can't be read. */
        fun decode(s: String?): SyncWatchMemory {
            if (s.isNullOrBlank()) return SyncWatchMemory()
            return try {
                RecordJson.json.decodeFromString(serializer(), s)
            } catch (_: SerializationException) {
                SyncWatchMemory()
            } catch (_: IllegalArgumentException) {
                SyncWatchMemory()
            }
        }
    }
}
