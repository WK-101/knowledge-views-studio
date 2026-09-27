package app.parley.common

import java.text.Normalizer

/** Accent- and case-insensitive substring search used by the contacts and history search boxes. */
object TextSearch {
    private val marks = Regex("\\p{Mn}+")

    fun normalize(s: String): String =
        marks.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

    /** A query normalised once, to test many names against (see [TextSearchIndex]). */
    class Query(query: String) {
        /** The query, trimmed and normalised. */
        val folded: String = normalize(query.trim())
        val isEmpty: Boolean get() = folded.isEmpty()
        private val words = folded.split(' ').filter { it.isNotEmpty() }
        private val digits = PhoneNumbers.digits(folded)

        /** Mostly digits: also searched in the numbers. */
        private val byNumber = digits.length >= 2 && digits.length * 2 >= folded.count { !it.isWhitespace() }

        /**
         * [foldedName] is already [normalize]d; [numberDigits] are the numbers as [PhoneNumbers.digits]; [extra] fields
         * are raw text (normalised here, only when the name and numbers didn't match).
         */
        fun matchesPrepared(foldedName: String, numberDigits: List<String>, extra: List<String> = emptyList()): Boolean {
            if (folded.isEmpty()) return true
            if (words.all { foldedName.contains(it) }) return true
            if (byNumber && numberDigits.any { it.contains(digits) }) return true
            return extra.any { normalize(it).contains(folded) }
        }
    }

    /**
     * Matches if every query word is contained in the name, or the query digits appear in
     * one of the numbers, or the query is contained in one of the extra fields (emails, notes…).
     */
    fun matches(query: String, name: String, numbers: List<String> = emptyList(), extra: List<String> = emptyList()): Boolean {
        val q = Query(query)
        if (q.isEmpty) return true
        return q.matchesPrepared(normalize(name), LazyDigits(numbers), extra)
    }

    /** The numbers' digits, worked out only if the query is searched by number. */
    private class LazyDigits(private val numbers: List<String>) : AbstractList<String>() {
        override val size: Int get() = numbers.size
        override fun get(index: Int): String = PhoneNumbers.digits(numbers[index])
    }
}

/**
 * Items prepared once for [TextSearch] (names normalised, numbers reduced to digits), so a search per keystroke
 * normalises only the query. Build it again when the items change.
 */
class TextSearchIndex<T>(items: List<T>, name: (T) -> String, numbers: (T) -> List<String> = { emptyList() }) {
    private class Row<T>(val item: T, val name: String, val digits: List<String>)

    private val rows = items.map { Row(it, TextSearch.normalize(name(it)), numbers(it).map(PhoneNumbers::digits)) }

    val size: Int get() = rows.size

    /** The items matching [query] ([TextSearch.matches] rules), in their order; all of them for a blank query. */
    fun search(query: String): List<T> {
        val q = TextSearch.Query(query)
        if (q.isEmpty) return rows.map { it.item }
        return rows.filter { q.matchesPrepared(it.name, it.digits) }.map { it.item }
    }
}
