package app.parley.common.calls

/**
 * The subject a caller sent with the call (`TelecomManager.EXTRA_CALL_SUBJECT`, RCS Call Composer), made safe to
 * show: it comes from anyone who can place a call, so control characters, line breaks and bidirectional overrides
 * (which could make it read backwards or spill over other text) are dropped, spaces are collapsed and it is cut to
 * [MAX_LENGTH]. It is always shown as plain text, never as a link.
 */
object CallSubject {
    const val MAX_LENGTH = 80

    fun clean(raw: CharSequence?): String? {
        if (raw.isNullOrBlank()) return null
        val sb = StringBuilder(raw.length)
        raw.forEach { c ->
            when {
                c == '\n' || c == '\r' || c == '\t' -> sb.append(' ')
                Character.isISOControl(c) || c in BIDI_CONTROLS || Character.getType(c) == Character.FORMAT.toInt() -> Unit
                else -> sb.append(c)
            }
        }
        val text = sb.toString().replace(SPACES, " ").trim()
        if (text.isEmpty()) return null
        if (text.length <= MAX_LENGTH) return text
        // Never cut a surrogate pair in half.
        var end = MAX_LENGTH - 1
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private val BIDI_CONTROLS = setOf('‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩', '‎', '‏', '؜')
    private val SPACES = Regex("\\s+")
}
