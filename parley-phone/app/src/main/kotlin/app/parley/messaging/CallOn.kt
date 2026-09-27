package app.parley.messaging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import app.parley.common.CallRoute
import app.parley.common.CallRoutes
import app.parley.common.MessengerApp
import app.parley.common.MessengerLinks
import app.parley.common.PhoneNumbers
import app.parley.common.ReachApp
import app.parley.common.ReachGroups
import app.parley.container
import app.parley.data.MessengerAction
import app.parley.data.Messengers
import app.parley.ui.contact.ContactMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.ui.contact.AppBadge
import app.parley.ui.contact.ReachActionButton

/** V34: a section title inside the "Message or call on…" sheets ("Message on", "Call on"). */
@Composable
fun SheetSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * V34: an app that added call rows for this number: its name, then Voice and Video buttons for the calls it offers
 * (the other is left out rather than shown disabled). "Usual" fills the button.
 */
@Composable
fun DirectCallItem(
    label: String, voice: Boolean, video: Boolean, voiceUsual: Boolean = false, videoUsual: Boolean = false,
    packageName: String? = null,
    onVoice: () -> Unit, onVideo: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                stringResource(
                    when {
                        voice && video -> R.string.v34msg_call_direct
                        video -> R.string.v34msg_call_video_only
                        else -> R.string.v34msg_call_voice_only
                    },
                ),
            )
        },
        leadingContent = { AppBadge(label, packageName = packageName) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (voice) ReachActionButton(Icons.Rounded.Call, stringResource(R.string.v34msg_voice_on_app, label), voiceUsual, onVoice)
                if (video) ReachActionButton(Icons.Rounded.Videocam, stringResource(R.string.v34msg_video_on_app, label), videoUsual, onVideo)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * V34: an installed chat app without a call row for this number. It says what happens: the chat opens, and the
 * call is placed from there. [sub] replaces the explanation (e.g. why it can't open for this number).
 */
@Composable
fun ViaChatCallItem(label: String, enabled: Boolean = true, sub: String? = null, packageName: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.v34msg_call_via_chat, label)) },
        supportingContent = { Text(sub ?: stringResource(R.string.v34msg_call_via_chat_sub, label)) },
        leadingContent = { AppBadge(label, packageName = packageName) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = if (enabled) ListItemDefaults.colors(containerColor = Color.Transparent)
        else ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/**
 * V34: "Call on" for a number that may not be saved. Each installed chat app offers its own voice and video rows
 * when it has linked this number (a saved or temporary visible contact the app has synced), otherwise it opens the
 * chat with a hint to use the chat's call button: WhatsApp, Signal, Telegram and Viber have no link that starts a
 * call to a number. Other apps' call rows (Threema, Meet…) for the number follow. An unsaved number can be saved as
 * a temporary visible contact so the apps can add call rows once they sync. [known]: saved as a contact or a private
 * one, null while that is still being looked up.
 */
@Composable
internal fun CallOnSection(
    number: String, e164: String?, unavailable: String?, installed: List<MessengerApp>, known: Boolean?, region: String,
    onLaunched: (MessengerApp?) -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val c = context.container
    val store = c.messaging
    val scope = rememberCoroutineScope()
    var actions by remember(number) { mutableStateOf<List<MessengerAction>>(emptyList()) }
    var askSave by remember { mutableStateOf(false) }
    LaunchedEffect(number, e164) {
        actions = withContext(Dispatchers.IO) { runCatching { Messengers.actionsForNumber(context, e164 ?: number) }.getOrDefault(emptyList()) }
    }
    val lastCall = remember { store.lastCallApp }
    val chatApps = remember(installed) {
        installed.filter { it != MessengerApp.TELEGRAM_WEB || MessengerApp.TELEGRAM !in installed }.sortedByDescending { it.packageName == lastCall }
    }
    val rows = actions.map { it.row }
    val isContact = known == true
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun start(id: Long) {
        val a = actions.firstOrNull { it.dataId == id } ?: return
        val err = ContactMessaging.start(context, a.intent(), a.appName)
        if (err != null) return toast(err)
        store.lastCallApp = a.accountType
        onLaunched(MessengerApp.forPackage(a.accountType))
    }
    fun viaChat(app: MessengerApp) {
        val link = e164?.let { MessengerLinks.build(app, it) } ?: return toast(unavailable ?: res.getString(R.string.msg_cant_open_number))
        val err = MessengerLauncher.open(context, link, app)
        if (err != null) return toast(err)
        toast(res.getString(R.string.v34msg_call_via_chat_hint))
        store.lastCallApp = app.packageName
        store.recordOpened(number, app, app.label, isContact)
        onLaunched(app)
    }

    SheetSection(stringResource(R.string.v34msg_section_call))
    chatApps.forEach { app ->
        val voice = (CallRoutes.forApp(app, video = false, rows) as? CallRoute.Row)?.row
        val video = (CallRoutes.forApp(app, video = true, rows) as? CallRoute.Row)?.row
        if (voice == null && video == null) {
            ViaChatCallItem(app.label, enabled = unavailable == null, sub = unavailable, packageName = app.packageName) { viaChat(app) }
        } else {
            DirectCallItem(app.label, voice != null, video != null, packageName = app.packageName, onVoice = { voice?.let { start(it.dataId) } }, onVideo = { video?.let { start(it.dataId) } })
        }
    }
    val chatKeys = chatApps.flatMap { ReachApp.forMessengerApp(it)?.accountTypes ?: listOf(it.packageName) }.toSet()
    val others = ReachGroups.group(rows) { a, b -> PhoneNumbers.same(a, b, region) }.filter { it.canCall && it.appKey !in chatKeys }.distinctBy { it.appKey }
    others.forEach { g ->
        DirectCallItem(g.appLabel, g.voice != null, g.video != null, packageName = g.appKey, onVoice = { g.voice?.let { start(it.dataId) } }, onVideo = { g.video?.let { start(it.dataId) } })
    }
    if (chatApps.isEmpty() && others.isEmpty()) {
        Text(stringResource(R.string.v34msg_no_call_apps), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    // v3.4 review #3: only once the lookup says the number is neither a contact nor a private one.
    if (known == false && chatApps.isNotEmpty()) {
        ListItem(
            headlineContent = { Text(pluralStringResource(R.plurals.v34msg_save_for_calls, TemporaryContact.DEFAULT_DAYS, TemporaryContact.DEFAULT_DAYS)) },
            supportingContent = { Text(stringResource(R.string.v34msg_save_for_calls_sub)) },
            leadingContent = { Icon(Icons.Rounded.Timer, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(role = Role.Button) { askSave = true },
        )
    }
    if (askSave) {
        // F5: private unless the user ticks "visible"; the notice says plainly that apps only see it when ticked.
        TemporaryNameDialog(
            TemporaryContact.suggestedName(number, null, region), notice = stringResource(R.string.v34msg_save_for_calls_notice),
            initialVisible = false, onDismiss = { askSave = false },
        ) { name, visible ->
            askSave = false
            scope.launch {
                // Checked again right before saving: never a second copy of a contact or a private person's number.
                val unknown = withContext(Dispatchers.IO) { runCatching { ChatThenDecide.stillUnknown(c, number) && (e164 == null || ChatThenDecide.stillUnknown(c, e164)) }.getOrDefault(false) }
                if (!unknown) return@launch toast(res.getString(R.string.v34msg_save_already_known))
                val saved = withContext(Dispatchers.IO) { runCatching { TemporaryContact.save(c, e164 ?: number, name, private = !visible) }.getOrNull() }
                toast(TemporaryContact.savedMessage(res, saved))
            }
        }
    }
}
