package app.parley.common.qr

import java.net.IDN
import java.net.URI

/**
 * Q4: one parser for http(s) addresses, shared by [UrlSafety] and [MessengerQr], that reads the host the way a
 * browser does (the WHATWG URL rules for "special" schemes): tabs and line breaks anywhere are dropped, `\` is a
 * path separator just like `/`, any run of slashes after the scheme is skipped, the text before the last `@` of the
 * authority is a user name, the host is percent-decoded, converted to ASCII (punycode) and lower-cased, and a host
 * that ends in a number is an IPv4 address (`0x7f.1` is 127.0.0.1).
 *
 * [href] is the address rebuilt from those parts, without the user name, with an unambiguous host and a path that
 * starts with `/`: Parley shows [host] and opens [href], so what the sheet says is where the browser goes. Anything
 * whose host a browser might read differently (a `/`, `\`, `%` or other forbidden character after decoding, a bad
 * port, an empty label) is refused, and the code is then shown as plain text.
 */
data class WebUrl(
    /** "http" or "https". */
    val scheme: String,
    /** ASCII (punycode) and lower-case; IPv6 in brackets; IPv4 in dotted form. */
    val host: String,
    /** Null for the scheme's default port. */
    val port: Int?,
    /** Starts with `/`; percent-encoded. */
    val path: String,
    /** Without the `?`; percent-encoded; null when there is none. */
    val query: String?,
    /** Without the `#`; percent-encoded; null when there is none. */
    val fragment: String?,
    /** The address carried a `user@` part (dropped from [href]). */
    val hadUserInfo: Boolean,
) {
    val isIp: Boolean get() = host.startsWith("[") || IPV4_DOTTED.matches(host)

    val href: String
        get() = buildString {
            append(scheme).append("://").append(host)
            port?.let { append(':').append(it) }
            append(path)
            query?.let { append('?').append(it) }
            fragment?.let { append('#').append(it) }
        }

    companion object {
        private val IPV4_DOTTED = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")
        private val HOST_CHARS = Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)*$""")
        private val IPV6 = Regex("""^[0-9a-f:.]+$""")
        private const val FRAGMENT_SET = "\"<>`"
        private const val QUERY_SET = "\"<>'"
        private const val PATH_SET = "\"#<>?`{}"

        /** [input] as a browser would read it, or null when it isn't an http(s) address with a clear host. */
        fun parse(input: String): WebUrl? {
            // WHATWG: leading/trailing C0 controls and spaces are trimmed, tabs and line breaks removed everywhere.
            val s = input.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
            val sm = SCHEME.find(s) ?: return null
            val scheme = sm.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") return null
            var i = sm.range.last + 1
            // Any mix of slashes and backslashes after "https:" is skipped ("https:\\evil.com", "https:evil.com").
            while (i < s.length && (s[i] == '/' || s[i] == '\\')) i++
            var end = i
            while (end < s.length && s[end] !in "/\\?#") end++
            val authority = s.substring(i, end)
            val rest = s.substring(end)
            val at = authority.lastIndexOf('@')
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            val rawHost: String
            val rawPort: String?
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                rawHost = hostPort.substring(0, close + 1)
                val after = hostPort.substring(close + 1)
                rawPort = when {
                    after.isEmpty() -> null
                    after.startsWith(":") -> after.substring(1)
                    else -> return null
                }
            } else {
                rawHost = hostPort.substringBefore(':')
                rawPort = if (':' in hostPort) hostPort.substringAfter(':') else null
            }
            val host = host(rawHost) ?: return null
            val port = when {
                rawPort.isNullOrEmpty() -> null
                rawPort.all { it in '0'..'9' } -> rawPort.trimStart('0').ifEmpty { "0" }.takeIf { it.length <= 5 }?.toInt()?.takeIf { it <= 65535 } ?: return null
                else -> return null
            }?.takeUnless { (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
            // Path, query, fragment: in the path `\` is `/` as well.
            val hash = rest.indexOf('#')
            val beforeHash = if (hash >= 0) rest.substring(0, hash) else rest
            val fragment = if (hash >= 0) encode(rest.substring(hash + 1), FRAGMENT_SET) else null
            val q = beforeHash.indexOf('?')
            val rawPath = (if (q >= 0) beforeHash.substring(0, q) else beforeHash).replace('\\', '/')
            val query = if (q >= 0) encode(beforeHash.substring(q + 1), QUERY_SET) else null
            val path = encode(rawPath, PATH_SET).let { if (it.startsWith("/")) it else "/$it" }
            val url = WebUrl(scheme, host, port, path, query, fragment, hadUserInfo = at >= 0)
            // Belt and braces: java.net.URI must read the same host (the path can't change it: it starts with "/").
            val origin = "$scheme://$host" + (port?.let { ":$it" } ?: "") + "/"
            val check = runCatching { URI(origin) }.getOrNull() ?: return null
            if (check.userInfo != null || !check.host.equals(host, ignoreCase = true)) return null
            return url
        }

        /** The browser's reading of [raw]: decoded, ASCII, lower-case, no trailing dot; null when ambiguous. */
        internal fun host(raw: String): String? {
            if (raw.isEmpty()) return null
            if (raw.startsWith("[")) {
                val inner = raw.substring(1, raw.length - 1).lowercase()
                return if (':' in inner && IPV6.matches(inner)) "[$inner]" else null
            }
            val decoded = QrText.percentDecode(raw)
            if ('\uFFFD' in decoded) return null
            val ascii = try {
                IDN.toASCII(decoded, IDN.ALLOW_UNASSIGNED).lowercase()
            } catch (_: IllegalArgumentException) {
                return null
            }
            val h = ascii.removeSuffix(".")
            if (h.isEmpty() || h.length > 253 || !HOST_CHARS.matches(h)) return null
            if (endsInNumber(h)) return ipv4(h)
            return h
        }

        /** WHATWG: a host whose last label is a number (decimal or 0x…) is an IPv4 address. */
        private fun endsInNumber(h: String): Boolean {
            val last = h.substringAfterLast('.')
            if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
            return last.startsWith("0x") && last.drop(2).all { it in '0'..'9' || it in 'a'..'f' }
        }

        /** IPv4 in any of the forms browsers accept ("127.1", "0x7f.0.0.1", "2130706433", "0177.0.0.1"), dotted. */
        private fun ipv4(h: String): String? {
            val parts = h.split('.')
            if (parts.size > 4) return null
            val nums = parts.map { p ->
                when {
                    p.startsWith("0x") -> if (p.length == 2) 0L else p.drop(2).takeIf { it.length <= 8 }?.toLongOrNull(16)
                    p.length > 1 && p.startsWith("0") -> p.drop(1).takeIf { it.length <= 11 && it.all { c -> c in '0'..'7' } }?.toLongOrNull(8)
                    else -> p.takeIf { it.length <= 10 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
                } ?: return null
            }
            if (nums.dropLast(1).any { it > 255 }) return null
            val lastMax = 1L shl (8 * (5 - nums.size))
            if (nums.last() >= lastMax) return null
            var value = nums.last()
            nums.dropLast(1).forEachIndexed { idx, n -> value += n shl (8 * (3 - idx)) }
            return (3 downTo 0).joinToString(".") { ((value shr (8 * it)) and 0xFF).toString() }
        }

        /** WHATWG percent-encoding: controls, space, non-ASCII and the characters in [set]; everything else as is. */
        private fun encode(s: String, set: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c > ' ' && c.code < 0x7F && c !in set) {
                    append(c)
                    i++
                    continue
                }
                val cp = s.codePointAt(i)
                String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).forEach { append('%').append(HEX[(it.toInt() shr 4) and 0xF]).append(HEX[it.toInt() and 0xF]) }
                i += Character.charCount(cp)
            }
        }

        private const val HEX = "0123456789ABCDEF"

        private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }
}
