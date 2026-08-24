package com.todocompanion.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.todocompanion.app.domain.ThemeMode

private val Brand = Color(0xFF5B57D9)
private val BrandDark = Color(0xFF8C86FF)

private fun lightScheme(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = lerp(primary, Color.White, 0.82f),
    onPrimaryContainer = lerp(primary, Color.Black, 0.5f),
    secondary = Color(0xFF5A6472),
    background = Color(0xFFF4F5F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEEF3),
)

private fun darkScheme(primary: Color, black: Boolean) = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF0B0A1B),
    primaryContainer = lerp(primary, Color.Black, 0.62f),
    onPrimaryContainer = lerp(primary, Color.White, 0.5f),
    secondary = Color(0xFFC1C6DD),
    background = if (black) Color(0xFF000000) else Color(0xFF111319),
    surface = if (black) Color(0xFF0A0B0F) else Color(0xFF191C24),
    surfaceVariant = if (black) Color(0xFF16181E) else Color(0xFF212530),
)

val AppTypography = Typography()

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
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
