package com.todocompanion.app

import com.todocompanion.app.domain.TextInsights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Track 3.3 — on-device sentiment (with negation) and TF-IDF-style theme extraction, both pure Kotlin.
 */
class TextInsightsTest {

    @Test fun sentimentReadsPositiveAndNegativeTone() {
        assertTrue("a glowing day scores positive", TextInsights.sentiment("An amazing, wonderful day — I felt proud and grateful.") > 0.3)
        assertTrue("a rough day scores negative", TextInsights.sentiment("Awful day, exhausted and overwhelmed, everything failed.") < -0.3)
        assertEquals("no lexicon words → neutral", 0.0, TextInsights.sentiment("Met the team at the usual place."), 1e-9)
        assertEquals("empty → neutral", 0.0, TextInsights.sentiment("   "), 1e-9)
    }

    @Test fun negationFlipsPolarity() {
        val plain = TextInsights.sentiment("today was good")
        val negated = TextInsights.sentiment("today was not good")
        assertTrue("plain positive", plain > 0)
        assertTrue("negated reads worse than plain", negated < plain)
        assertTrue("\"not bad\" is not strongly negative", TextInsights.sentiment("it was not bad") > -0.2)
    }

    @Test fun labelBandsAreMonotonic() {
        assertEquals("very positive", TextInsights.label(0.9))
        assertEquals("positive", TextInsights.label(0.3))
        assertEquals("mixed", TextInsights.label(0.0))
        assertEquals("negative", TextInsights.label(-0.3))
        assertEquals("very negative", TextInsights.label(-0.9))
    }

    @Test fun themesSurfaceRecurringContentWordsNotStopWords() {
        val docs = listOf(
            "Long run this morning, running felt strong.",
            "A quiet run by the river, running clears my head.",
            "Deadline stress at work, the deadline loomed all day.",
            "Family dinner, the family laughed a lot.",
            "Another run before the family arrived.",
        )
        val themes = TextInsights.themes(docs, topN = 5)
        val words = themes.map { it.word }
        assertTrue("recurring 'run' surfaces", words.contains("run"))
        assertTrue("no stop-word leaks in", words.none { it in TextInsights.STOP_WORDS })
        // "run" appears across three days — its document frequency should be the strongest.
        assertEquals("run", themes.first().word)
    }

    @Test fun threeWordsGivesUpToThreeDisplayWords() {
        val docs = listOf(
            "focus focus deep work",
            "focus and rest, good rest",
            "rest day, family time family",
        )
        val three = TextInsights.threeWords(docs)
        assertTrue("at most three", three.size <= 3)
        assertTrue("titlecased for display", three.all { it.first().isUpperCase() })
    }

    @Test fun themesEmptyOnEmptyCorpus() {
        assertTrue(TextInsights.themes(emptyList()).isEmpty())
        assertTrue(TextInsights.themes(listOf("", "  ")).isEmpty())
    }
}
