package app.parley.common.ux

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Contrast of the colour roles Parley's screens pair, checked on the brand schemes' real values (core:ui's tests) and
 * on dynamic colour by *tone* (P16). Material's dynamic colour builds every role from a tonal palette of the wallpaper's
 * colours, and a role is always the same tone (primary is tone 40 in light, 80 in dark…). Tone is CIE L*, which fixes
 * the luminance whatever the hue and chroma, so the contrast of each pair is the same for every wallpaper: checking
 * the tones covers all of them.
 */
object ThemeContrast {
    /** One pairing as it appears on screen: [fg] drawn on [bg], needing [min] (text 4.5:1, icons and outlines 3:1). */
    data class Pair(val name: String, val fg: Int, val bg: Int, val min: Double) {
        val ratio: Double get() = Contrast.ratio(fg, bg)
        val ok: Boolean get() = ratio >= min
    }

    /** A grey with the luminance of tone [tone] (0 black … 100 white), the same as any colour of that tone. */
    fun toneColor(tone: Double): Int {
        val l = tone.coerceIn(0.0, 100.0)
        val y = if (l > 8.0) ((l + 16.0) / 116.0).pow(3) else l / 903.2963
        val srgb = if (y <= 0.0031308) 12.92 * y else 1.055 * y.pow(1 / 2.4) - 0.055
        val c = (srgb * 255.0).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }

    /** Role tones of a dynamic scheme (Material 3 for Android 12 and later; 14+ adds the surface containers). */
    data class Tones(
        val primary: Double, val onPrimary: Double, val primaryContainer: Double, val onPrimaryContainer: Double,
        val secondaryContainer: Double, val onSecondaryContainer: Double,
        val tertiary: Double, val onTertiary: Double, val tertiaryContainer: Double, val onTertiaryContainer: Double,
        val error: Double, val onError: Double, val errorContainer: Double, val onErrorContainer: Double,
        val onSurface: Double, val onSurfaceVariant: Double, val outline: Double, val surfaceVariant: Double,
        /** surface, surfaceContainerLowest … Highest, dim and bright: every background text sits on. */
        val surfaces: List<Double>,
    )

    val DYNAMIC_LIGHT = Tones(
        primary = 40.0, onPrimary = 100.0, primaryContainer = 90.0, onPrimaryContainer = 10.0,
        secondaryContainer = 90.0, onSecondaryContainer = 10.0,
        tertiary = 40.0, onTertiary = 100.0, tertiaryContainer = 90.0, onTertiaryContainer = 10.0,
        error = 40.0, onError = 100.0, errorContainer = 90.0, onErrorContainer = 10.0,
        onSurface = 10.0, onSurfaceVariant = 30.0, outline = 50.0, surfaceVariant = 90.0,
        // 98/99 surface (14+ / 12–13), containers 100, 96, 94, 92, 90, dim 87.
        surfaces = listOf(99.0, 98.0, 100.0, 96.0, 94.0, 92.0, 90.0, 87.0),
    )

    val DYNAMIC_DARK = Tones(
        primary = 80.0, onPrimary = 20.0, primaryContainer = 30.0, onPrimaryContainer = 90.0,
        secondaryContainer = 30.0, onSecondaryContainer = 90.0,
        tertiary = 80.0, onTertiary = 20.0, tertiaryContainer = 30.0, onTertiaryContainer = 90.0,
        error = 80.0, onError = 20.0, errorContainer = 30.0, onErrorContainer = 90.0,
        onSurface = 90.0, onSurfaceVariant = 80.0, outline = 60.0, surfaceVariant = 30.0,
        // 10/6 surface (12–13 / 14+), containers 4, 10, 12, 17, 22, bright 24.
        surfaces = listOf(10.0, 6.0, 4.0, 12.0, 17.0, 22.0, 24.0),
    )

    /** The pairs Parley's screens use, from tones; [surfaces] replaces the tone surfaces (the black AMOLED ones). */
    fun pairs(t: Tones, surfaces: List<Int> = t.surfaces.map(::toneColor)): List<Pair> {
        fun c(tone: Double) = toneColor(tone)
        val onContainers = listOf(
            Pair("onPrimary/primary", c(t.onPrimary), c(t.primary), Contrast.TEXT),
            Pair("onPrimaryContainer/primaryContainer", c(t.onPrimaryContainer), c(t.primaryContainer), Contrast.TEXT),
            Pair("onSecondaryContainer/secondaryContainer", c(t.onSecondaryContainer), c(t.secondaryContainer), Contrast.TEXT),
            Pair("onTertiary/tertiary", c(t.onTertiary), c(t.tertiary), Contrast.TEXT),
            Pair("onTertiaryContainer/tertiaryContainer", c(t.onTertiaryContainer), c(t.tertiaryContainer), Contrast.TEXT),
            Pair("onError/error", c(t.onError), c(t.error), Contrast.TEXT),
            Pair("onErrorContainer/errorContainer", c(t.onErrorContainer), c(t.errorContainer), Contrast.TEXT),
            Pair("onSurfaceVariant/surfaceVariant", c(t.onSurfaceVariant), c(t.surfaceVariant), Contrast.TEXT),
        )
        return onContainers + onSurfaces(c(t.onSurface), c(t.onSurfaceVariant), c(t.primary), c(t.error), c(t.outline), surfaces)
    }

    /**
     * Text and icons on every surface: body text, secondary text, primary (text buttons, links, the selected tab),
     * error text, and outlines (3:1, as the boundary of a field or a chip).
     */
    fun onSurfaces(onSurface: Int, onSurfaceVariant: Int, primary: Int, error: Int, outline: Int, surfaces: List<Int>): List<Pair> =
        surfaces.flatMap { s ->
            val hex = "%08X".format(s)
            listOf(
                Pair("onSurface/$hex", onSurface, s, Contrast.TEXT),
                Pair("onSurfaceVariant/$hex", onSurfaceVariant, s, Contrast.TEXT),
                Pair("primary/$hex", primary, s, Contrast.TEXT),
                Pair("error/$hex", error, s, Contrast.TEXT),
                Pair("outline/$hex", outline, s, Contrast.LARGE),
            )
        }

    fun failures(pairs: List<Pair>): List<Pair> = pairs.filterNot { it.ok }
}
