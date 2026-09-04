package com.cairn.reader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Cairn's brand palette — an "ink & paper" reading identity: a cool paper neutral,
 * deep slate-teal ink, a reader-teal primary, and a marigold "highlighter" accent
 * reserved for save/highlight moments. Used as the fallback when dynamic color is off
 * or unavailable (< Android 12).
 */

// Core hues
internal val Teal10 = Color(0xFF00201F)
internal val Teal20 = Color(0xFF003735)
internal val Teal30 = Color(0xFF0A5B59)
internal val Teal40 = Color(0xFF0E7C79)
internal val Teal80 = Color(0xFF57DAD3)
internal val Teal90 = Color(0xFFB6F1EC)

internal val Marigold30 = Color(0xFF6E4A00)
internal val Marigold40 = Color(0xFF8C6100)
internal val Marigold80 = Color(0xFFF2B441)
internal val Marigold90 = Color(0xFFFFDEA6)

// Neutrals (slightly teal-biased, not pure grey)
internal val Paper = Color(0xFFF7F9F8)
internal val PaperDim = Color(0xFFEDF1F0)
internal val Ink = Color(0xFF161D1D)
internal val InkSoft = Color(0xFF3F4948)
internal val Outline = Color(0xFF6F7978)

internal val InkNight = Color(0xFF0D1414)
internal val SurfaceNight = Color(0xFF141B1B)
internal val SurfaceNightHigh = Color(0xFF1D2625)
internal val OnNight = Color(0xFFDDE4E2)

internal val CrimsonError = Color(0xFFBA1A1A)
internal val CrimsonErrorDark = Color(0xFFFFB4AB)

val CairnLightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Teal30,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8E5),
    onSecondaryContainer = Teal10,
    tertiary = Marigold40,
    onTertiary = Color.White,
    tertiaryContainer = Marigold90,
    onTertiaryContainer = Marigold30,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F5F4),
    surfaceContainer = Color(0xFFECF0EF),
    surfaceContainerHigh = Color(0xFFE6EAE9),
    surfaceContainerHighest = Color(0xFFE0E4E3),
    outline = Outline,
    outlineVariant = Color(0xFFC3C9C8),
    error = CrimsonError,
    onError = Color.White,
)

val CairnDarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal10,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Teal80,
    onSecondary = Teal10,
    secondaryContainer = Color(0xFF14403E),
    onSecondaryContainer = Color(0xFFCDE8E5),
    tertiary = Marigold80,
    onTertiary = Marigold30,
    tertiaryContainer = Marigold40,
    onTertiaryContainer = Marigold90,
    background = InkNight,
    onBackground = OnNight,
    surface = SurfaceNight,
    onSurface = OnNight,
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C7),
    surfaceContainerLowest = Color(0xFF0B1211),
    surfaceContainerLow = Color(0xFF141B1B),
    surfaceContainer = Color(0xFF181F1F),
    surfaceContainerHigh = SurfaceNightHigh,
    surfaceContainerHighest = Color(0xFF283130),
    outline = Color(0xFF899391),
    outlineVariant = Color(0xFF3F4948),
    error = CrimsonErrorDark,
    onError = Color(0xFF690005),
)
