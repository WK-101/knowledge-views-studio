package com.todocompanion.app.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todocompanion.app.App
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.screens.QuickCapturePanel
import com.todocompanion.app.ui.theme.AppTheme

/**
 * The "add a task without opening the whole app" popup the Quick-add widget (and shared/voice/deep-link
 * captures) fire into. It floats over the launcher in its own task (excludeFromRecents + taskAffinity=""
 * in the manifest) and renders [QuickCapturePanel] — the SAME calm quick-add body used in-app, but drawn
 * straight into this window's own Surface rather than a ModalBottomSheet. (R68: a ModalBottomSheet opens
 * a second Dialog window, and inside this transient, translucent activity that second window crashed the
 * widget/shortcut the instant it opened — so the popup is painted directly instead.) It has the identical
 * option row (date · priority · tag · list · reminder · voice), understands the same quick-add grammar
 * ("tomorrow 3pm p1 ~Home #bills"), and writes straight to Room. Fully offline; no network, no account.
 */
class QuickCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as App
        // Prefill from a shared/voice text or a todocompanion://add?text= deep link, if present.
        val prefill = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.data?.getQueryParameter("text")
            ?: ""

        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.todocompanion.app.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                val vm: AppViewModel = viewModel()
                // Dismissing the panel (tap-away or after adding) keeps the task widgets current, then
                // closes the floating window — the app itself never comes forward.
                QuickCapturePanel(vm, initialText = prefill, onDismiss = {
                    AgendaWidget.refresh(this@QuickCaptureActivity)
                    TodayWidget.refresh(this@QuickCaptureActivity)
                    DoNextWidget.refresh(this@QuickCaptureActivity)
                    Next7Widget.refresh(this@QuickCaptureActivity)
                    finish()
                })
            }
        }
    }
}
