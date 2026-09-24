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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

/** Settings › Calls entry: "Keep full call history" with its switch; tapping opens the details page. */
@Composable
fun KeepFullHistoryRow(vm: AppViewModel, open: (String) -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    var confirmOff by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open(HistoryRoutes.SETTINGS) },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = icon?.let { { Icon(it, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant) } },
        headlineContent = { Text(app.parley.common.SettingsCatalog["archive"].title) },
        supportingContent = {
            Text(
                if (prefs.archiveEnabled) stringResource(R.string.hist_archive_on_summary)
                else stringResource(R.string.hist_archive_off_summary),
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
        title = { Text(stringResource(R.string.hist_archive_off_title)) },
        text = { Text(stringResource(R.string.hist_archive_off_text)) },
        confirmButton = { TextButton({ vm.setArchiveEnabled(false); onDismiss() }) { Text(stringResource(R.string.hist_archive_off_confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
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
        TopAppBar(title = { Text(stringResource(R.string.hist_settings_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                ListItem(
                    modifier = Modifier.clickable { if (prefs.archiveEnabled) confirmOff = true else vm.setArchiveEnabled(true) },
                    headlineContent = { Text(stringResource(R.string.hist_keep_full)) },
                    supportingContent = { Text(if (prefs.archiveEnabled) pluralStringResource(R.plurals.hist_archive_count, count, count) else stringResource(R.string.dc_off)) },
                    trailingContent = { Switch(prefs.archiveEnabled, { v -> if (v) vm.setArchiveEnabled(true) else confirmOff = true }) },
                )
            }
            item {
                Text(
                    stringResource(R.string.hist_archive_explain) + "\n\n" +
                        (if (settings.callLogRetentionDays > 0) pluralStringResource(R.plurals.hist_retention_days, settings.callLogRetentionDays, settings.callLogRetentionDays)
                        else stringResource(R.string.hist_retention_forever)),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (prefs.archiveEnabled) {
                item { Section(stringResource(R.string.hist_kept_forever)) }
                if (kept.isEmpty()) item {
                    Text(
                        stringResource(R.string.hist_kept_forever_empty),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
                kept.entries.sortedBy { it.value }.forEach { (key, number) ->
                    item(key = "k$key") {
                        ListItem(
                            headlineContent = { Text(vm.contactFor(number)?.displayName ?: Format.number(number, vm.countryIso)) },
                            supportingContent = { Text(Format.number(number, vm.countryIso)) },
                            trailingContent = { IconButton({ scope.launch { vm.c.history.removeKeepForeverKeys(listOf(key)) } }) { Icon(Icons.Rounded.Close, stringResource(R.string.hist_stop_keeping)) } },
                        )
                    }
                }
            }
            item { Section(stringResource(R.string.hist_recently_deleted)) }
            if (trash.isEmpty()) item { Text(stringResource(R.string.hist_recently_deleted_empty), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            trash.forEach { b ->
                item(key = "t${b.batchId}") {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.History, null) },
                        headlineContent = { Text(pluralStringResource(R.plurals.hist_calls_deleted, b.count, b.count)) },
                        supportingContent = { Text(Format.fullDate(context, b.deletedAt)) },
                        trailingContent = {
                            TextButton({
                                scope.launch {
                                    val n = vm.c.history.undoDelete(b.batchId)
                                    vm.toast(if (n > 0) context.resources.getQuantityString(R.plurals.hist_restored_calls, n, n) else context.getString(R.string.hist_restore_failed_default))
                                    trash = vm.c.history.trashBatches()
                                }
                            }) { Text(stringResource(R.string.dc_restore)) }
                        },
                    )
                }
            }
            item { Section(stringResource(R.string.hist_export_import)) }
            item {
                ListItem(
                    modifier = Modifier.clickable { scope.launch { vm.c.history.prefs.setCsvBom(!prefs.csvBom) } },
                    headlineContent = { Text(stringResource(R.string.hist_csv_bom)) },
                    supportingContent = { Text(stringResource(R.string.hist_csv_bom_summary)) },
                    trailingContent = { Switch(prefs.csvBom, { v -> scope.launch { vm.c.history.prefs.setCsvBom(v) } }) },
                )
                ListItem(
                    modifier = Modifier.clickable { open(HistoryRoutes.IMPORT) },
                    headlineContent = { Text(stringResource(R.string.hist_import_csv)) },
                    supportingContent = { Text(stringResource(R.string.hist_import_csv_summary)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) },
                )
                Text(
                    stringResource(R.string.hist_export_hint),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmOff) ArchiveOffDialog(vm) { confirmOff = false }
}
