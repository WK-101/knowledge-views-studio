package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.ux.ListSections

/** How the Contacts list is ordered: by name (Settings' "Sort by" says first or last name), or by one of the others. */
enum class ContactSort { NAME, RECENTLY_ADDED, MOST_CALLED, COMPANY }

/**
 * What a sort other than by name reads, by list id (negative for a private contact). Each map is filled only for the
 * sort that needs it.
 */
data class SortFacts(
    /** When each contact was added, as far as it is known: a private contact's save time, a device contact's last change. */
    val addedAt: Map<Long, Long> = emptyMap(),
    /** Calls with each contact over the call history Parley has loaded. */
    val calls: Map<Long, Int> = emptyMap(),
    /** Each contact's company, as written (private contacts' from their caller-ID copy, readable while locked). */
    val company: Map<Long, String> = emptyMap(),
)

/**
 * The Contacts list in the chosen order, with its section headers. Device and private contacts are ordered together
 * (docs/CONTACT_MODEL.md); within equal keys the list keeps its name order.
 */
object ContactSorting {
    /**
     * [list] (already in name order, as the address book gives it) as rows for [sort]. [letterOf] heads the name order;
     * [noCompany] heads the contacts without a company; [notCalled] the contacts never called, after the ones that were.
     */
    fun rows(
        list: List<ContactSummary>,
        sort: ContactSort,
        facts: SortFacts,
        compare: Comparator<String>,
        noCompany: String,
        notCalled: String,
        letterOf: (ContactSummary) -> String = { ListSections.letterOf(it.sortName) },
    ): List<ListSections.Row<String, ContactSummary>> = when (sort) {
        ContactSort.NAME -> ListSections.interleave(list, letterOf)
        ContactSort.RECENTLY_ADDED -> recentlyAdded(list, facts.addedAt).map { ListSections.Row.Item(it) }
        ContactSort.MOST_CALLED -> mostCalled(list, facts.calls, notCalled)
        ContactSort.COMPANY -> byCompany(list, facts.company, compare, noCompany)
    }

    /**
     * Newest first. Android keeps no "added on" date, but each new address-book contact gets a higher id, so device
     * contacts go by id (newest first, as other contacts apps do). Private contacts go by their save time and join them
     * before the first device contact last changed before they were saved; the device contacts' own order never changes.
     */
    fun recentlyAdded(list: List<ContactSummary>, addedAt: Map<Long, Long>): List<ContactSummary> {
        val device = list.filter { !PrivateListing.isPrivate(it) }.sortedByDescending { it.id }
        val private = list.filter { PrivateListing.isPrivate(it) }.sortedByDescending { addedAt[it.id] ?: 0L }
        if (private.isEmpty()) return device
        val out = ArrayList<ContactSummary>(list.size)
        var j = 0
        for (d in device) {
            val t = addedAt[d.id] ?: 0L
            while (j < private.size && (addedAt[private[j].id] ?: 0L) > t) out += private[j++]
            out += d
        }
        while (j < private.size) out += private[j++]
        return out
    }

    /** Most calls first; the contacts never called follow under their own header, in name order. */
    fun mostCalled(list: List<ContactSummary>, calls: Map<Long, Int>, notCalled: String): List<ListSections.Row<String, ContactSummary>> {
        val (called, never) = list.partition { (calls[it.id] ?: 0) > 0 }
        val out = ArrayList<ListSections.Row<String, ContactSummary>>(list.size + 1)
        called.sortedByDescending { calls[it.id] ?: 0 }.forEach { out += ListSections.Row.Item(it) }
        never.firstOrNull()?.let { out += ListSections.Row.Header(notCalled, it) }
        never.forEach { out += ListSections.Row.Item(it) }
        return out
    }

    /**
     * Grouped by company (one header per company, however its letters are cased or spaced), companies in [compare]'s
     * order, names in list order within each; contacts without one last, under [noCompany].
     */
    fun byCompany(
        list: List<ContactSummary>,
        company: Map<Long, String>,
        compare: Comparator<String>,
        noCompany: String,
    ): List<ListSections.Row<String, ContactSummary>> {
        fun keyOf(c: ContactSummary): String? = company[c.id]?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
        val groups = LinkedHashMap<String, Pair<String, MutableList<ContactSummary>>>()
        val none = ArrayList<ContactSummary>()
        for (c in list) {
            val shown = keyOf(c)
            if (shown == null) none += c else groups.getOrPut(shown.lowercase()) { shown to ArrayList() }.second += c
        }
        val out = ArrayList<ListSections.Row<String, ContactSummary>>(list.size + groups.size + 1)
        groups.values.sortedWith { a, b -> compare.compare(a.first, b.first) }.forEach { (shown, members) ->
            out += ListSections.Row.Header(shown, members.first())
            members.forEach { out += ListSections.Row.Item(it) }
        }
        none.firstOrNull()?.let { out += ListSections.Row.Header(noCompany, it) }
        none.forEach { out += ListSections.Row.Item(it) }
        return out
    }
}
