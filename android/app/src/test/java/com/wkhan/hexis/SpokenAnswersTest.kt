package com.wkhan.hexis

import com.wkhan.hexis.domain.voice.SpokenAnswers
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4 — the on-device spoken answer/briefing composer. Pure, deterministic. */
class SpokenAnswersTest {

    private fun snap(
        today: Int = 0, next: Int = 0, attn: Int = 0, habits: Int = 0,
    ) = SpokenAnswers.Snapshot(
        greeting = "Good morning",
        todayCount = today, doNextCount = next, needsAttention = attn, habitsLoggedToday = habits,
    )

    @Test fun briefing_pluralizesAndOrders() {
        val b = SpokenAnswers.briefing(snap(today = 3, next = 5, attn = 1, habits = 2))
        assertTrue(b.startsWith("Good morning."))
        assertTrue(b.contains("3 tasks due today"))
        assertTrue(b.contains("5 tasks to do next"))
        assertTrue(b.contains("1 task need")) // singular task
        assertTrue(b.contains("2 habits today"))
    }

    @Test fun briefing_allClearIsEncouraging() {
        val b = SpokenAnswers.briefing(snap())
        assertTrue(b.contains("Nothing is due today"))
        assertTrue(b.contains("all clear"))
    }

    @Test fun answer_routesByKeyword() {
        assertTrue(SpokenAnswers.answer("what's due today", snap(today = 4)).contains("4 tasks due today"))
        assertTrue(SpokenAnswers.answer("what should I do next", snap(next = 2)).contains("2 tasks to do next"))
        assertTrue(SpokenAnswers.answer("anything overdue?", snap(attn = 0)).contains("Nothing needs attention"))
        assertTrue(SpokenAnswers.answer("how are my habits", snap(habits = 1)).contains("1 habit today"))
        assertTrue(SpokenAnswers.answer("give me a briefing", snap(today = 1)).startsWith("Good morning."))
    }

    @Test fun answer_unknownGivesHelp() {
        assertTrue(SpokenAnswers.answer("tell me a joke", snap()).contains("daily briefing"))
    }
}
