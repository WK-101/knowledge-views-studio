package app.parley.ui.history

import app.parley.AppViewModel
import app.parley.ui.Destination
import app.parley.ui.activityViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.ui.home.RecentsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.res.stringResource
import app.parley.R

/** Recents top-bar action: Insights. */
@Composable
fun RecentsInsightsAction(open: (Destination) -> Unit) {
    IconButton({ open(HistoryRoutes.Insights) }) { Icon(Icons.Rounded.Insights, stringResource(R.string.hist_insights_action)) }
}

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
    val groups by activityViewModel<RecentsViewModel>().groups.collectAsStateWithLifecycle()
    val calls = remember(groups) { groups.orEmpty().flatMap { it.calls } }
    ExportSheet(vm, calls, subject = null) { exportRequested.value = false }
}
