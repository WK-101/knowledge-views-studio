package app.parley.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.data.history.TrashBatch
import app.parley.ui.common.Format
import app.parley.ui.contact.Section
import kotlinx.coroutines.launch

/** Settings › Calls entry: "Keep full call history" with its switch; tapping opens the details page. */
@Composable
fun KeepFullHistoryRow(vm: AppViewModel, open: (String) -> Unit) {
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    var confirmOff by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open(HistoryRoutes.SETTINGS) },
        headlineContent = { Text("Keep full call history") },
        supportingContent = {
            Text(
                if (prefs.archiveEnabled) "On: Parley keeps its own encrypted copy, because Android may drop old calls"
                else "Off: only what Android keeps",
            )
        },
        trailingContent = { Switch(prefs.archiveEnabled, { v -> if (v) vm.setArchiveEnabled(true) else confirmOff = true }) },
    )
    if (confirmOff) ArchiveOffDialog(vm) { confirmOff = false }
}

private fun AppViewModel.setArchiveEnabled(on: Boolean) {
    c.scope.launch { c.history.setArchiveEnabled(on) }
}

@Composable
private fun ArchiveOffDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn off the call-history archive?") },
        text = { Text("Parley deletes its encrypted copy of your calls. Your phone's own call history is not changed, but calls Android already dropped are gone for good.") },
        confirmButton = { TextButton({ vm.setArchiveEnabled(false); onDismiss() }) { Text("Turn off and delete") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** H1 details: archive state, numbers kept forever, recently deleted calls (30-day undo), export and import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistorySettingsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val kept by vm.c.history.keptForever.collectAsStateWithLifecycle()
    val archive by vm.c.history.archive.collectAsStateWithLifecycle()
    var count by remember { mutableIntStateOf(0) }
    var trash by remember { mutableStateOf<List<TrashBatch>>(emptyList()) }
    var confirmOff by remember { mutableStateOf(false) }
    LaunchedEffect(archive) { count = vm.c.history.archiveCount() }
    LaunchedEffect(Unit) { trash = vm.c.history.trashBatches() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Call history") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                ListItem(
                    modifier = Modifier.clickable { if (prefs.archiveEnabled) confirmOff = true else vm.setArchiveEnabled(true) },
                    headlineContent = { Text("Keep full call history") },
                    supportingContent = { Text(if (prefs.archiveEnabled) "$count calls in Parley's archive" else "Off") },
                    trailingContent = { Switch(prefs.archiveEnabled, { v -> if (v) vm.setArchiveEnabled(true) else confirmOff = true }) },
                )
            }
            item {
                Text(
                    "Some phones keep only the most recent calls. With this on, Parley copies every call into its own database on this phone, " +
                        "encrypted with a key kept in Android's secure key store, and shows those calls in Recents. Nothing leaves the phone. " +
                        "Calls you delete in Parley are removed from the copy too; calls deleted in other apps stay in it.\n\n" +
                        (if (settings.callLogRetentionDays > 0) "Your setting keeps call history for ${settings.callLogRetentionDays} days; older calls are deleted from both, except numbers you keep forever."
                        else "Your setting keeps call history forever."),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (prefs.archiveEnabled) {
                item { Section("Kept forever") }
                if (kept.isEmpty()) item {
                    Text(
                        "Open a contact or a number's history and turn on “Keep this call history forever” to keep it whatever the retention setting.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
                kept.entries.sortedBy { it.value }.forEach { (key, number) ->
                    item(key = "k$key") {
                        ListItem(
                            headlineContent = { Text(vm.contactFor(number)?.displayName ?: Format.number(number, vm.countryIso)) },
                            supportingContent = { Text(Format.number(number, vm.countryIso)) },
                            trailingContent = { IconButton({ scope.launch { vm.c.history.removeKeepForeverKeys(listOf(key)) } }) { Icon(Icons.Rounded.Close, "Stop keeping forever") } },
                        )
                    }
                }
            }
            item { Section("Recently deleted calls") }
            if (trash.isEmpty()) item { Text("Nothing deleted in the last 30 days.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            trash.forEach { b ->
                item(key = "t${b.batchId}") {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.History, null) },
                        headlineContent = { Text("${b.count} ${if (b.count == 1) "call" else "calls"} deleted") },
                        supportingContent = { Text(Format.fullDate(context, b.deletedAt)) },
                        trailingContent = {
                            TextButton({
                                scope.launch {
                                    val n = vm.c.history.undoDelete(b.batchId)
                                    vm.toast(if (n > 0) "Restored $n calls" else "Couldn't restore. Make Parley your default phone app first.")
                                    trash = vm.c.history.trashBatches()
                                }
                            }) { Text("Restore") }
                        },
                    )
                }
            }
            item { Section("Export & import") }
            item {
                ListItem(
                    modifier = Modifier.clickable { scope.launch { vm.c.history.prefs.setCsvBom(!prefs.csvBom) } },
                    headlineContent = { Text("Excel-friendly CSV") },
                    supportingContent = { Text("Adds a byte-order mark so accents show correctly in Excel") },
                    trailingContent = { Switch(prefs.csvBom, { v -> scope.launch { vm.c.history.prefs.setCsvBom(v) } }) },
                )
                ListItem(
                    modifier = Modifier.clickable { open(HistoryRoutes.IMPORT) },
                    headlineContent = { Text("Import call history from CSV") },
                    supportingContent = { Text("From Parley, Logger or a spreadsheet") },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) },
                )
                Text(
                    "To export, open Recents › ⋮ › Export, or a number's history.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmOff) ArchiveOffDialog(vm) { confirmOff = false }
}
