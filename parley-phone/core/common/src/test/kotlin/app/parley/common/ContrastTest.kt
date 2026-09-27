package app.parley.common

import app.parley.common.ux.AvatarPalette
import app.parley.common.ux.Contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test fun black_on_white_is_21_to_1_and_order_does_not_matter() {
        assertEquals(21.0, Contrast.ratio(black, white), 0.01)
        assertEquals(Contrast.ratio(white, black), Contrast.ratio(black, white), 0.0001)
        assertEquals(1.0, Contrast.ratio(white, white), 0.0001)
    }

    @Test fun known_pairs_match_the_wcag_formula() {
        // The old lime avatar with white initials, and the old call green with white text.
        assertEquals(1.79, Contrast.ratio(0xFFC0CA33.toInt(), white), 0.01)
        assertEquals(3.45, Contrast.ratio(0xFF1E9E5A.toInt(), white), 0.01)
    }

    @Test fun every_avatar_hue_has_readable_initials() {
        for (c in AvatarPalette.colors) {
            val ink = AvatarPalette.inkFor(c)
            val r = Contrast.ratio(c, ink)
            assertTrue("0x%08X gives %.2f".format(java.util.Locale.ROOT, c, r), r >= Contrast.TEXT)
        }
    }

    @Test fun light_hues_take_the_dark_ink() {
        assertEquals(AvatarPalette.DARK_INK, AvatarPalette.inkFor(0xFFC0CA33.toInt()))
        assertEquals(AvatarPalette.WHITE_INK, AvatarPalette.inkFor(0xFF3F51B5.toInt()))
    }

    @Test fun the_same_name_always_gets_the_same_colour() {
        assertEquals(AvatarPalette.colorFor("Anna Smith"), AvatarPalette.colorFor("Anna Smith"))
        assertTrue(AvatarPalette.colorFor("Anna Smith") in AvatarPalette.colors)
    }
}
