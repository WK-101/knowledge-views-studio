package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactChipsTest {
    @Test fun icons_only_row_fits_a_normal_phone() {
        // Seven 48 dp targets with 2 dp gaps on a 392 dp wide phone with 8 dp margins.
        assertTrue(CompactChips.iconsFit(available = 376, chips = 7, chipWidth = 48, gap = 2))
        // A narrow 320 dp screen can't hold them: the row scrolls instead.
        assertFalse(CompactChips.iconsFit(available = 304, chips = 7, chipWidth = 48, gap = 2))
    }

    @Test fun selected_names_show_only_when_everything_fits() {
        assertTrue(CompactChips.labelsFit(available = 376, chips = 6, chipWidth = 48, gap = 2, labelWidths = listOf(70)))
        assertFalse(CompactChips.labelsFit(available = 376, chips = 7, chipWidth = 48, gap = 2, labelWidths = listOf(70)))
        // Two selected chips (a call type and a saved filter) both need room.
        assertFalse(CompactChips.labelsFit(available = 376, chips = 5, chipWidth = 48, gap = 2, labelWidths = listOf(70, 70)))
        assertTrue(CompactChips.labelsFit(available = 376, chips = 5, chipWidth = 48, gap = 2, labelWidths = listOf(60, 60)))
    }

    @Test fun exact_fit_and_edge_cases() {
        assertTrue(CompactChips.labelsFit(available = 100, chips = 2, chipWidth = 48, gap = 4, labelWidths = emptyList()))
        assertFalse(CompactChips.labelsFit(available = 99, chips = 2, chipWidth = 48, gap = 4, labelWidths = emptyList()))
        assertTrue(CompactChips.labelsFit(available = 0, chips = 0, chipWidth = 48, gap = 4, labelWidths = emptyList()))
        // A negative measurement never makes room.
        assertFalse(CompactChips.labelsFit(available = 99, chips = 2, chipWidth = 48, gap = 4, labelWidths = listOf(-10)))
    }

    @Test fun monogram_takes_the_first_letter_or_digit() {
        assertEquals("W", CompactChips.monogram("work calls"))
        assertEquals("S", CompactChips.monogram("  (sim 2)"))
        assertEquals("2", CompactChips.monogram("2 min+"))
        assertEquals("É", CompactChips.monogram("écoles"))
        assertEquals("ع", CompactChips.monogram("عمل"))
        assertEquals("", CompactChips.monogram("★ ★"))
        assertEquals("", CompactChips.monogram(""))
    }

    @Test fun monogram_keeps_whole_code_points() {
        // A letter outside the basic plane (Deseret) is two chars; it stays whole.
        val deseret = String(Character.toChars(0x10428))
        assertEquals(String(Character.toChars(0x10400)), CompactChips.monogram("${deseret}x"))
    }
}
