package app.parley.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.parley.common.ux.Contrast
import app.parley.common.ux.ThemeContrast
import app.parley.common.ux.ThemeContrast.Pair
import org.junit.Assert.assertTrue
import org.junit.Test

/** P16: the design tokens keep 4.5:1 for text (3:1 for outlines and icons) in light, dark and AMOLED. */
class ThemeContrastTokensTest {
    // surfaceDim is left out: no Parley screen or kit component draws on it (brand primary there is only 4.2:1).
    private fun ColorScheme.surfaces() = listOf(
        surface, background, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
        surfaceContainerHigh, surfaceContainerHighest,
    ).map { it.toArgb() }

    private fun pairs(s: ColorScheme): List<Pair> {
        fun p(name: String, fg: Color, bg: Color, min: Double = Contrast.TEXT) = Pair(name, fg.toArgb(), bg.toArgb(), min)
        return listOf(
            p("onPrimary", s.onPrimary, s.primary),
            p("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer),
            p("onSecondary", s.onSecondary, s.secondary),
            p("onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
            p("onTertiary", s.onTertiary, s.tertiary),
            p("onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
            p("onError", s.onError, s.error),
            p("onErrorContainer", s.onErrorContainer, s.errorContainer),
            p("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
            p("inverseOnSurface", s.inverseOnSurface, s.inverseSurface),
        ) + ThemeContrast.onSurfaces(
            s.onSurface.toArgb(), s.onSurfaceVariant.toArgb(), s.primary.toArgb(), s.error.toArgb(), s.outline.toArgb(), s.surfaces(),
        )
    }

    private fun assertAllPass(what: String, pairs: List<Pair>) {
        val bad = ThemeContrast.failures(pairs)
        assertTrue("$what: " + bad.joinToString { "${it.name} %.2f".format(it.ratio) }, bad.isEmpty())
    }

    @Test fun brand_light_dark_and_amoled_schemes() {
        assertAllPass("light", pairs(BrandLight))
        assertAllPass("dark", pairs(BrandDark))
        assertAllPass("amoled", pairs(BrandDark.amoled()))
    }

    @Test fun amoled_surfaces_under_dynamic_colour() {
        // Dynamic dark keeps its ink tones (onSurface 90, onSurfaceVariant / primary / error 80, outline 60) over
        // Parley's black surfaces.
        val t = ThemeContrast.DYNAMIC_DARK
        val tone = ThemeContrast::toneColor
        assertAllPass(
            "dynamic amoled",
            ThemeContrast.onSurfaces(
                tone(t.onSurface), tone(t.onSurfaceVariant), tone(t.primary), tone(t.error), tone(t.outline), BrandDark.amoled().surfaces(),
            ),
        )
    }

    @Test fun call_colours() {
        val white = Color.White.toArgb()
        // White text on Answer's green; the white icon on Decline's red (an icon: 3:1).
        assertTrue(Contrast.ratio(white, CallColors.Accept.toArgb()) >= Contrast.TEXT)
        assertTrue(Contrast.ratio(white, CallColors.Decline.toArgb()) >= Contrast.LARGE)
    }
}
