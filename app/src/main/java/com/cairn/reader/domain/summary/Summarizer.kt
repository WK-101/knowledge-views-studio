package com.cairn.reader.domain.summary

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Extractive summarization, on-device — a TextRank-style pass that scores each sentence by how
 * central it is to the whole article (a PageRank over a sentence-similarity graph) and returns the
 * few most representative ones, in reading order. No model, no network; just the words already here.
 */
@Singleton
class Summarizer @Inject constructor() {

    /** Return up to [maxSentences] key sentences from [text], in their original order. */
    fun summarize(text: String, maxSentences: Int = 5): List<String> {
        val sentences = splitSentences(text)
        if (sentences.size <= maxSentences) return sentences
        val tokenized = sentences.map { tokens(it) }
        // Build a similarity matrix (sparse-ish): overlap normalized by length, TextRank's classic weight.
        val n = sentences.size
        val sim = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val s = similarity(tokenized[i], tokenized[j])
            sim[i][j] = s; sim[j][i] = s
        }
        // PageRank over the sentence graph.
        val d = 0.85
        var rank = DoubleArray(n) { 1.0 / n }
        val outSum = DoubleArray(n) { i -> sim[i].sum().takeIf { it > 0 } ?: 1e-9 }
        repeat(30) {
            val next = DoubleArray(n) { (1 - d) / n }
            for (i in 0 until n) for (j in 0 until n) {
                if (i != j && sim[i][j] > 0) next[i] += d * (sim[i][j] / outSum[j]) * rank[j]
            }
            rank = next
        }
        // Take the top-ranked sentences, then restore reading order.
        val topIdx = (0 until n).sortedByDescending { rank[it] }.take(maxSentences).sorted()
        return topIdx.map { sentences[it] }
    }

    private fun splitSentences(text: String): List<String> =
        text.replace(Regex("\\s+"), " ").trim()
            .split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"'\\u2018\\u201C])"))
            .map { it.trim() }
            .filter { it.length in 40..400 && it.count { c -> c == ' ' } >= 5 }

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOP }

    /** TextRank sentence similarity: shared words normalized by the log of each length. */
    private fun similarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val setB = b.toHashSet()
        val overlap = a.toHashSet().count { it in setB }.toDouble()
        val norm = (kotlin.math.ln((a.size + 1).toDouble()) + kotlin.math.ln((b.size + 1).toDouble()))
        return if (norm == 0.0) 0.0 else overlap / norm
    }

    private companion object {
        val STOP = setOf(
            "the", "and", "for", "are", "but", "not", "you", "your", "with", "from", "this", "that",
            "have", "has", "was", "will", "what", "how", "why", "who", "can", "all", "new", "out",
            "about", "into", "over", "more", "than", "then", "they", "them", "its", "his", "her",
            "one", "our", "she", "him", "had", "were", "been", "when", "where", "which", "would",
            "there", "their", "said", "also", "such", "some", "any", "may", "these", "those",
        )
    }
}
