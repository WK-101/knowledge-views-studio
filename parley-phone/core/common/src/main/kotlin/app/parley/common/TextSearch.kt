package app.parley.common

import app.parley.common.people.ContactSearch
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

        /** Mostly digits and no letters: the whole query is also searched in the numbers. */
        private val byNumber = digits.length >= 2 && folded.none { it.isLetter() } &&
            digits.length * 2 >= folded.count { !it.isWhitespace() }

        /** Each word's digits when it is a number ("912", "+351"), else null: such a word may be found in a number. */
        private val wordDigits: List<String?> = words.map { w ->
            if (w.length >= 2 && w.all { it in '0'..'9' || it == '+' }) PhoneNumbers.digits(w) else null
        }

        /**
         * [foldedName] is already [normalize]d; [numberDigits] are the numbers as [PhoneNumbers.digits]; [extra] fields
         * are raw text (normalised here, only when the name and numbers didn't match).
         */
        fun matchesPrepared(foldedName: String, numberDigits: List<String>, extra: List<String> = emptyList()): Boolean {
            if (folded.isEmpty()) return true
            // Every word is needed: in the name, or (a word of digits) in a number. "ana 912" is Ana whose number holds 912.
            if (words.indices.all { i -> foldedName.contains(words[i]) || wordDigits[i]?.let { d -> numberDigits.any { it.contains(d) } } == true }) {
                return true
            }
            if (byNumber && numberDigits.any { it.contains(digits) }) return true
            return extra.any { normalize(it).contains(folded) }
        }
    }

    /**
     * Matches if every query word is contained in the name (or, a word of digits, in a number), or a query without
     * letters appears in one of the numbers, or the query is contained in one of the extra fields (emails, notes…).
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
 * Items prepared once for search by name and number with the Contacts search's engine ([ContactSearch]): names folded,
 * numbers in their national and international digit forms, so a search per keystroke folds only the query. The
 * keypad's header search uses it. Build it again when the items change; [region] is the phone's country.
 */
class TextSearchIndex<T>(items: List<T>, name: (T) -> String, numbers: (T) -> List<String> = { emptyList() }, region: String? = null) {
    private class Row<T>(val item: T, val doc: ContactSearch.Doc)

    private val rows = items.mapIndexed { i, item ->
        val b = ContactSearch.Builder(i.toLong(), region)
        b.name(name(item))
        numbers(item).forEach { b.number(it) }
        Row(item, b.build())
    }

    val size: Int get() = rows.size

    /** The items whose name holds every word of [query], or whose numbers hold its digits; all of them for a blank query. */
    fun search(query: String): List<T> {
        val q = ContactSearch.Query(query)
        if (q.isEmpty) return rows.map { it.item }
        return rows.filter { ContactSearch.match(q, it.doc) != null }.map { it.item }
    }
}
