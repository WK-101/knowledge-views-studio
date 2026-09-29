package app.parley.calls

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.lifecycleScope
import app.parley.MissedCallActionReceiver
import app.parley.R
import app.parley.container
import app.parley.security.LockedActivity
import app.parley.telecom.R as TelecomR
import app.parley.telecom.ui.RemindTimes
import app.parley.ui.ParleySheet
import app.parley.ui.ParleyTheme
import app.parley.ui.Spacing
import app.parley.ui.common.ProvideAppKit
import app.parley.ui.systemMessage
import kotlinx.coroutines.launch

/**
 * "Remind me" on a missed-call notification (P3): a small sheet with the fixed times. It names no one (the
 * notification already said who), so it needs no app unlock; the system asks to unlock the phone before any
 * activity starts from the lock screen. The caller's notification goes, as if seen, once the reminder is set.
 */
class RemindMeActivity : LockedActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() } ?: return finish()
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        // Secure until the settings say otherwise.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            val settings = container.settings.current()
            if (!settings.secureScreen) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setContent {
                ParleyTheme(settings.themeMode, settings.amoledBlack, settings.dynamicColor, settings.density) {
                    ProvideAppKit {
                        ParleySheet(
                            onDismissRequest = { finish() },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            title = stringResource(R.string.to_call_remind_title),
                        ) {
                            RemindTimes.choices().forEach { (choice, at) ->
                                ListItem(
                                    headlineContent = { Text(RemindTimes.label(this@RemindMeActivity, choice, at)) },
                                    leadingContent = { Icon(Icons.Rounded.AlarmAdd, null) },
                                    modifier = Modifier.clickable(role = Role.Button) { pick(number, accountId, at, notificationId) },
                                )
                            }
                            Spacer(Modifier.navigationBarsPadding().padding(bottom = Spacing.l))
                        }
                    }
                }
            }
        }
    }

    private fun pick(number: String, accountId: String?, at: Long, notificationId: Int) {
        val app = applicationContext
        container.scope.launch {
            if (ToCallReminders.remind(app, number, accountId, at)) {
                // That caller's missed-call notification is dealt with; the last one gone means all were seen.
                if (notificationId != 0) app.getSystemService(NotificationManager::class.java).cancel(notificationId)
                if (!MissedCallNotifier.anyShowing(app, childrenOnly = true)) {
                    MissedCallNotifier.cancelAll(app)
                    MissedCallActionReceiver.seen(app)
                }
            }
        }
        systemMessage(this, getString(TelecomR.string.remind_set, RemindTimes.whenText(this, at)))
        finish()
    }

    companion object {
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun intent(context: Context, number: String, accountId: String?, notificationId: Int): Intent =
            Intent(context, RemindMeActivity::class.java)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
