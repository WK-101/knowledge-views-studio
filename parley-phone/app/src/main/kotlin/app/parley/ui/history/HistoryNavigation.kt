// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

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
    /** Call insights; [quality]: scrolled to the Call quality card (Tools › Call quality). */
    @Serializable data class Insights(val quality: Boolean = false) : Destination

    /** The former "Call history" sub-screen; it is part of Settings › Recents & history now. */
    @Serializable data object Settings : Destination

    @Serializable data object Import : Destination

    /**
     * The SIMs, with the abroad settings. Plan minutes per SIM show only from Tools' row ([plans]) or once a plan is
     * set: most plans are unlimited, so Settings doesn't offer them up front.
     */
    @Serializable data class Sims(val plans: Boolean = false) : Destination

    @Serializable data class Sim(val id: String) : Destination

    fun sim(id: String): Destination = Sim(id)

    /** An organisation's case file ([app.parley.common.cases.CaseFile.id]). */
    @Serializable data class Case(val id: String) : Destination

    /** Tools › Case files: every case kept, the newest activity first. */
    @Serializable data object Cases : Destination
}

/** Call history: a number's history, insights, import, the SIMs' settings and case files. */
fun NavGraphBuilder.historyGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (Destination) -> Unit = { r -> nav.navigate(r) }
    composable<Routes.History> { NumberHistoryScreen(appVm(), it.toRoute<Routes.History>().number, back = back, open = open) }
    composable<HistoryRoutes.Insights> { InsightsScreen(appVm(), back = back, open = open, quality = it.toRoute<HistoryRoutes.Insights>().quality) }
    // Old links to the former sub-screen land on its section of the settings page.
    composable<HistoryRoutes.Settings> { SettingsPageScreen(appVm(), SettingsCategory.HISTORY, "archive", back = back, open = open) }
    composable<HistoryRoutes.Import> { ImportCallsScreen(appVm(), back = back) }
    composable<HistoryRoutes.Sims> { SimListScreen(appVm(), it.toRoute<HistoryRoutes.Sims>().plans, back = back, open = open) }
    composable<HistoryRoutes.Sim> { SimSettingsScreen(appVm(), it.toRoute<HistoryRoutes.Sim>().id, back = back) }
    composable<HistoryRoutes.Case> { app.parley.ui.cases.CaseScreen(appVm(), it.toRoute<HistoryRoutes.Case>().id, back = back) }
    composable<HistoryRoutes.Cases> { app.parley.ui.cases.CaseListScreen(appVm(), back = back, open = open) }
}
