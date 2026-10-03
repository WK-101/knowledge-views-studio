package app.parley.common.people

/**
 * Contacts-tab filter: labels (by title, so the same label in two accounts is one label), "Unlabelled",
 * AND/OR combination of several labels, an optional account, and the field filters ([fields]). [matches] checks the
 * labels and account; the list checks [fields] against each contact's facets.
 */
data class LabelFilter(
    val labels: Set<String> = emptySet(),
    /** true = the contact must have every selected label (AND); false = any of them (OR). */
    val matchAll: Boolean = false,
    val unlabelled: Boolean = false,
    /** Account label ("Google · me@…"), or null for every account. */
    val account: String? = null,
    /** The other filters (country, company, has an email…), applied with these ([FieldFilter.matches]). */
    val fields: FieldFilter = FieldFilter(),
) {
    val isEmpty: Boolean get() = labels.isEmpty() && !unlabelled && account == null && fields.isEmpty

    fun matches(extra: PersonExtra?): Boolean {
        val have = extra?.labels.orEmpty()
        if (account != null && account !in extra?.accounts.orEmpty()) return false
        val labelOk = when {
            labels.isEmpty() && !unlabelled -> true
            labels.isEmpty() -> have.isEmpty()
            matchAll -> have.containsAll(labels)
            else -> have.any { it in labels } || (unlabelled && have.isEmpty())
        }
        return labelOk
    }

    fun toggle(label: String): LabelFilter = copy(labels = if (label in labels) labels - label else labels + label)

    /** Drops labels that no longer exist (renamed, merged or deleted). */
    fun retain(existing: Set<String>): LabelFilter = if (labels.all { it in existing }) this else copy(labels = labels.filter { it in existing }.toSet())
}

object LabelMerge {
    /**
     * Plans "Merge labels": every member of [sources] ends up in [target]. Returns the contact ids that must be
     * added to the target (the rest are already in it). Pure so the rule is testable: nobody is lost, nobody
     * is added twice.
     */
    fun toAdd(members: Map<String, Set<Long>>, sources: Set<String>, target: String): Set<Long> {
        val already = members[target].orEmpty()
        return sources.filter { it != target }.flatMap { members[it].orEmpty() }.toSet() - already
    }
}
