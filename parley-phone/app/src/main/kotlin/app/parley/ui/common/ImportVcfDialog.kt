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
import app.parley.AppViewModel
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

    AlertDialog(
        onDismissRequest = { if (!running) onDone() },
        title = { Text(if (result != null) "Import finished" else "Import contacts into") },
        text = {
            Column {
                when {
                    result != null -> Text(result!!)
                    running -> {
                        Text("Importing…")
                        LinearProgressIndicator(progress = { progress })
                    }
                    else -> accounts.forEach { a ->
                        ListItem(
                            headlineContent = { Text(vm.accountLabel(a)) },
                            modifier = Modifier.clickable {
                                running = true
                                scope.launch {
                                    result = try {
                                        val r = vm.c.vcards.importVCard(uri, a, { done, total -> progress = if (total > 0) done.toFloat() / total else 0f }, skipDuplicates = true)
                                        r.summary() + " into ${a.displayLabel}."
                                    } catch (e: Exception) {
                                        "Import failed: ${e.message}"
                                    }
                                    running = false
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { if (!running) TextButton(onDone) { Text(if (result != null) "Done" else "Cancel") } },
    )
}
