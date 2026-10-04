package app.parley.common.recall

import app.parley.common.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class RecallQueryTest {
    /** A Sunday. */
    private val today = LocalDate.of(2026, 10, 4)

    private fun parse(text: String, locale: Locale = Locale.UK) = RecallQuery.parse(text, today, locale)

    private fun span(q: RecallQuery) = q.dates!!.start to q.dates!!.end

    @Test fun aMonthNameIsTheLastMarchThatBegan() {
        val q = parse("plumber march")
        assertEquals("plumber", q.words)
        assertEquals(LocalDate.of(2026, 3, 1) to LocalDate.of(2026, 4, 1), span(q))
        assertEquals(RecallQuery.DateSpan.Kind.MONTH, q.dates!!.kind)
        assertNull(q.callTypes)
        assertFalse(q.onlyCalls)
    }

    @Test fun aMonthStillToComeIsLastYears() {
        assertEquals(LocalDate.of(2025, 11, 1) to LocalDate.of(2025, 12, 1), span(parse("november")))
    }

    @Test fun whoCalledInMarchIsCallsThatCameIn() {
        val q = parse("Who called in March?")
        assertEquals("", q.words)
        assertTrue(q.search.isEmpty)
        assertEquals(setOf(CallType.INCOMING, CallType.MISSED), q.callTypes)
        assertTrue(q.onlyCalls)
        assertEquals(LocalDate.of(2026, 3, 1), q.dates!!.start)
        assertFalse(q.isEmpty)
    }

    @Test fun iCalledIsOutgoing() {
        val q = parse("I called Ana yesterday")
        assertEquals("Ana", q.words)
        assertEquals(setOf(CallType.OUTGOING), q.callTypes)
        assertEquals(LocalDate.of(2026, 10, 3) to today, span(q))
    }

    @Test fun missedCallsAndDirections() {
        assertEquals(setOf(CallType.MISSED), parse("missed calls").callTypes)
        assertEquals("", parse("missed calls").words)
        assertEquals(setOf(CallType.INCOMING), parse("incoming bank").callTypes)
        assertEquals(setOf(CallType.OUTGOING), parse("outgoing").callTypes)
        val calls = parse("calls dentist")
        assertTrue(calls.callsOnly)
        assertNull(calls.callTypes)
        assertEquals("dentist", calls.words)
    }

    @Test fun lastWeekRunsFromTheStartOfLastWeekToToday() {
        val q = parse("bank last week")
        assertEquals("bank", q.words)
        // Weeks start on Monday in the UK: last week began on 21 September.
        assertEquals(LocalDate.of(2026, 9, 21) to LocalDate.of(2026, 10, 5), span(q))
        assertEquals(RecallQuery.DateSpan.Kind.SINCE_LAST_WEEK, q.dates!!.kind)
        // In the US weeks start on Sunday, today.
        assertEquals(LocalDate.of(2026, 9, 27), parse("last week", Locale.US).dates!!.start)
    }

    @Test fun thisMonthAndYears() {
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 11, 1), span(parse("this month")))
        assertEquals(LocalDate.of(2025, 1, 1) to LocalDate.of(2026, 10, 5), span(parse("last year")))
        val y = parse("insurance 2024")
        assertEquals("insurance", y.words)
        assertEquals(LocalDate.of(2024, 1, 1) to LocalDate.of(2025, 1, 1), span(y))
        assertEquals(LocalDate.of(2024, 3, 1) to LocalDate.of(2024, 4, 1), span(parse("march 2024")))
    }

    @Test fun aDayOfAMonth() {
        assertEquals(LocalDate.of(2026, 5, 12) to LocalDate.of(2026, 5, 13), span(parse("12 may")))
        assertEquals(LocalDate.of(2026, 5, 12), parse("may 12th").dates!!.start)
        // Not come yet this year: last year's.
        assertEquals(LocalDate.of(2025, 12, 24), parse("24 december").dates!!.start)
        assertEquals(LocalDate.of(2024, 3, 3), parse("march 3 2024").dates!!.start)
    }

    @Test fun todayYesterdayAndWeekdays() {
        assertEquals(today to today.plusDays(1), span(parse("today")))
        assertEquals(LocalDate.of(2026, 10, 3) to today, span(parse("yesterday")))
        // The last Friday; a Sunday is today.
        assertEquals(LocalDate.of(2026, 10, 2), parse("friday garage").dates!!.start)
        assertEquals(today, parse("sunday").dates!!.start)
    }

    @Test fun monthsInThePhonesLanguageAndAccentsIgnored() {
        assertEquals(LocalDate.of(2026, 3, 1), parse("março", Locale.forLanguageTag("pt-PT")).dates!!.start)
        assertEquals(LocalDate.of(2026, 3, 1), parse("MARCO", Locale.forLanguageTag("pt-PT")).dates!!.start)
        assertEquals(LocalDate.of(2025, 12, 1), parse("dezember", Locale.GERMANY).dates!!.start)
        // Abbreviations that are also names are names.
        assertNull(parse("jan").dates)
        assertEquals("jan", parse("jan").words)
        assertEquals(LocalDate.of(2026, 8, 1), parse("aug").dates!!.start)
    }

    @Test fun numbersStayNumbers() {
        val q = parse("912 345")
        assertNull(q.dates)
        assertEquals("912 345", q.words)
        assertFalse(q.interpreted)
        // Not a year: too long, or out of range.
        assertNull(parse("20245").dates)
        assertNull(parse("1234").dates)
        assertEquals("ana 12", parse("ana 12").words)
    }

    @Test fun smallWordsGoOnlyWhenSomethingIsLeft() {
        assertEquals("the who", parse("the who").words)
        assertFalse(parse("the who").interpreted)
        val q = parse("what did the plumber say")
        assertEquals("plumber say", q.words)
        assertTrue(q.interpreted)
        assertNull(q.dates)
        assertTrue(parse("   ").isEmpty)
    }

    @Test fun datesContainTheirMillis() {
        val zone = java.time.ZoneOffset.UTC
        val q = parse("yesterday")
        val inside = LocalDate.of(2026, 10, 3).atTime(23, 59).toInstant(zone).toEpochMilli()
        val outside = today.atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(q.inDates(inside, zone))
        assertFalse(q.inDates(outside, zone))
        assertTrue(parse("bank").inDates(outside, zone))
    }
}
