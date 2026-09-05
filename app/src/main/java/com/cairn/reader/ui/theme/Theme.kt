package com.cairn.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Cairn theme. Prefers Material You dynamic color on Android 12+ (a user toggle later),
 * falling back to the hand-tuned brand palette otherwise. Dark/light follows the system.
 */
@Composable
fun CairnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    accent: AppAccent = AppAccent.DEFAULT,
    trueBlack: Boolean = false,
    seedColor: Int = 0,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // A hand-picked custom seed is the most explicit request of all — it wins over everything.
        seedColor != 0 -> cairnSchemeFromSeed(seedColor, darkTheme, trueBlack)
        // A chosen accent is an explicit request and takes precedence over Material You.
        accent != AppAccent.DEFAULT -> cairnScheme(accent, darkTheme, trueBlack)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val scheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (darkTheme && trueBlack) scheme.copy(
                background = androidx.compose.ui.graphics.Color.Black,
                surface = androidx.compose.ui.graphics.Color.Black,
                surfaceContainerLowest = androidx.compose.ui.graphics.Color.Black,
            ) else scheme
        }
        else -> cairnScheme(AppAccent.DEFAULT, darkTheme, trueBlack)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CairnTypography,
        shapes = CairnShapes,
        content = content,
    )
}
