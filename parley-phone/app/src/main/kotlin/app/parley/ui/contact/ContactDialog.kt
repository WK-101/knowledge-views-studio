package app.parley.ui.contact

import androidx.compose.runtime.saveable.Saver
import app.parley.common.ContactSummary
import app.parley.common.people.HandleLink
import app.parley.common.ux.MenuGroup
import app.parley.ui.menus.ReasonTarget

/**
 * The one dialog, sheet or menu a contact's page has open; the page shows at most one at a time, so one value replaces
 * a flag per dialog. Each opens from a tap and closes back to [None]; [ContactDialogHost] draws it.
 *
 * Kept across rotation by [Saver]: everything that is a plain value. The few that hold what was read for the moment
 * (the namesakes to choose from) close instead, as they did before.
 */
sealed interface ContactDialog {
    data object None : ContactDialog

    /** The ⋮ menu, and one of its groups (Share…, Privacy…, More…) as its own sheet. */
    data object Menu : ContactDialog

    data class MenuSheet(val group: MenuGroup) : ContactDialog

    data object ConfirmDelete : ContactDialog

    /** The sealed copy of a private contact couldn't be kept: asks before deleting it without one. */
    data object DeleteWithoutCopy : ContactDialog

    data object Qr : ContactDialog

    /** Says what a scanner gets before a private contact's plain code shows. */
    data object PrivateQrWarning : ContactDialog

    data object SecureQr : ContactDialog

    data object Photo : ContactDialog

    data object Expiry : ContactDialog

    data object AddToHomeScreen : ContactDialog

    /** "Add to your Circle" or its rhythm. */
    data object Rhythm : ContactDialog

    data object CopyToSim : ContactDialog

    data object EditNote : ContactDialog

    data object LogInteraction : ContactDialog

    /** A logged chat or visit, by id, opened to change it. */
    data class EditInteraction(val id: Long) : ContactDialog

    data object RemindToCall : ContactDialog

    data object ConfirmMakePrivate : ContactDialog

    data object ConfirmMakeVisible : ContactDialog

    data class SimFor(val number: String) : ContactDialog

    /** "Message or call on…" for [number] (empty: the default number). */
    data class MessageOn(val number: String) : ContactDialog

    data class WebLink(val link: HandleLink) : ContactDialog

    /** The pre-call peek before calling [number]. */
    data class Peek(val number: String) : ContactDialog

    data class CallReason(val target: ReasonTarget) : ContactDialog

    /** Several contacts have the relation's name: which one to open. */
    data class ChooseRelation(val people: List<ContactSummary>) : ContactDialog

    companion object {
        private val objects: List<ContactDialog> = listOf(
            None, Menu, ConfirmDelete, DeleteWithoutCopy, Qr, PrivateQrWarning, SecureQr, Photo, Expiry, AddToHomeScreen, Rhythm,
            CopyToSim, EditNote, LogInteraction, RemindToCall, ConfirmMakePrivate, ConfirmMakeVisible,
        )

        /** Writes a dialog as a list of plain values (bundle-safe); `null` for one that closes on rotation. */
        fun save(d: ContactDialog): ArrayList<Any>? = when (d) {
            is MenuSheet -> arrayListOf<Any>("MenuSheet", d.group.name)
            is EditInteraction -> arrayListOf<Any>("EditInteraction", d.id)
            is SimFor -> arrayListOf<Any>("SimFor", d.number)
            is MessageOn -> arrayListOf<Any>("MessageOn", d.number)
            is WebLink -> arrayListOf<Any>("WebLink", d.link.uri, ArrayList(d.link.packages), d.link.isWeb, d.link.isCall)
            is Peek -> arrayListOf<Any>("Peek", d.number)
            is CallReason -> arrayListOf<Any>("CallReason", d.target.number, d.target.name.orEmpty(), d.target.simId.orEmpty())
            is ChooseRelation -> null
            else -> arrayListOf<Any>(d.toString())
        }

        @Suppress("UNCHECKED_CAST")
        fun restore(v: List<Any>): ContactDialog? = when (v.firstOrNull()) {
            "MenuSheet" -> MenuGroup.entries.firstOrNull { it.name == v[1] }?.let(::MenuSheet)
            "EditInteraction" -> EditInteraction(v[1] as Long)
            "SimFor" -> SimFor(v[1] as String)
            "MessageOn" -> MessageOn(v[1] as String)
            "WebLink" -> WebLink(HandleLink(v[1] as String, v[2] as List<String>, v[3] as Boolean, v[4] as Boolean))
            "Peek" -> Peek(v[1] as String)
            "CallReason" -> CallReason(ReasonTarget(v[1] as String, (v[2] as String).ifEmpty { null }, (v[3] as String).ifEmpty { null }))
            else -> objects.firstOrNull { it.toString() == v.firstOrNull() }
        }

        /** For `rememberSaveable(stateSaver = ContactDialog.Saver)`: a dialog that can't be kept comes back as [None]. */
        val Saver: Saver<ContactDialog, Any> = Saver(
            save = { d -> save(d) ?: save(None) },
            restore = { v -> (v as? List<*>)?.filterNotNull()?.let(::restore) ?: None },
        )

        /**
         * The saver for a private contact's page: a dialog holding one of its numbers, its name or a handle closes on
         * rotation instead, since saved state is kept by the system, outside Parley's own sealed storage.
         */
        val PrivateSaver: Saver<ContactDialog, Any> = Saver(
            save = { d -> save(if (namesSomeone(d)) None else d) ?: save(None) },
            restore = { v -> (v as? List<*>)?.filterNotNull()?.let(::restore) ?: None },
        )

        private fun namesSomeone(d: ContactDialog): Boolean = when (d) {
            is SimFor, is Peek, is CallReason, is WebLink -> true
            is MessageOn -> d.number.isNotEmpty()
            else -> false
        }
    }
}
