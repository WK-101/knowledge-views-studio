package com.todocompanion.app.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todocompanion.app.App
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.screens.QuickAddSheet
import com.todocompanion.app.ui.theme.AppTheme

/**
 * The "add a task without opening the whole app" popup the Quick-add widget (and shared/voice/deep-link
 * captures) fire into. It floats over the launcher in its own task (excludeFromRecents + taskAffinity=""
 * in the manifest) and — rather than a bespoke, cruder mini-form — renders the SAME [QuickAddSheet] used
 * inside the app (R19 #2). So the widget capture has the identical calm, borderless design and the full
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
                // Dismissing the sheet (tap-away or after adding) keeps the task widgets current, then
                // closes the floating window — the app itself never comes forward.
                QuickAddSheet(vm, initialText = prefill, onDismiss = {
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
