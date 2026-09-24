package app.parley.common

import java.text.BreakIterator
import java.util.Locale

/**
 * F27: avatar initials by user-perceived character (grapheme cluster), never half a surrogate pair: "𝒜lice" gives
 * "𝒜", "Élodie" written with a combining accent keeps the accent, and a name starting with an emoji is skipped
 * like any word that doesn't start with a letter.
 */
object Initials {
    fun of(name: String, locale: Locale = Locale.getDefault()): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && Character.isLetter(it.codePointAt(0)) }
        return when {
            words.isEmpty() -> ""
            words.size == 1 -> upper(firstGrapheme(words[0]), locale)
            else -> upper(firstGrapheme(words.first()), locale) + upper(firstGrapheme(words.last()), locale)
        }
    }

    /** The first grapheme cluster of [s] (a letter with its combining marks, a whole surrogate pair…). */
    fun firstGrapheme(s: String): String {
        if (s.isEmpty()) return ""
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.next().takeIf { e -> e != BreakIterator.DONE && e > 0 } ?: Character.charCount(s.codePointAt(0))
        // Never cut a surrogate pair, whatever the break iterator says.
        val safeEnd = if (end < s.length && Character.isLowSurrogate(s[end])) end + 1 else end
        return s.substring(0, safeEnd.coerceAtMost(s.length))
    }

    /** Upper case unless that changes the length ("ß" stays one letter). */
    private fun upper(g: String, locale: Locale): String = g.uppercase(locale).takeIf { it.length == g.length } ?: g
}
