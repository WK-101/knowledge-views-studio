package app.parley.common

import java.text.Normalizer

/**
 * T9 (phone keypad) search. Every character of a name maps to at most one keypad character, and each encoded
 * character remembers its position in the name, so match positions can be used directly for highlighting.
 *
 * Latin letters are always searchable. A [KeypadLayout] decides where the letters of a second alphabet sit; letters
 * of other scripts still get a key from a built-in fallback so no name becomes unsearchable.
 *
 * In queries `0` stands for a space and `1` for punctuation between words ("John Smith" = 5646**0**76484,
 * "O'Brien" = 6**1**27436).
 */
object T9 {
    private val latin = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4', 'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6', 'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8', 'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9',
    )
    private val special = mapOf('ß' to '7', 'æ' to '2', 'ø' to '6', 'ł' to '5', 'đ' to '3', 'œ' to '6', 'þ' to '8', 'ı' to '4')

    /**
     * Letters of other Cyrillic alphabets (Ukrainian, Belarusian, Serbian, Macedonian…) that aren't in the Russian
     * layout, placed on the key of the Russian letter they sound like. Used when the chosen layout has no key for them.
     */
    private val cyrillicExtra = mapOf(
        'і' to '4', 'ї' to '4', 'ј' to '4', 'ѝ' to '4', 'ӣ' to '4', 'љ' to '4', 'ќ' to '4', // like и / й / л / к
        'є' to '3', 'ђ' to '3', 'ѕ' to '3', // like е / д / з
        'ґ' to '2', 'ѓ' to '2', // like г
        'ў' to '6', 'ӯ' to '6', // like у
        'њ' to '5', // like н
        'ћ' to '7', 'џ' to '7', // like ч
    )

    /** Fallback order for letters the chosen layout doesn't cover. The scripts don't overlap, except Cyrillic. */
    private val fallbacks = listOf(
        KeypadLayout.RUSSIAN.keyMap, cyrillicExtra, KeypadLayout.GREEK.keyMap, KeypadLayout.HEBREW.keyMap, KeypadLayout.ARABIC.keyMap,
    )

    /** ASCII digit for any decimal digit (Arabic-Indic ٣, Persian ۳, full-width ３…), or null. */
    fun asciiDigit(ch: Char): Char? {
        if (ch in '0'..'9') return ch
        if (!Character.isDigit(ch)) return null
        val v = Character.digit(ch, 10)
        return if (v in 0..9) '0' + v else null
    }

    /**
     * Characters that are part of a word but have no key: combining accents, Arabic vowel marks (harakat),
     * tatweel, zero-width joiners. They neither split words nor take part in matching.
     */
    fun isIgnorable(ch: Char): Boolean {
        if (ch == 'ـ') return true
        return when (Character.getType(ch).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK, Character.FORMAT -> true
            else -> false
        }
    }

    /** Keypad digit for a character, or null for separators. Digits map to themselves. */
    fun digitFor(ch: Char, layout: KeypadLayout = KeypadLayout.LATIN): Char? {
        asciiDigit(ch)?.let { return it }
        val lower = ch.lowercaseChar()
        latin[lower]?.let { return it }
        layout.keyMap[lower]?.let { return it }
        special[lower]?.let { return it }
        for (m in fallbacks) m[lower]?.let { return it }
        // Strip accents: é -> e, ñ -> n, å -> a, й stays й (it has its own key) ...
        val base = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).firstOrNull()
        if (base != null && base != lower) {
            latin[base]?.let { return it }
            layout.keyMap[base]?.let { return it }
            for (m in fallbacks) m[base]?.let { return it }
        }
        return null
    }

    /** Same length as [text]; non-mappable characters (and ignorable marks) become ' '. */
    fun encode(text: String, layout: KeypadLayout = KeypadLayout.LATIN): String = buildString(text.length) {
        for (c in text) append(if (isIgnorable(c)) ' ' else digitFor(c, layout) ?: ' ')
    }

    data class Match(
        /** Higher is better. */
        val score: Int,
        /** Highlighted character ranges in the display name. */
        val nameRanges: List<IntRange>,
        /** Number that matched, if the match was on a number. */
        val matchedNumber: String?,
    )

    /** One word of a name: its keypad code and, for each code character, the index of the name character. */
    class Token(
        val code: String,
        private val positions: IntArray,
        /** Kind of gap before this word: [GAP_SPACE], [GAP_PUNCT] or [GAP_NONE] for the first word. */
        val gapBefore: Char,
    ) {
        val start: Int get() = positions[0]

        /** Name range covering the first [length] code characters. */
        fun range(length: Int, from: Int = 0): IntRange = positions[from]..positions[from + length - 1]
    }

    const val GAP_NONE = '\u0000'
    const val GAP_SPACE = ' '
    const val GAP_PUNCT = '.'

    /** Splits [name] into keypad words. */
    fun tokenize(name: String, layout: KeypadLayout = KeypadLayout.LATIN): List<Token> {
        val out = ArrayList<Token>()
        val code = StringBuilder()
        val pos = ArrayList<Int>()
        var gap = GAP_NONE
        var pendingGap = GAP_NONE
        fun flush() {
            if (code.isEmpty()) return
            out += Token(code.toString(), pos.toIntArray(), gap)
            code.setLength(0)
            pos.clear()
        }
        for ((i, c) in name.withIndex()) {
            if (isIgnorable(c)) continue
            val d = digitFor(c, layout)
            if (d == null) {
                flush()
                // A gap containing any whitespace is a space; otherwise it's punctuation ("O'Brien", "Jean-Luc").
                pendingGap = if (c.isWhitespace() || pendingGap == GAP_SPACE) GAP_SPACE else GAP_PUNCT
                continue
            }
            if (code.isEmpty()) {
                gap = if (out.isEmpty()) GAP_NONE else pendingGap
                pendingGap = GAP_NONE
            }
            code.append(d)
            pos += i
        }
        flush()
        return out
    }

    /**
     * Pre-encoded name for fast repeated matching.
     *
     * @param syllables for names in Chinese, Japanese or Korean: the romanised reading of each name character
     *   (same length as [name], null for characters without one). Enables [SyllableMatcher] matching.
     * @param phonetic the contact's phonetic name, already in Latin letters.
     */
    class Encoded(
        val name: String,
        val layout: KeypadLayout = KeypadLayout.LATIN,
        syllables: List<String?>? = null,
        phonetic: String? = null,
    ) {
        val tokens: List<Token> = tokenize(name, layout)
        val initials: String = tokens.joinToString("") { it.code.take(1) }

        /** Keypad units for syllable matching: one per romanised character, one per other word. */
        internal val units: List<SyllableMatcher.Unit>? = syllables?.let { buildUnits(name, it, layout) }

        /** Phonetic name as its own encoded name (matches without highlighting). */
        internal val phoneticName: Encoded? = phonetic?.trim()?.takeIf { it.isNotEmpty() && it != name }?.let { Encoded(it, layout) }
    }

    private fun buildUnits(name: String, syllables: List<String?>, layout: KeypadLayout): List<SyllableMatcher.Unit>? {
        if (syllables.none { !it.isNullOrEmpty() }) return null
        val out = ArrayList<SyllableMatcher.Unit>()
        val word = StringBuilder()
        val wordPos = ArrayList<Int>()
        fun flushWord() {
            if (word.isNotEmpty()) out += SyllableMatcher.Unit(word.toString(), wordPos.toIntArray())
            word.setLength(0)
            wordPos.clear()
        }
        for ((i, c) in name.withIndex()) {
            val syl = syllables.getOrNull(i)
            if (!syl.isNullOrEmpty()) {
                flushWord()
                val code = buildString { for (ch in syl) digitFor(ch)?.let { append(it) } }
                if (code.isNotEmpty()) out += SyllableMatcher.Unit(code, IntArray(code.length) { i })
                continue
            }
            if (isIgnorable(c)) continue
            val d = digitFor(c, layout)
            if (d == null) { flushWord(); continue }
            word.append(d)
            wordPos += i
        }
        flushWord()
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Matches [query] (keypad digits) against an encoded name and a list of numbers.
     * Returns null when nothing matches.
     *
     * Matching is prefix-closed from 3 digits on: if a query of 3+ digits matches, every shorter query of 3+ digits
     * that it extends matches too. [DialSearch] relies on this to narrow previous results.
     */
    fun match(query: String, name: Encoded, numbers: List<String>): Match? {
        if (query.isEmpty()) return null
        val tokens = name.tokens
        // 1. Prefix of a word (first word scores highest).
        tokens.forEachIndexed { idx, tok ->
            if (tok.code.startsWith(query)) {
                return Match(1000 - idx * 10 - (tok.code.length - query.length).coerceAtMost(9), listOf(tok.range(query.length)), null)
            }
        }
        // 2. Query spans several consecutive words ("johnsm" for "John Smith").
        for (t in tokens.indices) {
            val ranges = spanMatch(query, tokens, t)
            if (ranges != null) return Match(900 - t * 10, ranges, null)
        }
        // 2b. 0 and 1 typed as the space or punctuation between words ("jo0sm", "o1brien").
        if (query[0] != '0' && query[0] != '1' && (query.contains('0') || query.contains('1'))) {
            for (t in tokens.indices) {
                val ranges = separatorMatch(query, tokens, t)
                if (ranges != null) return Match(880 - t * 10, ranges, null)
            }
        }
        // 3. Initials ("js" for "John Smith").
        if (query.length >= 2 && name.initials.startsWith(query)) {
            val ranges = tokens.take(query.length).map { it.range(1) }
            return Match(800, ranges, null)
        }
        // 3b. Chinese, Japanese and Korean names by their romanised syllables (initials or whole syllables).
        name.units?.let { units ->
            SyllableMatcher.match(query, units)?.let { r ->
                return Match(850 - r.firstUnit * 10, r.ranges, null)
            }
        }
        // 3c. The contact's phonetic name.
        name.phoneticName?.let { ph ->
            match(query, ph, emptyList())?.let { m -> if (m.matchedNumber == null) return Match(m.score - 150, emptyList(), null) }
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
            tokens.forEach { tok ->
                val at = tok.code.indexOf(query)
                if (at > 0) return Match(500, listOf(tok.range(query.length, at)), null)
            }
        }
        return null
    }

    private fun spanMatch(query: String, tokens: List<Token>, from: Int): List<IntRange>? {
        var rest = query
        val ranges = ArrayList<IntRange>()
        var t = from
        while (rest.isNotEmpty() && t < tokens.size) {
            val tok = tokens[t]
            val common = tok.code.commonPrefixWith(rest).length
            if (common == 0) return null
            if (common < tok.code.length && common < rest.length) return null
            ranges += tok.range(common)
            rest = rest.substring(common)
            t++
        }
        return if (rest.isEmpty() && ranges.size > 1) ranges else null
    }

    /**
     * Query split at 0 (space) and 1 (punctuation): each part must start consecutive words, and the gap before each
     * following word must be of the typed kind. A trailing separator only requires that another word follows.
     */
    private fun separatorMatch(query: String, tokens: List<Token>, from: Int): List<IntRange>? {
        val ranges = ArrayList<IntRange>()
        var t = from
        var i = 0
        var gap = GAP_NONE
        while (i < query.length) {
            val end = query.indexOfAny(charArrayOf('0', '1'), i).let { if (it < 0) query.length else it }
            val part = query.substring(i, end)
            if (t >= tokens.size) return null
            val tok = tokens[t]
            if (gap != GAP_NONE && tok.gapBefore != gap) return null
            // Two separators in a row ("00") never match: every part needs letters.
            if (part.isEmpty() || !tok.code.startsWith(part)) return null
            ranges += tok.range(part.length)
            if (end == query.length) break
            gap = if (query[end] == '0') GAP_SPACE else GAP_PUNCT
            t++
            i = end + 1
            // Trailing separator: the next word must exist and follow the right kind of gap.
            if (i == query.length) {
                if (t >= tokens.size || tokens[t].gapBefore != gap) return null
            }
        }
        return ranges.takeIf { it.isNotEmpty() }
    }
}
