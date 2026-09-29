package app.parley.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.SettingsCategory
import app.parley.ui.journal.HistoryTab
import kotlinx.serialization.Serializable

/**
 * A screen of the navigation graph. Every destination is a `@Serializable` object or class (Navigation's type-safe
 * routes): its arguments are typed, kept in the back stack's saved state, and never hand-built into a query string.
 * Each feature declares its own and registers them in a `NavGraphBuilder.…Graph()` in its `*Navigation.kt`.
 */
interface Destination

/** The app's view model for the screens of the graph (provided by the root, so graphs are built without it). */
val LocalAppViewModel = staticCompositionLocalOf<AppViewModel> { error("No AppViewModel provided") }

@Composable
fun appVm(): AppViewModel = LocalAppViewModel.current

/** The core destinations: home, a contact, the editor, the settings and the app-wide tools. */
object Routes {
    @Serializable data object Home : Destination

    @Serializable data class Contact(val id: Long) : Destination

    /**
     * The contact editor. [id] -1: a new contact; [vault] -1: not a private one. [prefill]: the pending details handed
     * over in memory (another app's Insert, "add to contact"). [handshake]: the received card this editor was opened for.
     */
    @Serializable
    data class Edit(
        val id: Long = -1,
        val name: String = "",
        val phone: String = "",
        val email: String = "",
        val addPhone: String = "",
        val prefill: Boolean = false,
        val vault: Long = -1,
        val handshake: String = "",
    ) : Destination

    /** A private contact by vault id: kept for old links; it opens the one contact page ([Contact] with -[id]). */
    @Serializable data class Vault(val id: Long) : Destination

    @Serializable data class History(val number: String) : Destination

    /** Picker for "add to existing contact": the number to add, or [PREFILL_MARK] for the pending prefill. */
    @Serializable data class Pick(val number: String) : Destination

    @Serializable data object Settings : Destination

    @Serializable data class SettingsPage(val category: String, val focus: String? = null) : Destination

    @Serializable data object Temporary : Destination

    @Serializable data object Blocking : Destination

    @Serializable data object Duplicates : Destination

    @Serializable data object Privacy : Destination

    @Serializable data object SpeedDial : Destination

    @Serializable data object Birthdays : Destination

    @Serializable data object Health : Destination

    /** History & undo, on one tab ([HistoryTab.key]). */
    @Serializable data class Journal(val tab: String? = null) : Destination

    @Serializable data object Tools : Destination

    @Serializable data object Backup : Destination

    @Serializable data object Sync : Destination

    @Serializable data object CallTime : Destination

    @Serializable data class Versions(val id: Long) : Destination

    fun contact(id: Long): Destination = Contact(id)

    /** Private contact [id] (vault id): the same contact page as every contact, under its negative id. */
    fun vault(id: Long): Destination = Contact(-id)
    fun history(number: String): Destination = History(number)
    fun pick(number: String): Destination = Pick(number)
    fun versions(id: Long): Destination = Versions(id)
    fun journal(tab: HistoryTab = HistoryTab.CONTACTS): Destination = Journal(tab.key)
    fun settingsPage(category: SettingsCategory, focus: String? = null): Destination = SettingsPage(category.name, focus)

    fun edit(
        id: Long? = null, name: String? = null, phone: String? = null, email: String? = null, addPhone: String? = null, prefill: Boolean = false,
        vault: Long? = null, handshake: String? = null,
    ): Destination = Edit(id ?: -1, name.orEmpty(), phone.orEmpty(), email.orEmpty(), addPhone.orEmpty(), prefill, vault ?: -1, handshake.orEmpty())

    /** Picker for "add to existing contact"; the number (or "_" = use the pending prefill). */
    const val PREFILL_MARK = "_"

    /** Where a navigation event from outside the graph (a link, a notification, a shortcut) goes; null for a tab. */
    fun forEvent(e: NavEvent): Destination? = when (e) {
        is NavEvent.Contact -> Contact(e.id)
        is NavEvent.History -> History(e.number)
        is NavEvent.Vault -> Contact(-e.id)
        is NavEvent.Route -> e.route
        is NavEvent.NewContact -> edit(prefill = true)
        else -> null
    }
}
