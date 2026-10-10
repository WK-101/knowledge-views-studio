package app.parley

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import app.parley.common.StartTab
import app.parley.common.sync.shared.SharedLabelInvites
import app.parley.common.sync.shared.SharedLabelUpdates
import app.parley.common.vcard.SealedVCard
import app.parley.messaging.MessagingRoutes
import app.parley.ui.Destination
import app.parley.ui.Routes
import app.parley.ui.blocking.BlockingRoutes
import app.parley.ui.calls.ToCallRoutes
import app.parley.ui.extras.ExtrasRoutes
import app.parley.ui.people.PeopleRoutes
import app.parley.ui.qr.QrRoutes
import app.parley.ui.settings.CallsRoutes
import app.parley.ui.settings.CallsSubPage
import app.parley.ui.situations.SituationRoutes
import app.parley.ui.sync.shared.SharedLabelRoutes

/**
 * What an intent reaching the main screen asks for: a `parley://` link, a `tel:` link, a launcher shortcut, a Quick
 * Settings tile, a notification's action, another app's Insert, Edit or Quick Contact. [event] is where to go; the other
 * fields are what the activity must do first (hand a link or picture to its screen, look a contact up).
 */
data class IntentTarget(
    val event: NavEvent? = null,
    /** A picture shared to Parley, searched for QR codes on the scan screen. */
    val qrImage: Uri? = null,
    /** A Simple-mode setup (`parley://simple`). */
    val simpleSetup: Uri? = null,
    /** A shared rule-pack template (`parley://template`). */
    val template: Uri? = null,
    /** A contacts URI to resolve to a contact (off the main thread) and open. */
    val resolveContact: Uri? = null,
    /** Another app's "Edit contact": a contacts URI to resolve and open in the editor. */
    val editContact: Uri? = null,
    /** SHOW_OR_CREATE_CONTACT: open the matching contact or offer to create one. */
    val showOrCreate: Uri? = null,
    /** The post-call card's "Report" for a number. */
    val report: String? = null,
    /** The post-call card's or a missed-call notification's "Block": the one Block question, for a number. */
    val block: String? = null,
    /** The post-call card's "Unblock" for a number blocked already. */
    val unblock: String? = null,
    /** Missed calls were opened: they count as seen (once the screen shows unlocked). */
    val missedSeen: Boolean = false,
    /** A private-name request's "Allow…": the phone app's package (it asked through the contacts Directory). */
    val approvePrivateName: String? = null,
    /** A prepared export to share or print now. */
    val openExport: app.parley.jobs.UserJobs.Opener? = null,
    /** A shared label's update or invitation file another app handed over (its screen checks which it is). */
    val labelFile: Uri? = null,
)

/** The mapping from intents to [IntentTarget]s; pure, so every old link and shortcut is tested to still resolve. */
object IntentRoutes {
    /**
     * The non-exported alias of MainActivity (manifest) that Parley's own notifications, widgets, shortcuts and tiles
     * start. Only intents that arrive through it may carry Parley's internal actions: the exported MainActivity is
     * reachable by any app, which must not be able to mark missed calls as seen or drive Parley's screens.
     */
    const val OWN_ENTRY = "app.parley.InternalEntry"

    /** An intent for Parley's own entry point ([OWN_ENTRY]); set an internal action on it. */
    fun own(context: Context): Intent = Intent().setComponent(ComponentName(context, OWN_ENTRY))

    /** Whether [intent] came in through [OWN_ENTRY], so only Parley itself (or a PendingIntent it made) sent it. */
    fun fromParley(intent: Intent): Boolean = intent.component?.className == OWN_ENTRY

    /** The actions only Parley may send; from any other app they open nothing ([resolve]). */
    val INTERNAL_ACTIONS: Set<String> by lazy {
        setOf(
            ACTION_ADD_CALL, ACTION_BULK_ADD, ACTION_PASTE_CONTACT, ACTION_OPEN_BACKUP, ACTION_SCAN_QR, ACTION_OPEN_BLOCKING,
            ACTION_OPEN_SYNC, ACTION_OPEN_TEMPORARY, ACTION_OPEN_HEALTH, ACTION_SHOW_MISSED, ACTION_SHOW_CIRCLE,
            ACTION_SHOW_TO_CALL, ACTION_SHOW_CALLER, ACTION_POST_CALL, ACTION_APPROVE_PRIVATE_NAME, ACTION_OPEN_EXPORT,
            ACTION_EXPORT_CONTACTS, ACTION_RESCUE_CALL, ACTION_OPEN_LABEL, ACTION_OPEN_SITUATIONS,
        )
    }

    /** A private-name request's "Allow…": the approval sheet, behind the app lock (the extras name the app). */
    const val ACTION_APPROVE_PRIVATE_NAME = "app.parley.APPROVE_PRIVATE_NAME"
    const val EXTRA_PACKAGE = "package"

    /** Set to false on the requests the removed lookup provider posted (true or absent: a Directory request). */
    private const val LEGACY_EXTRA_DIRECTORY = "directory"

    /** A request notification of the removed lookup provider, posted before the upgrade: it must answer nothing. */
    fun isLegacyLookupRequest(intent: Intent): Boolean =
        intent.hasExtra(LEGACY_EXTRA_DIRECTORY) && !intent.getBooleanExtra(LEGACY_EXTRA_DIRECTORY, true)

    const val ACTION_ADD_CALL = "app.parley.ADD_CALL"

    /**
     * A prepared export's notification or snackbar was tapped: share or print the file (its name in the export folder),
     * from the activity.
     */
    const val ACTION_OPEN_EXPORT = "app.parley.OPEN_EXPORT"
    const val EXTRA_FILE = "file"
    const val EXTRA_MIME = "mime"
    const val EXTRA_PRINT = "print"

    /** "Save all…" from the number sheet; the text waits in [app.parley.messaging.MessagingInbox]. */
    const val ACTION_BULK_ADD = "app.parley.BULK_ADD"

    /** "Make a contact from this text" from the number sheet; the text waits in [app.parley.ui.contact.PasteInbox]. */
    const val ACTION_PASTE_CONTACT = "app.parley.PASTE_CONTACT"

    /** The id [app.parley.ui.contact.PasteInbox] gave the shared text; the editor takes the text only with it. */
    const val EXTRA_PASTE_ID = "paste_id"
    const val ACTION_OPEN_BACKUP = "app.parley.OPEN_BACKUP"

    /** Opens the Scan QR screen (launcher shortcut, Quick Settings tile). */
    const val ACTION_SCAN_QR = "app.parley.action.SCAN_QR"

    /** Opens Rescue call's screen (launcher shortcut). */
    const val ACTION_RESCUE_CALL = "app.parley.action.RESCUE_CALL"

    /** Android's long press on a Quick Settings tile ([android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES]). */
    const val QS_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"

    /** The Situation tile, whose long press opens Rescue call. */
    private const val SITUATION_TILE = "app.parley.situations.SituationTileService"

    private const val MAIN_ACTIVITY = "app.parley.MainActivity"

    private fun isSituationTile(pkg: String?, tile: ComponentName?) = tile != null && tile.packageName == pkg && tile.className == SITUATION_TILE

    /**
     * Where a long press on one of Parley's tiles goes ([QS_TILE_PREFERENCES], which Android sends to the app for every
     * tile it holds): the Situation tile's opens Rescue call, every other tile's opens App info, as Android does for a
     * tile with no screen of its own.
     *
     * The activity that receives the long press is exported, so any app can send it this intent with any tile named.
     * It therefore hands Rescue call to the exported MainActivity as the same public request ([resolve] reads it there),
     * never as an internal action through Parley's own entry: it can open nothing another app couldn't open directly.
     */
    fun tileLongPress(context: Context, intent: Intent): Intent {
        val tile = tileComponent(intent)
        return if (isSituationTile(context.packageName, tile)) {
            Intent(QS_TILE_PREFERENCES).setClassName(context, MAIN_ACTIVITY).putExtra(Intent.EXTRA_COMPONENT_NAME, tile)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    const val ACTION_OPEN_BLOCKING = "app.parley.OPEN_BLOCKING"

    /** Folder sync paused and waits for the user (its notification). */
    const val ACTION_OPEN_SYNC = "app.parley.OPEN_SYNC"

    /** The Export contacts screen (the notice that it replaced the folder export of notes). */
    const val ACTION_EXPORT_CONTACTS = "app.parley.EXPORT_CONTACTS"

    /** Temporary contacts are due to be deleted and wait for your answer (its notification). */
    const val ACTION_OPEN_TEMPORARY = "app.parley.OPEN_TEMPORARY"

    /** A label's page ([EXTRA_LABEL]: its title), or Labels without one: where an ended chapter asks what to do. */
    const val ACTION_OPEN_LABEL = "app.parley.OPEN_LABEL"
    const val EXTRA_LABEL = "label"

    /** Contacts went missing (the sync watchdog's notification): the card waits in the Contact health check. */
    const val ACTION_OPEN_HEALTH = "app.parley.OPEN_HEALTH"

    /** Calls › Situations (the notice while a Situation lets only some people ring). */
    const val ACTION_OPEN_SITUATIONS = "app.parley.OPEN_SITUATIONS"
    const val QUICK_CONTACT = "android.provider.action.QUICK_CONTACT"
    const val QUICK_CONTACT_LEGACY = "com.android.contacts.action.QUICK_CONTACT"
    const val SHOW_OR_CREATE = "com.android.contacts.action.SHOW_OR_CREATE_CONTACT"
    const val ACTION_SHOW_MISSED = "app.parley.SHOW_MISSED"
    const val ACTION_SHOW_CIRCLE = "app.parley.SHOW_CIRCLE"

    /** The To call reminder opens the list. */
    const val ACTION_SHOW_TO_CALL = "app.parley.SHOW_TO_CALL"
    const val ACTION_SHOW_CALLER = "app.parley.SHOW_CALLER"
    const val ACTION_POST_CALL = "app.parley.POST_CALL"
    const val EXTRA_POST_CALL_ACTION = "post_call_action"
    const val EXTRA_CONTACT_ID = "contact_id"
    const val EXTRA_NUMBER = "number"

    /** A name to start from (the post-call card's "Save": the name the network sent). */
    const val EXTRA_NAME = "name"

    /** The raw contact an Edit link names (`content://com.android.contacts/raw_contacts/12`); null for a contact link. */
    fun rawContactId(uri: Uri): Long? {
        val seg = uri.pathSegments
        return if (uri.authority == ContactsContract.AUTHORITY && seg.size == 2 && seg[0] == "raw_contacts") seg[1].toLongOrNull() else null
    }

    /** The editor another app's "Edit contact" opens: the copy the link names, else the whole contact. */
    fun editorFor(contactId: Long, rawId: Long?): Destination =
        if (rawId != null) PeopleRoutes.editRaw(contactId, rawId) else Routes.edit(id = contactId)

    private fun isVcard(type: String?) = type != null && (type.contains("vcard") || type == "text/directory")

    private fun go(e: NavEvent) = IntentTarget(e)

    /** The tile a long press came from, from the extra Android adds. */
    private fun tileComponent(intent: Intent): ComponentName? = runCatching {
        @Suppress("DEPRECATION") // The typed getter needs Android 13; this runs on older phones too.
        intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
    }.getOrNull()

    /** An encrypted vCard ([SealedVCard]), known by its name: its type is octet-stream, like any unknown file's. */
    private fun isSealedVcard(uri: Uri) = uri.lastPathSegment.orEmpty().endsWith(SealedVCard.EXTENSION, ignoreCase = true)

    /**
     * A shared label's update or invitation file: by its type, or by its name when the sending app didn't know the
     * type (many send any unknown file as octet-stream; the screen then checks what it really is, and passes an
     * encrypted vCard whose link doesn't show its name on to the import).
     */
    private fun isLabelFile(type: String?, uri: Uri): Boolean {
        val name = uri.lastPathSegment.orEmpty()
        return type == SharedLabelUpdates.MIME || type == OCTET_STREAM ||
            name.endsWith(SharedLabelUpdates.FILE_EXTENSION, ignoreCase = true) || name.endsWith(SharedLabelInvites.FILE_EXTENSION, ignoreCase = true)
    }

    private const val OCTET_STREAM = "application/octet-stream"

    private fun labelFile(uri: Uri) = IntentTarget(NavEvent.Route(SharedLabelRoutes.OpenFile), labelFile = uri)

    /** A link Parley may look up as a contact: Android's contacts provider only, never any other app's (or Parley's own). */
    private fun contactLink(uri: Uri, readable: (Uri) -> Boolean): Uri? =
        uri.takeIf { it.scheme == "content" && it.authority in CONTACT_AUTHORITIES && readable(it) }

    private val CONTACT_AUTHORITIES = setOf(ContactsContract.AUTHORITY, "contacts")

    /** A link to view or a number to dial: Parley's own links, a vCard or label file, a number, Recents or a contact. */
    private fun viewOrDial(intent: Intent, readable: (Uri) -> Boolean, typeOf: (Uri) -> String?): IntentTarget? {
        val data = intent.data
        if (data?.scheme == "parley") parleyLink(data)?.let { return it }
        if (data != null && data.scheme == "content" && readable(data)) sharedFile(data, intent.type ?: typeOf(data))?.let { return it }
        return when {
            data?.scheme == "tel" -> go(NavEvent.Tab(StartTab.KEYPAD, dial = data.schemeSpecificPart.orEmpty()))
            intent.type == "vnd.android.cursor.dir/calls" -> go(NavEvent.Tab(StartTab.RECENTS))
            intent.action == Intent.ACTION_DIAL -> go(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
            data != null -> contactLink(data, readable)?.let { IntentTarget(resolveContact = it) }
            else -> null
        }
    }

    /** A `parley://` link: a secure QR code, a simple-mode setup shared as a QR code, or a blocking template. */
    private fun parleyLink(data: Uri): IntentTarget? = when (data.host) {
        "qr" -> go(NavEvent.SecureQr(data))
        "simple" -> IntentTarget(NavEvent.Route(ExtrasRoutes.SimpleImport), simpleSetup = data)
        "template" -> IntentTarget(NavEvent.Route(BlockingRoutes.Templates), template = data)
        else -> null
    }

    /** A readable file another app opened with Parley ([type] its type): a vCard to import, or a shared label's file. */
    private fun sharedFile(data: Uri, type: String?): IntentTarget? = when {
        isVcard(type) || isSealedVcard(data) -> go(NavEvent.ImportVcf(data))
        isLabelFile(type, data) -> labelFile(data)
        else -> null
    }

    /**
     * [typeOf] reads a content URI's type (only asked for `content:` links without one). [readable] says whether Parley
     * may read a URI another app handed over (see [app.parley.security.SharedUris]): other apps' `content:` URIs only.
     * [fromParley]: the intent came through Parley's own entry ([OWN_ENTRY]); otherwise [INTERNAL_ACTIONS] open nothing.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per intent action Parley answers.
    fun resolve(
        intent: Intent,
        fromParley: Boolean,
        readable: (Uri) -> Boolean = { it.scheme == "content" },
        typeOf: (Uri) -> String?,
    ): IntentTarget? {
        val data = intent.data
        if (!fromParley && intent.action in INTERNAL_ACTIONS) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION") // The typed getter needs Android 13; this runs on older phones too.
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.takeIf(readable) ?: return null
                when {
                    isVcard(intent.type) || isSealedVcard(stream) -> go(NavEvent.ImportVcf(stream))
                    // A picture shared to Parley is searched for QR codes.
                    intent.type?.startsWith("image/") == true -> IntentTarget(NavEvent.Route(QrRoutes.Scan), qrImage = stream)
                    isLabelFile(intent.type, stream) -> labelFile(stream)
                    else -> null
                }
            }
            QUICK_CONTACT, QUICK_CONTACT_LEGACY -> data?.let { contactLink(it, readable) }?.let { IntentTarget(resolveContact = it) }
            SHOW_OR_CREATE -> data?.let { IntentTarget(showOrCreate = it) }
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> viewOrDial(intent, readable, typeOf)
            Intent.ACTION_CALL_BUTTON -> go(NavEvent.Tab(StartTab.RECENTS))
            Intent.ACTION_APPLICATION_PREFERENCES -> go(NavEvent.Route(Routes.Settings))
            ACTION_OPEN_BACKUP -> go(NavEvent.Route(Routes.Backup))
            ACTION_OPEN_BLOCKING -> go(NavEvent.Route(Routes.Blocking))
            ACTION_OPEN_SYNC -> go(NavEvent.Route(Routes.Sync))
            ACTION_EXPORT_CONTACTS -> go(NavEvent.Route(Routes.Export()))
            ACTION_OPEN_TEMPORARY -> go(NavEvent.Route(Routes.Temporary))
            ACTION_OPEN_LABEL ->
                go(NavEvent.Route(intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() }?.let(PeopleRoutes::label) ?: PeopleRoutes.Labels))
            ACTION_OPEN_HEALTH -> go(NavEvent.Route(Routes.Health))
            ACTION_OPEN_SITUATIONS -> go(NavEvent.Route(CallsRoutes.Page(CallsSubPage.SITUATIONS.name)))
            ACTION_ADD_CALL -> go(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
            ACTION_BULK_ADD -> go(NavEvent.Route(MessagingRoutes.BulkAdd))
            ACTION_PASTE_CONTACT -> intent.getStringExtra(EXTRA_PASTE_ID)?.takeIf { it.isNotEmpty() }?.let { go(NavEvent.Route(Routes.edit(paste = it))) }
            ACTION_SCAN_QR -> go(NavEvent.Route(QrRoutes.Scan))
            ACTION_RESCUE_CALL -> go(NavEvent.Route(SituationRoutes.RescueCall))
            // The Situation tile's long press, handed on by its exported activity: a public request (any app could send
            // it), so it opens only Rescue call's screen, behind the app lock like everything here.
            QS_TILE_PREFERENCES -> go(NavEvent.Route(SituationRoutes.RescueCall)).takeIf { tileComponent(intent)?.className == SITUATION_TILE }
            // The keep-in-touch digest opens the Circle (as the bar's extra tab while it's hidden).
            ACTION_SHOW_CIRCLE -> go(NavEvent.Tab(StartTab.CIRCLE))
            ACTION_SHOW_TO_CALL -> go(NavEvent.Route(ToCallRoutes.List))
            ACTION_OPEN_EXPORT -> intent.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotEmpty() }?.let { f ->
                val mime = intent.getStringExtra(EXTRA_MIME).orEmpty()
                IntentTarget(openExport = app.parley.jobs.UserJobs.Opener(f, mime, intent.getBooleanExtra(EXTRA_PRINT, false)))
            }
            ACTION_SHOW_MISSED -> IntentTarget(NavEvent.Tab(StartTab.RECENTS, missedOnly = true), missedSeen = true)
            ACTION_SHOW_CALLER -> {
                val id = intent.getLongExtra(EXTRA_CONTACT_ID, -1)
                val number = intent.getStringExtra(EXTRA_NUMBER)
                when {
                    id > 0 -> go(NavEvent.Contact(id))
                    !number.isNullOrBlank() -> go(NavEvent.History(number))
                    else -> null
                }
            }
            // The post-call card's "Block" (or "Unblock"), "Report" and remembered line for an unknown number.
            ACTION_POST_CALL -> intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }?.let { number ->
                when (intent.getStringExtra(EXTRA_POST_CALL_ACTION)) {
                    "BLOCK" -> IntentTarget(block = number)
                    "UNBLOCK" -> IntentTarget(unblock = number)
                    "SAVE" -> go(NavEvent.Route(Routes.edit(name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }, phone = number)))
                    "ADD_TO_CONTACT" -> go(NavEvent.Route(Routes.pick(number)))
                    "REPORT" -> IntentTarget(report = number)
                    // The number's history, where what Parley remembers about it offers its action.
                    "NUMBER_MEMORY" -> go(NavEvent.History(number))
                    else -> null
                }
            }
            Intent.ACTION_EDIT -> data?.let { contactLink(it, readable) }?.let { IntentTarget(editContact = it) }
            // The sheet itself checks the app again and asks before anything is allowed.
            // A request left by the removed lookup provider (it said directory=false) answers nothing.
            ACTION_APPROVE_PRIVATE_NAME -> intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() && !isLegacyLookupRequest(intent) }?.let {
                IntentTarget(approvePrivateName = it)
            }
            Intent.ACTION_INSERT -> go(NavEvent.NewContact(InsertPrefill.from(intent)))
            Intent.ACTION_INSERT_OR_EDIT -> go(NavEvent.InsertOrEdit(InsertPrefill.from(intent)))
            else -> null
        }
    }
}
