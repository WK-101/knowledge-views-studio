package com.cairn.reader.data.repo

import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemText
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.sqrt

/** A related article with a similarity score (0..1). */
data class RelatedItem(val id: String, val title: String, val sourceTitle: String?, val score: Double)

/** A discovered topic cluster: a label built from its top shared terms and the items in it. */
data class TopicCluster(val label: String, val items: List<ItemText>)

/**
 * On-device "semantic" relevance — no model, no embeddings server, just classic TF-IDF vectors and
 * cosine similarity over the words in titles and excerpts. Enough to surface genuinely related
 * reading and to cluster the library into topics, entirely privately.
 */
@Singleton
class SemanticRepository @Inject constructor(
    private val itemDao: ItemDao,
) {
    /** Articles most similar to [itemId], by cosine similarity of their TF-IDF term vectors. */
    suspend fun related(itemId: String, limit: Int = 8, pool: Int = 400): List<RelatedItem> {
        val docs = itemDao.recentText(pool)
        val target = docs.firstOrNull { it.id == itemId }
            ?: itemDao.getItem(itemId)?.let { ItemText(it.id, it.title, it.excerpt, it.siteName) }
            ?: return emptyList()
        val corpus = if (docs.any { it.id == itemId }) docs else docs + target
        val idf = buildIdf(corpus)
        val targetVec = tfidf(tokens(target), idf)
        if (targetVec.isEmpty()) return emptyList()
        return corpus.asSequence()
            .filter { it.id != itemId }
            .map { d -> RelatedItem(d.id, d.title, d.sourceTitle, cosine(targetVec, tfidf(tokens(d), idf))) }
            .filter { it.score > 0.04 }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /**
     * Greedy single-pass clustering of recent items into topics: seed a cluster with an unassigned
     * item, absorb everything similar enough, label it by the terms its members share. Cheap and
     * deterministic — good enough to show "what you're following, by theme".
     */
    suspend fun clusters(pool: Int = 250, minSize: Int = 2, threshold: Double = 0.12): List<TopicCluster> {
        val docs = itemDao.recentText(pool)
        if (docs.size < minSize) return emptyList()
        val idf = buildIdf(docs)
        val vecs = docs.associate { it.id to tfidf(tokens(it), idf) }
        val assigned = HashSet<String>()
        val out = ArrayList<TopicCluster>()
        for (seed in docs) {
            if (seed.id in assigned) continue
            val seedVec = vecs[seed.id].orEmpty()
            if (seedVec.isEmpty()) continue
            val members = ArrayList<ItemText>()
            for (d in docs) {
                if (d.id in assigned) continue
                if (d.id == seed.id || cosine(seedVec, vecs[d.id].orEmpty()) >= threshold) {
                    members += d; assigned += d.id
                }
            }
            if (members.size >= minSize) out += TopicCluster(labelFor(members, idf), members)
        }
        return out.sortedByDescending { it.items.size }
    }

    // -- TF-IDF machinery -------------------------------------------------------

    private fun tokens(d: ItemText): List<String> =
        (d.title + " " + d.excerpt.orEmpty()).lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP }

    private fun buildIdf(corpus: List<ItemText>): Map<String, Double> {
        val df = HashMap<String, Int>()
        corpus.forEach { d -> tokens(d).toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = corpus.size.toDouble()
        return df.mapValues { (_, c) -> ln((n + 1) / (c + 1)) + 1.0 }
    }

    private fun tfidf(tokens: List<String>, idf: Map<String, Double>): Map<String, Double> {
        if (tokens.isEmpty()) return emptyMap()
        val tf = HashMap<String, Int>()
        tokens.forEach { tf[it] = (tf[it] ?: 0) + 1 }
        return tf.mapValues { (t, c) -> (c.toDouble() / tokens.size) * (idf[t] ?: 1.0) }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var dot = 0.0
        small.forEach { (t, w) -> large[t]?.let { dot += w * it } }
        if (dot == 0.0) return 0.0
        val na = sqrt(a.values.sumOf { it * it })
        val nb = sqrt(b.values.sumOf { it * it })
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
    }

    /** Label a cluster by the highest-IDF terms shared across the most members. */
    private fun labelFor(members: List<ItemText>, idf: Map<String, Double>): String {
        val score = HashMap<String, Double>()
        members.forEach { d -> tokens(d).toSet().forEach { score[it] = (score[it] ?: 0.0) + (idf[it] ?: 1.0) } }
        return score.entries.sortedByDescending { it.value }.take(3)
            .joinToString(" · ") { it.key.replaceFirstChar { c -> c.uppercase() } }
            .ifBlank { "Topic" }
    }

    private companion object {
        val STOP = setOf(
            "the", "and", "for", "are", "but", "not", "you", "your", "with", "from", "this", "that",
            "have", "has", "was", "will", "what", "how", "why", "who", "can", "all", "new", "out",
            "about", "into", "over", "more", "than", "then", "they", "them", "its", "his", "her",
            "one", "two", "our", "she", "him", "had", "were", "been", "when", "where", "which",
        )
    }
}
