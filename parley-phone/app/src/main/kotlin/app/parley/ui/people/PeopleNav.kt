package app.parley.ui.people

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.parley.AppViewModel
import app.parley.ui.Routes
import app.parley.ui.contact.ContactEditScreen
import kotlinx.coroutines.flow.MutableStateFlow

/** Routes of the contacts and privacy features (registered by [peopleRoutes]). */
object PeopleRoutes {
    const val LABELS = "labels"
    const val LABEL = "label/{title}"
    fun label(title: String) = "label/" + Uri.encode(title)
    const val EDIT_RAW = "editraw/{id}/{raw}"
    fun editRaw(contactId: Long, rawId: Long) = "editraw/$contactId/$rawId"
    const val SIM_IMPORT = "simimport"
    const val WHO_CAN_SEE = "whocansee"
    const val PRIVATE_NAMES = "privatenames"
    const val DIAGNOSTICS = "diagnostics"

    /**
     * Label page overflow → Blocking screen, prefilled: "blocking?label=Family&mode=block" (or mode=allow).
     * The same request is also left in [LabelBlockingRequest.pending] for the Blocking screen to consume.
     */
    fun blockLabel(title: String, allow: Boolean): String {
        LabelBlockingRequest.pending.value = LabelBlockingRequest(title, allow)
        return Routes.BLOCKING + "?label=" + Uri.encode(title) + "&mode=" + if (allow) "allow" else "block"
    }
}

/**
 * "Block everyone in this label" / "Always let this label through", handed to the Blocking screen. The Blocking
 * screen reads (and clears) [pending], or the `label` and `mode` navigation arguments, to open its rule editor
 * prefilled with a label rule.
 */
data class LabelBlockingRequest(val label: String, val allow: Boolean) {
    companion object {
        val pending = MutableStateFlow<LabelBlockingRequest?>(null)
    }
}

/** Adds the contacts/privacy screens to the app's navigation graph. */
fun NavGraphBuilder.peopleRoutes(vm: AppViewModel, nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (String) -> Unit = { r -> nav.navigate(r) }
    composable(PeopleRoutes.LABELS) { ManageLabelsScreen(vm, back, open) }
    composable(PeopleRoutes.LABEL) { LabelScreen(vm, Uri.decode(it.arguments?.getString("title").orEmpty()), back, open) }
    composable(
        PeopleRoutes.EDIT_RAW,
        arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("raw") { type = NavType.LongType }),
    ) {
        val a = it.arguments!!
        ContactEditScreen(
            vm, contactId = a.getLong("id"), prefillName = "", prefillPhone = "", prefillEmail = "", addPhone = "",
            rawId = a.getLong("raw"),
            done = { saved ->
                nav.popBackStack()
                if (saved != null && saved > 0 && nav.currentDestination?.route != Routes.CONTACT) nav.navigate(Routes.contact(saved)) { launchSingleTop = true }
            },
        )
    }
    composable(PeopleRoutes.SIM_IMPORT) { SimImportScreen(vm, back) }
    composable(PeopleRoutes.WHO_CAN_SEE) { WhoCanSeeScreen(vm, back, open) }
    composable(PeopleRoutes.PRIVATE_NAMES) { PrivateNamesScreen(vm, back) }
    composable(PeopleRoutes.DIAGNOSTICS) { DiagnosticsScreen(vm, back) }
}
