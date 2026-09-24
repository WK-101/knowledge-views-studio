package app.parley.ui.history

import android.net.Uri
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
import androidx.navigation.NavController
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.AppViewModel

/** Destinations of the call-history features. */
object HistoryRoutes {
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings/history"
    const val IMPORT = "settings/history/import"
    const val SIMS = "settings/sims"
    const val SIM = "settings/sim/{id}"
    fun sim(id: String) = "settings/sim/" + Uri.encode(id)
}

/** Registers the call-history screens in the app's NavHost. */
fun NavGraphBuilder.historyDestinations(vm: AppViewModel, nav: NavController) {
    composable(HistoryRoutes.INSIGHTS) { InsightsScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
    composable(HistoryRoutes.SETTINGS) { CallHistorySettingsScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
    composable(HistoryRoutes.IMPORT) { ImportCallsScreen(vm, back = { nav.popBackStack() }) }
    composable(HistoryRoutes.SIMS) { SimListScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
    composable(HistoryRoutes.SIM) { SimSettingsScreen(vm, Uri.decode(it.arguments?.getString("id").orEmpty()), back = { nav.popBackStack() }) }
}

/** Recents top-bar action: Insights (H5). */
@Composable
fun RecentsInsightsAction(open: (String) -> Unit) {
    IconButton({ open(HistoryRoutes.INSIGHTS) }) { Icon(Icons.Rounded.Insights, "Call insights") }
}

/** Recents overflow item "Export…" (H2); the sheet itself is shown by [RecentsExportHost] in Recents. */
@Composable
fun RecentsExportMenuItem(closeMenu: () -> Unit) {
    DropdownMenuItem({ Text("Export…") }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null) }, onClick = {
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
    val groups by vm.recentGroups.collectAsStateWithLifecycle()
    val calls = remember(groups) { groups.orEmpty().flatMap { it.calls } }
    ExportSheet(vm, calls, subject = null) { exportRequested.value = false }
}
