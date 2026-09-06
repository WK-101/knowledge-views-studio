package com.cairn.reader.domain.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sm2Test {
    private val day = 24L * 60 * 60 * 1000

    @Test fun `again resets streak, lowers ease, and reschedules within a minute`() {
        val state = SrState(intervalDays = 30, ease = 250, reps = 5, lapses = 1)
        val r = Sm2.review(state, Grade.AGAIN, now = 0L)
        assertEquals(0, r.state.intervalDays)
        assertEquals(0, r.state.reps)
        assertEquals(2, r.state.lapses)
        assertEquals(230, r.state.ease)          // 250 - 20
        assertTrue("due within ~a minute", r.dueAt in 1L..120_000L)
    }

    @Test fun `ease never falls below the floor`() {
        var state = SrState(ease = 140, reps = 3, intervalDays = 10)
        repeat(5) { state = Sm2.review(state, Grade.AGAIN, 0L).state }
        assertTrue("ease floored at 130", state.ease >= 130)
    }

    @Test fun `first good review schedules one day out`() {
        val r = Sm2.review(SrState(reps = 0), Grade.GOOD, 0L)
        assertEquals(1, r.state.intervalDays)
        assertEquals(1, r.state.reps)
        assertEquals(day, r.dueAt)
    }

    @Test fun `second good review schedules six days out`() {
        val r = Sm2.review(SrState(reps = 1, intervalDays = 1), Grade.GOOD, 0L)
        assertEquals(6, r.state.intervalDays)
        assertEquals(6 * day, r.dueAt)
    }

    @Test fun `mature good review multiplies by ease`() {
        val r = Sm2.review(SrState(reps = 3, intervalDays = 10, ease = 250), Grade.GOOD, 0L)
        assertEquals(25, r.state.intervalDays)   // 10 * 2.5
    }

    @Test fun `easy grows faster than good which grows faster than hard`() {
        val base = SrState(reps = 3, intervalDays = 20, ease = 250)
        val hard = Sm2.review(base, Grade.HARD, 0L).state.intervalDays
        val good = Sm2.review(base, Grade.GOOD, 0L).state.intervalDays
        val easy = Sm2.review(base, Grade.EASY, 0L).state.intervalDays
        assertTrue("$hard < $good", hard < good)
        assertTrue("$good < $easy", good < easy)
    }

    @Test fun `interval always advances for a mature card`() {
        val r = Sm2.review(SrState(reps = 3, intervalDays = 1, ease = 130), Grade.HARD, 0L)
        assertTrue("must advance past current", r.state.intervalDays > 1)
    }

    @Test fun `preview labels are human readable`() {
        assertEquals("<1m", Sm2.preview(SrState(reps = 5, intervalDays = 30), Grade.AGAIN))
        assertEquals("1d", Sm2.preview(SrState(reps = 0), Grade.GOOD))
        val mature = Sm2.preview(SrState(reps = 4, intervalDays = 300, ease = 250), Grade.GOOD)
        assertTrue("months or years", mature.endsWith("mo") || mature.endsWith("y"))
    }
}
