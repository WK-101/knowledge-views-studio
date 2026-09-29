package app.parley.common.people

import app.parley.common.EventDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ContactGlanceTest {
    private val today = LocalDate.of(2026, 9, 29)

    @Test fun no_calls_and_nothing_else() {
        assertEquals(listOf(GlanceFact.NoCalls), ContactGlance.facts(null, emptyList(), today, 0))
    }

    @Test fun last_talked_then_a_close_date_then_promises() {
        val dates = listOf(EventDate(1990, 12, 1), EventDate(null, 10, 5))
        assertEquals(
            listOf(GlanceFact.LastTalked(42L), GlanceFact.NextDate(1, 6L), GlanceFact.OpenPromises(2)),
            ContactGlance.facts(42L, dates, today, 2),
        )
    }

    @Test fun a_date_further_away_than_the_horizon_is_left_out() {
        val dates = listOf(EventDate(1990, 12, 1)) // 63 days away
        assertEquals(listOf(GlanceFact.LastTalked(1L)), ContactGlance.facts(1L, dates, today, 0))
        assertEquals(GlanceFact.NextDate(0, 63L), ContactGlance.facts(1L, dates, today, 0, horizonDays = 90).last())
    }

    @Test fun a_date_today_shows() {
        assertEquals(GlanceFact.NextDate(0, 0L), ContactGlance.facts(null, listOf(EventDate(null, 9, 29)), today, 0)[1])
    }
}
