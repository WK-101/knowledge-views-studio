package app.parley.ui.backup

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.data.backup.BackupFileInfo
import app.parley.data.backup.BackupSchedule
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.contact.Section
import app.parley.work.BackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val repo = vm.c.backup
    val state by repo.prefs.state.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<BackupFileInfo>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var setPass by remember { mutableStateOf(false) }
    var changePass by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf<String?>(null) }
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
            vm.toast(if (out.ok && !out.vaultIncluded && vm.c.vault.contacts.value.isNotEmpty()) res.getString(R.string.bkp_vault_skipped, out.message) else out.message)
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
                val share = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(share, res.getString(R.string.bkp_send_chooser))) }
            } else {
                vm.toast(out.message)
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.bkp_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                val ready = state.hasKeys && state.folderUri != null
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { Icon(if (state.lastBackupAt > 0) Icons.Rounded.CheckCircle else Icons.Rounded.Backup, null); Text("  " + if (state.lastBackupAt > 0) stringResource(R.string.bkp_last_backup, Format.shortWhen(context, state.lastBackupAt)) else stringResource(R.string.bkp_no_backup), style = MaterialTheme.typography.titleMedium) }
                        if (state.lastVerifiedAt > 0) Text(
                            state.keyId?.let { stringResource(R.string.bkp_verified_key, Format.fullDate(context, state.lastVerifiedAt), it) }
                                ?: stringResource(R.string.bkp_verified, Format.fullDate(context, state.lastVerifiedAt)),
                            style = MaterialTheme.typography.bodySmall)
                        state.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (state.rotationPaused) Row { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error); Text("  " + stringResource(R.string.bkp_rotation_paused), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (state.rotationPaused) TextButton({ repo.resumeRotation() }) { Text(stringResource(R.string.bkp_resume_rotation)) }
                        Text(
                            stringResource(R.string.bkp_explain),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(::runBackup, enabled = ready && busy == null, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.bkp_back_up_now)) }
                        busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                }
            }
            item { Section(stringResource(R.string.bkp_set_up)) }
            item {
                ListItem(
                    modifier = Modifier.clickable { if (state.hasKeys) changePass = true else setPass = true },
                    leadingContent = { Icon(Icons.Rounded.Key, null) },
                    headlineContent = { Text(if (state.hasKeys) stringResource(R.string.bkp_change_pass) else stringResource(R.string.bkp_set_pass)) },
                    supportingContent = { Text(if (state.hasKeys) stringResource(R.string.bkp_change_pass_summary) else stringResource(R.string.bkp_set_pass_summary)) },
                )
                ListItem(
                    modifier = Modifier.clickable { folderPicker.launch(null) },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    headlineContent = { Text(stringResource(R.string.bkp_folder)) },
                    supportingContent = { Text(state.folderName ?: stringResource(R.string.bkp_folder_none)) },
                )
                ListItem(
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
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bkp_keep)) },
                    supportingContent = {
                        val opts = listOf(0 to stringResource(R.string.bkp_keep_smart), 5 to "%d".format(5), 10 to "%d".format(10), 30 to "%d".format(30))
                        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                            opts.forEachIndexed { i, (n, label) ->
                                SegmentedButton(state.keepLast == n, { repo.prefs.update { it.putInt("keepLast", n) } }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label) }
                            }
                        }
                    },
                    trailingContent = null,
                )
                if (state.keepLast == 0) Text(stringResource(R.string.bkp_keep_smart_summary), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item { Section(stringResource(R.string.bkp_restore)) }
            item {
                ListItem(
                    modifier = Modifier.clickable { filePicker.launch(arrayOf("*/*")) },
                    leadingContent = { Icon(Icons.Rounded.Restore, null) },
                    headlineContent = { Text(stringResource(R.string.bkp_restore_file)) },
                    supportingContent = { Text(stringResource(R.string.bkp_restore_file_summary)) },
                )
                ListItem(
                    modifier = Modifier.clickable(enabled = state.hasKeys && busy == null) { transfer() },
                    leadingContent = { Icon(Icons.Rounded.PhoneAndroid, null) },
                    headlineContent = { Text(stringResource(R.string.bkp_move_phone)) },
                    supportingContent = { Text(stringResource(R.string.bkp_move_phone_summary)) },
                )
                if (state.lastRestoreIds.isNotEmpty()) {
                    ListItem(
                        modifier = Modifier.clickable { scope.launch { val n = repo.undoLastRestore(); vm.toast(res.getQuantityString(R.plurals.bkp_undo_done, n, n)) } },
                        headlineContent = { Text(stringResource(R.string.bkp_undo_restore)) },
                        supportingContent = { Text(pluralStringResource(R.plurals.bkp_undo_restore_summary, state.lastRestoreIds.size, state.lastRestoreIds.size)) },
                    )
                }
            }
            if (files.isNotEmpty()) {
                item { Section(stringResource(R.string.bkp_in_folder)) }
                items(files, key = { it.uri.toString() }) { f ->
                    ListItem(
                        modifier = Modifier.clickable { restoreUri = f.uri },
                        headlineContent = { Text(Format.fullDate(context, f.time)) },
                        supportingContent = { Text(android.text.format.Formatter.formatShortFileSize(context, f.size)) },
                        trailingContent = { Text(stringResource(R.string.dc_restore), color = MaterialTheme.colorScheme.primary) },
                    )
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
    recovery?.let { key ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.bkp_recovery_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.bkp_recovery_text))
                    Text(key, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton({ recovery = null }) { Text(stringResource(R.string.bkp_recovery_saved)) } },
            dismissButton = { TextButton({ Intents.copy(context, key) }) { Text(stringResource(R.string.bkp_copy)) } },
        )
    }
    restoreUri?.let { uri -> RestoreFlow(vm, uri) { restoreUri = null; refresh++ } }
}

@Composable
private fun PassphraseDialog(change: Boolean, onDismiss: () -> Unit, onSave: (String?, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val ok = new.length >= 10 && new == confirm && (!change || old.isNotEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (change) stringResource(R.string.bkp_change_pass_title) else stringResource(R.string.bkp_pass_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (change) PassField(stringResource(R.string.bkp_current_pass), old) { old = it }
                PassField(stringResource(R.string.bkp_new_pass), new) { new = it }
                PassField(stringResource(R.string.bkp_repeat), confirm) { confirm = it }
                Text(stringResource(R.string.bkp_pass_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ onSave(old.takeIf { change }, new) }, enabled = ok) { Text(stringResource(R.string.dc_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
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
