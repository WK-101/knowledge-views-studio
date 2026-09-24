package app.parley.common

import java.text.Normalizer

/** Accent- and case-insensitive substring search used by the contacts and history search boxes. */
object TextSearch {
    private val marks = Regex("\\p{Mn}+")

    fun normalize(s: String): String =
        marks.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

    /**
     * Matches if every query word is contained in the name, or the query digits appear in
     * one of the numbers, or the query is contained in one of the extra fields (emails, notes…).
     */
    fun matches(query: String, name: String, numbers: List<String> = emptyList(), extra: List<String> = emptyList()): Boolean {
        val q = normalize(query.trim())
        if (q.isEmpty()) return true
        val n = normalize(name)
        if (q.split(' ').filter { it.isNotEmpty() }.all { n.contains(it) }) return true
        val qd = PhoneNumbers.digits(q)
        if (qd.length >= 2 && qd.length * 2 >= q.count { !it.isWhitespace() } && numbers.any { PhoneNumbers.digits(it).contains(qd) }) return true
        return extra.any { normalize(it).contains(q) }
    }
}
