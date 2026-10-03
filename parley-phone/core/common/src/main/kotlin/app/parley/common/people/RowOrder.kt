package app.parley.common.people

/**
 * The order of a contact's rows of one kind (its numbers, emails, addresses…). Android's contacts provider has no
 * order column: every app shows a kind's rows in the order the provider returns them, which is the order they were
 * inserted (their `_ID`). So an order chosen in the editor is kept by writing some rows again, in that order, and
 * read back sorted by `_ID`.
 */
object RowOrder {
    /**
     * The index row [index] swaps with to move one place up ([up]) or down among [shown] (the indices of a list that
     * one group of the editor shows: profiles and websites share one list), or null at the group's edge.
     */
    fun neighbour(shown: List<Int>, index: Int, up: Boolean): Int? {
        val at = shown.indexOf(index)
        if (at < 0) return null
        return shown.getOrNull(if (up) at - 1 else at + 1)
    }

    /** [list] with the rows at [a] and [b] swapped (unchanged when either is out of range). */
    fun <T> swap(list: List<T>, a: Int, b: Int): List<T> {
        if (a !in list.indices || b !in list.indices || a == b) return list
        return list.toMutableList().also { l ->
            val t = l[a]
            l[a] = l[b]
            l[b] = t
        }
    }

    /**
     * The saved rows to write again (delete, then insert at their place) so that reading the kind back by `_ID` gives
     * [ids]' order. [ids]: the rows as they will be saved, in the chosen order; a saved row by its id, a new row as
     * null (blank rows, which aren't saved, left out). New rows are inserted after every row kept, so the rows kept
     * are the longest start of the list whose ids already rise; every saved row after it is written again.
     *
     * A row in [locked] (read-only for its sync adapter) can't be written again; when the order would need that,
     * nothing is written again and the rows keep the provider's order.
     */
    fun rewrite(ids: List<Long?>, locked: Set<Long> = emptySet()): Set<Long> {
        var last = Long.MIN_VALUE
        var kept = 0
        for (id in ids) {
            if (id == null || id <= last) break
            last = id
            kept++
        }
        val again = ids.drop(kept).filterNotNull().toSet()
        return if (again.any { it in locked }) emptySet() else again
    }

    /** Whether the rows in [ids] can be put in another order: none of them is read-only ([locked]). */
    fun canReorder(ids: List<Long?>, locked: Set<Long>): Boolean = ids.none { it != null && it in locked }
}
