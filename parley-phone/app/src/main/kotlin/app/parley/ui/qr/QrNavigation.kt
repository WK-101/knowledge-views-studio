// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.qr

import app.parley.ui.parleyBack
import app.parley.ui.parleyOpen
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.ui.Destination
import app.parley.ui.appVm
import kotlinx.serialization.Serializable

/** The "Scan QR" screen, reached from Contacts, the keypad, My card, Settings, the launcher and the tile. */
object QrRoutes {
    @Serializable data object Scan : Destination
}

fun NavGraphBuilder.qrGraph(nav: NavController) {
    composable<QrRoutes.Scan> { QrScanScreen(appVm(), back = nav.parleyBack(), open = nav.parleyOpen()) }
}
