package app.parley.common.people

/**
 * The quiet line at the end of the Contacts list: how many are shown, in the words of what is shown. A search counts
 * results; a single label or account names it; any other filter says it is filtered.
 */
object ContactsFooter {
    sealed interface Line {
        val count: Int

        /** "12 contacts", or "12 contacts · 3 private" when private contacts are in the list. */
        data class All(override val count: Int, val private: Int = 0) : Line

        /** "4 results" while searching. */
        data class Results(override val count: Int) : Line

        /** "12 contacts in Family" (one label or one account). */
        data class In(override val count: Int, val name: String) : Line

        /** "5 unlabelled contacts". */
        data class Unlabelled(override val count: Int) : Line

        /** "7 contacts match the filter" (several labels, labels with an account, or any other filter). */
        data class Filtered(override val count: Int) : Line

        /** "3 private contacts" (the Private list). */
        data class Private(override val count: Int) : Line
    }

    /**
     * The line for [count] contacts shown under [query] and [filter]; [private] of them are private contacts (shown
     * in the same list). [privateList]: the list shows only private contacts. Null: nothing to count.
     */
    @Suppress("CyclomaticComplexMethod") // One decision table.
    fun line(count: Int, query: String, filter: LabelFilter, private: Int = 0, privateList: Boolean = false): Line? {
        if (count <= 0) return null
        return when {
            query.isNotBlank() -> Line.Results(count)
            privateList -> Line.Private(count)
            filter.isEmpty -> Line.All(count, private.coerceIn(0, count))
            !filter.fields.isEmpty -> Line.Filtered(count)
            filter.labels.size == 1 && !filter.unlabelled && filter.account == null -> Line.In(count, filter.labels.single())
            filter.labels.isEmpty() && !filter.unlabelled && filter.account != null -> Line.In(count, filter.account)
            filter.labels.isEmpty() && filter.unlabelled && filter.account == null -> Line.Unlabelled(count)
            else -> Line.Filtered(count)
        }
    }
}
