package app.parley.ui.extras

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.ui.Destination
import app.parley.ui.appVm
import kotlinx.serialization.Serializable

/** Trip mode and Simple mode's setup and import. */
object ExtrasRoutes {
    @Serializable data object Trip : Destination

    @Serializable data object SimpleSetup : Destination

    @Serializable data object SimpleImport : Destination
}

fun NavGraphBuilder.extrasGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (Destination) -> Unit = { r -> nav.navigate(r) }
    composable<ExtrasRoutes.Trip> { TripScreen(appVm(), back, open) }
    composable<ExtrasRoutes.SimpleSetup> { SimpleSetupScreen(appVm(), back, open) }
    composable<ExtrasRoutes.SimpleImport> { SimpleImportScreen(appVm(), back, open) }
}
