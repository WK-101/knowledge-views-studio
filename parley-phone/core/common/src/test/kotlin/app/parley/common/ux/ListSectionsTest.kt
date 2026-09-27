package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class ListSectionsTest {
    private fun shape(rows: List<ListSections.Row<String, String>>) = rows.map {
        when (it) {
            is ListSections.Row.Header -> "[${it.section}]"
            is ListSections.Row.Item -> it.item
        }
    }

    @Test fun headers_go_before_each_new_section() {
        val rows = ListSections.interleave(listOf("Ann", "Anton", "Bob", "Émile", "Eve", "42 Taxi")) { ListSections.letterOf(it) }
        assertEquals(listOf("[A]", "Ann", "Anton", "[B]", "Bob", "[E]", "Émile", "Eve", "[#]", "42 Taxi"), shape(rows))
    }

    @Test fun header_points_at_the_first_item_of_its_section() {
        val rows = ListSections.interleave(listOf("Ann", "Bob")) { ListSections.letterOf(it) }
        assertEquals("Ann", (rows[0] as ListSections.Row.Header).first)
        assertEquals("Bob", (rows[2] as ListSections.Row.Header).first)
    }

    @Test fun empty_list_has_no_rows() {
        assertEquals(emptyList<ListSections.Row<String, String>>(), ListSections.interleave(emptyList<String>()) { it })
    }

    @Test fun first_rows_are_lazy_list_indices_after_the_offset() {
        val rows = ListSections.interleave(listOf("Ann", "Anton", "Bob", "Cy")) { ListSections.letterOf(it) }
        // Offset 2: chips and "My card" above. A=2 (header), Ann=3, Anton=4, B=5, Bob=6, C=7.
        assertEquals(linkedMapOf("A" to 2, "B" to 5, "C" to 7), ListSections.firstRows(rows, 2))
    }

    @Test fun a_repeated_section_keeps_its_first_position() {
        val rows = ListSections.interleave(listOf("Ann", "Bob", "Al")) { ListSections.letterOf(it) }
        assertEquals(linkedMapOf("A" to 0, "B" to 2), ListSections.firstRows(rows, 0))
    }

    @Test fun letters_fold_accents_and_skip_symbols() {
        assertEquals("E", ListSections.letterOf("émile"))
        assertEquals("O", ListSections.letterOf("  (Olga)"))
        assertEquals("#", ListSections.letterOf("007"))
        assertEquals("#", ListSections.letterOf("…"))
        assertEquals("Ж", ListSections.letterOf("жанна"))
    }

    @Test fun local_day_follows_the_time_zone() {
        val paris = TimeZone.getTimeZone("Europe/Paris")
        // 2024-03-10 23:30 UTC is already 11 March in Paris (UTC+1).
        val t = 1_710_113_400_000L
        assertEquals(ListSections.localDay(t, TimeZone.getTimeZone("UTC")) + 1, ListSections.localDay(t, paris))
    }

    @Test fun calls_on_one_day_share_a_header() {
        val utc = TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        val times = listOf(10 * day + 5_000, 10 * day + 1_000, 9 * day + 80_000, 7 * day)
        val rows = ListSections.interleave(times) { ListSections.localDay(it, utc) }
        assertEquals(3, rows.count { it is ListSections.Row.Header })
        assertEquals(7, rows.size)
    }
}
