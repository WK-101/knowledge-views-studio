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
     * match our own. Takes [host] + [port] from Ktor's `request.host()`/`request.port()` (which abstract over
     * HTTP/1.1's `Host` header and HTTP/2's `:authority` pseudo-header) — NOT the raw `Host` header, which is
     * absent under HTTP/2 (Netty negotiates it over TLS) and produced a false 403 on every call. Accepts the
     * authority with or without the explicit port so it holds whichever way the browser forms the Origin.
     */
    fun originAllowed(host: String?, port: Int, origin: String?): Boolean {
        if (origin == null) return true
        if (host.isNullOrBlank()) return false
        val authority = origin.substringAfter("://", "").substringBefore('/')
        return authority.equals("$host:$port", ignoreCase = true) || authority.equals(host, ignoreCase = true)
    }
}
