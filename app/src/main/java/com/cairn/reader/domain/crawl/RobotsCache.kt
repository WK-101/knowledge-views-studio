package com.cairn.reader.domain.crawl

import com.cairn.reader.data.net.HttpFetcher
import com.cairn.reader.util.coRunCatching
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tiny, polite robots.txt cache for the deep-archive crawler (Content Engine P3). Fetches a host's
 * robots.txt once, parses the rules that apply to a generic crawler (the `*` user-agent group), and
 * answers "may I fetch this path?" plus the declared Crawl-delay. In-memory only — it lives for the
 * duration of a crawl run (the crawler is a WorkManager job), which is exactly the right scope.
 *
 * Deliberately conservative: on any fetch/parse trouble we allow (fail-open) rather than blocking a
 * user's own reading, but we always honour an explicit Disallow and Crawl-delay when we can read them.
 */
@Singleton
class RobotsCache @Inject constructor(
    private val fetcher: HttpFetcher,
) {
    private data class Rules(val disallow: List<String>, val allow: List<String>, val crawlDelayMs: Long?)

    private val cache = HashMap<String, Rules>()

    /** Whether [url] may be fetched under its host's robots.txt (fail-open on any uncertainty). */
    suspend fun allowed(url: String): Boolean {
        val http = url.toHttpUrlOrNull() ?: return true
        val rules = rulesFor("${http.scheme}://${http.host}")
        val path = http.encodedPath.ifBlank { "/" } + (http.encodedQuery?.let { "?$it" } ?: "")
        // Longest-match wins between Allow and Disallow (the robots.txt convention).
        val allow = rules.allow.filter { path.startsWith(it) }.maxByOrNull { it.length }?.length ?: -1
        val disallow = rules.disallow.filter { it.isNotEmpty() && path.startsWith(it) }.maxByOrNull { it.length }?.length ?: -1
        return disallow < 0 || allow >= disallow
    }

    /** The Crawl-delay declared for the generic crawler on this host, in milliseconds, if any. */
    suspend fun crawlDelayMs(host: String): Long? = rulesFor(hostToOrigin(host)).crawlDelayMs

    private fun hostToOrigin(host: String) = if (host.startsWith("http")) host else "https://$host"

    private suspend fun rulesFor(origin: String): Rules {
        val key = origin.toHttpUrlOrNull()?.host ?: origin
        cache[key]?.let { return it }
        val body = coRunCatching { fetcher.fetch("$origin/robots.txt").body }.getOrNull()
        val rules = parse(body)
        cache[key] = rules
        return rules
    }

    /** Parse the `*` user-agent group. Simple, standards-aligned: group starts at a `User-agent: *`
     *  line and runs until the next User-agent line. */
    private fun parse(body: String?): Rules {
        if (body.isNullOrBlank()) return Rules(emptyList(), emptyList(), null)
        val disallow = ArrayList<String>()
        val allow = ArrayList<String>()
        var crawlDelayMs: Long? = null
        var applies = false
        for (rawLine in body.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val field = line.substringBefore(':', "").trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (field) {
                "user-agent" -> applies = value == "*"
                "disallow" -> if (applies) disallow += value
                "allow" -> if (applies) allow += value
                "crawl-delay" -> if (applies) crawlDelayMs = value.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: crawlDelayMs
            }
        }
        return Rules(disallow, allow, crawlDelayMs)
    }
}
