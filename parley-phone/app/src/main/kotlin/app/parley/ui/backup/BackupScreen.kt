package app.parley.ui.backup

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.data.backup.BackupFileInfo
import app.parley.data.backup.BackupSchedule
import app.parley.ui.Clipboard
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.common.Format
import app.parley.ui.startOrSay
import app.parley.work.BackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyDialog
import app.parley.ui.ConfirmDialog
import app.parley.ui.StrengthMeter
import app.parley.common.security.PassphraseStrength
import app.parley.common.backup.BackupFix
import app.parley.common.backup.BackupSetupCheck

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val repo = vm.c.backup
    val state by repo.prefs.state.collectAsStateWithLifecycle()
    val ux by vm.c.ux.state.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<BackupFileInfo>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var setPass by remember { mutableStateOf(false) }
    var changePass by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf<String?>(null) }
    var confirmPhone by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(state.folderUri, refresh) { files = withContext(Dispatchers.IO) { repo.listBackups() } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            repo.setFolder(uri, uri.lastPathSegment?.substringAfterLast(':'))
            refresh++
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) restoreUri = uri }

    fun runBackup() {
        busy = res.getString(R.string.bkp_backing_up)
        scope.launch {
            val out = repo.backupNow(scheduled = false)
            busy = null
            // Not after a duress unlock, when private contacts aren't there to skip (I21).
            val skipped = out.ok && !out.vaultIncluded && vm.settings.value.duress == null && vm.c.vault.contacts.value.isNotEmpty()
            vm.toast(if (skipped) res.getString(R.string.bkp_vault_skipped, out.message) else out.message)
            refresh++
        }
    }

    fun transfer() {
        busy = res.getString(R.string.bkp_preparing)
        scope.launch {
            val dir = File(context.cacheDir, "transfer").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "parley-transfer.parley")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val out = repo.backupNow(scheduled = false, target = uri)
            busy = null
            if (out.ok) {
                val share = Intent(Intent.ACTION_SEND).setType(
                    "application/octet-stream",
                ).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startOrSay(Intent.createChooser(share, res.getString(R.string.bkp_send_chooser)))
            } else {
                vm.toast(out.message)
            }
        }
    }

    ParleyScaffold(topBar = {
        ParleyTopBar(stringResource(R.string.bkp_title), onBack = back)
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            // Overdue reminder (also in Settings); "Not now" snoozes it.
            item { BackupReminderBanner(vm) }
            // Setup checker: one line saying what most needs doing (or that all is well), with its fix.
            item {
                BackupSetupStatus(state, ux.backupReminderDays) { fix ->
                    when (fix) {
                        BackupFix.SET_PASSPHRASE -> setPass = true
                        BackupFix.CHOOSE_FOLDER -> folderPicker.launch(null)
                        BackupFix.BACK_UP_NOW -> if (state.hasKeys && state.folderUri != null && busy == null) runBackup()
                        BackupFix.NONE -> Unit
                    }
                }
            }
            item {
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
                                ) else stringResource(R.string.bkp_no_backup),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (state.lastVerifiedAt > 0) Text(
                            state.keyId?.let { stringResource(R.string.bkp_verified_key, Format.fullDate(context, state.lastVerifiedAt), it) }
                                ?: stringResource(R.string.bkp_verified, Format.fullDate(context, state.lastVerifiedAt)),
                            style = MaterialTheme.typography.bodySmall)
                        state.resultText(LocalResources.current)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (state.rotationPaused) Row {
                            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                "  " + stringResource(R.string.bkp_rotation_paused),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state.rotationPaused) TextButton({ repo.resumeRotation() }) { Text(stringResource(R.string.bkp_resume_rotation)) }
                        Text(
                            stringResource(R.string.bkp_explain),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            ::runBackup, enabled = ready && busy == null, modifier = Modifier.padding(top = 4.dp),
                        ) { Text(stringResource(R.string.bkp_back_up_now)) }
                        busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                }
            }
            item { Section(stringResource(R.string.bkp_set_up)) }
            item {
                ParleyListItem(
                    modifier = Modifier.clickable { if (state.hasKeys) changePass = true else setPass = true },
                    leadingContent = { Icon(Icons.Rounded.Key, null) },
                    headlineContent = { Text(if (state.hasKeys) stringResource(R.string.bkp_change_pass) else stringResource(R.string.bkp_set_pass)) },
                    supportingContent = {
                        Text(if (state.hasKeys) stringResource(R.string.bkp_change_pass_summary) else stringResource(R.string.bkp_set_pass_summary))
                    },
                )
                // Keys made before backups were signed: one passphrase entry lets the key vouch for this phone.
                if (state.hasKeys && !state.signedAsYours) {
                    ParleyListItem(
                        modifier = Modifier.clickable { confirmPhone = true },
                        leadingContent = { Icon(Icons.Rounded.VerifiedUser, null) },
                        headlineContent = { Text(stringResource(R.string.bkp_confirm_phone)) },
                        supportingContent = { Text(stringResource(R.string.bkp_confirm_phone_summary)) },
                    )
                }
                ParleyListItem(
                    modifier = Modifier.clickable { folderPicker.launch(null) },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    headlineContent = { Text(stringResource(R.string.bkp_folder)) },
                    supportingContent = {
                        // Where it lives, from its location only (P13): "Parley · On this phone only".
                        val place = folderPlaceText(res, BackupSetupCheck.locate(state.folderUri))
                        Text(state.folderName?.let { name -> place?.let { "$name · $it" } ?: name } ?: stringResource(R.string.bkp_folder_none))
                    },
                )
                ParleyListItem(
                    headlineContent = { Text(stringResource(R.string.bkp_automatic)) },
                    supportingContent = {
                        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                            BackupSchedule.entries.forEachIndexed { i, s ->
                                SegmentedButton(state.schedule == s, {
                                    repo.prefs.update { it.putString("schedule", s.name) }
                                    BackupWorker.schedule(context, s)
                                }, SegmentedButtonDefaults.itemShape(i, 3)) {
                                    Text(
                                        stringResource(
                                            when (s) {
                                                BackupSchedule.OFF -> R.string.bkp_schedule_off
                                                BackupSchedule.DAILY -> R.string.bkp_schedule_daily
                                                BackupSchedule.WEEKLY -> R.string.bkp_schedule_weekly
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    },
                )
                ParleyListItem(
                    headlineContent = { Text(stringResource(R.string.bkp_keep)) },
                    supportingContent = {
                        val opts = listOf(0 to stringResource(R.string.bkp_keep_smart), 5 to "%d".format(5), 10 to "%d".format(10), 30 to "%d".format(30))
                        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                            opts.forEachIndexed { i, (n, label) ->
                                SegmentedButton(
                                    state.keepLast == n, { repo.prefs.update { it.putInt("keepLast", n) } }, SegmentedButtonDefaults.itemShape(i, opts.size),
                                ) { Text(label) }
                            }
                        }
                    },
                    trailingContent = null,
                )
                if (state.keepLast == 0) Text(
                    stringResource(R.string.bkp_keep_smart_summary),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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
            item { Section(stringResource(R.string.bkp_restore)) }
            item {
                ParleyListItem(
                    modifier = Modifier.clickable { filePicker.launch(arrayOf("*/*")) },
                    leadingContent = { Icon(Icons.Rounded.Restore, null) },
                    headlineContent = { Text(stringResource(R.string.bkp_restore_file)) },
                    supportingContent = { Text(stringResource(R.string.bkp_restore_file_summary)) },
                )
                ParleyListItem(
                    modifier = Modifier.clickable(enabled = state.hasKeys && busy == null) { transfer() },
                    leadingContent = { Icon(Icons.Rounded.PhoneAndroid, null) },
                    headlineContent = { Text(stringResource(R.string.bkp_move_phone)) },
                    supportingContent = { Text(stringResource(R.string.bkp_move_phone_summary)) },
                )
                if (state.lastRestoreIds.isNotEmpty()) {
                    ParleyListItem(
                        modifier = Modifier.clickable {
                            scope.launch { val n = repo.undoLastRestore(); vm.toast(res.getQuantityString(R.plurals.bkp_undo_done, n, n)) }
                        },
                        headlineContent = { Text(stringResource(R.string.bkp_undo_restore)) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.bkp_undo_restore_summary, state.lastRestoreIds.size, state.lastRestoreIds.size))
                        },
                    )
                }
            }
            if (files.isNotEmpty()) {
                item { Section(stringResource(R.string.bkp_in_folder)) }
                items(files, key = { it.uri.toString() }) { f ->
                    ParleyListItem(
                        modifier = Modifier.clickable { restoreUri = f.uri },
                        headlineContent = { Text(Format.fullDate(context, f.time)) },
                        supportingContent = { Text(Formatter.formatShortFileSize(context, f.size)) },
                        trailingContent = { Text(stringResource(R.string.dc_restore), color = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            // What a backup holds, and what stays here on purpose (and why), so a move to a new phone holds no surprise.
            item { Section(stringResource(R.string.bkp_kept_here_title)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bkp_holds_text), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.bkp_kept_here_text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (setPass || changePass) {
        PassphraseDialog(
            change = changePass,
            onDismiss = { setPass = false; changePass = false },
        ) { old, new ->
            scope.launch {
                if (changePass) {
                    val ok = repo.changePassphrase(old!!.toCharArray(), new.toCharArray())
                    vm.toast(res.getString(if (ok) R.string.bkp_pass_changed else R.string.bkp_pass_wrong))
                } else {
                    recovery = repo.setupKeys(new.toCharArray()).format()
                }
                setPass = false
                changePass = false
            }
        }
    }
    if (confirmPhone) {
        var pass by remember { mutableStateOf("") }
        ConfirmDialog(
            title = stringResource(R.string.bkp_confirm_phone),
            text = stringResource(R.string.bkp_confirm_phone_summary),
            confirmLabel = stringResource(R.string.dc_ok),
            onConfirm = {
                scope.launch {
                    val ok = repo.confirmThisPhone(pass.toCharArray())
                    vm.toast(res.getString(if (ok) R.string.bkp_confirm_phone_done else R.string.bkp_pass_wrong))
                    if (ok) confirmPhone = false
                }
            },
            onDismiss = { confirmPhone = false },
            dismissLabel = stringResource(R.string.dc_cancel),
            confirmEnabled = pass.isNotEmpty(),
            content = { PassField(stringResource(R.string.bkp_current_pass), pass) { pass = it } },
        )
    }
    recovery?.let { key ->
        ParleyDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.bkp_recovery_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.bkp_recovery_text))
                    Text(key, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton({ recovery = null }) { Text(stringResource(R.string.bkp_recovery_saved)) } },
            dismissButton = { TextButton({ Clipboard.copy(context, key) }) { Text(stringResource(R.string.bkp_copy)) } },
        )
    }
    restoreUri?.let { uri -> RestoreFlow(vm, uri) { restoreUri = null; refresh++ } }
}

@Composable
private fun PassphraseDialog(change: Boolean, onDismiss: () -> Unit, onSave: (String?, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val estimate = remember(new) { PassphraseStrength.estimate(new) }
    // Every backup carries the key wrapped under this passphrase, so it must stand up to offline guessing.
    val strongEnough = PassphraseStrength.acceptableForBackup(new)
    val ok = strongEnough && new == confirm && (!change || old.isNotEmpty())
    ConfirmDialog(
        title = if (change) stringResource(R.string.bkp_change_pass_title) else stringResource(R.string.bkp_pass_title),
        text = null,
        confirmLabel = stringResource(R.string.dc_save),
        onConfirm = { onSave(old.takeIf { change }, new) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        confirmEnabled = ok,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (change) PassField(stringResource(R.string.bkp_current_pass), old) { old = it }
                PassField(stringResource(R.string.bkp_new_pass), new) { new = it }
                if (new.isNotEmpty()) {
                    StrengthMeter(estimate.score, stringResource(strengthLabel(estimate.score)), strengthHint(estimate.hint)?.let { stringResource(it) })
                }
                PassField(stringResource(R.string.bkp_repeat), confirm) { confirm = it }
                val hint = if (new.isNotEmpty() && !strongEnough) R.string.bkp_strength_needed else R.string.bkp_pass_hint
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

internal fun strengthLabel(score: Int) = when (score) {
    0 -> R.string.bkp_strength_0
    1 -> R.string.bkp_strength_1
    2 -> R.string.bkp_strength_2
    3 -> R.string.bkp_strength_3
    else -> R.string.bkp_strength_4
}

internal fun strengthHint(h: PassphraseStrength.Hint): Int? = when (h) {
    PassphraseStrength.Hint.NONE -> null
    PassphraseStrength.Hint.TOO_SHORT -> R.string.bkp_strength_short
    PassphraseStrength.Hint.COMMON -> R.string.bkp_strength_common
    PassphraseStrength.Hint.KEYBOARD -> R.string.bkp_strength_keyboard
    PassphraseStrength.Hint.SEQUENCE -> R.string.bkp_strength_sequence
    PassphraseStrength.Hint.REPEAT -> R.string.bkp_strength_repeat
    PassphraseStrength.Hint.DATE -> R.string.bkp_strength_date
    PassphraseStrength.Hint.ADD_WORDS -> R.string.bkp_strength_words
}

@Composable
fun PassField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
