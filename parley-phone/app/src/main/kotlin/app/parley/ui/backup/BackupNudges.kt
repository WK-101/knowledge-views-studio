package app.parley.ui.backup

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.common.ux.BackupNudge
import app.parley.data.backup.BackupState
import app.parley.ui.Routes
import kotlinx.coroutines.launch

/** Backups are set up enough for a one-tap backup: a passphrase (public key) and a folder. */
private val BackupState.ready get() = hasKeys && folderUri != null

/**
 * C2: "Back up first?" before a change to many contacts at once (a large import, merging, a bulk delete) when the
 * last backup is more than 7 days old. [ask] runs the change straight away when no question is needed. Once the
 * user has answered, later changes through the same gate (the next duplicate group, say) don't ask again.
 */
@Stable
class BackupFirstGate internal constructor() {
    internal var pending by mutableStateOf<(() -> Unit)?>(null)
    internal var answered = false
    internal var check: (Int, Int) -> Boolean = { _, _ -> false }

    /** Runs [proceed], asking first when [count] contacts reach [threshold] and the last backup is old. */
    fun ask(count: Int, threshold: Int, proceed: () -> Unit) {
        if (answered || !check(count, threshold)) proceed() else pending = proceed
    }
}

/** C2: the gate and its dialog; call once per screen and use the returned gate's [BackupFirstGate.ask]. */
@Composable
fun rememberBackupFirst(vm: AppViewModel): BackupFirstGate {
    val gate = remember { BackupFirstGate() }
    val state by vm.c.backup.prefs.state.collectAsStateWithLifecycle()
    gate.check = { count, threshold -> BackupNudge.backupFirst(state.lastBackupAt, System.currentTimeMillis(), count, threshold) }
    val proceed = gate.pending ?: return gate
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    fun go() {
        gate.answered = true
        gate.pending = null
        failed = null
        proceed()
    }
    AlertDialog(
        onDismissRequest = { if (!busy) { gate.pending = null; failed = null } },
        icon = { Icon(Icons.Rounded.Backup, null) },
        title = { Text(stringResource(R.string.ux_backup_first_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (state.lastBackupAt > 0) stringResource(R.string.ux_backup_first_last, relative(state.lastBackupAt))
                    else stringResource(R.string.ux_backup_first_never),
                )
                Text(stringResource(if (state.ready) R.string.ux_backup_first_quick else R.string.ux_backup_first_setup))
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.bkp_backing_up))
                }
                failed?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state.ready) {
                TextButton(
                    {
                        busy = true
                        scope.launch {
                            val out = vm.c.backup.backupNow(scheduled = false)
                            busy = false
                            if (out.ok) {
                                vm.toast(out.message)
                                go()
                            } else {
                                failed = out.message
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.ux_backup_first_now)) }
            } else {
                TextButton({ gate.pending = null; vm.navigate(NavEvent.Route(Routes.BACKUP)) }) { Text(stringResource(R.string.ux_backup_set_up)) }
            }
        },
        dismissButton = {
            TextButton(::go, enabled = !busy) { Text(stringResource(R.string.ux_backup_first_skip)) }
        },
    )
    return gate
}

@Composable
private fun relative(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()

/**
 * C3: a quiet card once a backup is overdue (after 14 or 30 days, as chosen). "Not now" hides it for a week; it
 * comes back while no backup has been made, and is never switched off for good.
 */
@Composable
fun BackupReminderBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by vm.c.backup.prefs.state.collectAsStateWithLifecycle()
    val ux by vm.c.ux.state.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val since = BackupNudge.since(state.lastBackupAt, remember { vm.c.ux.installedAt(context) })
    if (!BackupNudge.showBanner(since, now, ux.backupReminderDays, ux.backupSnoozedUntil)) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Card(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Backup, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (state.lastBackupAt > 0) stringResource(R.string.ux_backup_due_title, relative(state.lastBackupAt))
                    else stringResource(R.string.ux_backup_due_never),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                stringResource(if (state.ready) R.string.ux_backup_due_body else R.string.ux_backup_due_body_setup),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                TextButton({ vm.c.ux.snoozeBackupBanner() }, enabled = !busy) { Text(stringResource(R.string.ux_not_now)) }
                if (state.ready) {
                    TextButton(
                        {
                            busy = true
                            scope.launch {
                                val out = vm.c.backup.backupNow(scheduled = false)
                                busy = false
                                vm.toast(out.message)
                            }
                        },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.ux_backup_first_now)) }
                } else {
                    TextButton({ vm.navigate(NavEvent.Route(Routes.BACKUP)) }) { Text(stringResource(R.string.ux_backup_set_up)) }
                }
            }
        }
    }
}

/** C3: "Remind me to back up after" 30 or 14 days (there is no "never": a dismissal only snoozes). */
@Composable
fun BackupReminderChoice(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ux by vm.c.ux.state.collectAsStateWithLifecycle()
    SingleChoiceSegmentedButtonRow(modifier) {
        BackupNudge.REMINDER_DAYS.forEachIndexed { i, days ->
            SegmentedButton(
                ux.backupReminderDays == days, { vm.c.ux.setBackupReminderDays(days) },
                SegmentedButtonDefaults.itemShape(i, BackupNudge.REMINDER_DAYS.size),
            ) { Text(pluralStringResource(R.plurals.ux_backup_after_days, days, days)) }
        }
    }
}
