package com.cairn.reader.domain.feed

import com.cairn.reader.data.net.HttpFetcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/**
 * Collector Tier 2 (P0): synthesise a feed from a site's sitemap when it publishes no RSS/Atom.
 * Nearly every CMS ships a sitemap; sorting its URLs by <lastmod> gives a reliable, on-device
 * reverse-chronological feed. Titles are derived from the URL slug and replaced by the real
 * article the first time an item is opened (Cairn auto-extracts on open).
 */
class SiteFeedBuilder @Inject constructor(
    private val fetcher: HttpFetcher,
) {
    /** Build a feed from [input] (a site origin, page, or a direct sitemap URL). Null if none found. */
    suspend fun build(input: String): ParsedFeed? {
        val http = input.toHttpUrlOrNull() ?: return null
        val origin = "${http.scheme}://${http.host}"
        val host = http.host.removePrefix("www.")

        val candidates = LinkedHashSet<String>()
        if (input.contains("sitemap", ignoreCase = true) && input.endsWith(".xml")) candidates += input
        // robots.txt often points at the real sitemap(s).
        runCatching {
            fetcher.fetch("$origin/robots.txt").body?.lineSequence()?.forEach { line ->
                val l = line.trim()
                if (l.startsWith("Sitemap:", ignoreCase = true)) {
                    l.substringAfter(':', "").trim().let { rest ->
                        // "Sitemap: https://..." — rejoin the scheme's colon if it was split.
                        val url = if (rest.startsWith("//")) "https:$rest" else l.removePrefix("Sitemap:").removePrefix("sitemap:").trim()
                        if (url.startsWith("http")) candidates += url
                    }
                }
            }
        }
        candidates += listOf(
            "$origin/sitemap.xml", "$origin/sitemap_index.xml", "$origin/sitemap-index.xml",
            "$origin/news-sitemap.xml", "$origin/sitemap-news.xml", "$origin/sitemap/sitemap.xml",
            "$origin/wp-sitemap.xml",
        )

        val collected = ArrayList<Pair<String, Long?>>()
        for (sm in candidates) {
            if (collected.size >= 80) break
            val body = runCatching { fetcher.fetch(sm).body }.getOrNull() ?: continue
            harvest(body, origin, collected, depth = 0)
            if (collected.size >= 15) break // a productive sitemap is enough
        }
        val items = collected
            .distinctBy { it.first }
            .filter { looksLikeArticle(it.first) }
            .sortedByDescending { it.second ?: 0L }
            .take(40)
            .map { (loc, lastmod) ->
                ParsedItem(
                    guid = loc, title = titleFromUrl(loc), link = loc, author = null,
                    publishedAt = lastmod, contentHtml = null, summary = null, imageUrl = null,
                )
            }
        if (items.isEmpty()) return null
        return ParsedFeed(title = host, siteUrl = origin, items = items)
    }

    /** Parse a sitemap or sitemap-index. Recurses into child sitemaps up to a small depth. */
    private suspend fun harvest(xml: String, origin: String, out: MutableList<Pair<String, Long?>>, depth: Int) {
        val doc = runCatching { Jsoup.parse(xml, origin, Parser.xmlParser()) }.getOrNull() ?: return
        val sitemaps = doc.select("sitemapindex > sitemap > loc")
        if (sitemaps.isNotEmpty() && depth < 2) {
            // Prefer post/news child sitemaps, then the rest; cap how many we open.
            val childUrls = sitemaps.map { it.text().trim() }.filter { it.startsWith("http") }
            val ordered = childUrls.sortedByDescending { u -> if (Regex("(post|news|article|sitemap-pt-post)").containsMatchIn(u.lowercase())) 1 else 0 }
            for (child in ordered.take(3)) {
                if (out.size >= 80) break
                val body = runCatching { fetcher.fetch(child).body }.getOrNull() ?: continue
                harvest(body, origin, out, depth + 1)
            }
            return
        }
        doc.select("urlset > url").forEach { url ->
            val loc = url.selectFirst("loc")?.text()?.trim() ?: return@forEach
            val mod = url.selectFirst("lastmod")?.text()?.trim()
                ?: url.selectFirst("news|publication_date")?.text()?.trim()
                ?: url.selectFirst("publication_date")?.text()?.trim()
            out += loc to (mod?.let { parseDate(it) })
        }
    }

    private fun looksLikeArticle(url: String): Boolean {
        val http = url.toHttpUrlOrNull() ?: return false
        val segs = http.pathSegments.filter { it.isNotBlank() }
        if (segs.isEmpty()) return false
        val path = "/" + segs.joinToString("/")
        val bad = Regex("(?i)/(tag|tags|category|categories|author|authors|page|topic|topics|feed|about|contact|privacy|terms|search)(/|$)")
        if (bad.containsMatchIn(path)) return false
        val last = segs.last().substringBeforeLast('.')
        // An article slug tends to be a few words (hyphenated) or to sit under a dated path.
        val dated = Regex("/20\\d\\d/").containsMatchIn(path)
        return dated || last.contains('-') || last.length > 12
    }

    private fun titleFromUrl(url: String): String {
        val http = url.toHttpUrlOrNull()
        val last = http?.pathSegments?.lastOrNull { it.isNotBlank() }?.substringBeforeLast('.') ?: return url
        val words = last.replace('-', ' ').replace('_', ' ').trim()
        return words.split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .ifBlank { http?.host ?: url }
    }

    private fun parseDate(s: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
        )
        for (p in patterns) {
            runCatching { return SimpleDateFormat(p, Locale.US).parse(s)?.time }.getOrNull()
        }
        return null
    }
}
