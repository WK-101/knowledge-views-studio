package app.parley.common

import java.text.Normalizer

/**
 * T9 (phone keypad) search. Every character of a name maps to exactly one keypad character so
 * that match positions can be used directly for highlighting.
 */
object T9 {
    private val latin = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4', 'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6', 'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8', 'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9',
    )
    private val special = mapOf('ß' to '7', 'æ' to '2', 'ø' to '6', 'ł' to '5', 'đ' to '3', 'œ' to '6', 'þ' to '8', 'ı' to '4')
    private val cyrillic = buildKeyMap("абвг", "дежз", "ийкл", "мноп", "рсту", "фхцч", "шщъы", "ьэюя")
    private val greek = buildKeyMap("αβγ", "δεζ", "ηθι", "κλμ", "νξο", "πρσς", "τυφ", "χψω")

    private fun buildKeyMap(vararg groups: String): Map<Char, Char> =
        groups.withIndex().flatMap { (i, g) -> g.map { it to ('2' + i) } }.toMap()

    /** Keypad digit for a character, or null for separators. Digits map to themselves. */
    fun digitFor(ch: Char): Char? {
        if (ch in '0'..'9') return ch
        val lower = ch.lowercaseChar()
        latin[lower]?.let { return it }
        special[lower]?.let { return it }
        cyrillic[lower]?.let { return it }
        if (lower == 'ё') return '3'
        greek[lower]?.let { return it }
        // Strip accents: é -> e, ñ -> n, å -> a ...
        val base = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).firstOrNull()
        if (base != null && base != lower) {
            latin[base]?.let { return it }
            greek[base]?.let { return it }
        }
        return null
    }

    /** Same length as [text]; non-mappable characters become ' '. */
    fun encode(text: String): String = buildString(text.length) {
        for (c in text) append(digitFor(c) ?: ' ')
    }

    data class Match(
        /** Higher is better. */
        val score: Int,
        /** Highlighted character ranges in the display name. */
        val nameRanges: List<IntRange>,
        /** Number that matched, if the match was on a number. */
        val matchedNumber: String?,
    )

    /** Pre-encoded name for fast repeated matching. */
    class Encoded(val name: String) {
        val t9 = encode(name)
        /** (startIndex, t9 token) for each word. */
        val tokens: List<Pair<Int, String>> = run {
            val out = ArrayList<Pair<Int, String>>()
            var i = 0
            while (i < t9.length) {
                if (t9[i] == ' ') { i++; continue }
                val start = i
                while (i < t9.length && t9[i] != ' ') i++
                out += start to t9.substring(start, i)
            }
            out
        }
        val initials: String = tokens.joinToString("") { it.second.take(1) }
        val compact: String = t9.replace(" ", "")
    }

    /**
     * Matches [query] (keypad digits) against an encoded name and a list of numbers.
     * Returns null when nothing matches.
     */
    fun match(query: String, name: Encoded, numbers: List<String>): Match? {
        if (query.isEmpty()) return null
        // 1. Prefix of a word (first word scores highest).
        name.tokens.forEachIndexed { idx, (start, tok) ->
            if (tok.startsWith(query)) {
                return Match(1000 - idx * 10 - (tok.length - query.length).coerceAtMost(9), listOf(start until start + query.length), null)
            }
        }
        // 2. Query spans several consecutive words ("johnsm" for "John Smith").
        for (t in name.tokens.indices) {
            val ranges = spanMatch(query, name.tokens, t)
            if (ranges != null) return Match(900 - t * 10, ranges, null)
        }
        // 3. Initials ("js" for "John Smith").
        if (query.length >= 2 && name.initials.startsWith(query)) {
            val ranges = name.tokens.take(query.length).map { (s, _) -> s..s }
            return Match(800, ranges, null)
        }
        // 4. Number prefix / substring.
        for (n in numbers) {
            val d = PhoneNumbers.clean(n).removePrefix("+")
            if (d.startsWith(query)) return Match(700, emptyList(), n)
        }
        for (n in numbers) {
            val d = PhoneNumbers.digits(n)
            if (query.length >= 3 && d.contains(query)) return Match(600, emptyList(), n)
        }
        // 5. Substring inside a word ("stone" in "Kingstone").
        if (query.length >= 3) {
            name.tokens.forEach { (start, tok) ->
                val at = tok.indexOf(query)
                if (at > 0) return Match(500, listOf(start + at until start + at + query.length), null)
            }
        }
        return null
    }

    private fun spanMatch(query: String, tokens: List<Pair<Int, String>>, from: Int): List<IntRange>? {
        var rest = query
        val ranges = ArrayList<IntRange>()
        var t = from
        while (rest.isNotEmpty() && t < tokens.size) {
            val (start, tok) = tokens[t]
            val common = tok.commonPrefixWith(rest).length
            if (common == 0) return null
            if (common < tok.length && common < rest.length) return null
            ranges += start until start + common
            rest = rest.substring(common)
            t++
        }
        return if (rest.isEmpty() && ranges.size > 1) ranges else null
    }
}
