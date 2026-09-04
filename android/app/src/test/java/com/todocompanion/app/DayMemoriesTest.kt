package com.todocompanion.app

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.domain.DayMemories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Wave 2 (feature 8) — local memory resurfacing: "on this day" picks a same-date past entry, the
 * recent-good fallback kicks in when there is no anniversary, and it is empty with no history.
 */
class DayMemoriesTest {

    private val today = LocalDate.of(2026, 9, 4).toEpochDay()

    private fun log(day: Long, highlight: String = "", good1: String = "", rating: Int = 0) =
        DayLogEntity(epochDay = day, highlight = highlight, good1 = good1, dayRating = rating)

    @Test fun onThisDayPicksSameDatePriorYear() {
        val yearAgo = LocalDate.of(2025, 9, 4).toEpochDay()
        val logs = listOf(
            log(yearAgo, highlight = "Hiked the ridge with friends"),
            // A recent moment that must NOT win over the same-date anniversary.
            log(today - 3, highlight = "A quiet good day"),
        )
        val m = DayMemories.select(today, logs)
        assertNotNull(m)
        assertEquals(DayMemories.Kind.ON_THIS_DAY, m!!.kind)
        assertEquals(yearAgo, m.epochDay)
        assertEquals("Hiked the ridge with friends", m.text)
        assertEquals("A year ago", m.whenLabel)
    }

    @Test fun fallsBackToRecentGoodMomentWhenNoAnniversary() {
        val logs = listOf(
            log(today - 2, good1 = "Finished the proposal", rating = 5),
            log(today - 6, highlight = "Ran a personal best", rating = 3),
        )
        val m = DayMemories.select(today, logs)
        assertNotNull(m)
        assertEquals(DayMemories.Kind.RECENT_GOOD, m!!.kind)
        // The highest-rated recent day wins the tie-break.
        assertEquals(today - 2, m.epochDay)
        assertEquals("Finished the proposal", m.text)
    }

    @Test fun emptyWhenNoHistory() {
        assertNull("no logs at all → nothing to resurface", DayMemories.select(today, emptyList()))
        // A recent day with a rating but no words is not memorable.
        assertNull("a wordless day is not resurfaced", DayMemories.select(today, listOf(log(today - 1, rating = 4))))
    }
}
