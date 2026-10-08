package app.parley.common.people

import app.parley.common.people.AlphabetIndex.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetIndexTest {
    private fun labels(e: List<AlphabetIndex.Entry>) = e.map { it.label }

    @Test fun only_the_sections_the_list_has() {
        val e = AlphabetIndex.entries(listOf("A" to 1, "B" to 5, "D" to 9, "#" to 20))
        assertEquals(listOf("A", "B", "D", "#"), labels(e))
        assertEquals(listOf(1, 5, 9, 20), e.map { it.target })
        // A key the list repeats (two runs under one letter) keeps its first place.
        assertEquals(listOf("A", "B"), labels(AlphabetIndex.entries(listOf("A" to 1, "B" to 3, "A" to 7))))
        assertTrue(AlphabetIndex.entries(emptyList()).isEmpty())
    }

    @Test fun mixed_scripts_keep_their_letters_in_list_order() {
        val e = AlphabetIndex.entries(listOf("A" to 1, "Z" to 4, "Б" to 6, "Д" to 8, "ب" to 10, "#" to 12))
        assertEquals(listOf("A", "Z", "Б", "Д", "ب", "#"), labels(e))
    }

    @Test fun a_script_with_hundreds_of_starting_characters_is_sampled() {
        val han = (0 until 300).map { i -> String(Character.toChars(0x4E00 + i)) to 100 + i }
        val e = AlphabetIndex.entries(listOf("A" to 1, "B" to 2) + han + listOf("#" to 500))
        val hanEntries = e.filter { Character.UnicodeScript.of(it.label.codePointAt(0)) == Character.UnicodeScript.HAN }
        assertEquals(AlphabetIndex.SAMPLED, hanEntries.size)
        // The first and the last are there, each jumping to its own section.
        assertEquals(100, hanEntries.first().target)
        assertEquals(399, hanEntries.last().target)
        assertEquals(listOf("A", "B"), labels(e).take(2))
        assertEquals("#", e.last().label)
        // Latin is never sampled, however many sections it has.
        val latin = ('A'..'Z').map { it.toString() to it.code }
        assertEquals(26, AlphabetIndex.entries(latin).size)
    }

    @Test fun a_list_without_headers_jumps_to_each_letters_first_row() {
        val names = listOf("Ada", "Alan", "Émile", "Grace", "1 Plumber", "Борис")
        assertEquals(listOf("A" to 3, "E" to 5, "G" to 6, "#" to 7, "Б" to 8), AlphabetIndex.sectionsOf(names, offset = 3))
    }

    @Test fun favourites_lead_only_when_the_star_jumps_there() {
        val sections = listOf("A" to 4, "B" to 9)
        assertEquals(listOf("A", "B"), labels(AlphabetIndex.entries(sections)))
        val combined = AlphabetIndex.entries(sections, favouritesAt = 2)
        assertEquals(listOf(AlphabetIndex.FAVOURITES, "A", "B"), labels(combined))
        assertEquals(2, combined.first().target)
    }

    @Test fun a_short_screen_draws_dots_between_letters() {
        assertEquals(listOf(0, 1, 2), AlphabetIndex.compact(3, 10))
        val c = AlphabetIndex.compact(27, 11)
        assertTrue(c.size <= 11)
        assertEquals(0, c.first())
        assertEquals(26, c.last())
        // Letters and dots alternate.
        c.forEachIndexed { i, v -> assertEquals(i % 2 == 1, v == null) }
        assertEquals(listOf(0), AlphabetIndex.compact(27, 2))
        assertTrue(AlphabetIndex.compact(0, 5).isEmpty())
    }

    @Test fun hidden_while_the_chips_my_card_and_favourites_have_the_screen() {
        // The A–Z part starts at row 4, below the bottom of a 1000 px list.
        assertEquals(Placement.HIDDEN, AlphabetIndex.placement(firstVisible = 0, start = 4, startTop = null, viewport = 1000f, header = 40f, minHeight = 200f))
        // Its first header is on screen but too low for an index.
        assertFalse(AlphabetIndex.placement(0, 4, startTop = 900f, viewport = 1000f, header = 40f, minHeight = 200f).shown)
    }

    @Test fun shown_below_the_first_header_then_below_the_pinned_one() {
        val partway = AlphabetIndex.placement(0, 4, startTop = 500f, viewport = 1000f, header = 40f, minHeight = 200f)
        assertEquals(Placement(true, 540f), partway)
        // Scrolled into the letters: right under the pinned header.
        assertEquals(Placement(true, 40f), AlphabetIndex.placement(12, 4, startTop = null, viewport = 1000f, header = 40f, minHeight = 200f))
        assertEquals(Placement(true, 40f), AlphabetIndex.placement(4, 4, startTop = -10f, viewport = 1000f, header = 40f, minHeight = 200f))
    }

    @Test fun stays_put_under_the_finger() {
        val held = Placement(true, 540f)
        // "★" scrolled the favourites back on screen: the index stays until the finger lifts.
        assertEquals(held, AlphabetIndex.placement(0, 4, null, 1000f, 40f, 200f, dragging = true, held = held))
        assertEquals(Placement.HIDDEN, AlphabetIndex.placement(0, 4, null, 1000f, 40f, 200f, dragging = false, held = held))
    }

    @Test fun a_list_too_short_for_an_index_has_none() {
        assertFalse(AlphabetIndex.placement(10, 4, null, viewport = 150f, header = 40f, minHeight = 200f).shown)
        assertFalse(AlphabetIndex.placement(10, -1, null, viewport = 1000f, header = 40f, minHeight = 200f).shown)
    }

    @Test fun a_script_in_short_runs_is_sampled_over_the_whole_list() {
        // Chinese names sorted by reading come between Latin ones, a few at a time: 30 runs of 3.
        val sections = (0 until 30).flatMap { r ->
            listOf(('A' + (r % 26)).toString() + r to r * 10) + (0 until 3).map { k -> String(Character.toChars(0x4E00 + r * 3 + k)) to r * 10 + 1 + k }
        }
        val han = AlphabetIndex.entries(sections).count { Character.UnicodeScript.of(it.label.codePointAt(0)) == Character.UnicodeScript.HAN }
        assertEquals(AlphabetIndex.SAMPLED, han)
    }

    @Test fun pickers_group_by_the_key_they_sort_by() {
        // A collation that sorts by something other than the first letter (a reading, a surname) would split letters.
        val byLength = Comparator<String> { a, b -> a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b) }
        val names = listOf("Bo", "Alexander", "Al", "Bartholomew", "Ann", "9 Lives")
        val grouped = AlphabetIndex.grouped(names, byLength) { it }
        assertEquals(listOf("Al", "Ann", "Alexander", "Bo", "Bartholomew", "9 Lives"), grouped)
        val keys = AlphabetIndex.sectionsOf(grouped, offset = 1).map { it.first }
        assertEquals(keys.distinct(), keys)
        // With the Contacts list's collation, accents fold into their letter's block.
        val french = Collation.Order(java.text.Collator.getInstance(java.util.Locale.FRENCH))
        val accented = AlphabetIndex.grouped(listOf("Émile", "Zoé", "Eva", "Ali"), french) { it }
        assertEquals(listOf("Ali", "Émile", "Eva", "Zoé"), accented)
    }

    @Test fun contacts_count_every_row_that_leads_the_list() {
        assertEquals(1, AlphabetIndex.Lead().rows)
        // Filtering with private details locked: the chips row and the locked card.
        assertEquals(2, AlphabetIndex.Lead(privateLocked = true).rows)
        val all = AlphabetIndex.Lead(privateLocked = true, me = true, favourites = true, circle = true)
        assertEquals(5, all.rows)
        assertEquals(3, all.favouritesAt)
        assertEquals(2, AlphabetIndex.Lead(me = true, favourites = true).favouritesAt)
        assertNull(AlphabetIndex.Lead(me = true).favouritesAt)
    }

    @Test fun talkback_steps_through_the_letters_not_the_star() {
        val withStar = AlphabetIndex.entries(listOf("A" to 4, "B" to 9), favouritesAt = 2)
        assertEquals(1..2, AlphabetIndex.spoken(withStar))
        assertEquals(0..1, AlphabetIndex.spoken(AlphabetIndex.entries(listOf("A" to 4, "B" to 9))))
        assertTrue(AlphabetIndex.spoken(emptyList()).isEmpty())
    }

    @Test fun large_fonts_make_rows_taller_and_letters_never_outgrow_them() {
        assertEquals(14f, AlphabetIndex.minSlot(14f, 1f), 0.001f)
        assertEquals(28f, AlphabetIndex.minSlot(14f, 2f), 0.001f)
        assertEquals(28f, AlphabetIndex.minSlot(14f, 3f), 0.001f)
        listOf(0.85f, 1f, 1.3f, 2f, 3f).forEach { scale ->
            listOf(AlphabetIndex.minSlot(14f, scale), 20f, 40f).forEach { row ->
                val letter = AlphabetIndex.letterDp(row, scale)
                assertTrue("$letter in $row at $scale", letter < row)
            }
        }
        assertEquals(AlphabetIndex.LETTER_DP, AlphabetIndex.letterDp(100f, 1f), 0.001f)
        assertEquals(AlphabetIndex.LETTER_DP * 2, AlphabetIndex.letterDp(100f, 2f), 0.001f)
    }
}
