package com.todocompanion.app

import com.todocompanion.app.domain.EmotionQuadrant
import com.todocompanion.app.domain.EmotionWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 1 — the pure emotion-word set: quadrant membership, the lookup, and the shape of the grid. */
class EmotionWordsTest {

    @Test fun eachWordResolvesToItsQuadrant() {
        EmotionWords.HIGH_PLEASANT.forEach { assertEquals("quadrant of $it", EmotionQuadrant.HIGH_PLEASANT, EmotionWords.quadrantOf(it)) }
        EmotionWords.LOW_PLEASANT.forEach { assertEquals("quadrant of $it", EmotionQuadrant.LOW_PLEASANT, EmotionWords.quadrantOf(it)) }
        EmotionWords.HIGH_UNPLEASANT.forEach { assertEquals("quadrant of $it", EmotionQuadrant.HIGH_UNPLEASANT, EmotionWords.quadrantOf(it)) }
        EmotionWords.LOW_UNPLEASANT.forEach { assertEquals("quadrant of $it", EmotionQuadrant.LOW_UNPLEASANT, EmotionWords.quadrantOf(it)) }
    }

    @Test fun lookupIsCaseAndWhitespaceInsensitive() {
        assertEquals(EmotionQuadrant.HIGH_PLEASANT, EmotionWords.quadrantOf("excited"))
        assertEquals(EmotionQuadrant.HIGH_PLEASANT, EmotionWords.quadrantOf("  EXCITED  "))
        assertEquals(EmotionQuadrant.LOW_UNPLEASANT, EmotionWords.quadrantOf("Numb"))
    }

    @Test fun unknownWordsReturnNull() {
        assertNull(EmotionWords.quadrantOf(""))
        assertNull(EmotionWords.quadrantOf("hangry"))
        assertFalse(EmotionWords.isKnown("whatever"))
        assertTrue(EmotionWords.isKnown("Calm"))
    }

    @Test fun theSetIsTwentyFourWordsAcrossFourQuadrantsOfSix() {
        assertEquals(4, EmotionWords.QUADRANTS.size)
        EmotionWords.QUADRANTS.forEach { (_, words) -> assertEquals(6, words.size) }
        assertEquals(24, EmotionWords.ALL.size)
        // No duplicate words across the whole set (each word maps to exactly one quadrant).
        assertEquals(24, EmotionWords.ALL.map { it.lowercase() }.toSet().size)
    }
}
