package com.todocompanion.app

import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.Routine
import com.todocompanion.app.domain.Routines
import com.todocompanion.app.domain.nlp.QuickTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier W — pure-logic coverage for routine tags, omni-capture routing, and the new muted settings. */
class TierWTests {

    // ---- W6: routine tags round-trip ----
    @Test fun routinesRoundTrip() {
        val list = listOf(
            Routine(id = "1", name = "Deep work", emoji = "🧠", activityId = "a1", habitCategory = "Focus"),
            Routine(id = "2", name = "Wind down", note = "phone away"),
        )
        val back = Routines.parse(Routines.encode(list))
        assertEquals(2, back.size)
        assertEquals("Deep work", back[0].name)
        assertEquals("🧠", back[0].emoji)
        assertEquals("a1", back[0].activityId)
        assertEquals("phone away", back[1].note)
        assertEquals("🔗", back[1].emoji) // default preserved
    }

    @Test fun routinesByNameIsCaseInsensitiveAndTrims() {
        val list = listOf(Routine(id = "1", name = "Deep Work"))
        assertEquals("1", Routines.byName(list, "  deep work ")?.id)
        assertNull(Routines.byName(list, "sleep"))
    }

    @Test fun routinesParseBlankIsEmpty() {
        assertTrue(Routines.parse("").isEmpty())
        assertTrue(Routines.parse("   ").isEmpty())
        assertTrue(Routines.parse("not json at all").isEmpty()) // never throws
    }

    // ---- W8: muted habits / lists persist losslessly through the KV store ----
    @Test fun mutedSettingsRoundTrip() {
        val s = AppSettings(
            routinesJson = Routines.encode(listOf(Routine(id = "1", name = "Morning"))),
            mutedHabits = setOf("h1", "h2"),
            mutedLists = setOf("L9"),
        )
        val back = AppSettings.fromMap(s.toMap())
        assertEquals(setOf("h1", "h2"), back.mutedHabits)
        assertEquals(setOf("L9"), back.mutedLists)
        assertEquals(1, Routines.parse(back.routinesJson).size)
    }

    @Test fun mutedEmptyStaysEmpty() {
        val back = AppSettings.fromMap(AppSettings().toMap())
        assertTrue(back.mutedHabits.isEmpty())
        assertTrue(back.mutedLists.isEmpty())
        assertEquals("", back.routinesJson)
    }

    // ---- W1: omni-capture verb/token detection is what routes to the timer ----
    // The routing predicate itself: a leading track/start/timer verb, or a bare @activity token.
    private fun routesToTimer(raw: String): Boolean {
        val verb = Regex("^(?:track|start|timer)\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw.trim())
        val tok = QuickTokens.parse(raw.trim())
        return verb != null || (tok.activity != null && tok.text.isBlank())
    }

    @Test fun omniCaptureRoutesTimerVerbs() {
        assertTrue(routesToTimer("track deep work"))
        assertTrue(routesToTimer("Start Reading"))
        assertTrue(routesToTimer("timer gym"))
        assertTrue(routesToTimer("@exercise"))          // bare activity token
    }

    @Test fun omniCaptureLeavesPlainCaptureAlone() {
        assertTrue(!routesToTimer("buy milk"))
        assertTrue(!routesToTimer("startup pitch notes"))   // "startup" is not the verb "start "
        assertTrue(!routesToTimer("gym @exercise"))          // has text → a task tagged to an activity
    }
}
