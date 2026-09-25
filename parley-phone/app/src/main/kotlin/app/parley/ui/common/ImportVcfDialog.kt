package app.parley.ui.common

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.data.AccountRef
import app.parley.ui.people.accountLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Import a .vcf opened or shared from another app: choose the account, import, show the result. */
@Composable
fun ImportVcfDialog(vm: AppViewModel, uri: Uri, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) { accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() } }
    val res = LocalResources.current
    // C2: a large file offers "Back up first?" before the import starts.
    val backupFirst = app.parley.ui.backup.rememberBackupFirst(vm)
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(uri) { count = vm.c.vcards.estimateCount(uri) }

    AlertDialog(
        onDismissRequest = { if (!running) onDone() },
        title = { Text(stringResource(if (result != null) R.string.import_finished else R.string.import_into)) },
        text = {
            Column {
                when {
                    result != null -> Text(result!!)
                    running -> {
                        Text(stringResource(R.string.import_importing))
                        LinearProgressIndicator(progress = { progress })
                    }
                    else -> accounts.forEach { a ->
                        ListItem(
                            headlineContent = { Text(vm.accountLabel(a)) },
                            modifier = Modifier.clickable {
                                backupFirst.ask(count, app.parley.common.ux.BackupNudge.LARGE_IMPORT) {
                                    running = true
                                    scope.launch {
                                        result = try {
                                            val r = vm.c.vcards.importVCard(uri, a, { done, total -> progress = if (total > 0) done.toFloat() / total else 0f }, skipDuplicates = true)
                                            res.getString(R.string.import_into_account, r.localizedSummary(res), a.displayLabel)
                                        } catch (e: Exception) {
                                            res.getString(R.string.import_failed, e.message.orEmpty())
                                        }
                                        running = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { if (!running) TextButton(onDone) { Text(stringResource(if (result != null) R.string.main_done else R.string.main_cancel)) } },
    )
}
