package app.parley.ui.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.ui.common.Format
import app.parley.ui.extras.MarkdownExportSection
import app.parley.work.FolderSyncNotice
import app.parley.work.FolderSyncWorker
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.ConfirmDialog
import app.parley.ui.StrengthMeter
import app.parley.ui.backup.PassField
import app.parley.common.security.PassphraseStrength
import app.parley.data.sync.FolderSync
import app.parley.data.sync.SyncMode
import app.parley.data.sync.SyncStatus
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.Groups
import app.parley.ui.Destination
import app.parley.ui.LinkRow
import app.parley.ui.sync.shared.SharedLabelRoutes

/** A list state that, when [atEnd], scrolls to the last item once the list has items. */
@Composable
private fun rememberListStartingAtEnd(atEnd: Boolean): LazyListState {
    val state = rememberLazyListState()
    if (atEnd) {
        LaunchedEffect(Unit) {
            val count = snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
            state.scrollToItem(count - 1)
        }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSyncScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit = {}, focusMarkdown: Boolean = false) {
    // "Export notes as Markdown" lands on its section (the last one), not on the top of Sync.
    val listState = rememberListStartingAtEnd(focusMarkdown)
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val sync = vm.c.folderSync
    val st by sync.status.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
    // Choosing how the folder's files are stored: a passphrase for encryption, or consent for plain files.
    var askPass by remember { mutableStateOf<Boolean?>(null) } // true: the folder already has a key
    var askPlain by remember { mutableStateOf(false) }
    fun chooseEncrypted() {
        scope.launch { askPass = sync.folderIsEncrypted() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            sync.setFolder(uri, uri.lastPathSegment?.substringAfterLast(':'))
            FolderSyncWorker.schedule(context, st.auto)
        }
    }
    fun run(allowMassDelete: Boolean = false) {
        running = true
        scope.launch {
            val r = runCatching { sync.syncNow(allowMassDelete) }
            running = false
            vm.toast(r.getOrNull()?.summary(res) ?: res.getString(R.string.sync_failed, r.exceptionOrNull()?.message.toString()))
            FolderSyncNotice.update(context, sync.status.value, post = false)
        }
    }
    ParleyScaffold(topBar = {
        ParleyTopBar(stringResource(R.string.sync_title), onBack = back)
    }) { p ->
        LazyColumn(Modifier.padding(p), state = listState) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.sync_card_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.sync_card_text),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { picker.launch(null) },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    headlineContent = { Text(stringResource(R.string.sync_folder)) },
                    supportingContent = { Text(st.folderName ?: stringResource(R.string.bkp_folder_none)) },
                )
                ListItem(
                    modifier = Modifier.toggleable(
                        st.auto,
                        role = Role.Switch,
                        onValueChange = { sync.setAuto(it); FolderSyncWorker.schedule(context, it && st.folderUri != null) },
                    ),
                    headlineContent = { Text(stringResource(R.string.sync_auto)) },
                    supportingContent = { Text(stringResource(R.string.sync_auto_summary)) },
                    trailingContent = { Switch(st.auto, onCheckedChange = null) },
                )
                ListItem(
                    headlineContent = { Text(if (st.lastSyncAt > 0) stringResource(R.string.sync_last, Format.shortWhen(context, st.lastSyncAt)) else stringResource(R.string.sync_never)) },
                    supportingContent = st.resultText(LocalResources.current)?.let { r -> { Text(r) } },
                    leadingContent = { Icon(Icons.Rounded.Sync, null) },
                )
                if (st.folderUri != null) SyncModeRows(st.mode, st.plainLeft, onEncrypted = ::chooseEncrypted, onPlain = { askPlain = true })
                SyncActions(st, running, onRun = ::run, onStop = { sync.setFolder(null, null); FolderSyncWorker.schedule(context, false) })
            }
            // Labels shared with other people's phones, each through a folder of its own.
            item {
                LinkRow(stringResource(R.string.set_shared_labels_title), stringResource(R.string.set_shared_labels_summary), Icons.Rounded.Groups) {
                    open(SharedLabelRoutes.All)
                }
            }
            // One-way Markdown notes, to a folder of their own.
            item { MarkdownExportSection(vm) }
        }
    }

    askPass?.let { existing ->
        SyncPassphraseDialog(
            sync, existing,
            onReady = { plainLeft ->
                askPass = null
                if (plainLeft) vm.toast(res.getString(R.string.sync_plain_left_toast))
                run()
            },
            onDismiss = { askPass = null },
        )
    }
    if (askPlain) PlainFilesConsent(onUse = { askPlain = false; sync.usePlain(); run() }, onDismiss = { askPlain = false })
}

/** Apply paused deletions, Sync now (once a mode is chosen), progress, and Stop syncing. */
@Composable
private fun SyncActions(st: SyncStatus, running: Boolean, onRun: (allowMassDelete: Boolean) -> Unit, onStop: () -> Unit) {
    if (st.pendingDeletions > 0 && !running) {
        OutlinedButton({ onRun(true) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(pluralStringResource(R.plurals.sync_apply_deletions, st.pendingDeletions, st.pendingDeletions))
        }
    }
    val canSync = st.folderUri != null && st.mode != SyncMode.UNSET && !running
    Button({ onRun(false) }, enabled = canSync, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.sync_now)) }
    if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
    if (st.folderUri != null) TextButton(onStop, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.sync_stop)) }
}

/** The shared sync passphrase: the folder's existing one (checked), or a new one that must be Strong. */
@Composable
private fun SyncPassphraseDialog(sync: FolderSync, existing: Boolean, onReady: (plainLeft: Boolean) -> Unit, onDismiss: () -> Unit) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val estimate = remember(pass) { PassphraseStrength.estimate(pass) }
    ConfirmDialog(
        title = stringResource(if (existing) R.string.sync_pass_existing_title else R.string.sync_pass_new_title),
        text = stringResource(if (existing) R.string.sync_pass_existing_text else R.string.sync_pass_new_text),
        confirmLabel = stringResource(R.string.dc_ok),
        onConfirm = {
            scope.launch {
                when (sync.useEncryption(pass.toCharArray())) {
                    FolderSync.EncryptionSetup.READY -> onReady(false)
                    FolderSync.EncryptionSetup.PLAIN_FILES_LEFT -> onReady(true)
                    FolderSync.EncryptionSetup.WRONG_PASSPHRASE -> error = res.getString(R.string.sync_pass_wrong)
                    FolderSync.EncryptionSetup.FAILED -> error = res.getString(R.string.sync_setup_failed)
                }
            }
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        // A new folder key must stand up to offline guessing, like a backup passphrase.
        confirmEnabled = if (existing) pass.isNotEmpty() else PassphraseStrength.acceptableForBackup(pass),
        content = {
            Column {
                PassField(stringResource(R.string.bkp_pass_title), pass) { pass = it; error = null }
                if (!existing && pass.isNotEmpty()) StrengthMeter(estimate.score, strengthName(estimate.score), modifier = Modifier.padding(top = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
    )
}

/** Plain vCard files only after the user ticked that anyone with the folder can read them. */
@Composable
private fun PlainFilesConsent(onUse: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    ConfirmDialog(
        title = stringResource(R.string.sync_plain_title),
        text = stringResource(R.string.sync_plain_text),
        confirmLabel = stringResource(R.string.sync_plain_use),
        onConfirm = onUse,
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        confirmEnabled = understood,
        content = {
            Row(Modifier.fillMaxWidth().toggleable(understood, role = Role.Checkbox) { understood = it }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(understood, onCheckedChange = null)
                Text(stringResource(R.string.sync_plain_check), modifier = Modifier.padding(start = 8.dp))
            }
        },
    )
}

/**
 * The folder's storage: a choice while unset (encrypted first), otherwise what it is, with a way to encrypt plain files,
 * and the plain files switching to encryption couldn't remove ([plainLeft]).
 */
@Composable
private fun SyncModeRows(mode: SyncMode, plainLeft: Int, onEncrypted: () -> Unit, onPlain: () -> Unit) {
    when (mode) {
        SyncMode.UNSET -> {
            Text(
                stringResource(R.string.sync_mode_title), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onEncrypted),
                leadingContent = { Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(R.string.sync_mode_encrypted)) },
                supportingContent = { Text(stringResource(R.string.sync_mode_encrypted_summary)) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onPlain),
                leadingContent = { Icon(Icons.Rounded.Description, null) },
                headlineContent = { Text(stringResource(R.string.sync_mode_plain)) },
                supportingContent = { Text(stringResource(R.string.sync_mode_plain_summary)) },
            )
        }
        SyncMode.ENCRYPTED -> ListItem(
            leadingContent = { Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.sync_mode_is_encrypted)) },
            supportingContent = if (plainLeft > 0) {
                { Text(pluralStringResource(R.plurals.sync_plain_left, plainLeft, plainLeft), color = MaterialTheme.colorScheme.error) }
            } else {
                null
            },
        )
        SyncMode.PLAIN -> ListItem(
            modifier = Modifier.clickable(onClick = onEncrypted),
            leadingContent = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
            headlineContent = { Text(stringResource(R.string.sync_mode_is_plain)) },
            supportingContent = { Text(stringResource(R.string.sync_switch_encrypted), color = MaterialTheme.colorScheme.primary) },
        )
    }
}

@Composable
internal fun strengthName(score: Int) = stringResource(
    when (score) {
        0 -> R.string.bkp_strength_0
        1 -> R.string.bkp_strength_1
        2 -> R.string.bkp_strength_2
        3 -> R.string.bkp_strength_3
        else -> R.string.bkp_strength_4
    },
)
