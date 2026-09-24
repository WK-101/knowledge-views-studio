package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class EventDateTest {
    private val today = LocalDate.of(2026, 9, 24)

    @Test fun parses_forms() {
        assertEquals(EventDate(1990, 3, 12), EventDate.parse("1990-03-12"))
        assertEquals(EventDate(null, 3, 12), EventDate.parse("--03-12"))
        assertEquals(EventDate(null, 3, 12), EventDate.parse("--0312"))
        assertEquals(EventDate(1990, 3, 12), EventDate.parse("19900312"))
        assertEquals(EventDate(null, 3, 12), EventDate.parse("1604-03-12"))
        assertEquals(EventDate(1990, 3, 12), EventDate.parse("1990-03-12T00:00:00Z"))
        assertNull(EventDate.parse("garbage"))
        assertNull(EventDate.parse("1990-13-01"))
    }

    @Test fun next_and_age() {
        val b = EventDate(1990, 9, 30)
        assertEquals(6, b.daysUntil(today))
        assertEquals(35, b.age(today))
        assertEquals(36, b.turning(today))
        val past = EventDate(1990, 1, 1)
        assertEquals(LocalDate.of(2027, 1, 1), past.next(today))
        assertEquals(36, past.age(today))
        assertEquals(0, EventDate(null, 9, 24).daysUntil(today))
    }

    @Test fun leap_day() {
        assertEquals(LocalDate.of(2027, 2, 28), EventDate(2000, 2, 29).next(today))
    }

    @Test fun format_round_trip() {
        assertEquals("--03-12", EventDate(null, 3, 12).format())
        assertEquals("1990-03-12", EventDate.parse("19900312")!!.format())
    }
}
