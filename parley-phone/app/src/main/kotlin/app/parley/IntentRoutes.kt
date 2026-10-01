package app.parley

import android.content.Intent
import android.net.Uri
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.StartTab
import app.parley.messaging.MessagingRoutes
import app.parley.ui.Routes
import app.parley.ui.blocking.BlockingRoutes
import app.parley.ui.extras.ExtrasRoutes
import app.parley.ui.qr.QrRoutes

/**
 * What an intent reaching the main screen asks for: a `parley://` link, a `tel:` link, a launcher shortcut, a Quick
 * Settings tile, a notification's action, another app's Insert or Quick Contact. [event] is where to go; the other
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
    /** SHOW_OR_CREATE_CONTACT: open the matching contact or offer to create one. */
    val showOrCreate: Uri? = null,
    /** The post-call card's "Report" for a number. */
    val report: String? = null,
    /** Missed calls were opened: they count as seen. */
    val missedSeen: Boolean = false,
)

/** The mapping from intents to [IntentTarget]s; pure, so every old link and shortcut is tested to still resolve. */
object IntentRoutes {
    const val ACTION_ADD_CALL = "app.parley.ADD_CALL"

    /** "Save all…" from the number sheet; the text waits in [app.parley.messaging.MessagingInbox]. */
    const val ACTION_BULK_ADD = "app.parley.BULK_ADD"
    const val ACTION_OPEN_BACKUP = "app.parley.OPEN_BACKUP"

    /** Opens the Scan QR screen (launcher shortcut, Quick Settings tile). */
    const val ACTION_SCAN_QR = "app.parley.action.SCAN_QR"
    const val ACTION_OPEN_BLOCKING = "app.parley.OPEN_BLOCKING"

    /** Folder sync paused and waits for the user (its notification). */
    const val ACTION_OPEN_SYNC = "app.parley.OPEN_SYNC"

    /** Temporary contacts are due to be deleted and wait for your answer (its notification). */
    const val ACTION_OPEN_TEMPORARY = "app.parley.OPEN_TEMPORARY"
    const val QUICK_CONTACT = "android.provider.action.QUICK_CONTACT"
    const val QUICK_CONTACT_LEGACY = "com.android.contacts.action.QUICK_CONTACT"
    const val SHOW_OR_CREATE = "com.android.contacts.action.SHOW_OR_CREATE_CONTACT"
    const val ACTION_SHOW_MISSED = "app.parley.SHOW_MISSED"
    const val ACTION_SHOW_CIRCLE = "app.parley.SHOW_CIRCLE"
    const val ACTION_SHOW_CALLER = "app.parley.SHOW_CALLER"
    const val ACTION_POST_CALL = "app.parley.POST_CALL"
    const val EXTRA_POST_CALL_ACTION = "post_call_action"
    const val EXTRA_CONTACT_ID = "contact_id"
    const val EXTRA_NUMBER = "number"

    private fun isVcard(type: String?) = type != null && (type.contains("vcard") || type == "text/directory")

    private fun go(e: NavEvent) = IntentTarget(e)

    /**
     * [typeOf] reads a content URI's type (only asked for `content:` links without one). [readable] says whether Parley
     * may read a URI another app handed over (see [app.parley.security.SharedUris]): other apps' `content:` URIs only.
     */
    @Suppress("CyclomaticComplexMethod")
    fun resolve(intent: Intent, readable: (Uri) -> Boolean = { it.scheme == "content" }, typeOf: (Uri) -> String?): IntentTarget? {
        val data = intent.data
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.takeIf(readable) ?: return null
                when {
                    isVcard(intent.type) -> go(NavEvent.ImportVcf(stream))
                    // A picture shared to Parley is searched for QR codes.
                    intent.type?.startsWith("image/") == true -> IntentTarget(NavEvent.Route(QrRoutes.Scan), qrImage = stream)
                    else -> null
                }
            }
            QUICK_CONTACT, QUICK_CONTACT_LEGACY -> data?.let { IntentTarget(resolveContact = it) }
            SHOW_OR_CREATE -> data?.let { IntentTarget(showOrCreate = it) }
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> when {
                data?.scheme == "parley" && data.host == "qr" -> go(NavEvent.SecureQr(data))
                // A simple-mode setup shared as a QR code.
                data?.scheme == "parley" && data.host == "simple" -> IntentTarget(NavEvent.Route(ExtrasRoutes.SimpleImport), simpleSetup = data)
                data?.scheme == "parley" && data.host == "template" -> IntentTarget(NavEvent.Route(BlockingRoutes.Templates), template = data)
                data != null && data.scheme == "content" && readable(data) && isVcard(intent.type ?: typeOf(data)) -> go(NavEvent.ImportVcf(data))
                data?.scheme == "tel" -> go(NavEvent.Tab(StartTab.KEYPAD, dial = data.schemeSpecificPart.orEmpty()))
                intent.type == "vnd.android.cursor.dir/calls" -> go(NavEvent.Tab(StartTab.RECENTS))
                intent.action == Intent.ACTION_DIAL -> go(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
                data != null -> IntentTarget(resolveContact = data)
                else -> null
            }
            Intent.ACTION_CALL_BUTTON -> go(NavEvent.Tab(StartTab.RECENTS))
            Intent.ACTION_APPLICATION_PREFERENCES -> go(NavEvent.Route(Routes.Settings))
            ACTION_OPEN_BACKUP -> go(NavEvent.Route(Routes.Backup))
            ACTION_OPEN_BLOCKING -> go(NavEvent.Route(Routes.Blocking))
            ACTION_OPEN_SYNC -> go(NavEvent.Route(Routes.Sync))
            ACTION_OPEN_TEMPORARY -> go(NavEvent.Route(Routes.Temporary))
            ACTION_ADD_CALL -> go(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
            ACTION_BULK_ADD -> go(NavEvent.Route(MessagingRoutes.BulkAdd))
            ACTION_SCAN_QR -> go(NavEvent.Route(QrRoutes.Scan))
            // The keep-in-touch digest opens the Circle (as the bar's extra tab while it's hidden).
            ACTION_SHOW_CIRCLE -> go(NavEvent.Tab(StartTab.CIRCLE))
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
            // The post-call card's "Block" and "Report" for an unknown number.
            ACTION_POST_CALL -> intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }?.let { number ->
                when (intent.getStringExtra(EXTRA_POST_CALL_ACTION)) {
                    "BLOCK" -> go(NavEvent.Route(BlockingRoutes.rule(0, RuleKind.BLOCK, RuleType.EXACT, number)))
                    "REPORT" -> IntentTarget(report = number)
                    else -> null
                }
            }
            Intent.ACTION_INSERT -> go(NavEvent.NewContact(InsertPrefill.from(intent)))
            Intent.ACTION_INSERT_OR_EDIT -> go(NavEvent.InsertOrEdit(InsertPrefill.from(intent)))
            else -> null
        }
    }
}
