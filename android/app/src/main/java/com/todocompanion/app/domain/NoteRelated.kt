package com.todocompanion.app.domain

import kotlin.math.sqrt

/**
 * Wave T — "related notes" without a cloud. Joplin/Reflect lean on a server LLM for this; Kairo computes
 * it on-device and deterministically: a note is related to another when they share entities ([[links]],
 * #tags) and vocabulary. The score blends a TF cosine similarity over the body text with bonuses for
 * shared wiki-links and shared tags — all derivable from the note bodies alone, so no extra data, no
 * network, no model. Pure and unit-tested.
 */
object NoteRelated {
    data class Doc(val id: String, val title: String, val body: String)

    private val wiki = Regex("\\[\\[([^\\]\\n|]+)(?:\\|[^\\]\\n]*)?]]")
    private val tag = Regex("(?<![\\w#/])#([A-Za-z][\\w/-]*)")
    private val token = Regex("[A-Za-z][A-Za-z']{2,}")
    private val stop = setOf(
        "the", "and", "for", "are", "but", "not", "you", "your", "with", "this", "that", "from", "have",
        "has", "was", "were", "will", "would", "can", "could", "should", "into", "out", "our", "their",
        "them", "they", "its", "it's", "about", "which", "when", "what", "who", "whom", "how", "why",
        "all", "any", "some", "one", "two", "get", "got", "than", "then", "there", "here", "just", "like",
    )

    private fun links(body: String): Set<String> = wiki.findAll(body).map { it.groupValues[1].trim().lowercase() }.toSet()
    private fun tags(body: String): Set<String> = tag.findAll(body).map { it.groupValues[1].lowercase() }.toSet()

    private fun tf(doc: Doc): Map<String, Double> {
        val counts = HashMap<String, Int>()
        val text = (doc.title + " " + doc.body).lowercase()
        for (m in token.findAll(text)) { val w = m.value; if (w !in stop) counts[w] = (counts[w] ?: 0) + 1 }
        val total = counts.values.sum().coerceAtLeast(1).toDouble()
        return counts.mapValues { it.value / total }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0
        val (small, big) = if (a.size <= b.size) a to b else b to a
        for ((k, v) in small) big[k]?.let { dot += v * it }
        val na = sqrt(a.values.sumOf { it * it }); val nb = sqrt(b.values.sumOf { it * it })
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
    }

    data class Hit(val id: String, val title: String, val score: Double)

    /** Rank [all] (excluding [targetId]) by relatedness to the target. Only positive scores; highest first. */
    fun related(targetId: String, all: List<Doc>, limit: Int = 5): List<Hit> {
        val target = all.firstOrNull { it.id == targetId } ?: return emptyList()
        val tTf = tf(target); val tLinks = links(target.body); val tTags = tags(target.body)
        val tTitle = target.title.trim().lowercase()
        return all.asSequence()
            .filter { it.id != targetId }
            .map { d ->
                val sharedLinks = (links(d.body) intersect tLinks).size
                val sharedTags = (tags(d.body) intersect tTags).size
                // a note that links to the target's title (or vice-versa) is strongly related
                val titleLink = (tTitle.isNotEmpty() && links(d.body).contains(tTitle)) ||
                    (d.title.trim().lowercase().isNotEmpty() && tLinks.contains(d.title.trim().lowercase()))
                val score = cosine(tTf, tf(d)) + 0.4 * sharedLinks + 0.5 * sharedTags + (if (titleLink) 0.6 else 0.0)
                Hit(d.id, d.title, score)
            }
            .filter { it.score > 0.02 }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }
}
