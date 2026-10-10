package app.parley.common.calls

/** Who wrote a part of an RTT conversation. */
enum class RttSide { THEM, ME }

/** One message of an RTT conversation. [open] while its writer is still typing it (no line break yet). */
data class RttBubble(val side: RttSide, val text: String, val open: Boolean)

/**
 * An RTT (real-time text) conversation assembled from the two character streams. RTT sends every character as it
 * is typed (T.140): a backspace (U+0008) takes back the last character, a line break (LF, CR LF, or T.140's U+2028)
 * ends a message, U+FEFF is a keep-alive. Each side has at most one open message; their next character goes into it,
 * wherever it sits, so both people can type at once. A backspace with nothing open takes back the line break and
 * opens that side's last message again. Text from the other side is shown as plain text, never as markup.
 */
data class RttTranscript(val bubbles: List<RttBubble> = emptyList()) {
    val isEmpty: Boolean get() = bubbles.none { it.text.isNotEmpty() }

    /** Characters from the other person. */
    fun received(chunk: String): RttTranscript = append(RttSide.THEM, chunk)

    /** Characters this phone sent. */
    fun sent(chunk: String): RttTranscript = append(RttSide.ME, chunk)

    fun append(side: RttSide, chunk: String): RttTranscript {
        if (chunk.isEmpty()) return this
        val list = bubbles.toMutableList()
        var i = 0
        while (i < chunk.length) {
            val cp = chunk.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == BACKSPACE || cp == DELETE -> backspace(list, side)
                cp == CR -> {
                    // CR LF is one line break.
                    if (i < chunk.length && chunk[i] == '\n') i++
                    close(list, side)
                }
                cp == LF || cp == LINE_SEPARATOR || cp == PARAGRAPH_SEPARATOR -> close(list, side)
                cp == TAB -> type(list, side, " ")
                ignored(cp) -> Unit
                else -> type(list, side, String(Character.toChars(cp)))
            }
        }
        return RttTranscript(trimmed(list))
    }

    /** Ends the open messages (RTT was turned off or the call ended). */
    fun closeAll(): RttTranscript = RttTranscript(bubbles.map { it.copy(open = false) })

    /** The conversation as plain text for a call note: one line per message, "Them: …" / "You: …". */
    fun asText(themLabel: String, meLabel: String): String = bubbles.filter { it.text.isNotBlank() }.joinToString("\n") {
        (if (it.side == RttSide.THEM) themLabel else meLabel) + ": " + it.text.trim()
    }

    private fun type(list: MutableList<RttBubble>, side: RttSide, text: String) {
        val at = list.indexOfLast { it.side == side && it.open }
        if (at >= 0) list[at] = list[at].copy(text = list[at].text + text) else list += RttBubble(side, text, open = true)
    }

    private fun close(list: MutableList<RttBubble>, side: RttSide) {
        val at = list.indexOfLast { it.side == side && it.open }
        if (at < 0) return
        // An empty message (a line break on its own) leaves nothing behind.
        if (list[at].text.isEmpty()) list.removeAt(at) else list[at] = list[at].copy(open = false)
    }

    private fun backspace(list: MutableList<RttBubble>, side: RttSide) {
        val at = list.indexOfLast { it.side == side && it.open }
        if (at < 0) {
            // Nothing open: the backspace takes back the last line break of this side.
            val last = list.indexOfLast { it.side == side }
            if (last >= 0) list[last] = list[last].copy(open = true)
            return
        }
        val text = list[at].text
        if (text.isEmpty()) {
            list.removeAt(at)
            backspace(list, side)
            return
        }
        val cut = text.offsetByCodePoints(text.length, -1)
        list[at] = list[at].copy(text = text.substring(0, cut))
    }

    private fun ignored(cp: Int): Boolean =
        cp == BOM || cp < SPACE || cp in C1_CONTROLS || cp in BIDI_CONTROLS || cp in BIDI_ISOLATES

    /** A long call keeps its latest [MAX_CHARS] characters; the oldest finished messages go first. */
    private fun trimmed(list: MutableList<RttBubble>): List<RttBubble> {
        var total = list.sumOf { it.text.length }
        while (total > MAX_CHARS && list.size > 1) {
            val oldest = list.indexOfFirst { !it.open }.takeIf { it >= 0 } ?: 0
            total -= list[oldest].text.length
            list.removeAt(oldest)
        }
        return list
    }

    companion object {
        const val MAX_CHARS = 20_000
        private const val BACKSPACE = 0x08
        private const val TAB = 0x09
        private const val LF = 0x0A
        private const val CR = 0x0D
        private const val SPACE = 0x20
        private const val DELETE = 0x7F
        private const val BOM = 0xFEFF
        private const val LINE_SEPARATOR = 0x2028
        private const val PARAGRAPH_SEPARATOR = 0x2029
        private val C1_CONTROLS = 0x80..0x9F
        private val BIDI_CONTROLS = 0x202A..0x202E
        private val BIDI_ISOLATES = 0x2066..0x2069
    }
}

/** What to send while the user types into the RTT field: one character at a time, as RTT expects. */
object RttTyping {
    /**
     * The characters that turn [before] into [after] on the other side: a backspace for every character (code point)
     * after the common start, then the new characters. Typing one letter sends one letter; deleting sends one
     * backspace; an autocorrect that changes a word sends the backspaces and the new word. Line breaks in pasted text
     * become spaces: a message ends only with Send.
     */
    fun diff(before: String, after: String): String {
        val a = clean(before)
        val b = clean(after)
        var common = 0
        val max = minOf(a.length, b.length)
        while (common < max && a[common] == b[common]) common++
        // Never split a surrogate pair.
        if (common > 0 && Character.isHighSurrogate(a[common - 1])) common--
        val removed = a.codePointCount(common, a.length)
        return "\b".repeat(removed) + b.substring(common)
    }

    /** The field's text as it is sent (line breaks as spaces). */
    fun clean(text: String): String = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')

    /** [text] split into single characters (code points), each written on its own. */
    fun characters(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out += String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        return out
    }

    /** What Send writes: the line break that ends the message. */
    const val END_OF_MESSAGE = "\n"
}
