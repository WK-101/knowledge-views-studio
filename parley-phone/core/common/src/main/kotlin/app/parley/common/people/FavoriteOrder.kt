package app.parley.common.people

import app.parley.common.ContactSummary
import java.text.Collator

enum class FavoriteSort(val title: String) { CUSTOM("Custom"), NAME("A–Z"), MOST_CALLED("Most called") }

/** Ordering of the Favorites grid. Custom order is stored as lookup keys, which survive re-aggregation. */
object FavoriteOrder {

    fun sort(
        favorites: List<ContactSummary>,
        sort: FavoriteSort,
        customOrder: List<String>,
        callCounts: Map<Long, Int> = emptyMap(),
        collator: Comparator<String> = Collator.getInstance().apply { strength = Collator.PRIMARY }.let { c -> Comparator { a, b -> c.compare(a, b) } },
    ): List<ContactSummary> {
        val byName = favorites.sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
        return when (sort) {
            FavoriteSort.NAME -> byName
            FavoriteSort.MOST_CALLED -> byName.sortedByDescending { callCounts[it.id] ?: 0 }
            FavoriteSort.CUSTOM -> {
                val rank = customOrder.withIndex().associate { (i, k) -> k to i }
                // Favourites not yet placed (starred elsewhere) go after the placed ones, alphabetically.
                byName.sortedBy { rank[it.lookupKey] ?: Int.MAX_VALUE }
            }
        }
    }

    /** New custom order after dragging the item at [from] to [to] in the currently shown [keys]. */
    fun move(keys: List<String>, from: Int, to: Int): List<String> {
        if (from !in keys.indices || to !in keys.indices || from == to) return keys
        val m = keys.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        return m
    }

    /** Grid columns after a pinch: zooming in (scale > 1) shows fewer, larger tiles. */
    fun columnsAfterPinch(current: Int, scale: Float, min: Int = 2, max: Int = 6): Int = when {
        scale > 1.25f -> (current - 1).coerceAtLeast(min)
        scale < 0.8f -> (current + 1).coerceAtMost(max)
        else -> current
    }
}
