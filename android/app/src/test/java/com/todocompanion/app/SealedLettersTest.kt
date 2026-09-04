package com.todocompanion.app

import com.todocompanion.app.domain.SealedLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Track 3.4 — the pure "what's changed since you sealed this" diff for a letter to your future self.
 */
class SealedLettersTest {

    @Test fun deltaPhraseCountsNewFinishes() {
        val d = SealedLetters.diff(sealedCount = 100, currentCount = 243, createdEpochDay = 0, todayEpochDay = 364)
        assertEquals(143, d.delta)
        assertTrue(d.phrase.contains("143 more things"))
    }

    @Test fun singularReadsNaturally() {
        val d = SealedLetters.diff(sealedCount = 10, currentCount = 11, createdEpochDay = 0, todayEpochDay = 30)
        assertEquals(1, d.delta)
        assertTrue(d.phrase.contains("1 more thing since"))
        assertTrue(d.phrase.endsWith("since then."))
    }

    @Test fun neverGoesNegativeAndSaysSoGently() {
        val d = SealedLetters.diff(sealedCount = 50, currentCount = 40, createdEpochDay = 0, todayEpochDay = 10)
        assertEquals(0, d.delta)
        assertTrue(d.phrase.contains("No new finishes"))
        assertNull("no pace line without a delta", d.paceLine)
    }

    @Test fun paceLineAppearsOnlyWithEnoughTimeAndDelta() {
        assertNull("under a week → no pace", SealedLetters.diff(0, 10, 0, 3).paceLine)
        val d = SealedLetters.diff(0, 14, 0, 14) // 14 finishes over 2 weeks → ~7/week
        assertTrue(d.paceLine!!.contains("a week"))
    }
}
