package app.parley.common.people

import app.parley.common.EventDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContactPageLayoutTest {
    @Test fun defaults_open_the_details_and_fold_the_rest() {
        val l = ContactPageLayout()
        assertEquals(ContactSection.entries, l.order)
        assertFalse(l.isFolded(ContactSection.PHONES))
        assertFalse(l.isFolded(ContactSection.TIMELINE))
        assertTrue(l.isFolded(ContactSection.SETTINGS))
        assertTrue(l.isDefault)
        assertEquals(l, ContactPageLayout.decode(null))
        assertEquals(l, ContactPageLayout.decode(""))
    }

    @Test fun encode_and_decode_round_trip() {
        val l = ContactPageLayout().moved(ContactSection.entries.indexOf(ContactSection.TIMELINE), 0).withMode(ContactSection.EMAILS, SectionMode.HIDDEN)
            .withMode(ContactSection.STAY, SectionMode.FOLDED).withFold(ContactSection.PHONES, true)
        val back = ContactPageLayout.decode(l.encode())
        assertEquals(l, back)
        assertEquals(ContactSection.TIMELINE, back.order.first())
        assertFalse(ContactSection.EMAILS in back.visible)
        assertTrue(back.isFolded(ContactSection.PHONES))
    }

    @Test fun a_fold_on_the_page_is_remembered_until_it_matches_the_start_mode_again() {
        val folded = ContactPageLayout().withFold(ContactSection.TIMELINE, true)
        assertTrue(folded.isFolded(ContactSection.TIMELINE))
        assertEquals(mapOf(ContactSection.TIMELINE to true), folded.folds)
        assertTrue(folded.withFold(ContactSection.TIMELINE, false).folds.isEmpty())
    }

    @Test fun a_new_start_mode_replaces_the_remembered_fold() {
        val l = ContactPageLayout().withFold(ContactSection.SETTINGS, false).withMode(ContactSection.SETTINGS, SectionMode.FOLDED)
        assertTrue(l.isFolded(ContactSection.SETTINGS))
        assertTrue(l.folds.isEmpty())
        assertTrue(l.modes.isEmpty()) // FOLDED is the default for Settings
    }

    @Test fun hiding_keeps_the_place_and_the_fold() {
        val l = ContactPageLayout().moved(ContactSection.NOTE.ordinal, 1).withFold(ContactSection.NOTE, true)
        val hidden = ContactPageLayout.decode(l.withMode(ContactSection.NOTE, SectionMode.HIDDEN).encode())
        assertEquals(1, hidden.order.indexOf(ContactSection.NOTE))
        assertEquals(ContactSection.entries.size, hidden.order.size)
        assertFalse(ContactSection.NOTE in hidden.visible)
        val shown = hidden.withMode(ContactSection.NOTE, SectionMode.OPEN)
        assertEquals(1, shown.visible.indexOf(ContactSection.NOTE))
    }

    @Test fun missing_sections_come_back_at_their_default_place() {
        // An older version stored no "insights" and no "stay"; the order was changed.
        val l = ContactPageLayout.decode("timeline:o,dates:o,phones:o,emails:o,addresses:o,messengers:o,about:o,other:f,note:o,settings:f|")
        assertEquals(ContactSection.STAY, l.order[0]) // nothing precedes it by default: first
        assertEquals(ContactSection.TIMELINE, l.order[1])
        assertEquals(ContactSection.INSIGHTS, l.order[2]) // right after its default predecessor (Timeline), wherever that moved
        assertEquals(ContactSection.entries.size, l.order.size)
    }

    @Test fun unknown_and_repeated_ids_survive_without_breaking_the_order() {
        val raw = "phones:h,future:o,phones:o,bogus|future:1,phones:1,notes:x"
        val l = ContactPageLayout.decode(raw)
        assertEquals(SectionMode.HIDDEN, l.mode(ContactSection.PHONES))
        assertEquals(listOf("future:o", "bogus"), l.unknown)
        assertEquals(mapOf(ContactSection.PHONES to true), l.folds)
        assertEquals(ContactSection.entries.size, l.order.toSet().size)
        assertTrue(ContactPageLayout.decode(l.encode()).unknown.contains("future:o"))
        assertEquals(listOf("future:o", "bogus"), l.reset().unknown)
    }

    @Test fun bad_modes_fall_back_to_the_default() {
        val l = ContactPageLayout.decode("settings:z,timeline")
        assertEquals(SectionMode.FOLDED, l.mode(ContactSection.SETTINGS))
        assertEquals(SectionMode.OPEN, l.mode(ContactSection.TIMELINE))
    }

    @Test fun moving_out_of_range_changes_nothing() {
        val l = ContactPageLayout()
        assertEquals(l, l.moved(-1, 3))
        assertEquals(l, l.moved(0, 99))
        assertEquals(ContactSection.entries[1], l.moved(1, 0).order[0])
    }

    @Test fun next_date_is_the_soonest() {
        val today = LocalDate.of(2026, 9, 27)
        val dates = listOf(EventDate(1990, 1, 5), EventDate(null, 10, 9), EventDate(2010, 9, 27))
        assertEquals(2 to 0L, ContactPage.nextDate(dates, today))
        assertEquals(1 to 12L, ContactPage.nextDate(dates.take(2), today))
        assertNull(ContactPage.nextDate(emptyList(), today))
    }
}
