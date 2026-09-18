package com.cairn.reader.domain.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure reflow/mapping tests for the transcript prose builder — no Android dependencies. */
class TranscriptProseTest {

    private fun cue(startMs: Long, endMs: Long, text: String) = TranscriptCue(startMs, endMs, text)

    @Test fun joinsShortCuesIntoFlowingParagraph() {
        val prose = TranscriptProse.from(
            listOf(
                cue(0, 900, "All right, so here we are"),
                cue(900, 1800, "in front of the elephants"),
                cue(1800, 2600, "the cool thing about these guys"),
            ),
        )
        // One paragraph, cues joined with spaces (not one line per cue); an inline timecode leads it.
        assertEquals(1, prose.paragraphs.size)
        assertEquals(
            "0:00  All right, so here we are in front of the elephants the cool thing about these guys",
            prose.text,
        )
        assertEquals(1, prose.timeMarks.size)
        assertEquals(0L, prose.timeMarks[0].ms)
    }

    @Test fun startsNewParagraphOnLongPause() {
        val prose = TranscriptProse.from(
            listOf(
                cue(0, 1000, "First topic ends here."),
                // 4s gap -> new paragraph
                cue(5000, 6000, "A brand new topic begins."),
            ),
        )
        assertEquals(2, prose.paragraphs.size)
        assertTrue(prose.text.contains("\n\n"))
        assertEquals(0L, prose.paragraphs[0].startMs)
        assertEquals(5000L, prose.paragraphs[1].startMs)
    }

    @Test fun mapsCharacterOffsetBackToCueTime() {
        val prose = TranscriptProse.from(
            listOf(
                cue(0, 1000, "hello world"),
                cue(3000, 4000, "second cue here"),
            ),
        )
        // The word "second" starts partway through the text; its offset resolves to the 2nd cue.
        val idx = prose.text.indexOf("second")
        assertTrue(idx > 0)
        assertEquals(3000L, prose.timeAt(idx))
        assertEquals(0L, prose.timeAt(0))
    }

    @Test fun rangeResolvesStartAndEndTimes() {
        val prose = TranscriptProse.from(
            listOf(
                cue(0, 1000, "alpha beta"),
                cue(2000, 3000, "gamma delta"),
            ),
        )
        val s = prose.text.indexOf("beta")
        val e = prose.text.indexOf("gamma") + "gamma".length
        assertEquals(0L, prose.startMsForRange(s, e))
        assertEquals(3000L, prose.endMsForRange(s, e))
    }

    @Test fun dropsRollingDuplicateCaptions() {
        val prose = TranscriptProse.from(
            listOf(
                cue(0, 1000, "repeated line"),
                cue(1000, 2000, "repeated line"),
                cue(2000, 3000, "new line"),
            ),
        )
        // The verbatim repeat is collapsed (with the leading inline timecode).
        assertEquals("0:00  repeated line new line", prose.text)
    }

    @Test fun timecodeMarkResolvesToParagraphTimeAndRangeSpansParagraphs() {
        val prose = TranscriptProse.from(
            listOf(
                cue(0, 1000, "first part."),
                cue(6000, 7000, "second part starts later."),
            ),
        )
        // Two paragraphs (long pause), each with an inline timecode mark.
        assertEquals(2, prose.timeMarks.size)
        // Tapping the second paragraph's timecode jumps to 6000, not the end of paragraph one.
        val secondMark = prose.timeMarks[1]
        assertEquals(6000L, prose.timeAt(secondMark.start))
        // A selection spanning both paragraphs resolves start=first cue, end=last cue.
        val s = prose.text.indexOf("first")
        val e = prose.text.indexOf("later") + "later".length
        assertEquals(0L, prose.startMsForRange(s, e))
        assertEquals(7000L, prose.endMsForRange(s, e))
    }

    @Test fun charRangeAtTimeFindsPlayingCue() {
        val prose = TranscriptProse.from(
            listOf(
                cue(0, 1000, "alpha beta"),
                cue(2000, 3000, "gamma delta"),
            ),
        )
        val r = prose.charRangeAtTime(2500)
        assertNotNull(r)
        assertEquals("gamma delta", prose.text.substring(r!!.first, r.last + 1))
    }
}
