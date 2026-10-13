package app.parley.ui.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.data.backup.BackupFileInfo
import app.parley.ui.Section
import app.parley.ui.startOrSay
import app.parley.work.BackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.common.backup.BackupFix
import android.content.Context
import android.content.res.Resources
import app.parley.data.backup.BackupRepository
import app.parley.data.backup.BackupOutcome
import app.parley.ui.scaffoldBody
import app.parley.ui.scaffoldBottom
import androidx.compose.material3.TopAppBarDefaults
import app.parley.ui.Spacing

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
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    LaunchedEffect(state.folderUri, refresh) { files = withContext(Dispatchers.IO) { repo.listBackups() } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            repo.setFolder(uri, uri.lastPathSegment?.substringAfterLast(':'))
            refresh++
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) dialog = BackupDialog.Restore(uri) }

    fun runBackup() {
        busy = res.getString(R.string.label_backing_up)
        scope.launch {
            val out = repo.backupNow(scheduled = false)
            busy = null
            // Not after a duress unlock, when private contacts aren't there to skip.
            val skipped = out.ok && !out.vaultIncluded && vm.settings.value.duress == null && vm.c.vault.contacts.value.isNotEmpty()
            vm.toast(if (skipped) res.getString(R.string.bkp_vault_skipped, out.message) else out.message)
            refresh++
        }
    }

    fun transfer() {
        busy = res.getString(R.string.bkp_preparing)
        scope.launch {
            val (out, uri) = writeTransfer(context, repo)
            busy = null
            if (out.ok) shareTransfer(context, res, uri) else vm.toast(out.message)
        }
    }

    val barTint = TopAppBarDefaults.pinnedScrollBehavior()
    ParleyScaffold(topBar = {
        ParleyTopBar(stringResource(R.string.label_backup_restore), onBack = back, scrollBehavior = barTint)
    }) { p ->
        LazyColumn(Modifier.scaffoldBody(p, barTint), contentPadding = scaffoldBottom(p)) {
            // Overdue reminder (also in Settings); "Not now" snoozes it.
            item { BackupReminderBanner(vm) }
            // Setup checker: one line saying what most needs doing (or that all is well), with its fix.
            item {
                BackupSetupStatus(state, ux.backupReminderDays) { fix ->
                    when (fix) {
                        BackupFix.SET_PASSPHRASE -> dialog = BackupDialog.Passphrase(change = false)
                        BackupFix.CHOOSE_FOLDER -> folderPicker.launch(null)
                        BackupFix.BACK_UP_NOW -> if (state.hasKeys && state.folderUri != null && busy == null) runBackup()
                        BackupFix.NONE -> Unit
                    }
                }
            }
            item { BackupStatusCard(state, busy, onBackUp = ::runBackup, onResume = repo::resumeRotation) }
            item { Section(stringResource(R.string.action_set_up)) }
            item {
                BackupSetupSection(
                    vm, state,
                    onPassphrase = { dialog = BackupDialog.Passphrase(change = state.hasKeys) },
                    onConfirmPhone = { dialog = BackupDialog.ConfirmPhone },
                    onFolder = { folderPicker.launch(null) },
                    onSchedule = { s ->
                        repo.prefs.update { it.putString("schedule", s.name) }
                        BackupWorker.schedule(context, s)
                    },
                    onKeep = { n -> repo.prefs.update { it.putInt("keepLast", n) } },
                )
            }
            item { Section(stringResource(R.string.dc_restore)) }
            item {
                BackupRestoreSection(
                    state, busy,
                    onFile = { filePicker.launch(arrayOf("*/*")) },
                    onTransfer = ::transfer,
                    onUndo = { scope.launch { val n = repo.undoLastRestore(); vm.toast(res.getQuantityString(R.plurals.bkp_undo_done, n, n)) } },
                )
            }
            backupFiles(files) { dialog = BackupDialog.Restore(it) }
            // What a backup holds, and what stays here on purpose (and why), so a move to a new phone holds no surprise.
            item { Section(stringResource(R.string.bkp_kept_here_title)) }
            item {
                Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(stringResource(R.string.bkp_holds_text), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.bkp_kept_here_text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    BackupDialogHost(vm, repo, dialog, scope, show = { dialog = it }) { restored ->
        dialog = null
        if (restored) refresh++
    }
}

/** "Move to a new phone": a backup written to Parley's own cache, ready for the share sheet ([shareTransfer]). */
private suspend fun writeTransfer(context: Context, repo: BackupRepository): Pair<BackupOutcome, Uri> {
    val dir = File(context.cacheDir, "transfer").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "parley-transfer.parley")
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    return repo.backupNow(scheduled = false, target = uri) to uri
}

private fun shareTransfer(context: Context, res: Resources, uri: Uri) {
    val share = Intent(Intent.ACTION_SEND).setType(
        "application/octet-stream",
    ).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startOrSay(Intent.createChooser(share, res.getString(R.string.bkp_send_chooser)))
}
