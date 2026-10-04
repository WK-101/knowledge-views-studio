// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.sync.shared

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.ui.Destination
import app.parley.ui.appVm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/** Destinations of shared labels (docs/SHARED_LABELS.md). */
object SharedLabelRoutes {
    /** Settings › … › Shared labels: every shared label, and joining one. */
    @Serializable data object All : Destination

    /** "Share this label…" for the label titled [title]. */
    @Serializable data class Share(val title: String) : Destination

    /** Members, invitations, every change, leaving: one shared label by its id. */
    @Serializable data class Manage(val id: String) : Destination

    /** Opening an invitation from [SharedLabelInbox]. */
    @Serializable data object Join : Destination

    /** An update file (or an invitation file) from [SharedLabelInbox.update]: picked, or sent to Parley by another app. */
    @Serializable data object OpenFile : Destination
}

/**
 * An invitation waiting to be opened: a scanned `parley://label` link, or a picked file. [update]: an update file, or
 * any file another app handed over as one (it may turn out to be an invitation).
 */
object SharedLabelInbox {
    val link = MutableStateFlow<String?>(null)
    val file = MutableStateFlow<Uri?>(null)
    val update = MutableStateFlow<Uri?>(null)
}

fun NavGraphBuilder.sharedLabelGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (Destination) -> Unit = { r -> nav.navigate(r) }
    composable<SharedLabelRoutes.All> { SharedLabelsScreen(appVm(), back, open) }
    composable<SharedLabelRoutes.Share> { ShareLabelScreen(appVm(), it.toRoute<SharedLabelRoutes.Share>().title, back, open) }
    composable<SharedLabelRoutes.Manage> { ManageSharedLabelScreen(appVm(), it.toRoute<SharedLabelRoutes.Manage>().id, back, open) }
    composable<SharedLabelRoutes.Join> { JoinSharedLabelScreen(appVm(), back) }
    composable<SharedLabelRoutes.OpenFile> { OpenLabelFileScreen(appVm(), back, open) }
}
