package app.parley.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.CallEntry
import app.parley.common.PhoneNumbers
import app.parley.common.calls.ClearHistory
import app.parley.common.calls.ClearScope
import app.parley.common.calls.RecentsLayout
import app.parley.common.history.ExportFormat
import app.parley.ui.Routes
import app.parley.ui.settings.LinkRow
import app.parley.ui.settings.settingSummary
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- P8: call-list layout

/** P8: the names of the three layouts, in [RecentsLayout] order. */
@Composable
fun recentsLayoutLabels(): List<String> = listOf(
    stringResource(R.string.recents_layout_grouped),
    stringResource(R.string.recents_layout_chronological),
    stringResource(R.string.recents_layout_by_day),
)

private val layoutRequested = MutableStateFlow(false)
private val clearRequested = MutableStateFlow(false)

/** P8: Recents ⋮ › "Call list layout" (the quick toggle; the same setting is in Settings › Recents & history). */
@Composable
fun RecentsLayoutMenuItem(vm: AppViewModel, closeMenu: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    DropdownMenuItem(
        { Text(stringResource(R.string.recents_layout_menu, recentsLayoutLabels()[s.recentsLayout.ordinal])) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ViewList, null) },
        onClick = {
            closeMenu()
            layoutRequested.value = true
        },
    )
}

/** P5: Recents ⋮ › "Clear call history…". */
@Composable
fun ClearHistoryMenuItem(closeMenu: () -> Unit) {
    DropdownMenuItem({ Text(stringResource(R.string.clear_history_menu)) }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, onClick = {
        closeMenu()
        clearRequested.value = true
    })
}

/** Shows the layout picker and "Clear call history" when asked from the Recents ⋮ menu. */
@Composable
fun RecentsV32Host(vm: AppViewModel, open: (String) -> Unit) {
    val layout by layoutRequested.collectAsStateWithLifecycle()
    val clear by clearRequested.collectAsStateWithLifecycle()
    if (layout) RecentsLayoutDialog(vm) { layoutRequested.value = false }
    if (clear) {
        val groups by vm.recentGroups.collectAsStateWithLifecycle()
        val shown = remember(groups) { groups.orEmpty().flatMap { it.calls } }
        ClearHistoryDialog(vm, shown, open) { clearRequested.value = false }
    }
}

@Composable
private fun RecentsLayoutDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val labels = recentsLayoutLabels()
    val hints = listOf(
        stringResource(R.string.recents_layout_grouped_hint),
        stringResource(R.string.recents_layout_chronological_hint),
        stringResource(R.string.recents_layout_by_day_hint),
    )
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(settingTitle("recents_layout")) },
        text = {
            Column(Modifier.selectableGroup()) {
                RecentsLayout.entries.forEachIndexed { i, l ->
                    ChoiceItem(labels[i], hints[i], s.recentsLayout == l) {
                        scope.launch { vm.c.settings.update { it.copy(recentsLayout = l) } }
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.clear_history_close)) } },
    )
}

@Composable
private fun ChoiceItem(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = { RadioButton(selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick),
    )
}

// ---------------------------------------------------------------- P5: clear call history

/** P5: Settings › Recents & history › Clear call history. */
@Composable
fun ClearHistoryRow(vm: AppViewModel, open: (String) -> Unit, icon: ImageVector? = null) {
    var show by remember { mutableStateOf(false) }
    LinkRow(settingTitle("clear_history"), settingSummary("clear_history"), icon) { show = true }
    if (show) ClearHistoryDialog(vm, shown = null, open = open) { show = false }
}

private enum class ClearStep { SCOPE, EXPORT, CONFIRM }

/**
 * P5: which calls (all, unknown numbers, missed, or what Recents shows now), then "Export first?" (a CSV file, or an
 * encrypted backup), then a confirmation. Deleted calls stay in Recently deleted for 30 days, with Undo.
 * Calls with private contacts live in the vault and are never touched here.
 */
@Composable
fun ClearHistoryDialog(vm: AppViewModel, shown: List<CallEntry>?, open: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val all by vm.c.history.calls.collectAsStateWithLifecycle()
    val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val iso = vm.countryIso
    val vaultKeys = remember(vault) { vault.flatMap { v -> v.numbers.map { PhoneNumbers.lineKey(it, iso) } }.toHashSet() }
    val isKnown = { n: String -> vm.contactFor(n) != null || PhoneNumbers.lineKey(n, iso) in vaultKeys }
    val shownIds = remember(shown) { shown?.map { it.id }?.toSet().orEmpty() }
    val scopes = buildList {
        // "What Recents shows now" only when filters or a search narrow it down.
        if (shown != null && shown.size < all.orEmpty().size) add(ClearScope.SHOWN)
        add(ClearScope.UNKNOWN_NUMBERS)
        add(ClearScope.MISSED)
        add(ClearScope.ALL)
    }
    val counts = remember(all, shownIds, vaultKeys) { ClearScope.entries.associateWith { ClearHistory.select(all.orEmpty(), it, isKnown, shownIds).size } }
    var picked by remember { mutableStateOf(scopes.first()) }
    var step by remember { mutableStateOf(ClearStep.SCOPE) }
    var busy by remember { mutableStateOf(false) }
    fun selected() = ClearHistory.select(all.orEmpty(), picked, isKnown, shownIds)
    val count = counts[picked] ?: 0

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when (step) {
                    ClearStep.SCOPE -> stringResource(R.string.clear_history_title)
                    ClearStep.EXPORT -> stringResource(R.string.clear_history_export_title)
                    ClearStep.CONFIRM -> pluralStringResource(R.plurals.clear_history_confirm_title, count, count)
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (step) {
                    ClearStep.SCOPE -> Column(Modifier.selectableGroup()) {
                        if (all == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        scopes.forEach { sc ->
                            val n = counts[sc] ?: 0
                            ChoiceItem(scopeLabel(sc), pluralStringResource(R.plurals.clear_history_calls, n, n), picked == sc) { picked = sc }
                        }
                    }
                    ClearStep.EXPORT -> {
                        Text(stringResource(R.string.clear_history_export_body), style = MaterialTheme.typography.bodyMedium)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.hist_export_csv)) },
                            supportingContent = { Text(stringResource(R.string.clear_history_export_csv_sub)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !busy) {
                                busy = true
                                scope.launch {
                                    try {
                                        val list = selected()
                                        val rows = ExportFiles.rows(context, list) { e -> ExportFiles.nameFor(e) { n -> vm.contactFor(n)?.displayName } }
                                        ExportFiles.share(context, ExportFiles.write(context, rows, null, ExportFormat.CSV), ExportFormat.CSV)
                                        step = ClearStep.CONFIRM
                                    } catch (e: Exception) {
                                        vm.toast(res.getString(R.string.hist_export_failed, e.message ?: e.javaClass.simpleName))
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.clear_history_export_backup)) },
                            supportingContent = { Text(stringResource(R.string.clear_history_export_backup_sub)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !busy) {
                                onDismiss()
                                open(Routes.BACKUP)
                            },
                        )
                    }
                    ClearStep.CONFIRM -> Text(stringResource(R.string.clear_history_confirm_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when (step) {
                ClearStep.SCOPE -> TextButton({ step = ClearStep.EXPORT }, enabled = count > 0) { Text(stringResource(R.string.clear_history_next)) }
                ClearStep.EXPORT -> TextButton({ step = ClearStep.CONFIRM }, enabled = !busy) { Text(stringResource(R.string.clear_history_skip_export)) }
                ClearStep.CONFIRM -> TextButton({
                    vm.deleteCallsWithUndo(selected())
                    onDismiss()
                }, enabled = count > 0) { Text(stringResource(R.string.clear_history_delete), color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton({ onDismiss() }, enabled = !busy) { Text(stringResource(R.string.clear_history_cancel)) } },
    )
}

@Composable
private fun scopeLabel(s: ClearScope): String = stringResource(
    when (s) {
        ClearScope.ALL -> R.string.clear_history_scope_all
        ClearScope.UNKNOWN_NUMBERS -> R.string.clear_history_scope_unknown
        ClearScope.MISSED -> R.string.clear_history_scope_missed
        ClearScope.SHOWN -> R.string.clear_history_scope_shown
    },
)
