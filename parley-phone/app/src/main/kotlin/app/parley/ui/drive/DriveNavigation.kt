// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.drive

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.ui.Destination
import app.parley.ui.appVm
import kotlinx.serialization.Serializable

/** The drive profile (Settings › Calls › Drive profile). */
object DriveRoutes {
    @Serializable data object Profile : Destination
}

fun NavGraphBuilder.driveGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    composable<DriveRoutes.Profile> { DriveProfileScreen(appVm(), back) }
}
