package com.todocompanion.app

import com.todocompanion.app.domain.ReflectionCompanion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 3 (feature E) — the rule-based reflection companion: good / hard / neutral chain selection, plus
 * composing and merging answers back into the day's reflection.
 */
class ReflectionCompanionTest {

    @Test fun goodDayPicksSavor() {
        assertEquals(ReflectionCompanion.Track.SAVOR, ReflectionCompanion.chainFor(rating = 5, mood = 4).track)
        assertEquals(ReflectionCompanion.Track.SAVOR, ReflectionCompanion.chainFor(rating = 4, mood = 0).track)
        // Mood-driven, rating unset.
        assertEquals(ReflectionCompanion.Track.SAVOR, ReflectionCompanion.chainFor(rating = 0, mood = 5).track)
    }

    @Test fun hardDayPicksReframe() {
        assertEquals(ReflectionCompanion.Track.REFRAME, ReflectionCompanion.chainFor(rating = 1, mood = 0).track)
        assertEquals(ReflectionCompanion.Track.REFRAME, ReflectionCompanion.chainFor(rating = 0, mood = 2).track)
    }

    @Test fun neutralDayPicksExamen() {
        assertEquals(ReflectionCompanion.Track.EXAMEN, ReflectionCompanion.chainFor(rating = 0, mood = 0).track)
        assertEquals(ReflectionCompanion.Track.EXAMEN, ReflectionCompanion.chainFor(rating = 3, mood = 3).track)
    }

    @Test fun hardWinsWhenSignalsConflict() {
        // A low rating but a high mood → still the kinder reframe track (the hard signal wins).
        assertEquals(ReflectionCompanion.Track.REFRAME, ReflectionCompanion.chainFor(rating = 2, mood = 5).track)
    }

    @Test fun everyChainHasTwoToThreePrompts() {
        listOf(
            ReflectionCompanion.chainFor(5, 5),
            ReflectionCompanion.chainFor(1, 1),
            ReflectionCompanion.chainFor(0, 0),
        ).forEach {
            assertTrue("2–3 prompts", it.prompts.size in 2..3)
            assertTrue("has an intro", it.intro.isNotBlank())
            it.prompts.forEach { p -> assertTrue(p.isNotBlank()) }
        }
    }

    @Test fun composeDropsBlankAnswersAndPairsQuestions() {
        val chain = ReflectionCompanion.chainFor(rating = 5, mood = 5)
        val block = ReflectionCompanion.compose(chain, listOf("The morning walk", "", "Do it again Saturday"))
        // Two answered → two couplets; the blank middle is dropped.
        assertTrue(block.contains("The morning walk"))
        assertTrue(block.contains("Do it again Saturday"))
        assertTrue(block.contains(chain.prompts[0]))
        assertFalse(block.contains(chain.prompts[1])) // its answer was blank
        assertEquals("", ReflectionCompanion.compose(chain, listOf("", "", "")))
    }

    // ── Track 3.6 — the adaptive, multi-turn chain ──

    @Test fun adaptiveChainAlwaysEndsOnAForwardStep() {
        val chain = ReflectionCompanion.adaptiveChain(ReflectionCompanion.Signals(rating = 1))
        assertEquals(ReflectionCompanion.Track.REFRAME, chain.track)
        assertTrue("closes on a WOOP-forward if-then", chain.prompts.last().contains("if-then"))
        assertTrue("richer than the bare 3-prompt spine possibility", chain.prompts.size >= 4)
    }

    @Test fun adaptiveChainGrowsWithMoreSignals() {
        val plain = ReflectionCompanion.adaptiveChain(ReflectionCompanion.Signals(rating = 3))
        val rich = ReflectionCompanion.adaptiveChain(
            ReflectionCompanion.Signals(rating = 3, energy = 1, wasWin = true, obstacleRecurred = true)
        )
        assertTrue("more signals → more prompts", rich.prompts.size > plain.prompts.size)
        assertTrue(rich.prompts.any { it.contains("break the pattern") })
        assertTrue(rich.prompts.any { it.contains("earn it") })
        assertTrue(rich.prompts.any { it.contains("refill") })
    }

    @Test fun adaptiveChainDeepensOnASubstantialFirstAnswer() {
        val s = ReflectionCompanion.Signals(rating = 5)
        val shallow = ReflectionCompanion.adaptiveChain(s, listOf("ok"))
        val deep = ReflectionCompanion.adaptiveChain(s, listOf("A really meaningful morning with family"))
        assertTrue("a long first answer earns a deepening follow-up", deep.prompts.size > shallow.prompts.size)
    }

    @Test fun mergeAppendsWithoutDuplicating() {
        val existing = "Felt good overall."
        val block = "What was the best moment of today?\n— The walk"
        val merged = ReflectionCompanion.merge(existing, block)
        assertTrue(merged.startsWith(existing))
        assertTrue(merged.contains(block))
        // Merging again is idempotent.
        assertEquals(merged, ReflectionCompanion.merge(merged, block))
        // Empty block leaves the original untouched; empty existing yields just the block.
        assertEquals(existing, ReflectionCompanion.merge(existing, ""))
        assertEquals(block, ReflectionCompanion.merge("", block))
    }
}
