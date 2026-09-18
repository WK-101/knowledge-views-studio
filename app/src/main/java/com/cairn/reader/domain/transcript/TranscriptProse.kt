package com.cairn.reader.domain.transcript

/**
 * Reflows a caption/transcript's timed [TranscriptCue]s into one continuous block of **flowing prose**
 * for comfortable reading — a reading app should present a transcript as paragraphs, not one stiff
 * line per caption.
 *
 * The whole transcript is a single string ([text]) so it can be rendered in one selectable Text: a
 * reader can then select across paragraph (time) boundaries in a single gesture. Each paragraph opens
 * with a compact, tappable timecode ("m:ss") embedded inline in the text and recorded in [timeMarks]
 * so it can be styled and used to jump. Every character still maps back to the cue that produced it
 * ([timeAt]), so a tap seeks and a selection resolves to a real start/end time.
 */
class TranscriptProse private constructor(
    /** The full flowing text (inline timecodes + paragraphs separated by a blank line). Highlight
     *  offsets and selections index into this. */
    val text: String,
    val paragraphs: List<Para>,
    /** The inline "m:ss" timecode labels, one per paragraph, for styling + tap-to-jump. */
    val timeMarks: List<Mark>,
    private val spans: List<Span>,
) {
    data class Para(val charStart: Int, val charEnd: Int, val startMs: Long, val endMs: Long)
    data class Span(val charStart: Int, val charEnd: Int, val startMs: Long, val endMs: Long)
    /** An inline timecode label occupying `[start, end)` in [text], jumping to [ms]. */
    data class Mark(val start: Int, val end: Int, val ms: Long)

    val isEmpty: Boolean get() = paragraphs.isEmpty()

    /** The media start-time (ms) of whatever is at [charOffset] — a timecode label or a cue. */
    fun timeAt(charOffset: Int): Long =
        timeMarks.firstOrNull { charOffset in it.start until it.end }?.ms
            ?: spanAt(charOffset)?.startMs ?: 0L

    /** True if [charOffset] falls on an inline timecode label. */
    fun markAt(charOffset: Int): Mark? = timeMarks.firstOrNull { charOffset in it.start until it.end }

    fun startMsForRange(s: Int, e: Int): Long =
        markAt(s)?.ms ?: spanAt(s)?.startMs ?: timeAt(minOf(s, e))

    fun endMsForRange(s: Int, e: Int): Long = spanAt((e - 1).coerceAtLeast(s))?.endMs ?: timeAt(e)

    /** The character range of the cue playing at [ms], for a live "now playing" highlight, or null. */
    fun charRangeAtTime(ms: Long): IntRange? {
        val s = spans.firstOrNull { ms >= it.startMs && ms < it.endMs.coerceAtLeast(it.startMs + 1) } ?: return null
        return s.charStart until s.charEnd
    }

    private fun spanAt(offset: Int): Span? {
        if (spans.isEmpty()) return null
        var lo = 0; var hi = spans.size - 1; var best: Span? = null
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
        private const val PARA_GAP_MS = 2_500L
        private const val PARA_SOFT_LEN = 320
        private const val PARA_HARD_LEN = 780
        private val SENTENCE_ENDERS = charArrayOf('.', '?', '!', '”', '"', '。', '؟')

        fun from(cues: List<TranscriptCue>): TranscriptProse {
            val sb = StringBuilder()
            val spans = ArrayList<Span>()
            val paras = ArrayList<Para>()
            val marks = ArrayList<Mark>()
            var paraStart = 0
            var paraStartMs = 0L
            var prevEnd = 0L
            var lastPiece = ""
            var paraOpen = false

            fun openParagraph(startMs: Long) {
                paraStart = sb.length
                paraStartMs = startMs
                val ls = sb.length
                sb.append(formatTimestamp(startMs)).append("  ")
                marks.add(Mark(ls, sb.length, startMs))
                paraOpen = true
            }
            fun closeParagraph(endMs: Long) {
                if (paraOpen) paras.add(Para(paraStart, sb.length, paraStartMs, endMs))
                paraOpen = false
            }

            for (cue in cues) {
                val piece = cue.text.trim().replace(Regex("\\s+"), " ")
                if (piece.isEmpty()) continue
                if (piece == lastPiece) { prevEnd = cue.endMs; continue } // drop rolling-caption repeats
                if (!paraOpen) {
                    openParagraph(cue.startMs)
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
                        openParagraph(cue.startMs)
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
            return TranscriptProse(sb.toString(), paras, marks, spans)
        }
    }
}
