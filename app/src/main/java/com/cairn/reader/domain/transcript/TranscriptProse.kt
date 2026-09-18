package com.cairn.reader.domain.transcript

/**
 * Reflows a caption/transcript's timed [TranscriptCue]s into **flowing prose** for comfortable
 * reading — a reading app should present a transcript as paragraphs, not one stiff line per caption.
 *
 * Cues are joined into sentences and grouped into paragraphs on a natural break (a speech pause, or a
 * sentence end past a soft length). The timing is not thrown away: every character maps back to the
 * cue that produced it ([timeAt]), so a tap still seeks the media and a text selection still resolves
 * to a real start/end timestamp — the reader reads seamlessly without ever worrying about the exact
 * line, yet highlights and tap-to-seek stay perfectly anchored.
 */
class TranscriptProse private constructor(
    /** The full flowing text (paragraphs separated by a blank line). Highlight offsets index into this. */
    val text: String,
    val paragraphs: List<Para>,
    private val spans: List<Span>,
) {
    /** A paragraph: its character range in [text] and the media time it spans. */
    data class Para(val charStart: Int, val charEnd: Int, val startMs: Long, val endMs: Long) {
        fun textIn(full: String): String = full.substring(charStart, charEnd)
    }

    /** One cue's character range in [text] and its media time — the atom of the char↔time mapping. */
    data class Span(val charStart: Int, val charEnd: Int, val startMs: Long, val endMs: Long)

    val isEmpty: Boolean get() = paragraphs.isEmpty()

    /** The media start-time (ms) of the cue covering [charOffset] — for tap-to-seek. */
    fun timeAt(charOffset: Int): Long = spanAt(charOffset)?.startMs ?: 0L

    /** The start-time of a selection [s,e): the first cue it touches. */
    fun startMsForRange(s: Int, e: Int): Long = spanAt(s)?.startMs ?: timeAt(minOf(s, e))

    /** The end-time of a selection [s,e): the last cue it touches. */
    fun endMsForRange(s: Int, e: Int): Long = spanAt((e - 1).coerceAtLeast(s))?.endMs ?: timeAt(e)

    /** The character range of the cue playing at [ms], for a live "now playing" highlight, or null. */
    fun charRangeAtTime(ms: Long): IntRange? {
        val s = spans.firstOrNull { ms >= it.startMs && ms < it.endMs.coerceAtLeast(it.startMs + 1) } ?: return null
        return s.charStart until s.charEnd
    }

    /** Binary-search the span whose range contains [offset], else the nearest preceding one. */
    private fun spanAt(offset: Int): Span? {
        if (spans.isEmpty()) return null
        var lo = 0
        var hi = spans.size - 1
        var best: Span? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = spans[mid]
            when {
                offset < s.charStart -> hi = mid - 1
                offset >= s.charEnd -> { best = s; lo = mid + 1 }
                else -> return s
            }
        }
        return best ?: spans.first()
    }

    companion object {
        private const val PARA_GAP_MS = 2_500L   // a pause this long starts a new paragraph
        private const val PARA_SOFT_LEN = 320    // past this, a sentence end starts a new paragraph
        private const val PARA_HARD_LEN = 780    // never let a paragraph run longer than this

        fun from(cues: List<TranscriptCue>): TranscriptProse {
            val sb = StringBuilder()
            val spans = ArrayList<Span>()
            val paras = ArrayList<Para>()
            var paraStart = 0
            var paraStartMs = 0L
            var prevEnd = 0L
            var lastPiece = ""

            fun closeParagraph(endMs: Long) {
                if (sb.length > paraStart) paras.add(Para(paraStart, sb.length, paraStartMs, endMs))
            }

            for (cue in cues) {
                val piece = cue.text.trim().replace(Regex("\\s+"), " ")
                if (piece.isEmpty()) continue
                // Drop a verbatim rolling-caption repeat (common in auto-captions).
                if (piece == lastPiece) { prevEnd = cue.endMs; continue }

                if (sb.length == paraStart) {
                    // Starting a (possibly first) paragraph.
                    paraStartMs = cue.startMs
                } else {
                    val gap = cue.startMs - prevEnd
                    val curLen = sb.length - paraStart
                    val sentenceEnd = sb.isNotEmpty() && sb.last() in SENTENCE_ENDERS
                    val breakHere = gap > PARA_GAP_MS ||
                        (curLen > PARA_SOFT_LEN && sentenceEnd) ||
                        curLen > PARA_HARD_LEN
                    if (breakHere) {
                        closeParagraph(prevEnd)
                        sb.append("\n\n")
                        paraStart = sb.length
                        paraStartMs = cue.startMs
                    } else {
                        sb.append(' ')
                    }
                }
                val spanStart = sb.length
                sb.append(piece)
                spans.add(Span(spanStart, sb.length, cue.startMs, cue.endMs))
                prevEnd = cue.endMs
                lastPiece = piece
            }
            closeParagraph(prevEnd)
            return TranscriptProse(sb.toString(), paras, spans)
        }

        private val SENTENCE_ENDERS = charArrayOf('.', '?', '!', '”', '"', '。', '؟')
    }
}
