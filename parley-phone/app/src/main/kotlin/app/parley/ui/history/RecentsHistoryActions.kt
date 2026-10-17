package app.parley.ui.history

import app.parley.AppViewModel
import app.parley.ui.activityViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.ui.home.RecentsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.res.stringResource
import app.parley.R

/** Recents overflow item "Export…"; the sheet itself is shown by [RecentsExportHost] in Recents. */
@Composable
fun RecentsExportMenuItem(closeMenu: () -> Unit) {
    DropdownMenuItem({ Text(stringResource(R.string.hist_export_menu)) }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null) }, onClick = {
        closeMenu()
        exportRequested.value = true
    })
}

private val exportRequested = MutableStateFlow(false)

/** Shows the export sheet for the calls currently in Recents (filters and search applied). */
@Composable
fun RecentsExportHost(vm: AppViewModel) {
    val show by exportRequested.collectAsStateWithLifecycle()
    if (!show) return
    val recents = activityViewModel<RecentsViewModel>()
    val groups by recents.groups.collectAsStateWithLifecycle()
    val calls = remember(groups) { groups.orEmpty().flatMap { it.calls } }
    // What the export covers, worded for the PDF ("Missed · Last 7 days"); nothing when nothing is filtered.
    val filter by recents.historyFilter.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val coverage = remember(filter, sims) {
        filter.takeIf { !it.isEmpty }?.let { f -> HistoryText.describe(res, f) { id -> sims.firstOrNull { it.id == id }?.label ?: id } }
    }
    ExportSheet(vm, calls, subject = null, coverage = coverage) { exportRequested.value = false }
}
