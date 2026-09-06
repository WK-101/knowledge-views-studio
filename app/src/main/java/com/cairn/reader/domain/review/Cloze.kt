package com.cairn.reader.domain.review

/**
 * Turns a highlight into a fill-in-the-blank recall prompt — deterministically, no model. It blanks
 * the single most "load-bearing" word (a proper noun, number, or the longest content word), which
 * is usually the thing worth remembering. Returns null when the quote is too short or has no good
 * candidate, so the caller can fall back to a plain "recall this highlight" prompt.
 */
object Cloze {
    data class Card(val prompt: String, val answer: String)

    private val STOP = setOf(
        "the", "and", "for", "are", "but", "not", "you", "your", "with", "from", "this", "that",
        "have", "has", "was", "will", "what", "how", "why", "who", "can", "all", "new", "out",
        "about", "into", "over", "more", "than", "then", "they", "them", "its", "his", "her",
        "one", "our", "she", "him", "had", "were", "been", "when", "where", "which", "would",
        "there", "their", "said", "also", "such", "some", "any", "may", "these", "those", "been",
    )

    fun of(quote: String): Card? {
        val q = quote.trim()
        // Tokenize keeping positions; a token is a run of letters/digits (with internal apostrophes).
        val regex = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")
        val matches = regex.findAll(q).toList()
        if (matches.size < 5) return null // too short to hide a word and still cue recall

        data class Cand(val range: IntRange, val text: String, val score: Int)
        val cands = matches.mapIndexedNotNull { idx, m ->
            val t = m.value
            val lower = t.lowercase().trim('\'', '’', '-')
            if (lower.length < 4 || lower in STOP) return@mapIndexedNotNull null
            val firstWord = idx == 0
            val capitalized = t[0].isUpperCase() && !firstWord      // proper-noun-ish, not sentence start
            val hasDigit = t.any { it.isDigit() }
            val score = t.length + (if (capitalized) 8 else 0) + (if (hasDigit) 10 else 0)
            Cand(m.range, t, score)
        }
        val pick = cands.maxByOrNull { it.score } ?: return null
        val blank = " ____ " // em-spaced blank
        val prompt = q.substring(0, pick.range.first) + blank + q.substring(pick.range.last + 1)
        return Card(prompt.trim(), pick.text)
    }
}
