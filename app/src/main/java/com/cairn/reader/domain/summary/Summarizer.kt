package com.cairn.reader.domain.summary

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Extractive summarization, on-device — no model, no network, just the words already on the page.
 *
 * The pipeline is a small stack of classic-but-effective IR techniques:
 *  1. Robust sentence segmentation (abbreviation- and decimal-aware, so "Dr. Smith" or "3.14" don't
 *     split a sentence in two).
 *  2. TF-IDF sentence vectors (sublinear term frequency, smoothed inverse document frequency) so rare,
 *     informative words carry the weight and boilerplate ("the", "and") is discounted automatically.
 *  3. A TextRank pass — PageRank over a graph whose edges are the cosine similarity between those
 *     vectors — measuring how central each sentence is to the whole article.
 *  4. A blend of centrality with a lead-position prior (articles front-load the point) and a
 *     term-salience prior (density of informative words).
 *  5. MMR (Maximal Marginal Relevance) selection, which greedily takes the strongest sentence and
 *     then penalizes any candidate that merely repeats one already chosen — so the summary covers
 *     the article rather than restating its single most-central idea several times over.
 * The chosen sentences are returned in their original reading order.
 */
@Singleton
class Summarizer @Inject constructor() {

    /** Return up to [maxSentences] key sentences from [text], in their original order. */
    fun summarize(text: String, maxSentences: Int = 5): List<String> {
        val allSentences = splitSentences(text)
        if (allSentences.size <= maxSentences) return allSentences
        // Bound the O(n²) work on pathologically long inputs; the lead prior means early text matters
        // most, and this cap is far above a typical article's sentence count.
        val sentences = if (allSentences.size > MAX_SENTENCES) allSentences.take(MAX_SENTENCES) else allSentences
        val n = sentences.size

        // --- TF-IDF sentence vectors -------------------------------------------------------------
        val tokenized = sentences.map { tokens(it) }
        val df = HashMap<String, Int>()
        tokenized.forEach { toks -> toks.toHashSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val idf = HashMap<String, Double>(df.size)
        df.forEach { (term, freq) -> idf[term] = ln((n + 1.0) / (freq + 1.0)) + 1.0 }

        // Unit-normalized TF-IDF vector per sentence (sublinear tf: 1 + ln(count)).
        val vectors = tokenized.map { toks ->
            val counts = HashMap<String, Int>()
            toks.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            val v = HashMap<String, Double>(counts.size)
            counts.forEach { (term, c) -> v[term] = (1.0 + ln(c.toDouble())) * (idf[term] ?: 0.0) }
            val norm = sqrt(v.values.sumOf { it * it })
            if (norm > 0) v.mapValues { it.value / norm } else v
        }

        // --- Similarity graph (cosine over the unit vectors) -------------------------------------
        val sim = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val s = cosine(vectors[i], vectors[j])
            sim[i][j] = s; sim[j][i] = s
        }

        // --- TextRank (PageRank over the graph) --------------------------------------------------
        val d = 0.85
        var rank = DoubleArray(n) { 1.0 / n }
        val outSum = DoubleArray(n) { i -> sim[i].sum().takeIf { it > 0 } ?: 1e-9 }
        repeat(40) {
            val next = DoubleArray(n) { (1 - d) / n }
            for (i in 0 until n) for (j in 0 until n) {
                if (i != j && sim[i][j] > 0) next[i] += d * (sim[i][j] / outSum[j]) * rank[j]
            }
            rank = next
        }

        // --- Blend centrality with priors --------------------------------------------------------
        // Lead prior: the opening of a piece carries a decaying bonus (news / essays front-load).
        val position = DoubleArray(n) { i -> exp(-i.toDouble() / (n * 0.35 + 1.0)) }
        // Salience prior: mean IDF of a sentence's distinct terms rewards informative, specific text.
        val salience = DoubleArray(n) { i ->
            val terms = tokenized[i].toHashSet()
            if (terms.isEmpty()) 0.0 else terms.sumOf { idf[it] ?: 0.0 } / terms.size
        }
        val rankN = normalize(rank)
        val posN = normalize(position)
        val salN = normalize(salience)
        val relevance = DoubleArray(n) { i -> 0.60 * rankN[i] + 0.22 * posN[i] + 0.18 * salN[i] }

        // --- MMR selection (relevance minus redundancy) ------------------------------------------
        val lambda = 0.72
        val selected = ArrayList<Int>(maxSentences)
        val remaining = (0 until n).toMutableList()
        while (selected.size < maxSentences && remaining.isNotEmpty()) {
            var bestIdx = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (c in remaining) {
                val redundancy = selected.maxOfOrNull { sim[c][it] } ?: 0.0
                val score = lambda * relevance[c] - (1 - lambda) * redundancy
                if (score > bestScore) { bestScore = score; bestIdx = c }
            }
            if (bestIdx < 0) break
            selected.add(bestIdx)
            remaining.remove(bestIdx)
        }

        // Restore reading order.
        return selected.sorted().map { sentences[it] }
    }

    /** Split into sentences, honoring abbreviations and decimals so they don't break a sentence. */
    private fun splitSentences(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            sb.append(c)
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                val next = clean.getOrNull(i + 1)
                val after = clean.getOrNull(i + 2)
                // A real boundary: end punctuation, a space, then something that starts a sentence.
                val boundary = next == ' ' && after != null &&
                    (after.isUpperCase() || after.isDigit() || after in "\"'‘“")
                if (boundary && !endsWithAbbreviation(sb)) {
                    out.add(sb.toString().trim())
                    sb.setLength(0)
                }
            }
            i++
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.map { it.trim() }.filter { it.length in 30..500 && it.count { ch -> ch == ' ' } >= 4 }
    }

    /** True when the buffer's final word (before the closing punctuation) is a known abbreviation. */
    private fun endsWithAbbreviation(sb: StringBuilder): Boolean {
        var end = sb.length
        while (end > 0 && sb[end - 1] in ".!?…") end--
        var start = end
        while (start > 0 && (sb[start - 1].isLetterOrDigit() || sb[start - 1] == '.')) start--
        if (start >= end) return false
        val word = sb.substring(start, end).lowercase().replace(".", "")
        return word in ABBREVIATIONS
    }

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOP }

    /** Cosine similarity of two unit-normalized sparse vectors (iterate the smaller one). */
    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var dot = 0.0
        for ((term, w) in small) large[term]?.let { dot += w * it }
        return dot
    }

    /** Rescale to [0, 1]; a flat array maps to all-zeros (no differentiation). */
    private fun normalize(v: DoubleArray): DoubleArray {
        val min = v.minOrNull() ?: 0.0
        val max = v.maxOrNull() ?: 0.0
        val span = max - min
        return if (span <= 1e-12) DoubleArray(v.size) else DoubleArray(v.size) { (v[it] - min) / span }
    }

    private companion object {
        const val MAX_SENTENCES = 600

        val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "eg", "ie", "al",
            "inc", "ltd", "co", "corp", "dept", "fig", "no", "vol", "pp", "ca", "approx", "gen",
            "sen", "rep", "gov", "pres", "cf", "ph", "dvd", "us", "uk", "eu", "un", "am", "pm",
        )

        // Function words only — content nouns/verbs are left in so TF-IDF can weight them.
        val STOP = setOf(
            "the", "and", "for", "are", "but", "not", "you", "your", "yours", "with", "from",
            "this", "that", "have", "has", "had", "was", "were", "will", "what", "how", "why",
            "who", "whom", "can", "could", "all", "out", "about", "into", "onto", "over", "under",
            "more", "most", "than", "then", "they", "them", "their", "theirs", "its", "his", "her",
            "hers", "one", "our", "ours", "she", "him", "been", "being", "when", "where", "which",
            "would", "should", "shall", "there", "here", "said", "also", "such", "some", "any",
            "may", "might", "must", "these", "those", "him", "her", "does", "did", "done", "doing",
            "each", "both", "few", "own", "same", "too", "very", "just", "only", "even", "still",
            "because", "while", "during", "before", "after", "between", "among", "through", "upon",
            "against", "above", "below", "off", "down", "again", "once", "further", "other",
            "another", "yet", "nor", "either", "neither", "whether", "though", "although", "however",
            "therefore", "thus", "hence", "meanwhile", "moreover", "instead", "within", "without",
            "toward", "towards", "per", "via", "unto", "amongst", "whereas", "whilst",
        )
    }
}
