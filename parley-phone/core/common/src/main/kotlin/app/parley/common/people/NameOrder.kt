package app.parley.common.people

import app.parley.common.ContactSummary

/**
 * "Sort by" and "Show names as", two settings as in Android's own Contacts: a list can be sorted by last name while
 * names still read "Robert Jones". [ContactSummary.displayName] is the shown name and [ContactSummary.sortName] the
 * one the list is ordered, sectioned and indexed (A–Z rail) by.
 */
object NameOrder {
    /**
     * "Show names as" last name first. Before it was a setting of its own, one setting did both, so a phone that
     * has never saved it shows names the way it sorts them.
     */
    fun showLastFirst(stored: Boolean?, sortByFirstName: Boolean): Boolean = stored ?: !sortByFirstName

    /**
     * [list] (in the address book's first-name order, with [ContactSummary.displayNameAlt] as "Family, Given") shown
     * and sorted as set. Sorting by first name keeps the address book's own order, which already handles phonetic
     * names; sorting by last name sorts by the "Family, Given" form with [compare]. Ties keep the original order.
     */
    fun apply(list: List<ContactSummary>, sortByFirstName: Boolean, lastFirst: Boolean, compare: Comparator<String>): List<ContactSummary> {
        if (sortByFirstName && !lastFirst) return list
        val shown = list.map { c ->
            c.copy(
                displayName = if (lastFirst) c.displayNameAlt else c.displayName,
                sortName = if (sortByFirstName) c.displayName else c.displayNameAlt,
            )
        }
        return if (sortByFirstName) shown else shown.sortedWith { a, b -> compare.compare(a.sortName, b.sortName) }
    }

    /** [primary] or [alternative] (the "Family, Given" form, when there is one), as "Show names as" says. */
    fun shown(primary: String, alternative: String?, lastFirst: Boolean): String =
        if (lastFirst) alternative?.takeIf { it.isNotBlank() } ?: primary else primary

    /**
     * [c] shown as [name] (a nickname, with "Prefer nicknames"): it then sorts by that name too, so "Bob" is listed
     * under B. Unchanged when [name] is already the shown one.
     */
    fun renamed(c: ContactSummary, name: String): ContactSummary = if (name == c.displayName) c else c.copy(displayName = name, sortName = name)
}
