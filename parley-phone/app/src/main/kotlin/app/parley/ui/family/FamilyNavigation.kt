// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.family

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.ui.Destination
import app.parley.ui.appVm
import kotlinx.serialization.Serializable

/** Family safety: the safe words (by label) and the helpers. */
object FamilyRoutes {
    @Serializable data object SafeWords : Destination

    @Serializable data object Helpers : Destination
}

fun NavGraphBuilder.familyGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (Destination) -> Unit = { r -> nav.navigate(r) }
    composable<FamilyRoutes.SafeWords> { SafeWordsScreen(appVm(), back, open) }
    composable<FamilyRoutes.Helpers> { HelpersScreen(appVm(), back) }
}
