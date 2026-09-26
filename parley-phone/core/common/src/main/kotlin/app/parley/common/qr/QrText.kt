package app.parley.common.qr

/** Q5: scanned text made safe to show. */
object QrText {
    /** Longest text shown in one place (the rest is still copied in full). */
    const val MAX_SHOWN = 2_000

    /**
     * Bidi controls (LRE, RLE, PDF, LRO, RLO, LRI, RLI, FSI, PDI, LRM, RLM, ALM): they can make "moc.evil" read as
     * "live.com". Removed from anything shown.
     */
    private fun isBidiControl(c: Char): Boolean =
        c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' || c == '؜'

    /** [s] without control and bidi-override characters; line breaks and tabs stay when [keepLines]. */
    fun clean(s: String, keepLines: Boolean = true): String = buildString(s.length) {
        for (c in s) {
            when {
                isBidiControl(c) -> Unit
                c == '\n' || c == '\t' -> if (keepLines) append(c) else append(' ')
                c == '\r' -> Unit
                Character.isISOControl(c) -> Unit
                // Unpaired surrogates and U+FFFE/U+FFFF aren't text.
                c == '￾' || c == '￿' -> Unit
                else -> append(c)
            }
        }
    }

    /** [clean] and cut to [max] characters with an ellipsis. */
    fun shown(s: String, max: Int = MAX_SHOWN, keepLines: Boolean = true): String {
        val c = clean(s, keepLines)
        return if (c.length <= max) c else c.take(max).trimEnd() + "…"
    }

    /** Whether [s] holds characters [clean] would remove (worth a note: "hidden characters were removed"). */
    fun hasHidden(s: String): Boolean = s.any { isBidiControl(it) || (Character.isISOControl(it) && it != '\n' && it != '\t' && it != '\r') }

    /** Percent-decoding (UTF-8); `+` stays a plus. Malformed escapes are kept as they are. */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0) {
                out.write(hex(s[i + 1]) * 16 + hex(s[i + 2]))
                i += 3
            } else {
                // A run up to the next '%' (keeps surrogate pairs together).
                var j = i + 1
                while (j < s.length && s[j] != '%') j++
                out.write(s.substring(i, j).toByteArray(Charsets.UTF_8))
                i = j
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Query parameters of `a=1&b=2` (names lower-cased; percent-decoded; `+` as space for [plusIsSpace]). */
    fun query(q: String?, plusIsSpace: Boolean = false): Map<String, String> {
        if (q.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        q.split('&').forEach { part ->
            if (part.isEmpty()) return@forEach
            val k = part.substringBefore('=')
            val v = if ('=' in part) part.substringAfter('=') else ""
            fun dec(x: String) = percentDecode(if (plusIsSpace) x.replace('+', ' ') else x)
            out.putIfAbsent(dec(k).lowercase(), dec(v))
        }
        return out
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
