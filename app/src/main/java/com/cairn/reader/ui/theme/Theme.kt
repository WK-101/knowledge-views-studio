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
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> CairnDarkColors
        else -> CairnLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CairnTypography,
        shapes = CairnShapes,
        content = content,
    )
}
