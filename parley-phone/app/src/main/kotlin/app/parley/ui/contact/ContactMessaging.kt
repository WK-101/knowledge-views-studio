package app.parley.ui.contact

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.MessengerApp
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.common.people.HandleLink
import app.parley.common.people.MessageRoute
import app.parley.common.people.MessageRoutes
import app.parley.common.people.MessengerPrefs
import app.parley.common.circle.InteractionChannel
import kotlinx.coroutines.launch
import app.parley.container
import app.parley.data.MessengerAction
import app.parley.data.PhoneEnv
import app.parley.messaging.MessengerLauncher
import app.parley.ui.Bidi

/**
 * M6/M7: how to reach one person by message: their numbers, the messenger rows apps added for them (none for
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
    /** R3: the saved contact this is (for "Log this?" when they're in the Circle); null for private contacts. */
    val lookupKey: String? = null,
    val contactId: Long? = null,
) {
    /** Account types of messengers with a chat row for this person. */
    val linked: Set<String> get() = messengers.filter { !it.isCall && !it.isVideo }.map { it.accountType }.toSet()
    val videoRows: List<MessengerAction> get() = messengers.filter { it.isVideo }
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
            val row = r.messengers.firstOrNull { it.accountType == route.accountType && !it.isCall && !it.isVideo }
            if (row == null) context.getString(R.string.msg_app_lost_contact) else start(context, row.intent(), row.appName).also { if (it == null) offerLog(context, r, InteractionChannel.forPackage(route.accountType)) }
        }
        is MessageRoute.MessengerLink -> {
            val e164 = NumberText.toE164(route.number, PhoneEnv.countryIso(context))
            val link = e164?.let { MessengerLinks.build(route.app, it) }
            if (link == null) {
                app.parley.messaging.MessagingText.unavailable(context.resources, e164)
            } else {
                MessengerLauncher.open(context, link, route.app).also { if (it == null) record(context, route.number, route.app, route.app.label) }
                    .also { if (it == null) offerLog(context, r, InteractionChannel.forMessenger(route.app.messenger)) }
            }
        }
        MessageRoute.Ask -> null
    }

    /**
     * R3: Parley just opened [channel] for [r]. If they're in the Circle, "Log this?" is asked when you come back
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
        if (err == null && !(m.isCall && !m.isVideo)) offerLog(context, r, if (m.isVideo) InteractionChannel.VIDEO else InteractionChannel.forPackage(m.accountType))
    }

    /** "Last messaged via…" for the number (private numbers are never recorded, see MessagingStore). */
    private fun record(context: Context, number: String, app: MessengerApp?, label: String) {
        runCatching {
            val store = context.container.messaging
            store.lastApp = app?.packageName ?: store.lastApp
            store.recordOpened(number, app, label, isContact = true)
        }
    }

    fun start(context: Context, intent: Intent, appName: String): String? = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) {
        context.getString(R.string.msg_app_unavailable, appName)
    } catch (_: SecurityException) {
        context.getString(R.string.msg_app_unavailable, appName)
    }

    /**
     * I1: opens a handle link. An app that handles it directly is used with its package; otherwise the system
     * chooser. A web link ([HandleLink.isWeb]) with no app to take it returns false so the caller can ask first:
     * nothing ever opens a browser without the user agreeing.
     */
    fun openHandle(context: Context, link: HandleLink, confirmedWeb: Boolean = false): Boolean {
        val uri = Uri.parse(link.uri)
        val pm = context.packageManager
        for (pkg in link.packages) {
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(pm) != null && runCatching { context.startActivity(i) }.isSuccess) return true
        }
        if (link.isWeb && !confirmedWeb) return false
        val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.msg_no_app_opens), Toast.LENGTH_SHORT).show()
        }
        return true
    }
}

private data class SheetRow(val key: String, val label: String, val sub: String?, val enabled: Boolean, val launch: () -> String?, val remember: MessengerPrefs.() -> MessengerPrefs)

/**
 * M6: "Message on…" for a saved or private contact: pick the number, then an installed messenger (opened directly
 * by number, or by the app's own row when it has linked the person), another messenger's row, or SMS. The choice is
 * remembered for this person when "Always use this" stays ticked ([onRemember]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactMessageSheet(r: Reach, onDismiss: () -> Unit, onRemember: (MessengerPrefs) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val installed = remember { MessengerLauncher.installed(context) }
    var number by rememberSaveable { mutableStateOf(r.prefs.number?.takeIf { n -> r.numbers.any { it.first == n } } ?: r.defaultNumber ?: r.numbers.firstOrNull()?.first) }
    var remember by rememberSaveable { mutableStateOf(true) }
    val region = remember { PhoneEnv.countryIso(context) }
    val e164 = remember(number) { number?.let { NumberText.toE164(it, region) } }
    val unavailable = remember(e164) { app.parley.messaging.MessagingText.unavailable(res, e164) }
    val linked = r.linked

    val rows = buildList {
        // One row per installed messenger app that opens chats by number.
        installed.filter { it != MessengerApp.TELEGRAM_WEB || MessengerApp.TELEGRAM !in installed }.forEach { app ->
            val isLinked = app.packageName in linked
            val hint = when {
                isLinked -> res.getString(R.string.msg_app_has_contact, app.label)
                MessageRoutes.showUnlinkedHint(app.packageName, linked, installed.map { it.packageName }.toSet()) ->
                    res.getString(R.string.msg_app_cant_see, app.label)
                else -> null
            }
            add(
                SheetRow(
                    app.packageName, app.label, if (!isLinked && unavailable != null) unavailable else hint, isLinked || unavailable == null,
                    launch = {
                        val route = if (isLinked) MessageRoute.MessengerRow(app.packageName) else number?.let { MessageRoute.MessengerLink(app, it) } ?: MessageRoute.Ask
                        ContactMessaging.open(context, route, r)
                    },
                    remember = { copy(message = app.packageName) },
                ),
            )
        }
        // Chat rows of other messengers (Threema, Wire, Element…) that registered this person.
        r.messengers.filter { !it.isCall && !it.isVideo && MessengerApp.forPackage(it.accountType) == null }.distinctBy { it.accountType }.forEach { m ->
            add(SheetRow(m.accountType, m.appName, m.label.takeIf { it != m.appName }, true, launch = { ContactMessaging.startRow(context, r, m) }, remember = { copy(message = m.accountType) }))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.msg_message_on_title, r.name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            if (r.numbers.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.numbers.forEach { (n, label) ->
                        FilterChip(number == n, { number = n }, label = { Text(listOf(label, Bidi.ltr(n)).filter { it.isNotBlank() }.joinToString(stringResource(R.string.main_separator))) })
                    }
                }
            } else if (number != null) {
                Text(Bidi.ltr(e164?.let(NumberText::formatInternational) ?: number.orEmpty()), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            if (installed.isEmpty() && rows.isEmpty()) {
                Text(
                    stringResource(R.string.msg_no_chat_apps),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            fun done(err: String?, prefs: MessengerPrefs) {
                if (err != null) {
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    return
                }
                if (remember) onRemember(prefs.copy(number = number?.takeIf { it != r.defaultNumber }))
                onDismiss()
            }
            rows.forEach { row ->
                val chosen = r.prefs.message == row.key
                ListItem(
                    headlineContent = { Text(row.label) },
                    supportingContent = row.sub?.let { s -> { Text(s) } },
                    leadingContent = { Icon(if (row.key in linked) Icons.Rounded.Link else Icons.AutoMirrored.Rounded.Chat, null) },
                    trailingContent = if (chosen) ({ Text(stringResource(R.string.detail_usual), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }) else null,
                    colors = if (row.enabled) ListItemDefaults.colors(containerColor = Color.Transparent)
                    else ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
                    modifier = Modifier.clickable(enabled = row.enabled) { done(row.launch(), row.remember(r.prefs)) },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.msg_sms)) },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) },
                trailingContent = if (r.prefs.message == MessengerPrefs.SMS) ({ Text(stringResource(R.string.detail_usual), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }) else null,
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(enabled = number != null) {
                    done(number?.let { ContactMessaging.open(context, MessageRoute.Sms(it), r) }, r.prefs.copy(message = MessengerPrefs.SMS))
                },
            )
            Row(Modifier.fillMaxWidth().clickable { remember = !remember }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(remember, { remember = it })
                Text(stringResource(R.string.msg_always_use, r.name), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (r.isPrivate) Icons.Rounded.Lock else Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(if (r.isPrivate) R.string.msg_note_private else R.string.msg_note_contact),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** U3: "Video" with a messenger that offers video calls for this person; asks which once, then remembers. */
@Composable
fun VideoChooser(r: Reach, onDismiss: () -> Unit, onRemember: (MessengerPrefs) -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_video_with)) },
        text = {
            Column {
                r.videoRows.forEach { m ->
                    ListItem(
                        headlineContent = { Text(m.appName) },
                        supportingContent = { Text(m.label) },
                        leadingContent = { Icon(Icons.Rounded.Videocam, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            val err = ContactMessaging.startRow(context, r, m)
                            if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show() else onRemember(r.prefs.copy(video = m.accountType))
                            onDismiss()
                        },
                    )
                }
                Text(stringResource(R.string.msg_video_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

/** "Open matrix.to in your browser?" before a handle's web link goes to a browser (I1). */
@Composable
fun ConfirmWebLink(link: HandleLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_open_browser_title)) },
        text = { Text(stringResource(R.string.msg_open_browser_body, Uri.parse(link.uri).host ?: stringResource(R.string.msg_this_link))) },
        confirmButton = { TextButton({ onDismiss(); ContactMessaging.openHandle(context, link, confirmedWeb = true) }) { Text(stringResource(R.string.msg_open)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}
