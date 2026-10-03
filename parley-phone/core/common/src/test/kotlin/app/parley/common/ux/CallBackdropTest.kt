package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun poster_needs_the_style_and_a_picture() {
        assertTrue(CallBackdrop.plan(CallScreenBackground.POSTER, warn = false, hasPicture = true).poster)
        assertEquals(CallBackdrop.Tint.CALLER, CallBackdrop.plan(CallScreenBackground.POSTER, warn = false, hasPicture = false).tint)
        assertFalse(CallBackdrop.plan(CallScreenBackground.POSTER, warn = false, hasPicture = false).poster)
        assertFalse(CallBackdrop.plan(CallScreenBackground.CALLER_COLOUR, warn = false, hasPicture = true).poster)
        assertFalse(CallBackdrop.plan(CallScreenBackground.PLAIN, warn = false, hasPicture = true).poster)
    }

    @Test fun no_poster_over_a_warning_in_the_small_window_or_on_a_masked_lock_screen() {
        assertFalse(CallBackdrop.plan(CallScreenBackground.POSTER, warn = true, hasPicture = true).poster)
        assertFalse(CallBackdrop.plan(CallScreenBackground.POSTER, warn = false, hasPicture = true, allowPicture = false).poster)
        assertFalse(CallBackdrop.plan(CallScreenBackground.POSTER, warn = false, hasPicture = true, masked = true).poster)
    }

    @Test fun poster_layout_only_in_one_roomy_column() {
        val plan = CallBackdrop.plan(CallScreenBackground.POSTER, warn = false, hasPicture = true)
        assertTrue(CallBackdrop.posterLayout(plan, twoPane = false, short = false, keypadOpen = false, callWaiting = false))
        assertFalse(CallBackdrop.posterLayout(plan, twoPane = true, short = false, keypadOpen = false, callWaiting = false))
        assertFalse(CallBackdrop.posterLayout(plan, twoPane = false, short = true, keypadOpen = false, callWaiting = false))
        assertFalse(CallBackdrop.posterLayout(plan, twoPane = false, short = false, keypadOpen = true, callWaiting = false))
        assertFalse(CallBackdrop.posterLayout(plan, twoPane = false, short = false, keypadOpen = false, callWaiting = true))
        val classic = CallBackdrop.plan(CallScreenBackground.CALLER_COLOUR, warn = false, hasPicture = true)
        assertFalse(CallBackdrop.posterLayout(classic, twoPane = false, short = false, keypadOpen = false, callWaiting = false))
    }

    @Test fun poster_text_always_sits_on_the_readable_scrim() {
        val scrim = CallBackdrop.scrimAlpha(light.surface, light.inks)
        listOf(0f, 0.05f, 0.3f, 0.55f, 0.9f, 1f).forEach { textTop ->
            val stops = CallBackdrop.posterStops(textTop, guard = 0.08f, fade = 0.06f, scrim = scrim)
            assertEquals(stops.map { it.first }, stops.map { it.first }.sorted())
            var y = textTop
            while (y <= 1f) {
                assertTrue("$textTop at $y", alphaAt(stops, y) >= scrim - 1e-4f)
                y += 0.01f
            }
            // The status bar's guard at the very top.
            assertEquals(scrim, alphaAt(stops, 0f), 1e-4f)
        }
        // Clear between the guard and the text.
        assertEquals(0f, alphaAt(CallBackdrop.posterStops(0.6f, guard = 0.08f, fade = 0.06f, scrim = scrim), 0.3f), 1e-4f)
        assertTrue(alphaAt(CallBackdrop.posterStops(0.6f, guard = 0.08f, fade = 0.06f, scrim = scrim), 1f) >= CallBackdrop.OPAQUE_BOTTOM)
    }

    @Test fun a_closed_poster_is_the_classic_scrim_all_the_way_down() {
        val scrim = CallBackdrop.scrimAlpha(dark.surface, dark.inks)
        val stops = CallBackdrop.posterStops(0.6f, guard = 0.08f, fade = 0.06f, scrim = scrim, open = 0f)
        var y = 0f
        while (y <= 1f) {
            assertTrue("at $y", alphaAt(stops, y) >= scrim - 1e-4f)
            y += 0.01f
        }
        // Half open: half the scrim over the clear part.
        assertEquals(scrim / 2, alphaAt(CallBackdrop.posterStops(0.6f, guard = 0.08f, fade = 0.06f, scrim = scrim, open = 0.5f), 0.3f), 1e-4f)
    }

    /** The alpha a vertical gradient through [stops] has at [y], as a Compose brush draws it. */
    private fun alphaAt(stops: List<Pair<Float, Float>>, y: Float): Float {
        if (y <= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val (p0, a0) = stops[i - 1]
            val (p1, a1) = stops[i]
            if (y <= p1) return if (p1 == p0) a1 else a0 + (a1 - a0) * (y - p0) / (p1 - p0)
        }
        return stops.last().second
    }
}
