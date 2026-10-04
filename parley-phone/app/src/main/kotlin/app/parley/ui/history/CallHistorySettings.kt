package app.parley.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroupScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.ui.common.Format
import app.parley.ui.SwitchRow
import app.parley.ui.settings.settingSummary
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ConfirmDialog

/**
 * Settings › Recents & history: "Keep full call history" with its switch and how many calls the copy holds. The
 * former "Call history" sub-screen is folded into that page: this row, [keptForeverRow] and [csvBomRow]; deleted
 * calls are restored from History & undo.
 */
@Composable
fun KeepFullHistoryRow(vm: AppViewModel, icon: ImageVector? = null) {
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    val archive by vm.c.history.archive.collectAsStateWithLifecycle()
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(archive) { count = vm.c.history.archiveCount() }
    var confirmOff by remember { mutableStateOf(false) }
    SwitchRow(
        settingTitle("archive"),
        if (prefs.archiveEnabled) stringResource(R.string.hist_archive_on_summary) + "\n" + pluralStringResource(R.plurals.hist_archive_count, count, count)
        else stringResource(R.string.hist_archive_off_summary),
        prefs.archiveEnabled,
        icon = icon,
        onChange = { v -> if (v) vm.setArchiveEnabled(true) else confirmOff = true },
    )
    if (confirmOff) ArchiveOffDialog(vm) { confirmOff = false }
}

private fun AppViewModel.setArchiveEnabled(on: Boolean) {
    c.scope.launch { c.history.setArchiveEnabled(on) }
}

@Composable
private fun ArchiveOffDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.hist_archive_off_title),
        text = stringResource(R.string.hist_archive_off_text),
        confirmLabel = stringResource(R.string.hist_archive_off_confirm),
        onConfirm = { vm.setArchiveEnabled(false); onDismiss() },
        onDismiss = onDismiss,
        destructive = true,
        dismissLabel = stringResource(R.string.dc_cancel),
    )
}

/** Numbers whose calls are kept forever, whatever the retention (only while the full history is kept). */
fun SegmentedGroupScope.keptForeverRow(vm: AppViewModel) = item("kept_forever") { KeptForever(vm) }

@Composable
private fun KeptForever(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val kept by vm.c.history.keptForever.collectAsStateWithLifecycle()
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    Column {
        ParleyListItem(
            colors = rowColors,
            leadingContent = { Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text(settingTitle("kept_forever")) },
            supportingContent = { Text(if (kept.isEmpty()) stringResource(R.string.hist_kept_forever_empty) else settingSummary("kept_forever")) },
        )
        kept.entries.sortedBy { it.value }.forEach { (key, number) ->
            ParleyListItem(
                colors = rowColors,
                headlineContent = { Text(vm.contactFor(number)?.displayName ?: Format.number(number, vm.countryIso)) },
                supportingContent = { Text(Format.number(number, vm.countryIso)) },
                trailingContent = {
                    IconButton({ scope.launch { vm.c.history.removeKeepForeverKeys(listOf(key)) } }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.hist_stop_keeping))
                    }
                },
            )
        }
    }
}

/** "Excel-friendly CSV" for call-history exports. */
fun SegmentedGroupScope.csvBomRow(vm: AppViewModel) = item("csv_bom") {
    val scope = rememberCoroutineScope()
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    SwitchRow(
        settingTitle("csv_bom"), settingSummary("csv_bom"), prefs.csvBom, Icons.Rounded.TableChart,
    ) { v -> scope.launch { vm.c.history.prefs.setCsvBom(v) } }
}

/** The archive explanation and where exports start, under the page's call-history groups. */
@Composable
fun CallHistoryNotes(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Text(
        stringResource(R.string.hist_archive_explain) + "\n\n" +
            (if (settings.callLogRetentionDays > 0) pluralStringResource(
                R.plurals.hist_retention_days, settings.callLogRetentionDays, settings.callLogRetentionDays,
            )
            else stringResource(R.string.hist_retention_forever)) + "\n\n" + stringResource(R.string.hist_export_hint),
        Modifier.padding(horizontal = 32.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
