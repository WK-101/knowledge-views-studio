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
     * What to store for "Show names as" when settings are saved: nothing while it was never stored and still equals
     * what [showLastFirst] derives, so saving another setting doesn't pin it. Once stored, always the value.
     */
    fun toStore(stored: Boolean?, sortByFirstName: Boolean, lastFirst: Boolean): Boolean? =
        if (stored == null && lastFirst == showLastFirst(null, sortByFirstName)) null else lastFirst

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
        return if (sortByFirstName) shown else Collation.sortedBy(shown, compare) { it.sortName }
    }

    /** [primary] or [alternative] (the "Family, Given" form, when there is one), as "Show names as" says. */
    fun shown(primary: String, alternative: String?, lastFirst: Boolean): String =
        if (lastFirst) alternative?.takeIf { it.isNotBlank() } ?: primary else primary

    /**
     * [c] shown as [name] (a nickname, with "Prefer nicknames"): it then sorts by that name too, so "Bob" is listed
     * under B. Unchanged when [name] is already the shown one.
     */
    fun renamed(c: ContactSummary, name: String): ContactSummary = if (name == c.displayName) c else c.copy(displayName = name, sortName = name)

    /**
     * The "Family, Given" form of a name Parley keeps itself (a private contact), as the address book makes it for
     * its own: "Brown, Dan Paul" from the name parts, "Brown, Jr." keeping a suffix. With no family name, the name as
     * it is.
     */
    fun alternative(given: String, middle: String, family: String, suffix: String): String? {
        val f = family.trim().ifEmpty { return null }
        val first = listOf(given, middle).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return listOf(f, first, suffix.trim()).filter { it.isNotEmpty() }.joinToString(", ")
    }

    /**
     * The "Family, Given" form guessed from a whole name when its parts aren't known: the last word is the family
     * name, with a lowercase particle before it ("van", "de") and without a trailing suffix ("Jr."), much as the
     * address book splits a name typed in one field. A one-word name, or one that already has a comma, stays as it is.
     */
    fun guessAlternative(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2 || name.contains(',')) return name.trim()
        val suffix = words.last().takeIf { it.trimEnd('.').lowercase() in SUFFIXES && words.size > 2 }
        val rest = if (suffix != null) words.dropLast(1) else words
        var start = rest.size - 1
        while (start > 1 && rest[start - 1] in PARTICLES) start--
        val family = rest.subList(start, rest.size).joinToString(" ")
        val given = rest.subList(0, start).joinToString(" ")
        return listOfNotNull(family, given, suffix).joinToString(", ")
    }

    private val SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv", "phd", "md")
    private val PARTICLES = setOf("van", "von", "de", "der", "den", "da", "di", "du", "del", "della", "la", "le", "ter", "ten", "bin", "al", "dos", "das")
}
