package app.parley.common.people

import java.text.CollationKey
import java.text.Collator

/**
 * Sorting names the way the phone's language does, without the cost of [Collator.compare] on every comparison. A
 * collator comparison does the language's work for both strings each time, about n·log n times per sort (300,000
 * times for 20,000 contacts); a [CollationKey] is made once per name and then compares as plain bytes.
 */
object Collation {
    /** Case- and accent-insensitive, as the lists sort. */
    fun primaryCollator(): Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    /**
     * A comparator over [collator] that [sortedBy] recognises and sorts with collation keys. Like the collator, not
     * thread-safe: one per flow that sorts.
     */
    class Order(private val collator: Collator = primaryCollator()) : Comparator<String> {
        override fun compare(a: String, b: String): Int = collator.compare(a, b)

        fun key(s: String): CollationKey = collator.getCollationKey(s)
    }

    /** [list] sorted by [name] in [order], stable (ties keep their order); with an [Order], by keys made once each. */
    fun <T> sortedBy(list: List<T>, order: Comparator<String>, name: (T) -> String): List<T> = when {
        list.size < 2 -> list
        order is Order -> list.map { order.key(name(it)) to it }.sortedBy { it.first }.map { it.second }
        else -> list.sortedWith { a, b -> order.compare(name(a), name(b)) }
    }
}
