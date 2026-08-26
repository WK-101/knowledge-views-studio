package com.todocompanion.app

import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.Reward
import com.todocompanion.app.domain.Rewards
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.domain.nlp.QuickTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier V — pure-logic coverage for the new habit maths, tokens, and rewards. */
class TierVTests {

    private fun habit(target: Int = 1, extra: Int? = null, comparison: String = "atleast") =
        HabitEntity(id = "h", name = "n", targetPerDay = target, extraTarget = extra, targetComparison = comparison, createdAt = 0L)

    // ---- V1: time-since ----
    @Test fun daysSinceLastDone() {
        val done = setOf(100L, 103L)
        assertEquals(2, HabitStats.daysSinceLastDone(done, 105L))
        assertEquals(0, HabitStats.daysSinceLastDone(done, 103L))
        assertEquals(-1, HabitStats.daysSinceLastDone(emptySet(), 105L))
    }

    @Test fun averageAndLongestGap() {
        val done = setOf(100L, 102L, 106L)   // gaps: 2, 4
        assertEquals(3.0, HabitStats.averageGapDays(done, 110L, 90)!!, 1e-9)
        assertEquals(4, HabitStats.longestGapDays(done, 110L, 90))
        assertNull(HabitStats.averageGapDays(setOf(100L), 110L, 90))
    }

    // ---- V2: gradated grade ----
    @Test fun gradeTiers() {
        val h = habit(target = 3, extra = 5)
        assertEquals(HabitStats.DayGrade.NONE, HabitStats.grade(h, 0))
        assertEquals(HabitStats.DayGrade.PARTIAL, HabitStats.grade(h, 2))
        assertEquals(HabitStats.DayGrade.MET, HabitStats.grade(h, 3))
        assertEquals(HabitStats.DayGrade.EXTRA, HabitStats.grade(h, 5))
    }

    @Test fun gradeAtMostIsMetWhenUnder() {
        val h = habit(target = 2, comparison = "atmost")
        assertEquals(HabitStats.DayGrade.MET, HabitStats.grade(h, 1))
        assertEquals(HabitStats.DayGrade.MET, HabitStats.grade(h, 2))
        assertEquals(HabitStats.DayGrade.NONE, HabitStats.grade(h, 3))
    }

    // ---- V9: quick tokens ----
    @Test fun quickTokensParse() {
        val p = QuickTokens.parse("read the report #t25 !! *")
        assertEquals("read the report", p.text)
        assertEquals(25, p.estimateMin)
        assertEquals(2, p.priorityLevel)
        assertTrue(p.star)
    }

    @Test fun quickTokensActivity() {
        val p = QuickTokens.parse("gym @exercise")
        assertEquals("gym", p.text)
        assertEquals("exercise", p.activity)
        assertNull(p.estimateMin)
    }

    @Test fun quickTokensNoneLeavesTextIntact() {
        val p = QuickTokens.parse("just a normal task")
        assertEquals("just a normal task", p.text)
        assertTrue(!p.hasAny)
    }

    // ---- V12: rewards ----
    @Test fun rewardsRoundTrip() {
        val list = listOf(Reward(id = "1", name = "Movie night", cost = 20), Reward(id = "2", name = "New book", cost = 40, redeemed = 1))
        val back = Rewards.parse(Rewards.encode(list))
        assertEquals(2, back.size)
        assertEquals("Movie night", back[0].name)
        assertEquals(1, back[1].redeemed)
    }
}
