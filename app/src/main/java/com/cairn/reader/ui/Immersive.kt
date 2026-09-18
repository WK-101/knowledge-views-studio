package com.cairn.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * True while the app is currently hiding the Android system bars — because app-wide full screen is on,
 * or (in the reader) immersive scroll has hidden them. Provided at the app root ([CairnRoot]) so any
 * surface can ask "are the bars hidden right now?" without threading a flag through every screen.
 */
val LocalBarsHidden = compositionLocalOf { false }

/**
 * Keeps full screen intact while a [androidx.compose.material3.ModalBottomSheet] / Dialog is open.
 *
 * A modal sheet hosts its content in its own focusable window (a `ComponentDialog`, exposed as a
 * [androidx.compose.ui.window.DialogWindowProvider]). That window does NOT inherit the Activity's
 * hidden-system-bar flags, so the instant it gains focus the system re-shows the status/navigation
 * bars — visibly dropping the app out of full screen, and the resulting window-inset change on the
 * edge-to-edge Activity shunts the content underneath. Re-hiding the bars on the sheet's *own* window
 * keeps everything immersive and the Activity's insets unchanged.
 *
 * [hide] defaults to the app-wide [LocalBarsHidden]; the reader passes its own (more precise) state so
 * a sheet stays immersive even when only immersive-scroll — not app-wide full screen — hid the bars.
 * A no-op when [hide] is false or when no dialog window is found (e.g. previews). The host re-asserts
 * its own bars once the sheet dismisses.
 */
@Composable
fun KeepImmersiveWhileOpen(hide: Boolean = LocalBarsHidden.current) {
    val view = LocalView.current
    LaunchedEffect(view, hide) {
        val window = generateSequence(view.parent) { (it as? android.view.View)?.parent }
            .filterIsInstance<androidx.compose.ui.window.DialogWindowProvider>()
            .firstOrNull()
            ?.window ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (hide) controller.hide(bars) else controller.show(bars)
    }
}
