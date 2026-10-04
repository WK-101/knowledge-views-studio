package app.parley.common.recall

import app.parley.common.PhoneIdentity
import app.parley.common.people.ContactSearch

/**
 * Recall's text helpers, with the Contacts search's folding ([ContactSearch.fold]): which parts of a shown text
 * matched (to highlight them), a short excerpt of a long note around its match, and whether a number holds the
 * query's digits.
 */
object RecallText {
    /** Characters of a note shown around its match. */
    const val EXCERPT = 120

    /** Characters kept before the match when a note is cut. */
    private const val LEAD = 32

    /** Whether [text] holds every word of [q] (folded). */
    fun containsAll(q: ContactSearch.Query, text: String): Boolean {
        if (q.isEmpty) return true
        val f = ContactSearch.fold(text)
        return q.words.all { f.contains(it) }
    }

    /** Whether [number] holds the query's digits (a whole number typed, or a word of digits). */
    fun numberMatches(q: ContactSearch.Query, number: String): Boolean {
        val d = PhoneIdentity.digits(number)
        if (d.isEmpty()) return false
        return q.digitForms.any { d.contains(it) } || q.wordDigits.any { forms -> forms?.any { d.contains(it) } == true }
    }

    /**
     * The ranges of [text] that hold a word of [q], accents and case ignored, merged where they touch; digits are
     * found through spaces and dashes ("912 345" in "+351 912-345 678").
     */
    fun marks(q: ContactSearch.Query, text: String): List<IntRange> {
        if (q.isEmpty || text.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        // Folded text with, for each of its characters, the index of the character it came from.
        val sb = StringBuilder(text.length)
        val from = ArrayList<Int>(text.length)
        text.forEachIndexed { i, c ->
            val f = ContactSearch.fold(c.toString())
            for (ch in f) {
                sb.append(ch)
                from += i
            }
        }
        val folded = sb.toString()
        for (w in q.words) {
            var at = folded.indexOf(w)
            while (at >= 0 && w.isNotEmpty()) {
                ranges += from[at]..from[at + w.length - 1]
                at = folded.indexOf(w, at + w.length)
            }
        }
        // Digits, skipping what numbers are written with.
        val digitAt = text.indices.filter { text[it].isDigit() }
        if (digitAt.isNotEmpty()) {
            val digits = String(CharArray(digitAt.size) { text[digitAt[it]] })
            val forms = q.digitForms + q.wordDigits.filterNotNull().flatten()
            for (d in forms.filter { it.length >= 2 }) {
                var at = digits.indexOf(d)
                while (at >= 0) {
                    ranges += digitAt[at]..digitAt[at + d.length - 1]
                    at = digits.indexOf(d, at + d.length)
                }
            }
        }
        return merge(ranges)
    }

    /** [ranges] sorted, with those that overlap or touch joined. */
    fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted.first()
        for (r in sorted.drop(1)) {
            if (r.first <= cur.last + 1) {
                cur = cur.first..maxOf(cur.last, r.last)
            } else {
                out += cur
                cur = r
            }
        }
        out += cur
        return out
    }

    /**
     * [text] on one line, cut to about [EXCERPT] characters around the first word of [q] it holds, with "…" where it
     * was cut.
     */
    fun excerpt(q: ContactSearch.Query, text: String): String {
        val line = text.replace('\n', ' ').trim()
        if (line.length <= EXCERPT) return line
        val first = marks(q, line).firstOrNull()?.first ?: 0
        var start = (first - LEAD).coerceAtLeast(0)
        // From the start of a word.
        if (start > 0) line.indexOf(' ', start).takeIf { it in start until first }?.let { start = it + 1 }
        val end = (start + EXCERPT).coerceAtMost(line.length)
        return (if (start > 0) "…" else "") + line.substring(start, end).trim() + (if (end < line.length) "…" else "")
    }
}
