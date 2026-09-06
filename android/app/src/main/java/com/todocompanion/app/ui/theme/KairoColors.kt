package com.todocompanion.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Kairo's semantic colour tokens — the one place status hues, the favourite star, and the
 * categorical chart palette are defined. Every screen reads these through [LocalKairoColors]
 * instead of hard-coding `Color(0xFF…)` literals, so a colour is defined once, adapts to
 * light / dark / AMOLED, and never drifts screen to screen.
 *
 * These sit *alongside* the Material 3 `ColorScheme` (which owns primary/surface/etc.). Use the
 * scheme for structural colour; use this for meaning — good / warning / bad / info, and data.
 */
@Immutable
data class KairoColors(
    val good: Color,      // success, positive delta, "kept" / on-track
    val warn: Color,      // caution, at-risk, medium priority
    val bad: Color,       // failure, overdue, high priority, negative delta
    val info: Color,      // informational / low priority / links
    val neutral: Color,   // no-priority, disabled meaning
    val star: Color,      // the favourite star (a fixed warm gold, distinct from `warn`)
    /** A validated categorical palette for data series (donuts, stacked bars, multi-series).
     *  Assign in fixed order, never cycled; a 9th series folds into "Other". */
    val chart: List<Color>,
) {
    /** Tonal soft background for a [good]/[warn]/[bad]/[info] chip — the hue at low alpha. */
    fun soft(c: Color): Color = c.copy(alpha = 0.16f)
}

/** Light-surface tokens: saturated enough to read on white cards. */
val LightKairoColors = KairoColors(
    good = Color(0xFF1E9E64),
    warn = Color(0xFFEA9A16),
    bad = Color(0xFFE5484D),
    info = Color(0xFF3E7BFA),
    neutral = Color(0xFF9AA3B2),
    star = Color(0xFFF5A623),
    chart = listOf(
        Color(0xFF5B57D9), // indigo (brand)
        Color(0xFF12A594), // teal
        Color(0xFFEA9A16), // amber
        Color(0xFFE5484D), // rose
        Color(0xFF8B5CF6), // violet
        Color(0xFF0EA5E9), // sky
        Color(0xFF65A30D), // lime
        Color(0xFFEC4899), // pink
    ),
)

/** Dark/AMOLED-surface tokens: lightened so they hold contrast on near-black. */
val DarkKairoColors = KairoColors(
    good = Color(0xFF4FC38A),
    warn = Color(0xFFE0A63F),
    bad = Color(0xFFEB6B6B),
    info = Color(0xFF6E9BFF),
    neutral = Color(0xFF8A93A3),
    star = Color(0xFFF6B84A),
    chart = listOf(
        Color(0xFF8C86FF), // indigo (brand-dark)
        Color(0xFF4FD1C5), // teal
        Color(0xFFF0B54A), // amber
        Color(0xFFEB6B6B), // rose
        Color(0xFFA78BFA), // violet
        Color(0xFF38BDF8), // sky
        Color(0xFFA3E635), // lime
        Color(0xFFF472B6), // pink
    ),
)

/** Provided at the root by [AppTheme]. Defaults to the light set so previews never crash. */
val LocalKairoColors = staticCompositionLocalOf { LightKairoColors }
