package com.todocompanion.app.ui.components

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * SEC (R2-C) — mark the CURRENT Compose dialog window FLAG_SECURE (no screenshots, blanked in recents,
 * blocked from non-secure displays). A Dialog/AlertDialog is its OWN window, so it does NOT inherit the
 * activity's FLAG_SECURE — a passphrase prompt could still be captured even while the main window is
 * secured. Drop [SecureDialogFlag] as the first child inside a dialog's content to close that gap.
 *
 * No-op if the composable isn't hosted in a Compose dialog window (nothing to secure). Idempotent.
 */
@Composable
fun SecureDialogFlag() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window
            ?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
}
