package com.cairn.reader.domain.feed

import com.cairn.reader.data.net.HttpFetcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
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
    /**
     * Build a feed from [input] using the collector ladder, richest first: JSON Feed, then the
     * WordPress REST API (both carry full content), then the sitemap (URLs only). First hit wins.
     */
    suspend fun build(input: String): ParsedFeed? {
        val http = input.toHttpUrlOrNull() ?: return null
        val origin = "${http.scheme}://${http.host}"
        buildFromJsonFeed(origin)?.let { return it }
        buildFromWordPress(origin)?.let { return it }
        buildFromSitemap(input)?.let { return it }
        return buildFromScrape(input)
    }

    // -- Scrape-to-feed: synthesise a feed from an index page's article links --------------
    private suspend fun buildFromScrape(input: String): ParsedFeed? {
        val http = input.toHttpUrlOrNull() ?: return null
        val origin = "${http.scheme}://${http.host}"
        val host = http.host.removePrefix("www.")
        val body = runCatching { fetcher.fetch(input) }.getOrNull()?.let { it.body ?: return null } ?: return null
        val doc = runCatching { Jsoup.parse(body, input) }.getOrNull() ?: return null

        data class Cand(val url: String, val text: String, val score: Int)
        val seen = HashSet<String>()
        val cands = ArrayList<Cand>()
        for (a in doc.select("a[href]")) {
            val url = a.absUrl("href").substringBefore('#').trim()
            if (url.isBlank()) continue
            val u = url.toHttpUrlOrNull() ?: continue
            if (u.host.removePrefix("www.") != host) continue          // same site only
            if (!looksLikeArticle(url)) continue
            if (!seen.add(url.trimEnd('/'))) continue
            val text = a.text().trim()
            // Score by how article-headline-ish the link is.
            var score = 0
            if (text.length >= 24) score += 2 else if (text.length >= 12) score += 1
            if (text.split(' ').size >= 4) score += 1
            val cls = (a.className() + " " + (a.parent()?.className() ?: "")).lowercase()
            if (Regex("(post|title|entry|headline|story|article|card)").containsMatchIn(cls)) score += 2
            if (a.closest("article, h1, h2, h3") != null) score += 2
            if (Regex("/20\\d\\d/").containsMatchIn(url)) score += 1
            if (score <= 0) continue
            cands += Cand(url, if (text.isBlank()) titleFromUrl(url) else text, score)
        }
        // Keep strong candidates, preserving page order (usually newest-first on an index).
        val items = cands.sortedByDescending { it.score }.take(60)
            .distinctBy { it.url.trimEnd('/') }
            .filter { it.text.length in 6..200 }
            .take(40)
            .map { c -> ParsedItem(guid = c.url, title = c.text, link = c.url, author = null, publishedAt = null, contentHtml = null, summary = null, imageUrl = null) }
        if (items.size < 3) return null // too few to be a real index
        return ParsedFeed(host, origin, items)
    }

    // -- JSON Feed (jsonfeed.org) ---------------------------------------------
    private suspend fun buildFromJsonFeed(origin: String): ParsedFeed? {
        for (path in listOf("/feed.json", "/feed/json", "/index.json", "/json")) {
            val body = runCatching { fetcher.fetch(origin + path).body }.getOrNull() ?: continue
            val obj = runCatching { JSONObject(body) }.getOrNull() ?: continue
            if (!obj.optString("version").contains("jsonfeed", true)) continue
            val arr = obj.optJSONArray("items") ?: continue
            val items = (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val link = it.optString("url").ifBlank { it.optString("external_url") }.ifBlank { null } ?: return@mapNotNull null
                ParsedItem(
                    guid = it.optString("id").ifBlank { link },
                    title = it.optString("title").ifBlank { titleFromUrl(link) }.let(::stripHtml),
                    link = link, author = null,
                    publishedAt = parseDate(it.optString("date_published")),
                    contentHtml = it.optString("content_html").ifBlank { null },
                    summary = it.optString("summary").ifBlank { it.optString("content_text").ifBlank { null } },
                    imageUrl = it.optString("image").ifBlank { it.optString("banner_image").ifBlank { null } },
                )
            }.take(40)
            if (items.isNotEmpty()) return ParsedFeed(obj.optString("title").ifBlank { origin.toHttpUrlOrNull()?.host }, origin, items)
        }
        return null
    }

    // -- WordPress REST API (JSON even when RSS is hidden) --------------------
    private suspend fun buildFromWordPress(origin: String): ParsedFeed? {
        val body = runCatching { fetcher.fetch("$origin/wp-json/wp/v2/posts?_embed&per_page=30").body }.getOrNull() ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        val items = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val link = p.optString("link").ifBlank { null } ?: return@mapNotNull null
            val image = p.optJSONObject("_embedded")
                ?.optJSONArray("wp:featuredmedia")?.optJSONObject(0)?.optString("source_url")?.ifBlank { null }
            ParsedItem(
                guid = link,
                title = stripHtml(p.optJSONObject("title")?.optString("rendered").orEmpty()).ifBlank { titleFromUrl(link) },
                link = link, author = null,
                publishedAt = parseDate(p.optString("date_gmt").ifBlank { p.optString("date") }),
                contentHtml = p.optJSONObject("content")?.optString("rendered")?.ifBlank { null },
                summary = stripHtml(p.optJSONObject("excerpt")?.optString("rendered").orEmpty()).ifBlank { null },
                imageUrl = image,
            )
        }
        if (items.isEmpty()) return null
        return ParsedFeed(origin.toHttpUrlOrNull()?.host?.removePrefix("www."), origin, items)
    }

    private fun stripHtml(html: String): String =
        if (html.isBlank()) "" else runCatching { Jsoup.parse(html).text().trim() }.getOrDefault(html.trim())

    /** Build a feed from a site's sitemap. Null if none found. */
    private suspend fun buildFromSitemap(input: String): ParsedFeed? {
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
