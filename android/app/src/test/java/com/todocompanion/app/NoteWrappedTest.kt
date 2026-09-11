package com.todocompanion.app

import com.todocompanion.app.domain.NoteWrapped
import com.todocompanion.app.domain.NoteWrapped.In
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Wave V — on-device Notes Wrapped recap. */
class NoteWrappedTest {
    private val z = ZoneId.of("UTC")
    private fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(z).toInstant().toEpochMilli()

    @Test fun computesRecapForTheYear() {
        val notes = listOf(
            In("1", "Alpha", "one two three #work #work", ms(2025, 1, 3), "note", null),
            In("2", "Bravo", "a longer body with many more words here today #life", ms(2025, 1, 20), "note", null),
            In("3", "Journal", "dear diary #life", ms(2025, 2, 2), "journal", LocalDate.of(2025, 2, 2).toEpochDay()),
            In("4", "OldOne", "last year", ms(2024, 5, 5), "note", null), // excluded
        )
        val s = NoteWrapped.compute(notes, 2025, z)
        assertEquals(3, s.created)
        assertEquals("January", s.busiestMonth)
        assertEquals(2, s.busiestMonthCount)
        assertEquals("Bravo", s.longestTitle)
        assertEquals(1, s.journalDays)
        // #work counted twice in one note; #life across two notes
        assertEquals("work", s.topTags.first().first)
        assertTrue(s.distinctTags >= 2)
    }

    @Test fun emptyYearIsEmpty() {
        val s = NoteWrapped.compute(listOf(In("1", "x", "y", ms(2020, 1, 1), "note", null)), 2025, z)
        assertTrue(s.isEmpty)
        assertEquals(0, s.created)
    }
}
