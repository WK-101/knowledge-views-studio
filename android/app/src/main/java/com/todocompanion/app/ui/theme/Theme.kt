package com.todocompanion.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.domain.ThemeMode

private val Brand = Color(0xFF5B57D9)
private val BrandDark = Color(0xFF8C86FF)

private fun lightScheme(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = lerp(primary, Color.White, 0.82f),
    onPrimaryContainer = lerp(primary, Color.Black, 0.5f),
    // Secondary/tertiary derive from the accent so selections, chips and toggles pick it up.
    secondary = lerp(primary, Color(0xFF5A6472), 0.35f),
    onSecondary = Color.White,
    secondaryContainer = lerp(primary, Color.White, 0.86f),
    onSecondaryContainer = lerp(primary, Color.Black, 0.55f),
    tertiary = primary,
    tertiaryContainer = lerp(primary, Color.White, 0.80f),
    onTertiaryContainer = lerp(primary, Color.Black, 0.5f),
    background = Color(0xFFF4F5F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEEF3),
    // M3 tonal surface roles (the modern replacement for `surface` + tonalElevation). The app's
    // aesthetic is white cards on a soft grey ground, so cards sit at the *lowest* container
    // (white) and nested tiles step up in tone.
    surfaceDim = Color(0xFFDDDEE6),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFBFD),
    surfaceContainer = Color(0xFFF6F7FB),
    surfaceContainerHigh = Color(0xFFF0F1F6),
    surfaceContainerHighest = Color(0xFFEAEBF2),
)

private fun darkScheme(primary: Color, black: Boolean) = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF0B0A1B),
    primaryContainer = lerp(primary, Color.Black, 0.62f),
    onPrimaryContainer = lerp(primary, Color.White, 0.5f),
    secondary = lerp(primary, Color.White, 0.22f),
    onSecondary = Color(0xFF0B0A1B),
    secondaryContainer = lerp(primary, if (black) Color.Black else Color(0xFF191C24), 0.52f),
    onSecondaryContainer = lerp(primary, Color.White, 0.62f),
    tertiary = primary,
    tertiaryContainer = lerp(primary, Color.Black, 0.5f),
    onTertiaryContainer = lerp(primary, Color.White, 0.55f),
    background = if (black) Color(0xFF000000) else Color(0xFF111319),
    surface = if (black) Color(0xFF0A0B0F) else Color(0xFF191C24),
    surfaceVariant = if (black) Color(0xFF16181E) else Color(0xFF212530),
    // Tonal surface roles. On AMOLED the ground is pure black for battery, but cards lift a step
    // off it (surfaceContainer) so they read as real surfaces instead of vanishing into black.
    surfaceDim = if (black) Color(0xFF000000) else Color(0xFF0E1015),
    surfaceBright = if (black) Color(0xFF24262C) else Color(0xFF32363F),
    surfaceContainerLowest = if (black) Color(0xFF000000) else Color(0xFF0F1116),
    surfaceContainerLow = if (black) Color(0xFF0C0D12) else Color(0xFF1B1E27),
    surfaceContainer = if (black) Color(0xFF121318) else Color(0xFF1F232C),
    surfaceContainerHigh = if (black) Color(0xFF191B21) else Color(0xFF262A34),
    surfaceContainerHighest = if (black) Color(0xFF212329) else Color(0xFF30343E),
)

// Material 3 Expressive-leaning refresh (C4): rounder, more generous shapes across every component
// (cards, buttons, chips, sheets, dialogs) for a softer, more modern feel.
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

// A complete, expressive type scale — not just heading overrides. Display + headline run bold with
// tight tracking for a confident tone; titles are semibold; labels get a touch of tracking so the
// small-caps card labels and chips read cleanly. Body stays on the M3 defaults (well-tuned already).
val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        displayMedium = displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(letterSpacing = 0.4.sp),
        labelSmall = labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    accentArgb: Long = 0L,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val amoled = themeMode == ThemeMode.AMOLED
    val hasAccent = accentArgb != 0L
    val accent = if (hasAccent) Color(accentArgb) else Brand
    val accentDark = if (hasAccent) lerp(Color(accentArgb), Color.White, 0.35f) else BrandDark

    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        // A chosen accent (or AMOLED) wins over dynamic color.
        hasAccent -> if (dark) darkScheme(accentDark, amoled) else lightScheme(accent)
        amoled -> darkScheme(BrandDark, true)
        dynamicColor && dynamicAvailable -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> darkScheme(BrandDark, false)
        else -> lightScheme(Brand)
    }
    // Semantic tokens (good/warn/bad/info + chart palette) travel alongside the M3 scheme so every
    // screen reads one adaptive source instead of hard-coding status hexes.
    val kairo = if (dark) DarkKairoColors else LightKairoColors
    androidx.compose.runtime.CompositionLocalProvider(LocalKairoColors provides kairo) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
