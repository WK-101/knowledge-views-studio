package app.parley.common.people

import app.parley.common.ContactSummary

/**
 * What the Contacts list runs on every keystroke (after a short debounce): the label, account and field filters, the
 * search over every field ([ContactSearch.match]) with the field that explains each match, and, with nicknames shown,
 * the renamed list sorted again. Pure, so its speed is measured as the app runs it (ContactSearchSpeedTest).
 */
object ContactListSearch {
    /** One listed contact, prepared once per list change: its [name] folded and its search [doc]. */
    class Entry(val contact: ContactSummary, val name: String, val doc: ContactSearch.Doc)

    /** The contacts shown, in order, and the field that explains each one found by another field than the name or number. */
    class Result(val shown: List<ContactSummary>, val explained: Map<Long, ContactSearch.Field>)

    @Suppress("LongParameterList") // The list's own inputs.
    fun run(
        list: List<Entry>,
        query: ContactSearch.Query,
        filter: LabelFilter,
        extras: Map<Long, PersonExtra>,
        temporary: Set<Long>,
        preferNickname: Boolean,
        order: Comparator<String>,
    ): Result {
        val labelsOn = !(filter.labels.isEmpty() && !filter.unlabelled && filter.account == null)
        val fields = filter.fields
        val explained = HashMap<Long, ContactSearch.Field>()
        var shown = list.mapNotNull { p ->
            val ct = p.contact
            if (labelsOn && !filter.matches(extras[ct.id])) return@mapNotNull null
            if (!fields.isEmpty && !fields.matches(p.doc.facets, photo = ct.photoUri != null, temporary = ct.id in temporary)) return@mapNotNull null
            val field = ContactSearch.match(query, p.doc, p.name) ?: return@mapNotNull null
            if (ContactSearch.explains(field)) explained[ct.id] = field
            ct
        }
        if (preferNickname) {
            shown = Collation.sortedBy(shown.map { ct -> NameOrder.renamed(ct, SecondLines.displayName(ct, extras[ct.id], true)) }, order) { it.sortName }
        }
        return Result(shown, explained)
    }
}
