package com.wkhan.hexis.web.server

/**
 * DNS-rebinding + CSRF guards, kept pure so they can be unit-tested (the previous Origin check had a port
 * bug that only surfaced on-device). A page on a public site that rebinds DNS to the phone, or a cross-site
 * script, is rejected here before any request runs.
 */
object RequestGuard {

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * [host] is the Host header's host WITHOUT the port (Ktor's `request.host()`). We only serve on a raw IP,
     * `localhost`, `*.local`, or an IPv6 literal — a rebound public hostname never matches.
     */
    fun hostAllowed(host: String?): Boolean {
        val h = host?.substringBefore(':')?.lowercase() ?: return false
        return h == "localhost" || h.endsWith(".local") || IPV4.matches(h) || h.contains(':')
    }

    /**
     * Same-origin check. A same-origin GET sends no Origin → allowed. Otherwise the Origin's authority must
     * equal the Host header's authority — compared **with the port**, because we serve on a non-default port
     * and the browser's Origin always carries it (comparing against a port-stripped host was the bug: it
     * rejected every legitimate same-origin `/api` call with 403).
     */
    fun originAllowed(hostHeader: String?, origin: String?): Boolean {
        if (origin == null) return true
        if (hostHeader.isNullOrBlank()) return false
        val authority = origin.substringAfter("://", "").substringBefore('/')
        return authority.equals(hostHeader, ignoreCase = true)
    }
}
