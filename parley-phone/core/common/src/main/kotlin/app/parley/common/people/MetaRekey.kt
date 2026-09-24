package app.parley.common.people

/**
 * Parley keeps per-contact information (pinned note, preferred messenger, keep-in-touch, relation links, call
 * backgrounds) under the contact's lookup key. Lookup keys change when contacts are linked or unlinked, when a
 * Google contact first syncs, when a local contact's name changes, or when a copy moves to another account. This is
 * the pure part of re-keying: given where each stored key resolves to now, what to move and how to merge.
 */
object MetaRekey {
    data class Move(val from: String, val to: String)

    /**
     * [resolved]: stored key -> the key it resolves to now (via `Contacts.lookupContact(getLookupUri(id, key))`), or
     * null when it couldn't be resolved (then the row is left alone: the contact may only be missing for a moment,
     * e.g. during a sync). Returns the moves, in a stable order. Several old keys may move to the same new key (two
     * contacts were linked); callers merge them with [merge].
     */
    fun plan(resolved: Map<String, String?>): List<Move> =
        resolved.entries.filter { (from, to) -> !to.isNullOrEmpty() && to != from }.sortedBy { it.key }.map { Move(it.key, it.value!!) }

    /** The fields of one stored row, independent of the database entity. */
    data class Values(
        val pinnedNote: String? = null,
        val preferredMessenger: String? = null,
        val reachOutDays: Int? = null,
        val lastNudgedAt: Long? = null,
        val relationLinks: String? = null,
    )

    /**
     * Combines two rows that now belong to one contact. Nothing the user wrote is dropped: two different pinned notes
     * are joined, relation links are united; for single choices the row already at the new key wins.
     */
    fun merge(into: Values?, from: Values): Values {
        if (into == null) return from
        val note = when {
            into.pinnedNote.isNullOrBlank() -> from.pinnedNote
            from.pinnedNote.isNullOrBlank() || from.pinnedNote.trim() == into.pinnedNote.trim() -> into.pinnedNote
            else -> into.pinnedNote.trimEnd() + "\n" + from.pinnedNote.trim()
        }
        val links = RelationLinks.encode(RelationLinks.decode(from.relationLinks) + RelationLinks.decode(into.relationLinks)).ifEmpty { null }
        return Values(
            pinnedNote = note,
            preferredMessenger = into.preferredMessenger ?: from.preferredMessenger,
            reachOutDays = listOfNotNull(into.reachOutDays, from.reachOutDays).minOrNull(),
            lastNudgedAt = listOfNotNull(into.lastNudgedAt, from.lastNudgedAt).maxOrNull(),
            relationLinks = links,
        )
    }
}
