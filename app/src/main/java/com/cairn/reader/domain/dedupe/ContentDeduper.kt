package com.cairn.reader.domain.dedupe

import com.cairn.reader.data.db.CacheStatus
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.net.UrlCanonicalizer

/**
 * Collapses duplicate articles for the Inbox "hide duplicates" filter.
 *
 * Two rows are duplicates when they share **either** a canonical URL (the same link — tracking,
 * mobile/AMP and query-order variants aside) **or** a normalized title (the same story syndicated
 * across several feeds, which is the case users most want collapsed). Grouping is transitive
 * (union-find): if A≈B by title and B≈C by URL, all three collapse to one.
 *
 * From each duplicate group the single most useful copy is kept — starred first, then a copy with an
 * offline/full-text body, then read-later, then unread, then the newest — and it is emitted in its
 * original list position, so the surrounding sort order is preserved.
 *
 * Pure and dependency-light (only [ItemListRow] + [UrlCanonicalizer]) so it is unit-testable off-device.
 */
object ContentDeduper {

    fun dedupe(rows: List<ItemListRow>): List<ItemListRow> {
        if (rows.size < 2) return rows
        val n = rows.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        val firstByUrl = HashMap<String, Int>()
        val firstByTitle = HashMap<String, Int>()
        rows.forEachIndexed { i, r ->
            val urlKey = UrlCanonicalizer.canonicalize(r.url)
            if (urlKey.isNotEmpty()) {
                val prev = firstByUrl.putIfAbsent(urlKey, i)
                if (prev != null) union(prev, i)
            }
            val titleKey = normalizeTitle(r.title)
            if (titleKey.isNotEmpty()) {
                val prev = firstByTitle.putIfAbsent(titleKey, i)
                if (prev != null) union(prev, i)
            }
        }

        // Keep the best-scoring member of each group.
        val bestByRoot = HashMap<Int, Int>()
        rows.indices.forEach { i ->
            val root = find(i)
            val cur = bestByRoot[root]
            if (cur == null || score(rows[i]) > score(rows[cur])) bestByRoot[root] = i
        }
        return rows.filterIndexed { i, _ -> bestByRoot[find(i)] == i }
    }

    /** Higher = more worth keeping. Flag bits dominate; recency is a bounded tie-breaker. */
    private fun score(r: ItemListRow): Long {
        var s = 0L
        if (r.isStarred) s += 1L shl 40
        if (r.cacheStatus == CacheStatus.PERMANENT.raw || r.extractStatus == "OK") s += 1L shl 39
        if (r.isReadLater) s += 1L shl 38
        if (!r.isRead) s += 1L shl 37
        s += ((r.publishedAt ?: r.savedAt) / 1000L).coerceIn(0L, (1L shl 36) - 1)
        return s
    }

    private val WS = Regex("\\s+")
    private val PUNCT = Regex("[\\p{Punct}]+")

    /**
     * Normalize a headline for cross-feed matching: drop a trailing " - Site" / " | Site" section
     * suffix (only when a substantial head remains, to avoid mangling real "A - B" titles), then
     * lower-case, replace punctuation with spaces, and collapse whitespace.
     */
    fun normalizeTitle(title: String): String {
        var t = title.trim()
        val sep = maxOf(t.lastIndexOf(" - "), t.lastIndexOf(" | "))
        if (sep > 12) t = t.substring(0, sep)
        return t.lowercase().replace(PUNCT, " ").replace(WS, " ").trim()
    }
}
