package app.parley.telecom.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.common.calls.RemindTime
import app.parley.common.calls.ToCall
import app.parley.telecom.CallManager
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.TelecomGraph
import app.parley.ui.Spacing
import app.parley.ui.systemMessage
import java.time.ZoneId

/** The fixed times of "Remind me" (L1/P2), in words, for the call screen and the app's To call list. */
object RemindTimes {
    /** "In 1 hour", "This evening, 18:00", "Tomorrow morning, 09:00". */
    fun label(context: Context, choice: RemindTime, at: Long): String = when (choice) {
        RemindTime.IN_AN_HOUR -> context.getString(R.string.remind_in_an_hour)
        RemindTime.THIS_EVENING -> context.getString(R.string.remind_this_evening, time(context, at))
        RemindTime.TOMORROW_MORNING -> context.getString(R.string.remind_tomorrow_morning, time(context, at))
    }

    private fun time(context: Context, at: Long) = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME)

    /** "18:00" today, "Wed 09:00" another day. */
    fun whenText(context: Context, at: Long, now: Long = System.currentTimeMillis()): String =
        if (DateUtils.isToday(at) || at < now) {
            time(context, at)
        } else {
            DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_TIME)
        }

    /** The choices that make sense now, each with its time. */
    fun choices(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<Pair<RemindTime, Long>> =
        ToCall.choices(now, zone).map { it to ToCall.at(it, now, zone) }
}

/** Whether the call can be declined with a follow-up: it has a number to call back or message. */
internal fun offersDeclineFollowUp(call: CallUi): Boolean = !call.hidden && !call.number.isNullOrBlank() && !call.isEmergency

/**
 * The incoming ⋮ menu's follow-ups (P2): "Decline & remind" opens its three fixed times in place, and "Decline &
 * message or call on…" declines, then opens that sheet once the phone is unlocked. [close] closes the menu.
 */
@Composable
internal fun DeclineFollowUpItems(call: CallUi, close: () -> Unit) {
    val number = call.number?.takeIf { offersDeclineFollowUp(call) } ?: return
    val context = LocalContext.current
    val activity = LocalActivity.current
    var times by remember { mutableStateOf(false) }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.remind_decline)) },
        leadingIcon = { Icon(Icons.Rounded.AlarmAdd, null) },
        onClick = { times = !times },
        modifier = Modifier.semantics { contentDescription = context.getString(R.string.remind_decline_a11y) },
    )
    if (times) {
        RemindTimes.choices().forEach { (choice, at) ->
            DropdownMenuItem(
                text = { Text(RemindTimes.label(context, choice, at)) },
                onClick = {
                    close()
                    CallManager.reject(call.id)
                    runCatching { TelecomGraph.dependencies.remindToCall(number, call.accountId, at) }
                    systemMessage(context, context.getString(R.string.remind_declined_set, RemindTimes.whenText(context, at)))
                },
                // Indented under "Decline & remind", where the menu's icons line up.
                modifier = Modifier.padding(start = Spacing.xl),
            )
        }
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.remind_decline_message)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
        onClick = {
            close()
            // The ringing stops at once; the call is declined and the sheet opens once the phone is unlocked.
            CallManager.ignore(call.id)
            val open = {
                CallManager.reject(call.id)
                runCatching { context.startActivity(messageOnIntent(context, number, call.accountId)) }
            }
            val km = context.getSystemService(KeyguardManager::class.java)
            if (activity != null && km.isKeyguardLocked) {
                km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        open()
                    }
                })
            } else {
                open()
            }
        },
    )
}

/** The app's "Message or call on…" sheet (this module can't depend on the app, so by class name). */
private fun messageOnIntent(context: Context, number: String, accountId: String?): Intent =
    Intent(ACTION_MESSAGE_ON).setClassName(context.packageName, MESSAGE_ON_ACTIVITY).putExtra("number", number)
        .apply { accountId?.let { putExtra("account_id", it) } }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private const val ACTION_MESSAGE_ON = "app.parley.action.MESSAGE_ON"
private const val MESSAGE_ON_ACTIVITY = "app.parley.messaging.NumberActionActivity"

/** "Remind me" on the post-call card (L1): the three fixed times, then [onDone]. */
@Composable
internal fun RemindMeAction(number: String, accountId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton({ open = true }) {
            Icon(Icons.Rounded.AlarmAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.remind_me))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            RemindTimes.choices().forEach { (choice, at) ->
                DropdownMenuItem(
                    text = { Text(RemindTimes.label(context, choice, at)) },
                    onClick = {
                        open = false
                        runCatching { TelecomGraph.dependencies.remindToCall(number, accountId, at) }
                        systemMessage(context, context.getString(R.string.remind_set, RemindTimes.whenText(context, at)))
                        onDone()
                    },
                )
            }
        }
    }
}
