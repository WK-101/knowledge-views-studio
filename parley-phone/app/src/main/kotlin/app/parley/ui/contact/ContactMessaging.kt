package app.parley.ui.contact

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.MessengerApp
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.common.PhoneIdentity
import app.parley.common.ReachGroup
import app.parley.common.ReachGroups
import app.parley.common.ReachKind
import app.parley.common.ReachRow
import app.parley.common.people.HandleLink
import app.parley.common.people.MessageRoute
import app.parley.common.people.MessageRoutes
import app.parley.common.people.MessengerPrefs
import app.parley.common.circle.InteractionChannel
import app.parley.messaging.MessagingText
import app.parley.ui.startOrSay
import kotlinx.coroutines.launch
import app.parley.container
import app.parley.data.MessengerAction
import app.parley.data.PhoneEnv
import app.parley.messaging.MessengerLauncher
import app.parley.ui.ConfirmDialog

/**
 * How to reach one person by message: their numbers, the messenger rows apps added for them (none for
 * private contacts: no other app can see those), and the remembered choice.
 */
data class Reach(
    val name: String,
    /** (number, label) of every number. */
    val numbers: List<Pair<String, String>>,
    val defaultNumber: String?,
    val messengers: List<MessengerAction>,
    val prefs: MessengerPrefs,
    /** A private contact: nothing about them is written outside Parley's encrypted storage. */
    val isPrivate: Boolean = false,
    /** The saved contact this is (for "Log this?" when they're in the Circle); null for private contacts. */
    val lookupKey: String? = null,
    val contactId: Long? = null,
) {
    /** Account types of messengers with a chat row for this person. */
    val linked: Set<String> get() = messengers.filter { it.kind == ReachKind.MESSAGE }.map { it.accountType }.toSet()
    val videoRows: List<MessengerAction> get() = messengers.filter { it.kind == ReachKind.VIDEO }

    /** The messenger rows per app and number, for "Reach via apps" and the sheet's "Call on". */
    fun groups(region: String?): List<ReachGroup> = ReachGroups.group(messengers.map { it.row }) { a, b -> PhoneIdentity.same(a, b, region) }

    /** The action behind [row] (a row of [groups]). */
    fun action(row: ReachRow): MessengerAction? = messengers.firstOrNull { it.dataId == row.dataId }
}

object ContactMessaging {
    fun installed(context: Context): Set<String> = MessengerLauncher.installed(context).map { it.packageName }.toSet()

    /** What the Message button does now for [r]. */
    fun route(context: Context, r: Reach): MessageRoute =
        MessageRoutes.plan(r.prefs, r.linked, installed(context), r.numbers.map { it.first }, r.defaultNumber)

    /** Starts [route]; returns an error to show, or null. [MessageRoute.Ask] is the caller's to handle. */
    fun open(context: Context, route: MessageRoute, r: Reach): String? = when (route) {
        is MessageRoute.Sms -> MessengerLauncher.open(context, MessengerLinks.sms(route.number, NumberText.toE164(route.number, PhoneEnv.countryIso(context)), null, MessengerLauncher.smsPackage(context)), null)
            .also { if (it == null) record(context, route.number, null, "SMS") }
            .also { if (it == null) offerLog(context, r, InteractionChannel.SMS) }
        is MessageRoute.MessengerRow -> {
            val row = r.messengers.firstOrNull { it.accountType == route.accountType && it.kind == ReachKind.MESSAGE }
            if (row == null) context.getString(R.string.msg_app_lost_contact) else start(context, row.intent(), row.appName).also { if (it == null) offerLog(context, r, InteractionChannel.forPackage(route.accountType)) }
        }
        is MessageRoute.MessengerLink -> {
            val e164 = NumberText.toE164(route.number, PhoneEnv.countryIso(context))
            val link = e164?.let { MessengerLinks.build(route.app, it) }
            if (link == null) {
                MessagingText.unavailable(context.resources, e164)
            } else {
                MessengerLauncher.open(context, link, route.app).also { if (it == null) record(context, route.number, route.app, route.app.label) }
                    .also { if (it == null) offerLog(context, r, InteractionChannel.forMessenger(route.app.messenger)) }
            }
        }
        MessageRoute.Ask -> null
    }

    /**
     * Parley just opened [channel] for [r]. If they're in the Circle, "Log this?" is asked when you come back
     * (or logged at once, per Settings). Calls are never logged here: the call log already has them.
     */
    fun offerLog(context: Context, r: Reach, channel: InteractionChannel) {
        if (r.isPrivate) return
        val key = r.lookupKey?.takeIf { it.isNotEmpty() } ?: return
        val c = context.container
        c.scope.launch { runCatching { c.circle.onLaunched(key, r.contactId, r.name, channel) } }
    }

    /** [start] for a messenger row of [r] (chat or video), then R3's "Log this?". */
    fun startRow(context: Context, r: Reach, m: MessengerAction): String? = start(context, m.intent(), m.appName).also { err ->
        if (err == null && m.kind != ReachKind.VOICE) offerLog(context, r, if (m.isVideo) InteractionChannel.VIDEO else InteractionChannel.forPackage(m.accountType))
    }

    /** "Last messaged via…" for the number (private numbers are never recorded, see MessagingStore). */
    private fun record(context: Context, number: String, app: MessengerApp?, label: String) {
        runCatching {
            val store = context.container.messaging
            store.lastApp = app?.packageName ?: store.lastApp
            store.recordOpened(number, app, label, isContact = true)
        }
    }

    fun start(context: Context, intent: Intent, appName: String): String? =
        if (context.startOrSay(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) null else context.getString(R.string.msg_app_unavailable, appName)

    /**
     * Opens a handle link. An app that handles it directly is used with its package; otherwise the system
     * chooser. A web link ([HandleLink.isWeb]) with no app to take it returns false so the caller can ask first:
     * nothing ever opens a browser without the user agreeing.
     */
    fun openHandle(context: Context, link: HandleLink, confirmedWeb: Boolean = false): Boolean {
        val uri = Uri.parse(link.uri)
        val pm = context.packageManager
        for (pkg in link.packages) {
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(pm) != null && context.startOrSay(i)) return true
        }
        if (link.isWeb && !confirmedWeb) return false
        val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startOrSay(chooser, context.getString(R.string.msg_no_app_opens))
        return true
    }
}

/** "Open matrix.to in your browser?" before a handle's web link goes to a browser. */
@Composable
fun ConfirmWebLink(link: HandleLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ConfirmDialog(
        title = stringResource(R.string.msg_open_browser_title),
        text = stringResource(R.string.msg_open_browser_body, Uri.parse(link.uri).host ?: stringResource(R.string.msg_this_link)),
        confirmLabel = stringResource(R.string.msg_open),
        onConfirm = { onDismiss(); ContactMessaging.openHandle(context, link, confirmedWeb = true) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.main_cancel),
    )
}
