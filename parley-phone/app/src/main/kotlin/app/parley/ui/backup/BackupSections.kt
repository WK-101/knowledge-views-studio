package app.parley.ui.backup

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.data.backup.BackupFileInfo
import app.parley.data.backup.BackupSchedule
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.common.Format
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.backup.BackupSetupCheck
import app.parley.data.backup.BackupState
import androidx.compose.foundation.lazy.LazyListScope

/** The card at the top: the last backup, its check, a paused rotation, and Back up now with its progress. */
@Composable
internal fun BackupStatusCard(state: BackupState, busy: String?, onBackUp: () -> Unit, onResume: () -> Unit) {
    val context = LocalContext.current
    val ready = state.hasKeys && state.folderUri != null
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Icon(if (state.lastBackupAt > 0) Icons.Rounded.CheckCircle else Icons.Rounded.Backup, null)
                Text(
                    "  " + if (state.lastBackupAt > 0) stringResource(
                        R.string.bkp_last_backup, Format.shortWhen(context, state.lastBackupAt),
                    ) else stringResource(R.string.label_no_backup_yet),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.lastVerifiedAt > 0) Text(
                state.keyId?.let { stringResource(R.string.bkp_verified_key, Format.fullDate(context, state.lastVerifiedAt), it) }
                    ?: stringResource(R.string.bkp_verified, Format.fullDate(context, state.lastVerifiedAt)),
                style = MaterialTheme.typography.bodySmall,
            )
            state.resultText(LocalResources.current)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.rotationPaused) Row {
                Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                Text(
                    "  " + stringResource(R.string.bkp_rotation_paused),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.rotationPaused) TextButton(onResume) { Text(stringResource(R.string.bkp_resume_rotation)) }
            Text(
                stringResource(R.string.bkp_explain),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onBackUp, enabled = ready && busy == null, modifier = Modifier.padding(top = 4.dp),
            ) { Text(stringResource(R.string.action_back_up_now)) }
            busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
    }
}

/** Set up: the passphrase, confirming this phone, the folder, automatic backups, how many to keep, the reminder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupSetupSection(
    vm: AppViewModel,
    state: BackupState,
    onPassphrase: () -> Unit,
    onConfirmPhone: () -> Unit,
    onFolder: () -> Unit,
    onSchedule: (BackupSchedule) -> Unit,
    onKeep: (Int) -> Unit,
) {
    val res = LocalResources.current
    Column {
        ParleyListItem(
            modifier = Modifier.clickable(onClick = onPassphrase),
            leadingContent = { Icon(Icons.Rounded.Key, null) },
            headlineContent = { Text(if (state.hasKeys) stringResource(R.string.bkp_change_pass) else stringResource(R.string.bkp_set_pass)) },
            supportingContent = {
                Text(if (state.hasKeys) stringResource(R.string.bkp_change_pass_summary) else stringResource(R.string.bkp_set_pass_summary))
            },
        )
        // Keys made before backups were signed: one passphrase entry lets the key vouch for this phone.
        if (state.hasKeys && !state.signedAsYours) {
            ParleyListItem(
                modifier = Modifier.clickable(onClick = onConfirmPhone),
                leadingContent = { Icon(Icons.Rounded.VerifiedUser, null) },
                headlineContent = { Text(stringResource(R.string.bkp_confirm_phone)) },
                supportingContent = { Text(stringResource(R.string.bkp_confirm_phone_summary)) },
            )
        }
        ParleyListItem(
            modifier = Modifier.clickable(onClick = onFolder),
            leadingContent = { Icon(Icons.Rounded.Folder, null) },
            headlineContent = { Text(stringResource(R.string.bkp_folder)) },
            supportingContent = {
                // Where it lives, from its location only: "Parley · On this phone only".
                val place = folderPlaceText(res, BackupSetupCheck.locate(state.folderUri))
                Text(state.folderName?.let { name -> place?.let { "$name · $it" } ?: name } ?: stringResource(R.string.bkp_folder_none))
            },
        )
        ParleyListItem(
            headlineContent = { Text(stringResource(R.string.label_automatic_backups)) },
            supportingContent = {
                SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                    BackupSchedule.entries.forEachIndexed { i, s ->
                        SegmentedButton(state.schedule == s, { onSchedule(s) }, SegmentedButtonDefaults.itemShape(i, 3)) {
                            Text(stringResource(scheduleLabel(s)))
                        }
                    }
                }
            },
        )
        BackupKeepRows(state.keepLast, onKeep)
        // When to remind about an overdue backup (14 or 30 days; at most one notification a month).
        ParleyListItem(
            headlineContent = { Text(stringResource(R.string.set_backup_reminder_title)) },
            supportingContent = {
                Column {
                    Text(stringResource(R.string.set_backup_reminder_summary))
                    BackupReminderChoice(vm, Modifier.padding(top = 8.dp))
                }
            },
        )
    }
}

private fun scheduleLabel(s: BackupSchedule): Int = when (s) {
    BackupSchedule.OFF -> R.string.dc_off
    BackupSchedule.DAILY -> R.string.bkp_schedule_daily
    BackupSchedule.WEEKLY -> R.string.bkp_schedule_weekly
}

/** How many backups to keep: smart (with what it means) or the last 5, 10 or 30. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupKeepRows(keepLast: Int, onKeep: (Int) -> Unit) {
    ParleyListItem(
        headlineContent = { Text(stringResource(R.string.action_keep)) },
        supportingContent = {
            val opts = listOf(0 to stringResource(R.string.bkp_keep_smart), 5 to "%d".format(5), 10 to "%d".format(10), 30 to "%d".format(30))
            SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                opts.forEachIndexed { i, (n, label) ->
                    SegmentedButton(
                        keepLast == n, { onKeep(n) }, SegmentedButtonDefaults.itemShape(i, opts.size),
                    ) { Text(label) }
                }
            }
        },
        trailingContent = null,
    )
    if (keepLast == 0) Text(
        stringResource(R.string.bkp_keep_smart_summary),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** Restore: from a file, moving to a new phone, and undoing the last restore while it can be undone. */
@Composable
internal fun BackupRestoreSection(state: BackupState, busy: String?, onFile: () -> Unit, onTransfer: () -> Unit, onUndo: () -> Unit) {
    Column {
        ParleyListItem(
            modifier = Modifier.clickable(onClick = onFile),
            leadingContent = { Icon(Icons.Rounded.Restore, null) },
            headlineContent = { Text(stringResource(R.string.bkp_restore_file)) },
            supportingContent = { Text(stringResource(R.string.bkp_restore_file_summary)) },
        )
        ParleyListItem(
            modifier = Modifier.clickable(enabled = state.hasKeys && busy == null, onClick = onTransfer),
            leadingContent = { Icon(Icons.Rounded.PhoneAndroid, null) },
            headlineContent = { Text(stringResource(R.string.bkp_move_phone)) },
            supportingContent = { Text(stringResource(R.string.bkp_move_phone_summary)) },
        )
        if (state.lastRestoreIds.isNotEmpty()) {
            ParleyListItem(
                modifier = Modifier.clickable(onClick = onUndo),
                headlineContent = { Text(stringResource(R.string.bkp_undo_restore)) },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.bkp_undo_restore_summary, state.lastRestoreIds.size, state.lastRestoreIds.size))
                },
            )
        }
    }
}

/** The backups in the folder, newest first; each opens its restore. */
internal fun LazyListScope.backupFiles(files: List<BackupFileInfo>, restore: (Uri) -> Unit) {
    if (files.isEmpty()) return
    item { Section(stringResource(R.string.bkp_in_folder)) }
    items(files, key = { it.uri.toString() }) { f ->
        val context = LocalContext.current
        ParleyListItem(
            modifier = Modifier.clickable { restore(f.uri) },
            headlineContent = { Text(Format.fullDate(context, f.time)) },
            supportingContent = { Text(Formatter.formatShortFileSize(context, f.size)) },
            trailingContent = { Text(stringResource(R.string.dc_restore), color = MaterialTheme.colorScheme.primary) },
        )
    }
}
