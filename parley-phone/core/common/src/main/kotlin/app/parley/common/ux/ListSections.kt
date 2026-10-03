package app.parley.common.ux

import java.text.Normalizer
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Lists with section headers (the letters of Contacts, the days of Recents), worked out once per list change in a
 * view model instead of in the list's builder on every recomposition.
 */
object ListSections {
    sealed interface Row<out K, out T> {
        /** A header for [section]; [first] is the item it stands above. */
        data class Header<K, T>(val section: K, val first: T) : Row<K, T>
        data class Item<T>(val item: T) : Row<Nothing, T>
    }

    /** [items] with a header before the first one and before every item whose section differs from the one above. */
    fun <K, T> interleave(items: List<T>, sectionOf: (T) -> K): List<Row<K, T>> {
        val out = ArrayList<Row<K, T>>(items.size + items.size / 8 + 1)
        var last: Any? = NONE
        for (t in items) {
            val s = sectionOf(t)
            if (s != last) {
                last = s
                out += Row.Header(s, t)
            }
            out += Row.Item(t)
        }
        return out
    }

    /**
     * The fast-scroll targets: each section's first header, as a lazy-list index. [offset] is the index of the first
     * row (rows above the list: chips, "My card"…); each header takes one index, each item one more.
     */
    fun <K> firstRows(rows: List<Row<K, *>>, offset: Int): LinkedHashMap<K, Int> {
        val map = LinkedHashMap<K, Int>()
        rows.forEachIndexed { i, r ->
            if (r is Row.Header<K, *> && r.section !in map) map[r.section] = offset + i
        }
        return map
    }

    /** Where an item sits among the items of its section, for a section drawn as one card with segmented corners. */
    enum class Place(val first: Boolean, val last: Boolean) {
        ONLY(first = true, last = true),
        FIRST(first = true, last = false),
        MIDDLE(first = false, last = false),
        LAST(first = false, last = true),
        ;

        companion object {
            fun of(first: Boolean, last: Boolean): Place = when {
                first && last -> ONLY
                first -> FIRST
                last -> LAST
                else -> MIDDLE
            }
        }
    }

    /** Each row's [Place] in its section, index for index with [rows]; null for the headers. */
    fun places(rows: List<Row<*, *>>): List<Place?> = List(rows.size) { i ->
        if (rows[i] is Row.Header<*, *>) {
            null
        } else {
            Place.of(first = i == 0 || rows[i - 1] is Row.Header<*, *>, last = i == rows.size - 1 || rows[i + 1] is Row.Header<*, *>)
        }
    }

    /** Contacts: the letter a name is filed under (accents folded, "#" for digits and symbols). */
    fun letterOf(name: String): String {
        val c = name.firstOrNull { it.isLetterOrDigit() } ?: return "#"
        if (c.isDigit()) return "#"
        return Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().uppercaseChar().toString()
    }

    /** Recents: the local calendar day of [millis] (days since the epoch in [tz]), to group calls under a date. */
    fun localDay(millis: Long, tz: TimeZone): Long = TimeUnit.MILLISECONDS.toDays(millis + tz.getOffset(millis))

    private object NONE
}
