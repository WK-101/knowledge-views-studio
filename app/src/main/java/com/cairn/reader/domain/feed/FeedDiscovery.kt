package com.cairn.reader.domain.feed

import com.cairn.reader.data.net.HttpFetcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import javax.inject.Inject

data class DiscoveryResult(
    val feedUrl: String,
    val feed: ParsedFeed,
)

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
    suspend fun discover(rawInput: String): DiscoveryResult? {
        val url = normalize(rawInput) ?: return null

        knownPattern(url)?.let { candidate ->
            tryFeed(candidate)?.let { return it }
        }

        val response = runCatching { fetcher.fetch(url) }.getOrNull()
        val body = response?.body
        if (body != null) {
            parser.parse(body, response.finalUrl)?.let { feed ->
                return DiscoveryResult(response.finalUrl, feed)
            }
            // Not a feed — treat as HTML and look for declared feeds.
            for (candidate in htmlFeedLinks(body, response.finalUrl)) {
                tryFeed(candidate)?.let { return it }
            }
        }

        for (candidate in guessPaths(url)) {
            tryFeed(candidate)?.let { return it }
        }
        return null
    }

    private suspend fun tryFeed(candidate: String): DiscoveryResult? {
        val res = runCatching { fetcher.fetch(candidate) }.getOrNull() ?: return null
        val body = res.body ?: return null
        val feed = parser.parse(body, res.finalUrl) ?: return null
        return DiscoveryResult(res.finalUrl, feed)
    }

    private fun htmlFeedLinks(html: String, baseUrl: String): List<String> = runCatching {
        val doc = Jsoup.parse(html, baseUrl)
        doc.select("link[rel~=(?i)alternate][href]")
            .filter { el ->
                val type = el.attr("type").lowercase()
                type.contains("rss") || type.contains("atom") || type.contains("xml") || type.contains("json")
            }
            .map { it.absUrl("href") }
            .filter { it.isNotBlank() }
            .distinct()
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

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()?.toString()
    }
}
