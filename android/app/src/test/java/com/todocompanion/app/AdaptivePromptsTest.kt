package com.todocompanion.app

import com.todocompanion.app.domain.AdaptivePrompts
import com.todocompanion.app.domain.DayPrompts
import com.todocompanion.app.domain.PromptKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 1 — the adaptive reflection prompt: which kind a day gets, and deterministic rotation. */
class AdaptivePromptsTest {

    @Test fun goodDayGetsSavor() {
        assertEquals(PromptKind.SAVOR, AdaptivePrompts.kindFor(rating = 4, mood = 0))
        assertEquals(PromptKind.SAVOR, AdaptivePrompts.kindFor(rating = 0, mood = 5))
        assertEquals(PromptKind.SAVOR, AdaptivePrompts.kindFor(rating = 5, mood = 3))
        assertEquals(PromptKind.SAVOR, AdaptivePrompts.promptFor(100L, 5, 4).kind)
    }

    @Test fun hardDayGetsReframe() {
        assertEquals(PromptKind.REFRAME, AdaptivePrompts.kindFor(rating = 1, mood = 0))
        assertEquals(PromptKind.REFRAME, AdaptivePrompts.kindFor(rating = 0, mood = 2))
        assertEquals(PromptKind.REFRAME, AdaptivePrompts.kindFor(rating = 2, mood = 3))
        assertEquals(PromptKind.REFRAME, AdaptivePrompts.promptFor(100L, 1, 1).kind)
    }

    @Test fun middlingOrUnsetDayIsNeutral() {
        assertEquals(PromptKind.NEUTRAL, AdaptivePrompts.kindFor(rating = 3, mood = 3))
        assertEquals(PromptKind.NEUTRAL, AdaptivePrompts.kindFor(rating = 0, mood = 0))
        // A neutral day falls back to the existing rotating DayPrompts set.
        val p = AdaptivePrompts.promptFor(7L, 3, 3)
        assertEquals(PromptKind.NEUTRAL, p.kind)
        assertEquals(DayPrompts.promptFor(7L), p.text)
    }

    @Test fun goodIsCheckedBeforeHardWhenSignalsConflict() {
        // rating >= 4 wins even if the mood face was low — spec order: good first, then hard.
        assertEquals(PromptKind.SAVOR, AdaptivePrompts.kindFor(rating = 4, mood = 1))
        assertEquals(PromptKind.SAVOR, AdaptivePrompts.kindFor(rating = 1, mood = 4))
    }

    @Test fun rotationIsDeterministicAndVariesByDay() {
        // Same day + same inputs → identical prompt every call.
        val a = AdaptivePrompts.promptFor(200L, 5, 5)
        val b = AdaptivePrompts.promptFor(200L, 5, 5)
        assertEquals(a.text, b.text)
        assertEquals(a.kind, b.kind)

        // The savor rotation lands on the curated word for its floor-mod index.
        val n = AdaptivePrompts.SAVOR.size
        assertEquals(AdaptivePrompts.SAVOR[(200L % n).toInt()], AdaptivePrompts.promptFor(200L, 5, 5).text)
        // Stepping a full cycle returns the same prompt.
        assertEquals(
            AdaptivePrompts.promptFor(200L, 5, 5).text,
            AdaptivePrompts.promptFor(200L + n, 5, 5).text,
        )

        // Negative epoch days still rotate (floor-mod), and the answer is one of the curated prompts.
        assertTrue(AdaptivePrompts.promptFor(-3L, 1, 1).text in AdaptivePrompts.REFRAME)
    }
}
