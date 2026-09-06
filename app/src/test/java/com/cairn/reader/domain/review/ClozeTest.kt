package com.cairn.reader.domain.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClozeTest {

    @Test fun `returns null for a too-short quote`() {
        assertNull(Cloze.of("Too short here"))       // < 5 tokens
        assertNull(Cloze.of(""))
    }

    @Test fun `blanks a load-bearing word and hides it from the prompt`() {
        val card = Cloze.of("The Apollo program landed twelve astronauts on the Moon")
        assertNotNull(card)
        card!!
        assertTrue("answer is a real token", card.answer.isNotBlank())
        assertTrue("prompt has a blank", card.prompt.contains("____"))
        assertTrue("answer removed from prompt", !card.prompt.contains(card.answer))
    }

    @Test fun `prefers a number as the answer`() {
        // A 4+ char token carrying a digit outscores the plain content words around it.
        val card = Cloze.of("the rocket finally launched in 1969 during a warm summer")
        assertNotNull(card)
        assertEquals("1969", card!!.answer)
    }

    @Test fun `does not blank a stopword`() {
        val card = Cloze.of("they were about to leave when the storm arrived suddenly")
        assertNotNull(card)
        assertTrue(card!!.answer.lowercase() !in setOf("they", "were", "about", "when", "the"))
    }
}
