package app.parley.common.people

/**
 * Field-by-field three-way merge of an edit that was overtaken by a change made elsewhere: [base] is what the editor
 * loaded, [mine] what the user typed and [theirs] what the provider holds now. A field only one side changed takes
 * that side; a field both sides changed differently is a conflict for the user to pick. With no base (it wasn't kept,
 * for example after the process was stopped), every difference is a conflict.
 */
object ThreeWayMerge {
    enum class Side { MINE, THEIRS }

    sealed interface Result<out T> {
        data class Resolved<T>(val value: T, val side: Side) : Result<T>

        data class Conflict<T>(val mine: T, val theirs: T) : Result<T>
    }

    fun <T> merge(base: T?, mine: T, theirs: T, hasBase: Boolean = base != null): Result<T> = when {
        mine == theirs -> Result.Resolved(theirs, Side.THEIRS)
        hasBase && mine == base -> Result.Resolved(theirs, Side.THEIRS)
        hasBase && theirs == base -> Result.Resolved(mine, Side.MINE)
        else -> Result.Conflict(mine, theirs)
    }

    /**
     * The side each field takes: [picks] for conflicts (a conflict without a pick keeps [fallback]); the merge's own
     * answer for the rest.
     */
    fun <K, T> sides(fields: Map<K, Triple<T?, T, T>>, picks: Map<K, Side>, fallback: Side, hasBase: Boolean): Map<K, Side> =
        fields.mapValues { (k, v) ->
            when (val r = merge(v.first, v.second, v.third, hasBase)) {
                is Result.Resolved -> r.side
                is Result.Conflict -> picks[k] ?: fallback
            }
        }

    /**
     * Carries ids of [mine]'s rows over to the rebased edit: a row keeps its id while that row still exists in
     * [theirIds] (and no earlier row claimed it); otherwise it becomes a new row, so a save never writes to a row
     * that is gone.
     */
    fun <T> rebaseIds(mine: List<T>, theirIds: Set<Long>, id: (T) -> Long?, withId: (T, Long?) -> T): List<T> {
        val free = theirIds.toMutableSet()
        return mine.map { row ->
            val i = id(row)
            if (i != null && free.remove(i)) row else if (i == null) row else withId(row, null)
        }
    }
}
