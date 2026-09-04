package com.cairn.reader.domain.feed

import com.cairn.reader.data.net.HttpFetcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import javax.inject.Inject

data class DiscoveryResult(
    val feedUrl: String,
    val feed: ParsedFeed,
)

/** Outcome of a discovery attempt — carries a human reason when nothing was found,
 *  so the UI can tell the user *why* rather than a generic failure. */
sealed interface Discovery {
    data class Found(val result: DiscoveryResult) : Discovery
    data class NotFound(val reason: String) : Discovery
}

/**
 * Turns a user-entered URL into an actual feed, trying in order:
 * 1. known platform patterns (YouTube / Reddit / GitHub / Medium / Substack),
 * 2. the URL itself if it's already a feed,
 * 3. `<link rel="alternate">` autodiscovery in the page's HTML,
 * 4. common guessed paths (`/feed`, `/rss.xml`, …).
 * The first candidate that parses as a feed wins.
 */
class FeedDiscovery @Inject constructor(
    private val fetcher: HttpFetcher,
    private val parser: FeedParser,
) {
    suspend fun discover(rawInput: String): Discovery {
        val url = normalize(rawInput)
            ?: return Discovery.NotFound("That doesn't look like a valid web address.")

        // 1. Known platform shortcuts (YouTube / Reddit / Substack / …).
        knownPattern(url)?.let { candidate ->
            tryFeed(candidate)?.let { return Discovery.Found(it) }
        }

        // 2. The URL itself — is it already a feed, or an HTML page that declares one?
        val fetched = runCatching { fetcher.fetch(url) }
        val response = fetched.getOrNull()
            ?: return Discovery.NotFound("Couldn't reach ${hostOf(url)} — check the address or your connection.")

        val body = response.body
        if (body != null) {
            parser.parse(body, response.finalUrl)?.let { feed ->
                return Discovery.Found(DiscoveryResult(response.finalUrl, feed))
            }
            for (candidate in htmlFeedLinks(body, response.finalUrl)) {
                tryFeed(candidate)?.let { return Discovery.Found(it) }
            }
        }

        // 3. Common guessed paths on the site's origin and the given path.
        for (candidate in guessPaths(url)) {
            tryFeed(candidate)?.let { return Discovery.Found(it) }
        }

        return Discovery.NotFound(
            when {
                !response.isSuccess ->
                    "The server responded with HTTP ${response.status}. This feed may block apps or require a login."
                body.isNullOrBlank() ->
                    "That address returned no content."
                else ->
                    "That page loaded, but no RSS/Atom feed was found on it. Try the feed's direct URL — often ending in /feed, /rss, or .xml."
            },
        )
    }

    private suspend fun tryFeed(candidate: String): DiscoveryResult? {
        val res = runCatching { fetcher.fetch(candidate) }.getOrNull() ?: return null
        val body = res.body ?: return null
        val feed = parser.parse(body, res.finalUrl) ?: return null
        return DiscoveryResult(res.finalUrl, feed)
    }

    private fun htmlFeedLinks(html: String, baseUrl: String): List<String> = runCatching {
        val doc = Jsoup.parse(html, baseUrl)
        // <link rel="alternate"|"feed" …>, matched by a feed-ish type OR a feed-ish href,
        // so pages that omit the type attribute are still discovered. <a> tags too.
        val links = doc.select("link[rel~=(?i)(alternate|feed)][href], a[href~=(?i)(/feed|/rss|atom|\\.xml|\\.rss)]")
        links.mapNotNull { el ->
            val href = el.absUrl("href").ifBlank { el.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val type = el.attr("type").lowercase()
            val looksFeed = type.contains("rss") || type.contains("atom") || type.contains("xml") || type.contains("json") ||
                Regex("(?i)(/feed|/rss|atom|\\.xml|\\.rss|feed=)").containsMatchIn(href)
            if (looksFeed) href else null
        }.distinct().take(8)
    }.getOrDefault(emptyList())

    private fun guessPaths(url: String): List<String> {
        val http = url.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${http.scheme}://${http.host}"
        val suffixes = listOf("/feed", "/feed/", "/rss", "/rss.xml", "/atom.xml", "/feed.xml", "/index.xml")
        val fromRoot = suffixes.map { origin + it }
        val fromPath = suffixes.map { url.trimEnd('/') + it }
        return (fromRoot + fromPath).distinct()
    }

    private fun knownPattern(url: String): String? {
        val http = url.toHttpUrlOrNull() ?: return null
        val host = http.host.removePrefix("www.")
        val segments = http.pathSegments.filter { it.isNotBlank() }
        return when {
            host.endsWith("youtube.com") && segments.getOrNull(0) == "channel" ->
                "https://www.youtube.com/feeds/videos.xml?channel_id=${segments[1]}"
            host.endsWith("reddit.com") && segments.getOrNull(0) in setOf("r", "user") ->
                "https://www.reddit.com/${segments[0]}/${segments.getOrNull(1)}/.rss"
            host == "github.com" && segments.size >= 2 ->
                "https://github.com/${segments[0]}/${segments[1]}/releases.atom"
            host == "github.com" && segments.size == 1 ->
                "https://github.com/${segments[0]}.atom"
            host == "medium.com" && segments.isNotEmpty() ->
                "https://medium.com/feed/${segments.joinToString("/")}"
            host.endsWith(".substack.com") ->
                "https://$host/feed"
            else -> null
        }
    }

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: "that site"

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()?.toString()
    }
}
