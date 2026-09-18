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
 * On-device "semantic" relevance — no model, no embeddings server. It builds weighted TF-IDF vectors
 * over the words *and* two-word phrases in each item and compares them by cosine similarity. Several
 * touches lift it above a plain bag-of-words match:
 *  - **Title weighting** — words in a headline are far more topical than words buried in the body, so
 *    they count for several times as much.
 *  - **Bigrams** — adjacent word pairs ("climate change", "supreme court") disambiguate documents that
 *    share only common single words, sharpening precision.
 *  - **A full-body query** — when the reader has the open article's text, the "find related" query is
 *    built from the whole article, not just its title/excerpt, so the match is grounded in what the
 *    piece actually says.
 *  - **De-duplication & source spread** — cross-posted duplicates are dropped and no single feed is
 *    allowed to monopolize the results, so the list reads as genuinely different further reading.
 * Everything stays on the device, entirely privately.
 */
@Singleton
class SemanticRepository @Inject constructor(
    private val itemDao: ItemDao,
) {
    /**
     * Articles most similar to [itemId]. When [targetBody] is supplied (the open article's full text),
     * the query vector is built from the whole body rather than just the stored title + excerpt.
     */
    suspend fun related(itemId: String, limit: Int = 8, pool: Int = 400, targetBody: String? = null): List<RelatedItem> {
        val docs = itemDao.recentText(pool)
        val target = docs.firstOrNull { it.id == itemId }
            ?: itemDao.getItem(itemId)?.let { ItemText(it.id, it.title, it.excerpt, it.siteName) }
            ?: return emptyList()
        val corpus = if (docs.any { it.id == itemId }) docs else docs + target
        val idf = buildIdf(corpus)
        // Richer query: title (weighted) + full body when we have it, otherwise the stored excerpt.
        val queryText = targetBody?.takeIf { it.isNotBlank() } ?: target.excerpt
        val targetVec = tfidf(featureWeights(target.title, queryText), idf)
        if (targetVec.isEmpty()) return emptyList()

        val scored = corpus.asSequence()
            .filter { it.id != itemId }
            .map { d -> RelatedItem(d.id, d.title, d.sourceTitle, cosine(targetVec, tfidf(docWeights(d), idf))) }
            .filter { it.score > MIN_SCORE }
            .sortedByDescending { it.score }
            .toList()

        // Drop cross-posted duplicates (same normalized title, including the target's own) and keep any
        // single source from monopolizing the list; then top up to [limit] if diversity left room.
        val seenTitles = HashSet<String>().apply { add(normalizedTitle(target.title)) }
        val perSource = HashMap<String, Int>()
        val out = ArrayList<RelatedItem>(limit)
        val overflow = ArrayList<RelatedItem>()
        for (r in scored) {
            if (!seenTitles.add(normalizedTitle(r.title))) continue
            val src = r.sourceTitle
            if (src != null && (perSource[src] ?: 0) >= MAX_PER_SOURCE) { overflow += r; continue }
            if (src != null) perSource[src] = (perSource[src] ?: 0) + 1
            out += r
            if (out.size >= limit) return out
        }
        // Fill remaining slots from the over-cap remainder (still de-duplicated) rather than return short.
        for (r in overflow) {
            if (out.size >= limit) break
            out += r
        }
        return out
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
        val vecs = docs.associate { it.id to tfidf(docWeights(it), idf) }
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

    /** Content unigrams: lowercase alnum tokens, min length 3, function words removed. */
    private fun unigrams(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOP }

    /**
     * A weighted bag of features (unigrams + adjacent bigrams) for a document. Title terms carry
     * [TITLE_WEIGHT]×; bigrams carry a fraction of their source weight so phrases add signal without
     * swamping the single words.
     */
    private fun featureWeights(title: String, body: String?): Map<String, Double> {
        val out = HashMap<String, Double>()
        fun ingest(text: String, weight: Double) {
            val toks = unigrams(text)
            for (t in toks) out[t] = (out[t] ?: 0.0) + weight
            for (k in 0 until toks.size - 1) {
                val bigram = toks[k] + "_" + toks[k + 1]
                out[bigram] = (out[bigram] ?: 0.0) + weight * BIGRAM_WEIGHT
            }
        }
        ingest(title, TITLE_WEIGHT)
        if (!body.isNullOrBlank()) ingest(body, 1.0)
        return out
    }

    private fun docWeights(d: ItemText): Map<String, Double> = featureWeights(d.title, d.excerpt)

    private fun buildIdf(corpus: List<ItemText>): Map<String, Double> {
        val df = HashMap<String, Int>()
        corpus.forEach { d -> docWeights(d).keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = corpus.size.toDouble()
        return df.mapValues { (_, c) -> ln((n + 1) / (c + 1)) + 1.0 }
    }

    /** Weighted tf × idf; features absent from the corpus (idf unknown) are dropped so they neither
     *  match anything nor distort the vector's norm. */
    private fun tfidf(weights: Map<String, Double>, idf: Map<String, Double>): Map<String, Double> {
        if (weights.isEmpty()) return emptyMap()
        val v = HashMap<String, Double>(weights.size)
        weights.forEach { (t, w) -> val i = idf[t]; if (i != null && i > 0.0) v[t] = w * i }
        return v
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

    /** Collapse a title to a comparable key so cross-posted duplicates fold together. */
    private fun normalizedTitle(t: String): String =
        t.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Label a cluster by the highest-IDF *single words* shared across its members (readable — no
     *  underscore-joined bigrams in the label). */
    private fun labelFor(members: List<ItemText>, idf: Map<String, Double>): String {
        val score = HashMap<String, Double>()
        members.forEach { d ->
            (unigrams(d.title) + unigrams(d.excerpt.orEmpty())).toSet()
                .forEach { score[it] = (score[it] ?: 0.0) + (idf[it] ?: 1.0) }
        }
        return score.entries.sortedByDescending { it.value }.take(3)
            .joinToString(" · ") { it.key.replaceFirstChar { c -> c.uppercase() } }
            .ifBlank { "Topic" }
    }

    private companion object {
        const val TITLE_WEIGHT = 3.0
        const val BIGRAM_WEIGHT = 0.5
        const val MIN_SCORE = 0.05
        const val MAX_PER_SOURCE = 3

        // Function words only — content words are left in so IDF can weight them.
        val STOP = setOf(
            "the", "and", "for", "are", "but", "not", "you", "your", "yours", "with", "from", "this",
            "that", "have", "has", "had", "was", "were", "will", "what", "how", "why", "who", "whom",
            "can", "could", "should", "would", "shall", "all", "new", "out", "about", "into", "onto",
            "over", "under", "more", "most", "than", "then", "they", "them", "their", "theirs", "its",
            "his", "her", "hers", "one", "two", "our", "ours", "she", "him", "been", "being", "when",
            "where", "which", "there", "here", "said", "also", "such", "some", "any", "may", "might",
            "must", "these", "those", "each", "both", "few", "own", "same", "too", "very", "just",
            "only", "even", "still", "because", "while", "during", "before", "after", "between",
            "among", "through", "upon", "against", "above", "below", "off", "down", "again", "once",
            "other", "another", "yet", "nor", "either", "neither", "whether", "though", "although",
            "however", "therefore", "thus", "within", "without", "toward", "towards", "per", "via",
        )
    }
}
