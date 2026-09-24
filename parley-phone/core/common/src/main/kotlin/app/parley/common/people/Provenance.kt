package app.parley.common.people

/** What Parley remembers about its own last write to a raw contact. */
data class ParleyWrite(
    val rawId: Long,
    val time: Long,
    /** RawContacts.VERSION right after Parley's write. */
    val versionAfter: Long,
    /** Human names of the fields that were actually written ("Phone", "Email"…). */
    val fields: List<String>,
)

/** Current state of one raw contact, as the provider reports it. */
data class RawState(
    val rawId: Long,
    val accountLabel: String,
    /** True for accounts with a sync adapter (Google, CardDAV…); false for phone-only storage. */
    val synced: Boolean,
    val version: Long,
    /** RawContacts.DIRTY: changed on this phone and not yet uploaded by the sync adapter. */
    val dirty: Boolean,
)

enum class ChangeSource { PARLEY, ANOTHER_APP, SYNC, UNKNOWN }

/** Which "Why did this change?" sentence applies; the app words it. */
enum class ProvenanceKind {
    /** Changed on this phone by another app after Parley's last save, not synced yet. */
    OTHER_APP_AFTER_PARLEY_UNSYNCED,
    /** Changed by [ProvenanceVerdict.account]'s sync after Parley's last save. */
    SYNC_AFTER_PARLEY,
    /** Changed by another app after Parley's last save. */
    OTHER_APP_AFTER_PARLEY,
    /** Changed by Parley ([ProvenanceVerdict.fields] written; [ProvenanceVerdict.otherAccount] is another copy, if any). */
    PARLEY,
    /** Last changed on this phone by another app, not synced yet. */
    LAST_OTHER_APP_UNSYNCED,
    /** Last changed, in step with [ProvenanceVerdict.account]'s sync. */
    LAST_SYNC,
    /** Last changed, not by Parley. */
    LAST_UNKNOWN,
}

/**
 * [time] is when the change happened (null when Android doesn't say). [account] names the syncing account for the
 * sync kinds; [fields] are the fields Parley wrote; [otherAccount] / [otherSynced] describe another copy Parley
 * doesn't track (PARLEY only).
 */
data class ProvenanceVerdict(
    val source: ChangeSource,
    val kind: ProvenanceKind,
    val time: Long?,
    val account: String? = null,
    val fields: List<String> = emptyList(),
    val otherAccount: String? = null,
    val otherSynced: Boolean = false,
)

/**
 * "Why did this change?": attributes the latest change of a contact.
 *
 * Android doesn't record which app wrote a contact, so this is inference, stated as such:
 * - Parley remembers the VERSION of each raw contact right after its own saves. Same version now → Parley.
 * - A newer version with DIRTY set means something on this phone changed it after Parley (another app).
 * - A newer version with DIRTY clear in a synced account means the sync adapter wrote it (only sync adapters
 *   clear DIRTY), i.e. the change came from the account: the web, another phone, or the server.
 */
object Provenance {
    fun verdict(raws: List<RawState>, writes: List<ParleyWrite>, lastUpdated: Long?): ProvenanceVerdict? {
        if (raws.isEmpty()) return null
        val byRaw = writes.groupBy { it.rawId }.mapValues { (_, w) -> w.maxBy { it.time } }
        val updated = lastUpdated?.takeIf { it > 0 }
        val newer = raws.filter { r -> byRaw[r.rawId]?.let { r.version > it.versionAfter } ?: false }
        val tracked = raws.filter { it.rawId in byRaw }
        val untracked = raws.filter { it.rawId !in byRaw }

        if (newer.isNotEmpty()) {
            newer.firstOrNull { it.dirty }?.let {
                return ProvenanceVerdict(ChangeSource.ANOTHER_APP, ProvenanceKind.OTHER_APP_AFTER_PARLEY_UNSYNCED, updated)
            }
            newer.firstOrNull { it.synced }?.let {
                return ProvenanceVerdict(ChangeSource.SYNC, ProvenanceKind.SYNC_AFTER_PARLEY, updated, account = it.accountLabel)
            }
            return ProvenanceVerdict(ChangeSource.ANOTHER_APP, ProvenanceKind.OTHER_APP_AFTER_PARLEY, updated)
        }
        if (tracked.isNotEmpty()) {
            val last = byRaw.values.maxBy { it.time }
            val other = untracked.firstOrNull()
            return ProvenanceVerdict(
                ChangeSource.PARLEY, ProvenanceKind.PARLEY, last.time,
                fields = last.fields, otherAccount = other?.accountLabel, otherSynced = other?.synced == true,
            )
        }
        raws.firstOrNull { it.dirty }?.let {
            return ProvenanceVerdict(ChangeSource.ANOTHER_APP, ProvenanceKind.LAST_OTHER_APP_UNSYNCED, updated)
        }
        raws.firstOrNull { it.synced }?.let {
            return ProvenanceVerdict(ChangeSource.SYNC, ProvenanceKind.LAST_SYNC, updated, account = it.accountLabel)
        }
        return updated?.let { ProvenanceVerdict(ChangeSource.UNKNOWN, ProvenanceKind.LAST_UNKNOWN, it) }
    }
}
