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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
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
        busy = "Backing up…"
        scope.launch {
            val out = repo.backupNow(scheduled = false)
            busy = null
            vm.toast(out.message + if (out.ok && !out.vaultIncluded && vm.c.vault.contacts.value.isNotEmpty()) " (private contacts skipped: unlock them first)" else "")
            refresh++
        }
    }

    fun transfer() {
        busy = "Preparing encrypted file…"
        scope.launch {
            val dir = File(context.cacheDir, "transfer").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "parley-transfer.parley")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val out = repo.backupNow(scheduled = false, target = uri)
            busy = null
            if (out.ok) {
                val share = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(share, "Send to your new phone")) }
            } else {
                vm.toast(out.message)
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Backup & restore") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                val ready = state.hasKeys && state.folderUri != null
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { Icon(if (state.lastBackupAt > 0) Icons.Rounded.CheckCircle else Icons.Rounded.Backup, null); Text("  " + if (state.lastBackupAt > 0) "Last backup ${Format.shortWhen(context, state.lastBackupAt)}" else "No backup yet", style = MaterialTheme.typography.titleMedium) }
                        if (state.lastVerifiedAt > 0) Text("Verified ${Format.fullDate(context, state.lastVerifiedAt)} · encrypted" + (state.keyId?.let { " · key $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        state.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (state.rotationPaused) Row { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error); Text("  Many contacts disappeared since the last backup, so old backups are being kept.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (state.rotationPaused) TextButton({ repo.resumeRotation() }) { Text("That's expected, resume rotation") }
                        Text(
                            "Contacts with full photos, call history, blocking rules, speed dial, settings and private contacts, in one encrypted file in a folder you choose (sync it with Syncthing, Nextcloud or a USB drive).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(::runBackup, enabled = ready && busy == null, modifier = Modifier.padding(top = 4.dp)) { Text("Back up now") }
                        busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                }
            }
            item { Section("Set up") }
            item {
                ListItem(
                    modifier = Modifier.clickable { if (state.hasKeys) changePass = true else setPass = true },
                    leadingContent = { Icon(Icons.Rounded.Key, null) },
                    headlineContent = { Text(if (state.hasKeys) "Change backup passphrase" else "Set a backup passphrase") },
                    supportingContent = { Text(if (state.hasKeys) "Scheduled backups never need it; only restoring does" else "Required: backups are always encrypted") },
                )
                ListItem(
                    modifier = Modifier.clickable { folderPicker.launch(null) },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    headlineContent = { Text("Backup folder") },
                    supportingContent = { Text(state.folderName ?: "Not chosen") },
                )
                ListItem(
                    headlineContent = { Text("Automatic backups") },
                    supportingContent = {
                        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                            BackupSchedule.entries.forEachIndexed { i, s ->
                                SegmentedButton(state.schedule == s, {
                                    repo.prefs.update { it.putString("schedule", s.name) }
                                    BackupWorker.schedule(context, s)
                                }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            }
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("Keep") },
                    supportingContent = {
                        val opts = listOf(0 to "Smart", 5 to "5", 10 to "10", 30 to "30")
                        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                            opts.forEachIndexed { i, (n, label) ->
                                SegmentedButton(state.keepLast == n, { repo.prefs.update { it.putInt("keepLast", n) } }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label) }
                            }
                        }
                    },
                    trailingContent = null,
                )
                if (state.keepLast == 0) Text("Smart keeps 7 daily, 5 weekly, 12 monthly and 3 yearly backups.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item { Section("Restore") }
            item {
                ListItem(
                    modifier = Modifier.clickable { filePicker.launch(arrayOf("*/*")) },
                    leadingContent = { Icon(Icons.Rounded.Restore, null) },
                    headlineContent = { Text("Restore from a file…") },
                    supportingContent = { Text("A .parley backup from this or another phone") },
                )
                ListItem(
                    modifier = Modifier.clickable(enabled = state.hasKeys && busy == null) { transfer() },
                    leadingContent = { Icon(Icons.Rounded.PhoneAndroid, null) },
                    headlineContent = { Text("Move to a new phone") },
                    supportingContent = { Text("Sends one encrypted file via Quick Share, Bluetooth or any app. On the new phone: install Parley → Backup → Restore from a file, and enter your passphrase.") },
                )
                if (state.lastRestoreIds.isNotEmpty()) {
                    ListItem(
                        modifier = Modifier.clickable { scope.launch { val n = repo.undoLastRestore(); vm.toast("Removed $n contacts added by the last restore") } },
                        headlineContent = { Text("Undo last restore") },
                        supportingContent = { Text("Removes the ${state.lastRestoreIds.size} contacts it added (they stay in Recently deleted)") },
                    )
                }
            }
            if (files.isNotEmpty()) {
                item { Section("Backups in the folder") }
                items(files, key = { it.uri.toString() }) { f ->
                    ListItem(
                        modifier = Modifier.clickable { restoreUri = f.uri },
                        headlineContent = { Text(Format.fullDate(context, f.time)) },
                        supportingContent = { Text("${f.size / 1024} KB") },
                        trailingContent = { Text("Restore", color = MaterialTheme.colorScheme.primary) },
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
                    vm.toast(if (ok) "Passphrase changed" else "The current passphrase is wrong")
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
            title = { Text("Your recovery key") },
            text = {
                Column {
                    Text("If you forget your passphrase, this key is the only other way to open your backups. Write it down and keep it somewhere safe. Parley can't show it again.")
                    Text(key, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton({ recovery = null }) { Text("I've saved it") } },
            dismissButton = { TextButton({ Intents.copy(context, key) }) { Text("Copy") } },
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
        title = { Text(if (change) "Change passphrase" else "Backup passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (change) PassField("Current passphrase", old) { old = it }
                PassField("New passphrase (10+ characters)", new) { new = it }
                PassField("Repeat", confirm) { confirm = it }
                Text("A few unrelated words make a strong, memorable passphrase.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ onSave(old.takeIf { change }, new) }, enabled = ok) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
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
