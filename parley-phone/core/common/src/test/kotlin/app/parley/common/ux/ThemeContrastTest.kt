package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun tones_map_to_their_luminance() {
        assertEquals(0xFF000000.toInt(), ThemeContrast.toneColor(0.0))
        assertEquals(0xFFFFFFFF.toInt(), ThemeContrast.toneColor(100.0))
        // Tone 50 is the mid grey #777777 (L* 50).
        assertEquals(0xFF777777.toInt(), ThemeContrast.toneColor(50.0))
        // Material's rule of thumb: tones 50 apart reach 4.5:1, 40 apart 3:1.
        assertTrue(ThemeContrast.pairs(ThemeContrast.DYNAMIC_LIGHT).isNotEmpty())
        assertTrue(Contrast.ratio(ThemeContrast.toneColor(40.0), ThemeContrast.toneColor(90.0)) >= Contrast.TEXT)
    }

    @Test fun dynamic_colour_reaches_the_contrast_for_any_wallpaper() {
        listOf(ThemeContrast.DYNAMIC_LIGHT, ThemeContrast.DYNAMIC_DARK).forEach { t ->
            val bad = ThemeContrast.failures(ThemeContrast.pairs(t))
            assertTrue(bad.joinToString { "${it.name} %.2f".format(it.ratio) }, bad.isEmpty())
        }
    }

    @Test fun the_checker_catches_a_bad_pair() {
        val grey = ThemeContrast.toneColor(60.0)
        val bad = ThemeContrast.failures(ThemeContrast.onSurfaces(grey, grey, grey, grey, grey, listOf(ThemeContrast.toneColor(70.0))))
        assertEquals(5, bad.size)
    }
}
