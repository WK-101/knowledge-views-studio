package com.cairn.reader.domain.feed

import com.cairn.reader.data.net.HttpFetcher
import com.cairn.reader.util.coRunCatching
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
    suspend fun build(input: String, selector: String? = null): ParsedFeed? {
        if (!selector.isNullOrBlank()) return buildWithSelector(input, selector)
        val http = input.toHttpUrlOrNull() ?: return null
        val origin = "${http.scheme}://${http.host}"
        buildFromJsonFeed(origin)?.let { return it }
        buildFromWordPress(origin)?.let { return it }
        // Path-aware ordering: when the user points at a specific section/index page (e.g.
        // /insights, /research), scrape THAT page's article links first — it's exactly what they
        // asked to follow, and beats a generic whole-site sitemap. For a bare domain, the sitemap
        // is the better reverse-chronological source, so try it first.
        val hasSectionPath = http.pathSegments.any { it.isNotBlank() } &&
            !(input.contains("sitemap", ignoreCase = true) && input.endsWith(".xml"))
        return if (hasSectionPath) {
            buildFromScrape(input) ?: buildFromSitemap(input)
        } else {
            buildFromSitemap(input) ?: buildFromScrape(input)
        }
    }

    /** Teach-by-example: build a feed from the links matched by a user-chosen CSS [selector]. */
    suspend fun buildWithSelector(pageUrl: String, selector: String): ParsedFeed? {
        val http = pageUrl.toHttpUrlOrNull() ?: return null
        val origin = "${http.scheme}://${http.host}"
        val host = http.host.removePrefix("www.")
        val body = coRunCatching { fetcher.fetch(pageUrl) }.getOrNull()?.let { it.body ?: return null } ?: return null
        val doc = coRunCatching { Jsoup.parse(body, pageUrl) }.getOrNull() ?: return null
        val els = coRunCatching { doc.select(selector) }.getOrNull() ?: return null
        val seen = HashSet<String>()
        val items = els.mapNotNull { el ->
            val a = if (el.tagName() == "a" && el.hasAttr("href")) el else el.selectFirst("a[href]")
            val url = a?.absUrl("href")?.substringBefore('#')?.trim().orEmptyNull() ?: return@mapNotNull null
            if (!seen.add(url.trimEnd('/'))) return@mapNotNull null
            val text = el.text().trim().ifBlank { a.text().trim() }
            ParsedItem(
                guid = url, title = (if (text.isBlank()) titleFromUrl(url) else text).take(200),
                link = url, author = null, publishedAt = null, contentHtml = null, summary = null, imageUrl = null,
            )
        }.take(40)
        if (items.isEmpty()) return null
        return ParsedFeed(host, origin, items)
    }

    private fun String?.orEmptyNull(): String? = this?.ifBlank { null }

    // -- Scrape-to-feed: synthesise a feed from an index page's article links --------------
    private suspend fun buildFromScrape(input: String): ParsedFeed? {
        val http = input.toHttpUrlOrNull() ?: return null
        val origin = "${http.scheme}://${http.host}"
        val host = http.host.removePrefix("www.")
        val body = coRunCatching { fetcher.fetch(input) }.getOrNull()?.let { it.body ?: return null } ?: return null
        val doc = coRunCatching { Jsoup.parse(body, input) }.getOrNull() ?: return null

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
            val body = coRunCatching { fetcher.fetch(origin + path).body }.getOrNull() ?: continue
            val obj = coRunCatching { JSONObject(body) }.getOrNull() ?: continue
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
        val body = coRunCatching { fetcher.fetch("$origin/wp-json/wp/v2/posts?_embed&per_page=30").body }.getOrNull() ?: return null
        val arr = coRunCatching { JSONArray(body) }.getOrNull() ?: return null
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

    /**
     * Full-archive search over a site (collector v3.37): WordPress exposes a REST `search`
     * parameter that queries every post ever published, not just the recent feed window, so a
     * WordPress site returns true historical matches. Returns an empty list for non-WordPress
     * sites (the caller falls back to a site-scoped web search).
     */
    suspend fun searchWordPressArchive(input: String, query: String, perPage: Int = 40): List<ParsedItem> {
        val http = input.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${http.scheme}://${http.host}"
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$origin/wp-json/wp/v2/posts?search=$q&per_page=${perPage.coerceIn(1, 100)}&_embed"
        val body = coRunCatching { fetcher.fetch(url).body }.getOrNull() ?: return emptyList()
        val arr = coRunCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
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
    }

    // -- Deep-archive enumeration (Content Engine P3) -------------------------------------------

    /** A discovered archive URL: where an article lives, its best-known date, and how it was found. */
    data class ArchiveUrl(val url: String, val lastmod: Long?, val via: String)

    /**
     * Enumerate (as completely as politely possible) every article URL a site ever published, via a
     * ladder of richest → most-universal sources: the WordPress REST API paginated to exhaustion, the
     * FULL sitemap tree (not the recent-40 window [buildFromSitemap] takes), and finally the Wayback
     * Machine's CDX index (which lists every URL archive.org has ever seen for the host — the fallback
     * for sites with no sitemap/CMS API, and a net that even recovers deleted articles). Results are
     * merged, filtered to real articles, de-duplicated, and capped at [maxUrls].
     */
    suspend fun enumerateArchive(siteUrl: String, maxUrls: Int = 20_000): List<ArchiveUrl> {
        val http = siteUrl.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${http.scheme}://${http.host}"
        val host = http.host.removePrefix("www.")

        val raw = ArrayList<Triple<String, Long?, String>>()
        coRunCatching { enumerateWordPress(origin, maxUrls) }.getOrDefault(emptyList())
            .forEach { raw += Triple(it.first, it.second, "wordpress") }
        if (raw.size < maxUrls) coRunCatching { enumerateSitemaps(siteUrl, origin, maxUrls) }.getOrDefault(emptyList())
            .forEach { raw += Triple(it.first, it.second, "sitemap") }
        if (raw.size < maxUrls) coRunCatching { enumerateWayback(host, maxUrls) }.getOrDefault(emptyList())
            .forEach { raw += Triple(it.first, it.second, "wayback") }

        val out = LinkedHashMap<String, ArchiveUrl>()
        for ((url, mod, via) in raw) {
            if (out.size >= maxUrls) break
            val u = url.substringBefore('#').trim()
            if (!u.startsWith("http")) continue
            val h = u.toHttpUrlOrNull() ?: continue
            if (h.host.removePrefix("www.") != host) continue        // same site only
            if (!looksLikeArticle(u)) continue
            val key = u.trimEnd('/')
            val existing = out[key]
            if (existing == null) out[key] = ArchiveUrl(u, mod, via)
            else if (existing.lastmod == null && mod != null) out[key] = existing.copy(lastmod = mod)
        }
        return out.values.toList()
    }

    /** Page the WordPress REST API to exhaustion, collecting (link, date) for every post. */
    private suspend fun enumerateWordPress(origin: String, max: Int): List<Pair<String, Long?>> {
        val out = ArrayList<Pair<String, Long?>>()
        var page = 1
        while (page <= WP_MAX_PAGES && out.size < max) {
            val url = "$origin/wp-json/wp/v2/posts?per_page=100&page=$page&_fields=link,date_gmt,modified_gmt"
            val body = coRunCatching { fetcher.fetch(url).body }.getOrNull() ?: break
            val arr = coRunCatching { JSONArray(body) }.getOrNull() ?: break   // 400 error object at end → stop
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val link = p.optString("link").ifBlank { null } ?: continue
                out += link to parseDate(p.optString("date_gmt").ifBlank { p.optString("modified_gmt") })
            }
            if (arr.length() < 100) break
            page++
        }
        return out
    }

    /** Walk the whole sitemap tree (robots.txt + common locations), collecting every <loc>+<lastmod>. */
    private suspend fun enumerateSitemaps(input: String, origin: String, max: Int): List<Pair<String, Long?>> {
        val candidates = LinkedHashSet<String>()
        if (input.contains("sitemap", ignoreCase = true) && input.endsWith(".xml")) candidates += input
        coRunCatching {
            fetcher.fetch("$origin/robots.txt").body?.lineSequence()?.forEach { line ->
                val l = line.trim()
                if (l.startsWith("Sitemap:", ignoreCase = true)) {
                    val rest = l.substringAfter(':', "").trim()
                    val url = if (rest.startsWith("//")) "https:$rest" else l.removePrefix("Sitemap:").removePrefix("sitemap:").trim()
                    if (url.startsWith("http")) candidates += url
                }
            }
        }
        candidates += listOf(
            "$origin/sitemap.xml", "$origin/sitemap_index.xml", "$origin/sitemap-index.xml",
            "$origin/news-sitemap.xml", "$origin/sitemap-news.xml", "$origin/sitemap/sitemap.xml",
            "$origin/wp-sitemap.xml",
        )
        val out = ArrayList<Pair<String, Long?>>()
        val fetches = intArrayOf(0)
        for (sm in candidates) {
            if (out.size >= max || fetches[0] >= SITEMAP_MAX_FETCHES) break
            val body = coRunCatching { fetcher.fetch(sm).body }.getOrNull() ?: continue
            fetches[0]++
            harvestAll(body, origin, out, depth = 0, max = max, fetches = fetches)
        }
        return out
    }

    /** Recurse a sitemap index into all child sitemaps (bounded), collecting URLs + dates. */
    private suspend fun harvestAll(
        xml: String, origin: String, out: MutableList<Pair<String, Long?>>, depth: Int, max: Int, fetches: IntArray,
    ) {
        if (out.size >= max) return
        val doc = coRunCatching { Jsoup.parse(xml, origin, Parser.xmlParser()) }.getOrNull() ?: return
        val sitemaps = doc.select("sitemapindex > sitemap > loc")
        if (sitemaps.isNotEmpty() && depth < 4) {
            val children = sitemaps.map { it.text().trim() }.filter { it.startsWith("http") }
                .sortedByDescending { u -> if (Regex("(post|news|article|sitemap-pt-post)").containsMatchIn(u.lowercase())) 1 else 0 }
            for (child in children) {
                if (out.size >= max || fetches[0] >= SITEMAP_MAX_FETCHES) break
                val body = coRunCatching { fetcher.fetch(child).body }.getOrNull() ?: continue
                fetches[0]++
                harvestAll(body, origin, out, depth + 1, max, fetches)
            }
            return
        }
        for (url in doc.select("urlset > url")) {
            if (out.size >= max) break
            val loc = url.selectFirst("loc")?.text()?.trim() ?: continue
            val mod = url.selectFirst("lastmod")?.text()?.trim()
                ?: url.selectFirst("news|publication_date")?.text()?.trim()
                ?: url.selectFirst("publication_date")?.text()?.trim()
            out += loc to (mod?.let { parseDate(it) })
        }
    }

    /** The Wayback Machine CDX index for the whole host — every URL archive.org has captured. */
    private suspend fun enumerateWayback(host: String, max: Int): List<Pair<String, Long?>> {
        val limit = max.coerceAtMost(WAYBACK_LIMIT)
        val url = "https://web.archive.org/cdx/search/cdx?url=${host}/*&output=json&fl=original,timestamp" +
            "&collapse=urlkey&filter=statuscode:200&limit=$limit"
        val body = coRunCatching { fetcher.fetch(url, maxBytes = 16L * 1024 * 1024).body }.getOrNull() ?: return emptyList()
        val arr = coRunCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pair<String, Long?>>()
        // Row 0 is the header ["original","timestamp"]; data rows follow.
        for (i in 1 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val original = row.optString(0).ifBlank { null } ?: continue
            out += original to parseWaybackTs(row.optString(1))
        }
        return out
    }

    /** Wayback CDX timestamps are yyyyMMddHHmmss (UTC). */
    private fun parseWaybackTs(ts: String): Long? {
        if (ts.length < 8) return null
        return coRunCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(ts.take(14).padEnd(14, '0'))?.time
        }.getOrNull()
    }

    private fun stripHtml(html: String): String =
        if (html.isBlank()) "" else coRunCatching { Jsoup.parse(html).text().trim() }.getOrDefault(html.trim())

    /** Build a feed from a site's sitemap. Null if none found. */
    private suspend fun buildFromSitemap(input: String): ParsedFeed? {
        val http = input.toHttpUrlOrNull() ?: return null
        val origin = "${http.scheme}://${http.host}"
        val host = http.host.removePrefix("www.")

        val candidates = LinkedHashSet<String>()
        if (input.contains("sitemap", ignoreCase = true) && input.endsWith(".xml")) candidates += input
        // robots.txt often points at the real sitemap(s).
        coRunCatching {
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
            val body = coRunCatching { fetcher.fetch(sm).body }.getOrNull() ?: continue
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
        val doc = coRunCatching { Jsoup.parse(xml, origin, Parser.xmlParser()) }.getOrNull() ?: return
        val sitemaps = doc.select("sitemapindex > sitemap > loc")
        if (sitemaps.isNotEmpty() && depth < 2) {
            // Prefer post/news child sitemaps, then the rest; cap how many we open.
            val childUrls = sitemaps.map { it.text().trim() }.filter { it.startsWith("http") }
            val ordered = childUrls.sortedByDescending { u -> if (Regex("(post|news|article|sitemap-pt-post)").containsMatchIn(u.lowercase())) 1 else 0 }
            for (child in ordered.take(3)) {
                if (out.size >= 80) break
                val body = coRunCatching { fetcher.fetch(child).body }.getOrNull() ?: continue
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
        // Skip static assets and CMS plumbing that carry hyphenated/long file names (Drupal's
        // /sites/…, /themes/…, /files/…; hashed CSS/JS; fonts and images) — these were slipping
        // through the slug heuristic and polluting scraped feeds.
        val asset = Regex("(?i)/(sites|themes|modules|libraries|assets|static|_next|wp-content|wp-includes|files|media|css|js|fonts?|images?|img)(/|$)")
        if (asset.containsMatchIn(path)) return false
        val last = segs.last()
        if (Regex("(?i)\\.(css|js|json|xml|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|eot|mp4|mp3|pdf|zip)$").containsMatchIn(last)) return false
        val bad = Regex("(?i)/(tag|tags|category|categories|author|authors|page|topic|topics|feed|about|contact|privacy|terms|search|login|subscribe|donate|careers?|events?|programs?|regions?)(/|$)")
        if (bad.containsMatchIn(path)) return false
        val slug = last.substringBeforeLast('.')
        // An article slug tends to be a few words (hyphenated) or to sit under a dated path.
        val dated = Regex("/20\\d\\d/").containsMatchIn(path)
        return dated || slug.contains('-') || slug.length > 12
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
            coRunCatching { return SimpleDateFormat(p, Locale.US).parse(s)?.time }.getOrNull()
        }
        return null
    }

    private companion object {
        /** WordPress caps offset paging near 10k posts by default; 100 pages × 100 is a safe ceiling. */
        const val WP_MAX_PAGES = 100
        /** Bound how many sitemap documents we open, so a pathological index can't spin forever. */
        const val SITEMAP_MAX_FETCHES = 300
        /** Upper bound on Wayback CDX rows requested for one host. */
        const val WAYBACK_LIMIT = 10_000
    }
}
