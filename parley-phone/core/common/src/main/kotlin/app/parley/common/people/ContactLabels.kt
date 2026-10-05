package app.parley.common.people

/**
 * The labels a contact's page shows under the name, and the ones its "Add to label" offers. A device contact's labels
 * are the address book's groups it is in (every account); a private contact's are the memberships Parley keeps sealed
 * for it ([PrivateLabels]), readable while private contacts are locked. Labels are one per title, as the Contacts tab,
 * label pages and rules name them; a shared label (docs/SHARED_LABELS.md) is marked so the chip can say so.
 */
object ContactLabels {
    /** One label on the page: its [title], and whether it is a shared label. */
    data class Chip(val title: String, val shared: Boolean)

    /** A label group as the address book has it: its id, title and the account it belongs to ("type/name"). */
    data class Group(val id: Long, val title: String, val account: String)

    /** [titles] once each (trimmed, blank ones dropped), in [order]; the ones in [shared] are marked. */
    fun chips(titles: Collection<String>, shared: Set<String>, order: Comparator<String>): List<Chip> {
        val sharedKeys = shared.map { it.trim() }.toSet()
        val unique = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return Collation.sortedBy(unique, order) { it }.map { Chip(it, it in sharedKeys) }
    }

    /**
     * What "Add to label" offers a contact already in [current]: one group per title it isn't in yet, in [order]. A
     * private contact joins any label (Parley keeps the membership, by title). A device contact joins a label through a
     * group of an account it has a copy in ([accounts]), since a group row belongs to one copy; null [accounts] (not
     * known) offers every group.
     */
    fun offered(groups: List<Group>, current: Set<String>, accounts: Set<String>?, private: Boolean, order: Comparator<String>): List<Group> {
        val have = current.map { it.trim() }.toSet()
        val usable = groups.filter { g ->
            val t = g.title.trim()
            t.isNotEmpty() && t !in have && (private || accounts == null || g.account in accounts)
        }
        return Collation.sortedBy(usable.distinctBy { it.title.trim() }, order) { it.title.trim() }
    }
}
