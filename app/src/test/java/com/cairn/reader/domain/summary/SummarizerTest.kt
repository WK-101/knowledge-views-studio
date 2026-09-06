package com.cairn.reader.domain.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerTest {

    private val summarizer = Summarizer()

    // Four distinct, well-formed sentences (each >= 40 chars with >= 5 spaces so the splitter keeps them).
    private val s1 = "The old lighthouse stood alone against the grey and restless northern sea."
    private val s2 = "Every evening its lamp turned slowly, sweeping a beam across the dark water."
    private val s3 = "Sailors far offshore trusted that steady light to guide them safely toward home."
    private val s4 = "When the storm finally broke, the keeper climbed the long stair once again."

    @Test fun `empty text yields no sentences`() {
        assertTrue(summarizer.summarize("").isEmpty())
    }

    @Test fun `a too-short fragment is filtered out`() {
        assertTrue(summarizer.summarize("Too short.").isEmpty())
    }

    @Test fun `returns every sentence when fewer than the cap`() {
        val text = "$s1 $s2 $s3"
        val out = summarizer.summarize(text, maxSentences = 5)
        assertEquals(listOf(s1, s2, s3), out)
    }

    @Test fun `respects the maxSentences cap`() {
        val text = "$s1 $s2 $s3 $s4"
        val out = summarizer.summarize(text, maxSentences = 2)
        assertEquals(2, out.size)
    }

    @Test fun `keeps selected sentences in original reading order`() {
        val text = "$s1 $s2 $s3 $s4"
        val out = summarizer.summarize(text, maxSentences = 2)
        // Whatever two rank highest, they must be real input sentences kept in source order.
        val order = listOf(s1, s2, s3, s4)
        out.forEach { assertTrue("unexpected sentence: $it", it in order) }
        val indices = out.map { order.indexOf(it) }
        assertEquals(indices.sorted(), indices)
    }
}
