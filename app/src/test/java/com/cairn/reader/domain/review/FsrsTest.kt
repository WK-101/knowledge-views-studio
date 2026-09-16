package com.cairn.reader.domain.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant tests for the FSRS scheduler. These don't pin exact reference values (the weights can
 * evolve); they lock in the properties that make it *correct as a scheduler*: better grades push
 * the next review further out, a lapse shrinks stability, difficulty/stability stay in range, and
 * the desired-retention knob moves intervals the right way.
 */
class FsrsTest {
    private val now = 1_000_000_000_000L
    private fun newCard() = FsrsState()

    @Test fun newCardIntervalsIncreaseWithGrade() {
        val again = Fsrs.review(newCard(), Grade.AGAIN, now, null)
        val hard = Fsrs.review(newCard(), Grade.HARD, now, null)
        val good = Fsrs.review(newCard(), Grade.GOOD, now, null)
        val easy = Fsrs.review(newCard(), Grade.EASY, now, null)

        assertEquals("Again relearns in-session", 0, again.intervalDays)
        assertTrue("Hard schedules at least a day out", hard.intervalDays >= 1)
        assertTrue("Good >= Hard", good.intervalDays >= hard.intervalDays)
        assertTrue("Easy >= Good", easy.intervalDays >= good.intervalDays)
        assertTrue("Easy strictly beats Hard for a new card", easy.intervalDays > hard.intervalDays)
    }

    @Test fun stateStaysInValidRanges() {
        var s = newCard()
        val grades = listOf(Grade.GOOD, Grade.HARD, Grade.EASY, Grade.AGAIN, Grade.GOOD, Grade.GOOD)
        var last: Long? = null
        var t = now
        for (g in grades) {
            val r = Fsrs.review(s, g, t, last)
            s = r.state
            assertTrue("difficulty in [1,10] (was ${s.difficulty})", s.difficulty in 1.0..10.0)
            assertTrue("stability positive (was ${s.stability})", s.stability > 0.0)
            last = t
            t = r.dueAt
        }
    }

    @Test fun lapseReducesStability() {
        // Build up a well-known card, then fail it.
        val first = Fsrs.review(newCard(), Grade.GOOD, now, null)
        val second = Fsrs.review(first.state, Grade.GOOD, first.dueAt, now)
        val strong = second.state
        val lapsed = Fsrs.review(strong, Grade.AGAIN, second.dueAt, first.dueAt)
        assertTrue("a lapse must not raise stability", lapsed.state.stability <= strong.stability)
        assertEquals("lapse count increments", strong.lapses + 1, lapsed.state.lapses)
        assertEquals("lapse relearns in-session", 0, lapsed.intervalDays)
    }

    @Test fun lowerRetentionMeansLongerIntervals() {
        val strong = Fsrs.review(Fsrs.review(newCard(), Grade.GOOD, now, null).state, Grade.GOOD, now + 3 * 86_400_000L, now).state
        val at95 = Fsrs.review(strong, Grade.GOOD, now + 10 * 86_400_000L, now + 3 * 86_400_000L, requestRetention = 0.95).intervalDays
        val at80 = Fsrs.review(strong, Grade.GOOD, now + 10 * 86_400_000L, now + 3 * 86_400_000L, requestRetention = 0.80).intervalDays
        assertTrue("80% retention target schedules further out than 95% ($at80 vs $at95)", at80 >= at95)
    }

    @Test fun maxIntervalIsRespected() {
        var s = newCard()
        var last: Long? = null
        var t = now
        // Grade Easy many times; without a cap intervals would explode past the cap.
        repeat(15) {
            val r = Fsrs.review(s, Grade.EASY, t, last, maxIntervalDays = 30)
            assertTrue("interval never exceeds the cap (was ${r.intervalDays})", r.intervalDays <= 30)
            s = r.state; last = t; t = r.dueAt
        }
    }

    @Test fun retrievabilityDecaysFromOne() {
        val s = 10.0
        assertEquals("R≈1 at t=0", 1.0, Fsrs.retrievability(0.0, s), 1e-9)
        val early = Fsrs.retrievability(5.0, s)
        val late = Fsrs.retrievability(40.0, s)
        assertTrue("R decreases over time", late < early && early < 1.0)
    }
}
