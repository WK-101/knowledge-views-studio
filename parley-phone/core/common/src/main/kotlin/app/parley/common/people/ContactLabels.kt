package app.parley.common.people

/**
 * The labels a contact's page shows under the name. A device contact's labels are the address book's groups it is in
 * (every account); a private contact's are the memberships Parley keeps sealed for it ([PrivateLabels]), readable
 * while private contacts are locked. Labels are one per title, as the Contacts tab, label pages and rules name them;
 * a shared label (docs/SHARED_LABELS.md) is marked so the chip can say so.
 */
object ContactLabels {
    /** One label on the page: its [title], and whether it is a shared label. */
    data class Chip(val title: String, val shared: Boolean)

    /** [titles] once each (trimmed, blank ones dropped), in [order]; the ones in [shared] are marked. */
    fun chips(titles: Collection<String>, shared: Set<String>, order: Comparator<String>): List<Chip> {
        val sharedKeys = shared.map { it.trim() }.toSet()
        val unique = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return Collation.sortedBy(unique, order) { it }.map { Chip(it, it in sharedKeys) }
    }
}
