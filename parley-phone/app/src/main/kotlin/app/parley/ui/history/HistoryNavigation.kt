package app.parley.ui.history

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.common.SettingsCategory
import app.parley.ui.Destination
import app.parley.ui.Routes
import app.parley.ui.appVm
import app.parley.ui.settings.SettingsPageScreen
import kotlinx.serialization.Serializable

/** Destinations of the call-history features. */
object HistoryRoutes {
    @Serializable data object Insights : Destination

    /** The former "Call history" sub-screen; it is part of Settings › Recents & history now. */
    @Serializable data object Settings : Destination

    @Serializable data object Import : Destination

    @Serializable data object Sims : Destination

    @Serializable data class Sim(val id: String) : Destination

    fun sim(id: String): Destination = Sim(id)
}

/** Call history: a number's history, insights, import, and the SIMs' settings. */
fun NavGraphBuilder.historyGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (Destination) -> Unit = { r -> nav.navigate(r) }
    composable<Routes.History> { NumberHistoryScreen(appVm(), it.toRoute<Routes.History>().number, back = back, open = open) }
    composable<HistoryRoutes.Insights> { InsightsScreen(appVm(), back = back, open = open) }
    // Old links to the former sub-screen land on its section of the settings page.
    composable<HistoryRoutes.Settings> { SettingsPageScreen(appVm(), SettingsCategory.HISTORY, "archive", back = back, open = open) }
    composable<HistoryRoutes.Import> { ImportCallsScreen(appVm(), back = back) }
    composable<HistoryRoutes.Sims> { SimListScreen(appVm(), back = back, open = open) }
    composable<HistoryRoutes.Sim> { SimSettingsScreen(appVm(), it.toRoute<HistoryRoutes.Sim>().id, back = back) }
}
