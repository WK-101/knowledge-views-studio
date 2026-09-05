package com.cairn.reader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Selectable accent themes. Cairn keeps its calm "ink & paper" neutrals for reading and swaps
 * only the accent family (primary/secondary) plus, for dark mode, an optional pure-black ground
 * for AMOLED screens. The marigold "highlighter" tertiary stays constant so save/highlight
 * moments read the same across every accent.
 */
enum class AppAccent(val label: String, val swatch: Color) {
    DEFAULT("Cairn teal", Teal40),
    SLATE("Slate", Color(0xFF40566B)),
    OCEAN("Ocean", Color(0xFF1C5EAF)),
    FOREST("Forest", Color(0xFF3B6A3C)),
    SUNSET("Sunset", Color(0xFFA1421F)),
    ROSE("Rose", Color(0xFFA63A5B)),
    GRAPE("Grape", Color(0xFF6A4BA6)),
    MONO("Mono", Color(0xFF4A5A59)),
}

/** Tonal stops for one accent family: light uses 40/90/10, dark uses 80/30/90. */
private data class AccentTones(
    val p40: Color, val p90: Color, val p10: Color, val p30: Color, val p80: Color,
    val sc: Color, val scDark: Color,
)

private fun tones(accent: AppAccent): AccentTones? = when (accent) {
    AppAccent.DEFAULT -> null // use the base Cairn scheme unchanged
    AppAccent.SLATE -> AccentTones(Color(0xFF40566B), Color(0xFFD5E3F7), Color(0xFF00131F), Color(0xFF283F52), Color(0xFFA8C8E8), Color(0xFFD5E3F7), Color(0xFF283F52))
    AppAccent.OCEAN -> AccentTones(Color(0xFF1C5EAF), Color(0xFFD8E2FF), Color(0xFF001A41), Color(0xFF00468F), Color(0xFFADC7FF), Color(0xFFD8E2FF), Color(0xFF00468F))
    AppAccent.FOREST -> AccentTones(Color(0xFF3B6A3C), Color(0xFFBCEFB6), Color(0xFF072109), Color(0xFF235024), Color(0xFFA0D39A), Color(0xFFBCEFB6), Color(0xFF235024))
    AppAccent.SUNSET -> AccentTones(Color(0xFFA1421F), Color(0xFFFFDBCF), Color(0xFF3B0A00), Color(0xFF7E2E0E), Color(0xFFFFB59B), Color(0xFFFFDBCF), Color(0xFF7E2E0E))
    AppAccent.ROSE -> AccentTones(Color(0xFFA63A5B), Color(0xFFFFD9E2), Color(0xFF3E001A), Color(0xFF83274A), Color(0xFFFFB1C6), Color(0xFFFFD9E2), Color(0xFF83274A))
    AppAccent.GRAPE -> AccentTones(Color(0xFF6A4BA6), Color(0xFFE9DDFF), Color(0xFF22005D), Color(0xFF523A86), Color(0xFFCFBCFF), Color(0xFFE9DDFF), Color(0xFF523A86))
    AppAccent.MONO -> AccentTones(Color(0xFF4A5A59), Color(0xFFD9E2E0), Color(0xFF101414), Color(0xFF33403E), Color(0xFFB4C0BE), Color(0xFFD9E2E0), Color(0xFF33403E))
}

/** Build the accent-adjusted [ColorScheme] for the given mode, optionally on a pure-black ground. */
fun cairnScheme(accent: AppAccent, dark: Boolean, trueBlack: Boolean): ColorScheme {
    val base = if (dark) CairnDarkColors else CairnLightColors
    val t = tones(accent)
    val accented = when {
        t == null -> base
        dark -> base.copy(
            primary = t.p80, onPrimary = t.p10, primaryContainer = t.p30, onPrimaryContainer = t.p90,
            secondary = t.p80, onSecondary = t.p10, secondaryContainer = t.scDark, onSecondaryContainer = t.p90,
            inversePrimary = t.p40,
        )
        else -> base.copy(
            primary = t.p40, onPrimary = Color.White, primaryContainer = t.p90, onPrimaryContainer = t.p10,
            secondary = t.p30, onSecondary = Color.White, secondaryContainer = t.sc, onSecondaryContainer = t.p10,
            inversePrimary = t.p80,
        )
    }
    if (!dark || !trueBlack) return accented
    // AMOLED: sink the grounds to true black, keep containers a hair above so cards still read.
    val black = Color(0xFF000000)
    return accented.copy(
        background = black,
        surface = black,
        surfaceContainerLowest = black,
        surfaceContainerLow = Color(0xFF0A0F0F),
        surfaceContainer = Color(0xFF0D1212),
        surfaceContainerHigh = Color(0xFF141A1A),
        surfaceContainerHighest = Color(0xFF1B2121),
    )
}
