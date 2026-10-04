// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.situations

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.ui.Destination
import app.parley.ui.appVm
import kotlinx.serialization.Serializable

/** A Situation's own page (Settings › Calls › Situations › one of them). */
object SituationRoutes {
    @Serializable data class Edit(val id: String) : Destination

    /** Rescue call's screen (Calls › Situations, Tools, the launcher shortcut, a long press on the Situation tile). */
    @Serializable data object RescueCall : Destination
}

fun NavGraphBuilder.situationGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (Destination) -> Unit = { nav.navigate(it) }
    composable<SituationRoutes.Edit> { SituationEditScreen(appVm(), it.toRoute<SituationRoutes.Edit>().id, back, open) }
    composable<SituationRoutes.RescueCall> { RescueCallScreen(appVm(), back) }
}
