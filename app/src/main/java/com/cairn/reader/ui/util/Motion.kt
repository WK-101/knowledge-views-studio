package com.cairn.reader.ui.util

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the user has asked the system to remove animations (Accessibility → Remove animations,
 * which sets the global animator duration scale to 0). UI can read this to collapse transitions to
 * an instant swap, honoring the OS-level reduced-motion preference.
 */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
