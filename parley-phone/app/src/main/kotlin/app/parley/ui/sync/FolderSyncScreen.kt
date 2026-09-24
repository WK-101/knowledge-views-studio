package app.parley.ui.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.ui.common.Format
import app.parley.work.FolderSyncWorker
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSyncScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val sync = vm.c.folderSync
    val st by sync.status.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
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
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.sync_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
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
                    modifier = Modifier.clickable { sync.setAuto(!st.auto); FolderSyncWorker.schedule(context, !st.auto && st.folderUri != null) },
                    headlineContent = { Text(stringResource(R.string.sync_auto)) },
                    supportingContent = { Text(stringResource(R.string.sync_auto_summary)) },
                    trailingContent = { Switch(st.auto, { sync.setAuto(it); FolderSyncWorker.schedule(context, it && st.folderUri != null) }) },
                )
                ListItem(
                    headlineContent = { Text(if (st.lastSyncAt > 0) stringResource(R.string.sync_last, Format.shortWhen(context, st.lastSyncAt)) else stringResource(R.string.sync_never)) },
                    supportingContent = st.lastResult?.let { r -> { Text(r) } },
                    leadingContent = { Icon(Icons.Rounded.Sync, null) },
                )
                if (st.pendingDeletions > 0 && !running) {
                    OutlinedButton({ run(allowMassDelete = true) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Text(pluralStringResource(R.plurals.sync_apply_deletions, st.pendingDeletions, st.pendingDeletions)) }
                }
                Button({ run() }, enabled = st.folderUri != null && !running, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.sync_now)) }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                if (st.folderUri != null) TextButton({ sync.setFolder(null, null); FolderSyncWorker.schedule(context, false) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.sync_stop)) }
            }
        }
    }
}
