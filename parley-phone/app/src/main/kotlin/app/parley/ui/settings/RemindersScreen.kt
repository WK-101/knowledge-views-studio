// The page's destination lives with its screen.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.AppSettings
import app.parley.common.calls.MissedReAlert
import app.parley.common.circle.CircleConfig
import app.parley.common.circle.ReminderDelivery
import app.parley.common.ux.BackupNudge
import app.parley.ui.Destination
import app.parley.ui.LinkRow
import app.parley.ui.LocalHighlightKey
import app.parley.ui.SegmentedGroup
import app.parley.ui.SegmentedGroupScope
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.calls.ToCallRoutes
import app.parley.work.ReminderChannels
import app.parley.work.RemindersWorker
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Settings › Reminders. [Page.focus]: a setting to scroll to and highlight (from search or an old link). */
object RemindersRoutes {
    @Serializable data class Page(val focus: String? = null) : Destination
}

/**
 * Every kind of reminder Parley sends, on one page: missed calls again, To call and follow-ups, keep in touch, birthdays
 * and dates, backups and temporary contacts that are due. Each has its switch and its time here (the settings stay
 * where they were stored; only the page is shared), and their channels share the notification group "Reminders".
 * Contacts, Recents & history, Calls and Backup & sync link here instead of repeating the rows.
 */
@Composable
fun RemindersScreen(vm: AppViewModel, focus: String?, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val circle by vm.c.circle.config.collectAsStateWithLifecycle()
    val set: ((AppSettings) -> AppSettings) -> Unit = { f -> scope.launch { vm.c.settings.update(f) } }
    val controller = controllerOf(focus, s, circle)
    // Channels an older version made join the group as soon as the page is seen.
    LaunchedEffect(Unit) { runCatching { ReminderChannels.regroupExisting(context) } }

    CompositionLocalProvider(LocalHighlightKey provides (controller ?: focus)) {
        SettingsScaffold(stringResource(R.string.set_reminders_title), back) {
            Text(
                stringResource(R.string.rem_intro),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.listInset),
            )
            if (focus != null && controller != null) {
                Text(
                    stringResource(R.string.set_search_shown_after, settingTitle(focus), settingTitle(controller)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.listInset),
                )
            }
            MissedCallsGroup(vm)
            SegmentedGroup(stringResource(R.string.rem_group_to_call)) {
                linkRow("to_call", Icons.Rounded.PhoneCallback) { open(ToCallRoutes.List) }
                // The prompt after a call is where follow-ups are set; it is also under Calls with the other note settings.
                switchRow("memory_prompt", circle.memoryPrompt, Icons.AutoMirrored.Rounded.NoteAdd) { v ->
                    vm.c.circle.updateConfig { it.copy(memoryPrompt = v) }
                }
            }
            SegmentedGroup(stringResource(R.string.rem_group_keep_in_touch)) {
                switchRow("nudges", s.reachOutNudges, Icons.Rounded.Handshake) { v -> set { it.copy(reachOutNudges = v) } }
                keepInTouchRows(vm, circle, s.reachOutNudges)
            }
            val at = stringResource(R.string.set_birthday_reminders_at, "${s.birthdayReminderHour}:00")
            SegmentedGroup(stringResource(R.string.rem_group_dates)) {
                switchRow("birthday_reminders", s.birthdayReminders, Icons.Rounded.Cake, sub = at) { v -> set { it.copy(birthdayReminders = v) } }
                if (s.birthdayReminders) {
                    menuRow("reminder_time", (6..22).map { "$it:00" }, (s.birthdayReminderHour - 6).coerceIn(0, 16), Icons.Rounded.Timer) { i ->
                        set { it.copy(birthdayReminderHour = i + 6) }
                        RemindersWorker.schedule(context, i + 6)
                    }
                }
                dateLeadRow(vm, circle, s.birthdayReminders)
            }
            BackupsGroup(vm)
            SegmentedGroup(stringResource(R.string.rem_group_temporary)) {
                // Also on the Temporary contacts screen, beside the contacts it's about.
                switchRow("temp_ask_first", s.askBeforeDeletingTemporary, Icons.Rounded.QuestionAnswer) { v ->
                    set { it.copy(askBeforeDeletingTemporary = v) }
                }
            }
            NotificationsGroup()
        }
    }
}

/** A row found by search that shows only while another is on: that other row, to point at instead (and say why). */
private fun controllerOf(focus: String?, s: AppSettings, circle: CircleConfig): String? = when (focus) {
    "reminder_time", "date_lead" -> "birthday_reminders".takeIf { !s.birthdayReminders }
    "circle_delivery" -> "nudges".takeIf { !s.reachOutNudges }
    "circle_weekly_cap" -> when {
        !s.reachOutNudges -> "nudges"
        circle.delivery != ReminderDelivery.AS_DUE -> "circle_delivery"
        else -> null
    }
    else -> null
}

/** Missed calls again, every few minutes until seen (stored with the other call extras). */
@Composable
private fun MissedCallsGroup(vm: AppViewModel) {
    val calls by vm.c.callExtras.config.collectAsStateWithLifecycle()
    val choices = MissedReAlert.CHOICES
    val labels = choices.map { if (it == 0) stringResource(R.string.set_off) else pluralStringResource(R.plurals.set_every_minutes, it, it) }
    val sub = if (calls.missedReAlertMinutes == 0) null else stringResource(R.string.set_missed_realert_on, calls.missedReAlertMinutes)
    SegmentedGroup(stringResource(R.string.rem_group_missed)) {
        menuRow("missed_realert", labels, choices.indexOf(calls.missedReAlertMinutes).coerceAtLeast(0), Icons.Rounded.NotificationsActive, sub = sub) { i ->
            vm.c.callExtras.update { it.copy(missedReAlertMinutes = choices[i]) }
        }
    }
}

/** How long without a backup before a quiet reminder. */
@Composable
private fun BackupsGroup(vm: AppViewModel) {
    val ux by vm.c.ux.state.collectAsStateWithLifecycle()
    val days = BackupNudge.REMINDER_DAYS
    val options = days.map { pluralStringResource(R.plurals.ux_backup_after_days, it, it) }
    SegmentedGroup(stringResource(R.string.rem_group_backups)) {
        menuRow("backup_reminder", options, days.indexOf(ux.backupReminderDays).coerceAtLeast(0), Icons.Rounded.NotificationsActive) { i ->
            vm.c.ux.setBackupReminderDays(days[i])
        }
    }
}

/** Android's notification settings for Parley, where the "Reminders" group is. */
@Composable
private fun NotificationsGroup() {
    val context = LocalContext.current
    SegmentedGroup(stringResource(R.string.rem_group_notifications)) {
        item("reminder_notifications") {
            LinkRow(
                stringResource(R.string.rem_notifications_title), stringResource(R.string.rem_notifications_summary), Icons.Rounded.Notifications,
                external = true,
            ) {
                runCatching { ReminderChannels.regroupExisting(context) }
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}

/** The "Reminders" row the pages people look on (Contacts, Recents & history, Calls, Backup & sync) link with. */
fun SegmentedGroupScope.remindersLinkRow(open: (Destination) -> Unit) =
    item("reminders") {
        LinkRow(settingTitle("reminders"), stringResource(R.string.rem_link_summary), Icons.Rounded.NotificationsActive) { open(RemindersRoutes.Page()) }
    }
