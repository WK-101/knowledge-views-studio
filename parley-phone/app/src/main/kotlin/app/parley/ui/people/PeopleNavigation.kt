// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.people

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.ui.Destination
import app.parley.ui.appVm
import app.parley.ui.contact.ContactEditScreen
import app.parley.ui.contact.afterEdit
import app.parley.ui.home.homePanes
import app.parley.ui.people.cards.NewNumberScreen
import app.parley.ui.people.cards.SharedWithScreen
import kotlinx.serialization.Serializable

/** Destinations of the contacts and privacy features (registered by [peopleGraph]). */
object PeopleRoutes {
    @Serializable data object Labels : Destination

    @Serializable data class Label(val title: String) : Destination

    /** "Edit this copy": one raw contact of a contact. */
    @Serializable data class EditRaw(val id: Long, val raw: Long) : Destination

    @Serializable data object SimImport : Destination

    @Serializable data object WhoCanSee : Destination

    @Serializable data object PrivateNames : Destination

    @Serializable data object Diagnostics : Destination

    @Serializable data object Me : Destination

    /** My card in the contact editor (its My card mode). */
    @Serializable data object MeEdit : Destination

    /** My card › Shared with (I22). */
    @Serializable data object SharedWith : Destination

    /** My card › "Changed my number" (I14). */
    @Serializable data object NewNumber : Destination

    fun label(title: String): Destination = Label(title)
    fun editRaw(contactId: Long, rawId: Long): Destination = EditRaw(contactId, rawId)
}

/** Labels, "Edit this copy", SIM import, who can see what, private names, diagnostics and My card. */
fun NavGraphBuilder.peopleGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (Destination) -> Unit = { r -> nav.navigate(r) }
    composable<PeopleRoutes.Labels> { ManageLabelsScreen(appVm(), back, open) }
    composable<PeopleRoutes.Label> { LabelScreen(appVm(), it.toRoute<PeopleRoutes.Label>().title, back, open) }
    composable<PeopleRoutes.EditRaw> {
        val a = it.toRoute<PeopleRoutes.EditRaw>()
        val panes = homePanes()
        ContactEditScreen(
            appVm(), contactId = a.id, prefillName = "", prefillPhone = "", prefillEmail = "", addPhone = "",
            rawId = a.raw,
            done = { saved -> nav.afterEdit(saved?.takeIf { it > 0 }, panes::showSaved) },
        )
    }
    composable<PeopleRoutes.SimImport> { SimImportScreen(appVm(), back) }
    composable<PeopleRoutes.WhoCanSee> { WhoCanSeeScreen(appVm(), back, open) }
    composable<PeopleRoutes.PrivateNames> { PrivateNamesScreen(appVm(), back) }
    composable<PeopleRoutes.Diagnostics> { DiagnosticsScreen(appVm(), back) }
    composable<PeopleRoutes.Me> { MeCardScreen(appVm(), back, open) }
    composable<PeopleRoutes.SharedWith> { SharedWithScreen(appVm(), back) }
    composable<PeopleRoutes.NewNumber> { NewNumberScreen(appVm(), back) }
    composable<PeopleRoutes.MeEdit> {
        ContactEditScreen(appVm(), contactId = null, prefillName = "", prefillPhone = "", prefillEmail = "", addPhone = "", meCard = true, done = { back() })
    }
}
