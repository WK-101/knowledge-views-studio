// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.contact

import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.ui.Destination
import app.parley.ui.LocalNavAnimScope
import app.parley.ui.Routes
import app.parley.ui.appVm
import app.parley.ui.extras.HandshakeInbox
import app.parley.ui.timemachine.VersionHistoryScreen
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.serialization.Serializable

/** The contact page's own screens. */
object ContactPageRoutes {
    @Serializable data class Timeline(val id: Long) : Destination

    @Serializable data object Sections : Destination

    fun timeline(id: Long): Destination = Timeline(id)
}

/**
 * After an edit: back to where the editor was opened from, then to the saved contact (a private one has a negative id,
 * and the same page) unless that is where we already are.
 */
internal fun NavController.afterEdit(savedId: Long?) {
    popBackStack()
    val here = currentDestination
    when {
        savedId == null -> Unit
        here?.hasRoute<Routes.Contact>() != true && here?.hasRoute<Routes.Vault>() != true -> navigate(Routes.Contact(savedId)) { launchSingleTop = true }
    }
}

/** Contacts: a contact's page, the editor, private contacts, the picker, duplicates, versions and the page's sections. */
fun NavGraphBuilder.contactGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val open: (Destination) -> Unit = { r -> nav.navigate(r) }
    composable<Routes.Contact> {
        CompositionLocalProvider(LocalNavAnimScope provides this) {
            ContactDetailScreen(appVm(), it.toRoute<Routes.Contact>().id, back = back, open = open)
        }
    }
    composable<Routes.Edit> {
        val a = it.toRoute<Routes.Edit>()
        val vm = appVm()
        val pasteText = remember(a.paste) { if (a.paste.isNotEmpty()) PasteInbox.take(a.paste) else null }
        ContactEditScreen(
            vm,
            contactId = a.id.takeIf { id -> id > 0 },
            prefillName = a.name,
            prefillPhone = a.phone,
            prefillEmail = a.email,
            addPhone = a.addPhone,
            prefill = if (a.prefill) vm.pendingPrefill.also { vm.pendingPrefill = null } else null,
            vaultId = a.vault.takeIf { v -> v >= 0 },
            // Taken once for this entry (L8), not on every recomposition.
            pasteText = pasteText,
            done = { savedId ->
                // A contact received by QR gets its "Met at…" entry once it's saved.
                HandshakeInbox.onSaved(vm, savedId, a.handshake)
                nav.afterEdit(savedId)
            },
        )
    }
    // Old links to a private contact's own page (shortcuts, notifications, saved back stacks) open the one contact page.
    composable<Routes.Vault> { ContactDetailScreen(appVm(), -it.toRoute<Routes.Vault>().id, back = back, open = open) }
    composable<Routes.Pick> {
        val number = it.toRoute<Routes.Pick>().number
        ContactPickerScreen(appVm(), back = back, onPick = { id ->
            nav.popBackStack()
            if (number == Routes.PREFILL_MARK) nav.navigate(Routes.edit(id = id, prefill = true)) else nav.navigate(Routes.edit(id = id, addPhone = number))
        })
    }
    composable<Routes.Duplicates> { DuplicatesScreen(appVm(), back = back) }
    composable<Routes.Versions> { VersionHistoryScreen(appVm(), it.toRoute<Routes.Versions>().id, back = back, open = open) }
    composable<ContactPageRoutes.Timeline> { ContactTimelineScreen(appVm(), it.toRoute<ContactPageRoutes.Timeline>().id, back = back) }
    composable<ContactPageRoutes.Sections> { ContactPageSettingsScreen(appVm(), back = back) }
}
