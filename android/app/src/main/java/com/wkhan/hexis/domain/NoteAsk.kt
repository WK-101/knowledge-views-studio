package com.wkhan.hexis.domain

/**
 * L9 — "Ask your notes" without a cloud. Every rival's answer box calls a server LLM; Hexis answers on-
 * device, deterministically, and honestly: it does not *generate* an answer, it *retrieves* the passage
 * most relevant to the question from your own notes (extractive, no model, no network). Scoring is query-
 * term coverage over blank-line passages, with a title-match boost, and a snippet centred on the first hit.
 * Pure and unit-testable — the same discipline as [NoteRelated].
 */
object NoteAsk {
    data class Doc(val id: String, val title: String, val body: String)
    data class Answer(val id: String, val title: String, val snippet: String, val score: Double)

    private val token = Regex("[A-Za-z][A-Za-z']{2,}")
    private val stop = setOf(
        "the", "and", "for", "are", "but", "not", "you", "your", "with", "this", "that", "from", "have",
        "has", "was", "were", "will", "would", "can", "could", "should", "into", "out", "our", "their",
        "them", "they", "its", "about", "which", "when", "what", "who", "whom", "how", "why", "does", "did",
        "all", "any", "some", "one", "two", "get", "got", "than", "then", "there", "here", "just", "like", "where",
    )

    private fun terms(q: String): Set<String> = token.findAll(q.lowercase()).map { it.value }.filter { it !in stop }.toSet()

    /** Rank [docs] by how well a passage answers [query]; returns the best snippet from each top hit. */
    fun answer(query: String, docs: List<Doc>, limit: Int = 6): List<Answer> {
        val qs = terms(query)
        if (qs.isEmpty()) return emptyList()
        return docs.asSequence().mapNotNull { d ->
            val titleHits = terms(d.title).count { it in qs }
            val passages = d.body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { listOf(d.body.trim()) }
            var best = ""; var bestScore = 0.0
            for (p in passages) {
                val pl = p.lowercase()
                val hits = qs.count { pl.contains(it) }
                if (hits == 0) continue
                val score = hits.toDouble() + hits.toDouble() / (1.0 + pl.length / 400.0)  // coverage, denser passages edge ahead
                if (score > bestScore) { bestScore = score; best = p }
            }
            val total = bestScore + titleHits * 1.5
            if (total <= 0.0) null else Answer(d.id, d.title.ifBlank { "Untitled" }, snippet(best.ifBlank { d.title }, qs), total)
        }.sortedByDescending { it.score }.take(limit).toList()
    }

    /** A clean, ~240-char snippet centred on the first query-term hit; markdown marks stripped. */
    private fun snippet(passage: String, qs: Set<String>): String {
        val clean = passage.replace(Regex("[#*_`>~]|(?<=\\s)-(?=\\s)"), " ").replace(Regex("\\s{2,}"), " ").trim()
        if (clean.length <= 240) return clean
        val lower = clean.lowercase()
        val at = qs.mapNotNull { val i = lower.indexOf(it); if (i >= 0) i else null }.minOrNull() ?: 0
        val start = (at - 80).coerceIn(0, (clean.length - 240).coerceAtLeast(0))
        val end = (start + 240).coerceAtMost(clean.length)
        return (if (start > 0) "…" else "") + clean.substring(start, end).trim() + (if (end < clean.length) "…" else "")
    }
}
