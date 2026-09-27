package app.parley.ui.contact

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
import app.parley.ui.vault.VaultDetailScreen
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.serialization.Serializable

/** The contact page's own screens. */
object ContactPageRoutes {
    @Serializable data class Timeline(val id: Long) : Destination

    @Serializable data object Sections : Destination

    fun timeline(id: Long): Destination = Timeline(id)
}

/**
 * After an edit: back to where the editor was opened from, then to the saved contact (a private one: negative id)
 * unless that is where we already are.
 */
internal fun NavController.afterEdit(savedId: Long?) {
    popBackStack()
    val here = currentDestination
    when {
        savedId == null -> Unit
        savedId < 0 -> if (here?.hasRoute<Routes.Vault>() != true) navigate(Routes.Vault(-savedId)) { launchSingleTop = true }
        here?.hasRoute<Routes.Contact>() != true -> navigate(Routes.Contact(savedId)) { launchSingleTop = true }
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
        ContactEditScreen(
            vm,
            contactId = a.id.takeIf { id -> id > 0 },
            prefillName = a.name,
            prefillPhone = a.phone,
            prefillEmail = a.email,
            addPhone = a.addPhone,
            prefill = if (a.prefill) vm.pendingPrefill.also { vm.pendingPrefill = null } else null,
            vaultId = a.vault.takeIf { v -> v >= 0 },
            done = { savedId ->
                // A contact received by QR gets its "Met at…" entry once it's saved.
                HandshakeInbox.onSaved(vm, savedId, a.handshake)
                nav.afterEdit(savedId)
            },
        )
    }
    composable<Routes.Vault> { VaultDetailScreen(appVm(), it.toRoute<Routes.Vault>().id, back = back, open = open) }
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
