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

data class ProvenanceVerdict(val source: ChangeSource, val text: String, val time: Long?)

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
    fun verdict(raws: List<RawState>, writes: List<ParleyWrite>, lastUpdated: Long?, formatTime: (Long) -> String): ProvenanceVerdict? {
        if (raws.isEmpty()) return null
        val byRaw = writes.groupBy { it.rawId }.mapValues { (_, w) -> w.maxBy { it.time } }
        val updated = lastUpdated?.takeIf { it > 0 }
        val at = updated?.let { " on " + formatTime(it) }.orEmpty()
        val newer = raws.filter { r -> byRaw[r.rawId]?.let { r.version > it.versionAfter } ?: false }
        val tracked = raws.filter { it.rawId in byRaw }
        val untracked = raws.filter { it.rawId !in byRaw }

        if (newer.isNotEmpty()) {
            newer.firstOrNull { it.dirty }?.let {
                return ProvenanceVerdict(ChangeSource.ANOTHER_APP, "Changed on this phone by another app$at, after Parley's last save (not synced yet)", updated)
            }
            newer.firstOrNull { it.synced }?.let {
                return ProvenanceVerdict(ChangeSource.SYNC, "Changed by ${it.accountLabel} sync$at, after Parley's last save", updated)
            }
            return ProvenanceVerdict(ChangeSource.ANOTHER_APP, "Changed by another app$at, after Parley's last save", updated)
        }
        if (tracked.isNotEmpty()) {
            val last = byRaw.values.maxBy { it.time }
            val fields = last.fields.takeIf { it.isNotEmpty() }?.joinToString(", ")
            val base = "Changed by Parley on ${formatTime(last.time)}" + (fields?.let { " · only changed fields were written ($it)" } ?: "")
            val other = untracked.firstOrNull() ?: return ProvenanceVerdict(ChangeSource.PARLEY, base, last.time)
            return ProvenanceVerdict(
                ChangeSource.PARLEY,
                "$base. The ${other.accountLabel} copy is kept up to date by ${if (other.synced) "its sync" else "other apps"}",
                last.time,
            )
        }
        raws.firstOrNull { it.dirty }?.let {
            return ProvenanceVerdict(ChangeSource.ANOTHER_APP, "Last changed on this phone by another app$at (not synced yet)", updated)
        }
        raws.firstOrNull { it.synced }?.let {
            return ProvenanceVerdict(ChangeSource.SYNC, "Last changed$at · in step with ${it.accountLabel} sync", updated)
        }
        return updated?.let { ProvenanceVerdict(ChangeSource.UNKNOWN, "Last changed$at, not by Parley", it) }
    }

    /** Journal line for Parley's own edits. */
    fun journalNote(fields: List<String>): String =
        if (fields.isEmpty()) "Nothing needed writing" else "Only changed fields were written: " + fields.joinToString(", ")
}
