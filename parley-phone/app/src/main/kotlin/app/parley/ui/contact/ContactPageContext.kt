package app.parley.ui.contact

import android.content.Context
import androidx.activity.ComponentActivity
import android.content.Intent
import android.content.res.Resources
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.EventDate
import app.parley.common.people.ContactCapabilities
import app.parley.common.people.ContactCapability
import app.parley.common.people.MessageRoute
import app.parley.common.people.MessageRoutes
import app.parley.common.people.MessengerPrefs
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.people.RelationFromOther
import app.parley.data.primary
import app.parley.security.AppLock
import app.parley.ui.Destination
import app.parley.ui.cases.CaseOwner
import app.parley.ui.cases.CaseShown
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.people.eventLabel
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope

/**
 * What the parts of a contact's page share while it draws: the contact as loaded, its view model, and the page's own
 * actions (call, message, open a dialog). [ContactDetailScreen] builds it on each composition; the parts read it and
 * never keep it.
 */
@Suppress("LongParameterList") // The page's shared state, gathered in one place so the parts take one parameter.
internal class ContactPageContext(
    val vm: AppViewModel,
    val page: ContactDetailViewModel,
    val ui: ContactDetailUiState,
    val d: ContactDetails,
    val contactId: Long,
    private val context: Context,
    /** The page's own scope, for work that outlives the dialog that started it. */
    val scope: CoroutineScope,
    private val res: Resources,
    /** A good time to call, from the calls with them and their local time. */
    val goodTime: String?,
    /** "Last talked 3 days ago", or that there are no calls yet. */
    val lastTalked: String,
    val today: LocalDate,
    /** Dates that come round again (a date of death doesn't), with their place in `d.events`. */
    val dated: List<Pair<Int, EventDate>>,
    val parleyRelations: List<DataItem>,
    val relationsFromOthers: List<RelationFromOther>,
    val open: (Destination) -> Unit,
    val back: () -> Unit,
    /** Opens one of the page's dialogs ([ContactDialog.None] closes it). */
    val show: (ContactDialog) -> Unit,
    /** Calls a number, through the pre-call peek when there's something to remember and it's on. */
    val callPeek: (number: String, name: String) -> Unit,
    /** Opens the contact a relation names: by its remembered link first, then by name. */
    val openRelation: (name: String) -> Unit,
    /** The system's ringtone picker; the choice comes back to the page. */
    val pickRingtone: (Intent) -> Unit,
    /** Their case file, when one is kept or they look like an organisation. */
    val case: CaseShown = CaseShown(null, false),
) {
    /** Who a case file for them is about. */
    val caseOwner: CaseOwner get() = caseOwnerOf(d, ui.isPrivate)

    val isPrivate: Boolean get() = ui.isPrivate
    val prefs: MessengerPrefs get() = ui.prefs
    val inCircle: Boolean get() = ui.meta?.reachOutDays != null
    private val caps = ContactCapabilities.of(ui.storage)

    /** One page for every contact: a private one differs only by what only the address book can do. */
    fun can(c: ContactCapability) = c in caps

    /** The ways to reach them: numbers, apps and the remembered choice (nothing written outside Parley for a private contact). */
    val reach = Reach(
        name = d.given.ifBlank { d.displayName },
        numbers = d.phones.map { it.value to Format.phoneType(res, it.type, it.label) },
        defaultNumber = d.phones.primary()?.value,
        messengers = ui.messengers,
        prefs = prefs,
        isPrivate = isPrivate,
        lookupKey = d.lookupKey.takeUnless { isPrivate },
        contactId = contactId.takeUnless { isPrivate },
    )
    val primary: DataItem? = d.phones.primary()
    val email: DataItem? = d.emails.primary()

    /** The app chosen for calls, which the Call tile then uses. */
    val preferredCall = ui.messengers.firstOrNull { it.accountType == prefs.call && it.isCall && !it.isVideo }
    val preferredVideo = reach.videoRows.firstOrNull { it.accountType == prefs.video }
    val canCall: Boolean get() = primary != null || preferredCall != null
    val canMessage: Boolean get() = primary != null || reach.linked.isNotEmpty()

    fun call() {
        if (preferredCall != null) ContactMessaging.start(context, preferredCall.intent(), preferredCall.appName)?.let { vm.toast(it) }
        else primary?.let { callPeek(it.value, d.displayName) }
    }

    /** Messages them the usual way, or asks how ("Message or call on…") when there is no usual way yet. */
    fun message(number: String? = null) {
        val r = reach.let { if (number != null) it.copy(defaultNumber = number, prefs = it.prefs.copy(number = null)) else it }
        when (val route = ContactMessaging.route(context, r)) {
            MessageRoute.Ask -> show(ContactDialog.MessageOn(number ?: r.defaultNumber.orEmpty()))
            else -> ContactMessaging.open(context, route, r)?.let { vm.toast(it) }
        }
    }

    /**
     * A number's own Message button: a message to that number (the usual chat app, or a text), never the sheet; the
     * row's "Message or call on…" button is the one that opens it.
     */
    fun messageNumber(number: String) {
        val r = reach.copy(defaultNumber = number)
        val route = MessageRoutes.forNumber(r.prefs, r.linked, ContactMessaging.installed(context), number)
        ContactMessaging.open(context, route, r)?.let { vm.toast(it) }
    }

    fun video() {
        val only = reach.videoRows.distinctBy { it.accountType }.singleOrNull()
        val target = preferredVideo ?: only
        if (target != null) ContactMessaging.startRow(context, reach, target)?.let { vm.toast(it) }
        else show(ContactDialog.MessageOn(primary?.value.orEmpty()))
    }

    /** The vault's unlock, in this page; the details load again once it succeeds. */
    fun unlock() {
        (context as? ComponentActivity)?.let { AppLock.authenticateForVault(it) { ok -> if (ok) page.reload() } }
    }

    /** Shares the contact as a vCard file. */
    fun shareFile() = Intents.shareVcard(context, page.vcardUri(d.lookupKey), d.displayName)

    /** "Birthday in 6 days" for the date at [i] of `d.events`, [days] away. */
    fun dateText(i: Int, days: Long): String {
        val label = eventLabel(res, d.events[i])
        return when (days) {
            0L -> res.getString(R.string.contact_page_date_today, label)
            1L -> res.getString(R.string.contact_page_date_tomorrow, label)
            else -> res.getQuantityString(R.plurals.contact_page_date_in, days.toInt(), label, days.toInt())
        }
    }
}

/** Who a case file for the contact [d] is about: its name, numbers and Parley key. */
internal fun caseOwnerOf(d: ContactDetails, private: Boolean): CaseOwner =
    CaseOwner(d.displayName, d.phones.map { it.value }.distinct(), private, d.lookupKey.ifEmpty { null })
