package app.parley.ui

import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.parley.common.ListDensity
import app.parley.common.ThemeMode

private val BrandLight = lightColorScheme(
    primary = Color(0xFF2F5BD3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF001552),
    secondary = Color(0xFF5A5D72),
    secondaryContainer = Color(0xFFDFE1F9),
    tertiary = Color(0xFF00897B),
    tertiaryContainer = Color(0xFFB2F1E6),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    error = Color(0xFFBA1A1A),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFB6C4FF),
    onPrimary = Color(0xFF00277F),
    primaryContainer = Color(0xFF1841B3),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFC3C5DD),
    secondaryContainer = Color(0xFF434659),
    tertiary = Color(0xFF80D5C8),
    tertiaryContainer = Color(0xFF005048),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    error = Color(0xFFFFB4AB),
)

private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1A1A1D),
    surfaceContainerHighest = Color(0xFF232326),
)

/** Colours used for the call accept / decline actions everywhere. */
object CallColors {
    val Accept = Color(0xFF1E9E5A)
    val Decline = Color(0xFFD93A3A)
}

val LocalDensityPref = staticCompositionLocalOf { ListDensity.COMFORTABLE }

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ParleyTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    amoled: Boolean = false,
    dynamicColor: Boolean = true,
    density: ListDensity = ListDensity.COMFORTABLE,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    var scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> BrandDark
        else -> BrandLight
    }
    if (dark && amoled) scheme = scheme.amoled()
    SystemBarsFollowTheme(dark)
    CompositionLocalProvider(LocalDensityPref provides density) {
        androidx.compose.material3.MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = androidx.compose.material3.MotionScheme.expressive(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
            typography = androidx.compose.material3.Typography(),
            content = content,
        )
    }
}

private val LightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Status- and navigation-bar icons follow Parley's theme, not only the system's: with "Dark" chosen in Parley on a
 * phone in light mode, the icons turn light. Content still draws edge to edge (each screen pads for the bars).
 */
@Composable
private fun SystemBarsFollowTheme(dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (view.isInEditMode) return
    androidx.compose.runtime.DisposableEffect(dark) {
        var ctx = view.context
        while (ctx is android.content.ContextWrapper && ctx !is androidx.activity.ComponentActivity) ctx = ctx.baseContext
        (ctx as? androidx.activity.ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(LightScrim, DarkScrim) { dark },
        )
        onDispose {}
    }
}
