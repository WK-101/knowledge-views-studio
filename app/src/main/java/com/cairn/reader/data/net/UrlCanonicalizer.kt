package com.cairn.reader.data.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Produces a stable canonical *key* for a URL, used to detect that two articles are the same link.
 *
 * Unlike [UrlCleaner.strip] — which returns a still-navigable URL with only tracking params removed —
 * this returns a normalized comparison key that deliberately discards things that never change the
 * underlying resource:
 *  - scheme (http/https treated as the same resource; the key is scheme-less),
 *  - host case and `www.` / `m.` / `amp.` / `mobile.` prefixes,
 *  - the URL fragment,
 *  - a trailing slash and a trailing `/amp` path segment,
 *  - tracking/analytics query params (via [UrlCleaner.isTracking]),
 *  - query-param order and case (remaining params are lower-cased and sorted).
 *
 * The result is NOT a navigable URL — it is a dedup key (e.g. `example.com/story?id=5`). Pure string
 * work, no network. Unparseable input falls back to the trimmed, lower-cased original.
 */
object UrlCanonicalizer {

    private val MOBILE_PREFIXES = listOf("www.", "m.", "amp.", "mobile.")

    fun canonicalize(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull() ?: return raw.trim().lowercase()
        var host = url.host.lowercase()
        for (p in MOBILE_PREFIXES) {
            if (host.startsWith(p) && host.length > p.length) { host = host.substring(p.length); break }
        }
        var path = url.encodedPath.trimEnd('/')
        if (path.endsWith("/amp")) path = path.removeSuffix("/amp").trimEnd('/')
        if (path.isEmpty()) path = "/"
        val params = (0 until url.querySize)
            .mapNotNull { i ->
                val name = url.queryParameterName(i)
                if (UrlCleaner.isTracking(name)) null else name.lowercase() to (url.queryParameterValue(i) ?: "")
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&") { "${it.first}=${it.second}" }
        return "$host$path$query"
    }
}
