package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallBackdropTest {
    // Parley's own light, dark and black (AMOLED) surfaces with their onSurface and onSurfaceVariant inks.
    private val light = Theme(0xFFFBF8FF.toInt(), 0xFF1A1B21.toInt(), 0xFF444653.toInt())
    private val dark = Theme(0xFF121318.toInt(), 0xFFE3E1E9.toInt(), 0xFFC5C6D5.toInt())
    private val black = Theme(0xFF000000.toInt(), 0xFFE3E1E9.toInt(), 0xFFC5C6D5.toInt())
    private val themes = listOf(light, dark, black)

    private data class Theme(val surface: Int, val ink: Int, val variant: Int) {
        val inks get() = intArrayOf(ink, variant)
    }

    private val accents = (AvatarPalette.colors + intArrayOf(0xFF2F5BD3.toInt(), 0xFFB6C4FF.toInt(), 0xFFBA1A1A.toInt(), 0xFFFFFF00.toInt()))

    @Test fun blend_is_a_translucent_layer() {
        assertEquals(0xFF808080.toInt(), CallBackdrop.blend(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.5f))
        assertEquals(0xFF123456.toInt(), CallBackdrop.blend(0xFFFFFFFF.toInt(), 0xFF123456.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), CallBackdrop.blend(0xFFFFFFFF.toInt(), 0xFF123456.toInt(), 1f))
    }

    @Test fun every_caller_tint_keeps_text_readable_in_every_theme() {
        themes.forEach { t ->
            accents.forEach { a ->
                val s = CallBackdrop.tintStrength(t.surface, a, t.inks)
                assertTrue(s in 0f..CallBackdrop.MAX_TINT)
                val bg = CallBackdrop.blend(a, t.surface, s)
                t.inks.forEach { assertTrue("ink on tinted ${Integer.toHexString(a)}", Contrast.ratio(it, bg) >= Contrast.TEXT) }
            }
        }
    }

    @Test fun a_calm_accent_gets_a_visible_tint() {
        // The brand blue on the dark theme isn't washed out to nothing.
        assertTrue(CallBackdrop.tintStrength(dark.surface, 0xFF2F5BD3.toInt(), dark.inks) >= 0.2f)
    }

    @Test fun the_picture_scrim_keeps_text_readable_over_any_pixel() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF1A1B21.toInt(),
            0xFFE3E1E9.toInt(),
        )
        themes.forEach { t ->
            val a = CallBackdrop.scrimAlpha(t.surface, t.inks)
            assertTrue(a < 1f)
            pixels.forEach { p ->
                val bg = CallBackdrop.blend(t.surface, p, a)
                t.inks.forEach { assertTrue(Contrast.ratio(it, bg) >= Contrast.TEXT) }
            }
        }
    }

    @Test fun the_scrim_is_no_stronger_than_it_needs_to_be() {
        // Light text on a black surface can let a lot of the picture through; dark text on light needs more cover.
        val onBlack = CallBackdrop.scrimAlpha(black.surface, black.inks)
        val onLight = CallBackdrop.scrimAlpha(light.surface, light.inks)
        assertTrue(onBlack < 0.9f)
        assertTrue(onLight < 0.95f)
        assertTrue(onBlack > 0.3f)
    }

    @Test fun no_tint_when_the_plain_surface_already_fails() {
        assertEquals(0f, CallBackdrop.tintStrength(0xFF777777.toInt(), 0xFF3F51B5.toInt(), intArrayOf(0xFF888888.toInt())), 0f)
    }

    @Test fun caller_colour_tints_and_plain_does_not() {
        assertEquals(CallBackdrop.Tint.CALLER, CallBackdrop.plan(CallScreenBackground.CALLER_COLOUR, warn = false, hasPicture = false).tint)
        assertEquals(CallBackdrop.Tint.NONE, CallBackdrop.plan(CallScreenBackground.PLAIN, warn = false, hasPicture = false).tint)
    }

    @Test fun a_spam_warning_keeps_its_red_wash_with_either_style() {
        CallScreenBackground.entries.forEach { style ->
            assertEquals(CallBackdrop.Tint.WARNING, CallBackdrop.plan(style, warn = true, hasPicture = false).tint)
        }
    }

    @Test fun a_contacts_picture_shows_with_either_style_but_not_in_the_small_window() {
        CallScreenBackground.entries.forEach { style ->
            assertTrue(CallBackdrop.plan(style, warn = false, hasPicture = true).picture)
            assertEquals(false, CallBackdrop.plan(style, warn = false, hasPicture = true, allowPicture = false).picture)
            assertEquals(false, CallBackdrop.plan(style, warn = false, hasPicture = false).picture)
        }
    }
}
