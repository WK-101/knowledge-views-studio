package app.parley.messaging

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.CallOnEntry
import app.parley.common.Messenger
import app.parley.common.MessengerApp
import app.parley.common.MessengerLinks
import app.parley.common.cards.ShareMethod
import app.parley.ui.people.cards.CardSharing
import app.parley.common.NumberText
import app.parley.common.PhoneNumbers
import app.parley.common.ReachGroup
import app.parley.common.ReachGroups
import app.parley.common.ReachKind
import app.parley.common.ReachPlan
import app.parley.common.people.MessageRoute
import app.parley.common.people.MessageRoutes
import app.parley.common.people.MessengerPrefs
import app.parley.container
import app.parley.data.MessengerAction
import app.parley.data.Messengers
import app.parley.data.PhoneEnv
import app.parley.ui.Bidi
import app.parley.ui.people.rememberCardForSending
import app.parley.ui.contact.AppBadge
import app.parley.ui.contact.ContactMessaging
import app.parley.ui.contact.Reach
import app.parley.ui.contact.ReachActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.ParleySheet
import app.parley.ui.showMessage

/**
 * Who "Message or call on…" is for. Every surface opens the same sheet, with the same layout: the number, Call via
 * the phone first, then "Message on" (chat apps and SMS), then "Call on" (calls in apps).
 */
sealed interface ReachTarget {
    /** A number that may not be saved (Recents, keypad, a notification, a scanned code, shared text). */
    data class Number(val number: String, val accountId: String? = null) : ReachTarget

    /**
     * A saved or private contact: every number, the rows apps added for them, and the remembered way. [onRemember]
     * stores the choice when "Always use this" stays ticked.
     */
    data class Person(val reach: Reach, val onRemember: (MessengerPrefs) -> Unit) : ReachTarget
}

/**
 * "Message or call on…" as a bottom sheet. [onCall] adds Call via the phone first (null where calling makes no
 * sense, e.g. during a call); calling closes the sheet. [onLaunched] runs after an app was opened (null app: SMS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReachSheet(
    target: ReachTarget,
    onDismiss: () -> Unit,
    onCall: ((String) -> Unit)? = null,
    onLaunched: (MessengerApp?) -> Unit = { onDismiss() },
) {
    ParleySheet(onDismissRequest = onDismiss) {
        ReachSheetContent(target, onCall = onCall?.let { call -> { n -> onDismiss(); call(n) } }, onLaunched = onLaunched)
    }
}

/** The sheet's content, for hosts that bring their own sheet (the number window over other apps). */
@Composable
fun ReachSheetContent(target: ReachTarget, onCall: ((String) -> Unit)? = null, onLaunched: (MessengerApp?) -> Unit) {
    when (target) {
        is ReachTarget.Number -> NumberReach(target.number, target.accountId, onCall, onLaunched)
        is ReachTarget.Person -> PersonReach(target.reach, target.onRemember, onCall, onLaunched)
    }
}

// ---------------------------------------------------------------- Shared layout and rows

/**
 * The one layout: title, [header] (whose number, the country), Call via the phone, [beforeMessages] (a draft),
 * "Message on" [messages], "Call on" [calls], then [footer].
 */
@Composable
private fun ReachLayout(
    callNumber: String?,
    onCall: ((String) -> Unit)?,
    header: @Composable ColumnScope.() -> Unit,
    beforeMessages: @Composable ColumnScope.() -> Unit = {},
    messages: @Composable ColumnScope.() -> Unit,
    calls: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(stringResource(R.string.reach_message_or_call_on), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        header()
        // Calling is the first, primary action; the number is dialled as given (the SIM's country applies).
        if (onCall != null && callNumber != null) CallFirstButton(callNumber) { onCall(callNumber) }
        beforeMessages()
        SheetSection(stringResource(R.string.reach_section_message))
        messages()
        SheetSection(stringResource(R.string.reach_section_call))
        calls()
        footer()
    }
}

/** A section title inside the sheet ("Message on", "Call on"). */
@Composable
fun SheetSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun UsualTag() {
    Text(stringResource(R.string.detail_usual), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/**
 * One chat app under "Message on": the app's badge (the same one the contact page uses), its name, why it can't open
 * or what happens, and "Usual" when it's the remembered way. [menu] adds a ⋮ with more actions (Telegram's profile).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageAppItem(
    label: String, packageName: String?, sub: String?, enabled: Boolean, usual: Boolean, onClick: () -> Unit,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null, menuLabel: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = sub?.let { s -> { Text(s) } },
        leadingContent = { AppBadge(label, packageName = packageName) },
        trailingContent = when {
            menu != null && enabled -> ({
                Box {
                    IconButton({ open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.msg_more_actions_for, label)) }
                    DropdownMenu(open, { open = false }) { menu { open = false } }
                }
            })
            usual -> ({ UsualTag() })
            else -> null
        },
        colors = if (enabled) ListItemDefaults.colors(containerColor = Color.Transparent)
        else ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
        modifier = Modifier.combinedClickable(
            enabled = enabled, role = Role.Button, onClick = onClick,
            onLongClick = if (menu != null) ({ open = true }) else null,
            onLongClickLabel = if (menu != null) menuLabel else null,
        ),
    )
}

@Composable
private fun SmsItem(usual: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.msg_sms)) },
        leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null, Modifier.padding(horizontal = 8.dp)) },
        trailingContent = if (usual) ({ UsualTag() }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/**
 * An app that added call rows for this number: its name, then Voice and Video buttons for the calls it offers
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
                        voice && video -> R.string.reach_call_direct
                        video -> R.string.reach_call_video_only
                        else -> R.string.reach_call_voice_only
                    },
                ),
            )
        },
        leadingContent = { AppBadge(label, packageName = packageName) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (voice) ReachActionButton(Icons.Rounded.Call, stringResource(R.string.reach_voice_on_app, label), voiceUsual, onVoice)
                if (video) ReachActionButton(Icons.Rounded.Videocam, stringResource(R.string.reach_video_on_app, label), videoUsual, onVideo)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * An installed chat app without a call row for this number. It says what happens: the chat opens, and the call is
 * placed from there. [sub] replaces the explanation (e.g. why it can't open for this number).
 */
@Composable
fun ViaChatCallItem(label: String, enabled: Boolean = true, sub: String? = null, packageName: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.reach_call_via_chat, label)) },
        supportingContent = { Text(sub ?: stringResource(R.string.reach_call_via_chat_sub, label)) },
        leadingContent = { AppBadge(label, packageName = packageName) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = if (enabled) ListItemDefaults.colors(containerColor = Color.Transparent)
        else ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/** The "Call on" rows for [entries] ([ReachPlan.callOn]), or a line saying no app for calls is installed. */
@Composable
private fun CallOnRows(
    entries: List<CallOnEntry>,
    viaChatEnabled: (MessengerApp) -> Boolean,
    viaChatSub: (MessengerApp) -> String?,
    usual: (ReachGroup, video: Boolean) -> Boolean,
    onStart: (ReachGroup, video: Boolean) -> Unit,
    onViaChat: (MessengerApp) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(stringResource(R.string.reach_no_call_apps), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    entries.forEach { e ->
        when (e) {
            is CallOnEntry.ViaChat -> {
                val enabled = viaChatEnabled(e.app)
                ViaChatCallItem(e.app.label, enabled = enabled, sub = if (enabled) null else viaChatSub(e.app), packageName = e.app.packageName) { onViaChat(e.app) }
            }
            is CallOnEntry.Direct -> {
                val g = e.group
                DirectCallItem(
                    g.appLabel, voice = g.voice != null, video = g.video != null, packageName = g.appKey,
                    voiceUsual = usual(g, false), videoUsual = usual(g, true),
                    onVoice = { onStart(g, false) }, onVideo = { onStart(g, true) },
                )
            }
        }
    }
}

@Composable
private fun FooterNote(icon: ImageVector, text: String) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- A saved or private person

/**
 * Pick the number, then an installed chat app (its own row when it has linked the person, otherwise by
 * number), another app's chat row, or SMS; "Call on" lists the calls apps added for that number and, for chat apps
 * that didn't, their chat (no app offers a link that starts a call to a number). A message choice and a call or
 * video choice are remembered separately (MessengerPrefs).
 */
@Composable
private fun PersonReach(r: Reach, onRemember: (MessengerPrefs) -> Unit, onCall: ((String) -> Unit)?, onLaunched: (MessengerApp?) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val store = context.container.messaging
    val installed = remember { MessengerLauncher.installed(context) }
    var number by rememberSaveable { mutableStateOf(r.prefs.number?.takeIf { n -> r.numbers.any { it.first == n } } ?: r.defaultNumber ?: r.numbers.firstOrNull()?.first) }
    var remember by rememberSaveable { mutableStateOf(true) }
    val region = remember { PhoneEnv.countryIso(context) }
    val e164 = remember(number) { number?.let { NumberText.toE164(it, region) } }
    val unavailable = remember(e164) { MessagingText.unavailable(res, e164) }
    val linked = r.linked
    val chatApps = remember(installed) { ReachPlan.chatApps(installed, store.lastApp) }
    val installedPackages = remember(installed) { installed.map { it.packageName }.toSet() }
    val calls = remember(number, r.messengers, chatApps) {
        val same = { a: String, b: String -> PhoneNumbers.same(a, b, region) }
        ReachPlan.callOn(chatApps, ReachGroups.forNumber(r.groups(region), number, same))
    }
    // Chat rows of apps that open no chat by number (Threema, Wire, Element…) and registered this person.
    val otherChats = r.messengers.filter { m -> m.kind == ReachKind.MESSAGE && chatApps.none { it.packageName == m.accountType || it.entry == m.app } }
        .distinctBy { it.accountType }

    fun done(app: MessengerApp?, err: String?, prefs: MessengerPrefs, withNumber: Boolean = true) {
        if (err != null) {
            showMessage(context, err, long = true)
            return
        }
        if (remember) onRemember(if (withNumber) prefs.copy(number = number?.takeIf { it != r.defaultNumber }) else prefs)
        onLaunched(app)
    }
    fun openChat(app: MessengerApp): String? {
        val key = ReachPlan.linkedKey(app, linked)
        val route = if (key != null) MessageRoute.MessengerRow(key) else number?.let { MessageRoute.MessengerLink(app, it) } ?: MessageRoute.Ask
        return ContactMessaging.open(context, route, r)
    }

    ReachLayout(
        callNumber = number, onCall = onCall,
        header = {
            Text(r.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
            if (r.numbers.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.numbers.forEach { (n, label) ->
                        FilterChip(number == n, { number = n }, label = { Text(listOf(label, Bidi.ltr(n)).filter { it.isNotBlank() }.joinToString(stringResource(R.string.main_separator))) })
                    }
                }
            } else if (number != null) {
                Text(Bidi.ltr(e164?.let(NumberText::formatInternational) ?: number.orEmpty()), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
        },
        messages = {
            if (chatApps.isEmpty() && otherChats.isEmpty()) {
                Text(stringResource(R.string.msg_no_chat_apps), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            chatApps.forEach { app ->
                val key = ReachPlan.linkedKey(app, linked)
                val sub = when {
                    key != null -> res.getString(R.string.msg_app_has_contact, app.label)
                    unavailable != null -> unavailable
                    MessageRoutes.showUnlinkedHint(app.packageName, linked, installedPackages) -> res.getString(R.string.msg_app_cant_see, app.label)
                    else -> null
                }
                val prefKey = key ?: app.packageName
                MessageAppItem(
                    app.label, app.packageName, sub, enabled = key != null || unavailable == null,
                    usual = r.prefs.message == prefKey || r.prefs.message == app.packageName,
                    onClick = { done(app, openChat(app), r.prefs.copy(message = prefKey)) },
                )
            }
            otherChats.forEach { m ->
                MessageAppItem(
                    m.appName, m.packageName ?: m.accountType, m.label.takeIf { it != m.appName }, enabled = true, usual = r.prefs.message == m.accountType,
                    onClick = { done(null, ContactMessaging.startRow(context, r, m), r.prefs.copy(message = m.accountType)) },
                )
            }
            SmsItem(usual = r.prefs.message == MessengerPrefs.SMS, enabled = number != null) {
                done(null, number?.let { ContactMessaging.open(context, MessageRoute.Sms(it), r) }, r.prefs.copy(message = MessengerPrefs.SMS))
            }
        },
        calls = {
            CallOnRows(
                calls,
                viaChatEnabled = { app -> ReachPlan.linkedKey(app, linked) != null || unavailable == null },
                viaChatSub = { unavailable },
                usual = { g, video -> (if (video) g.video else g.voice)?.let { row -> if (video) r.prefs.video == row.appKey else r.prefs.call == row.appKey } == true },
                onStart = { g, video ->
                    val row = if (video) g.video else g.voice
                    val m = row?.let(r::action)
                    if (m != null) {
                        val next = if (video) r.prefs.copy(video = m.accountType) else r.prefs.copy(call = m.accountType)
                        done(MessengerApp.forPackage(m.accountType), ContactMessaging.startRow(context, r, m), next, withNumber = false)
                    }
                },
                onViaChat = { app ->
                    val err = openChat(app)
                    if (err == null) showMessage(context, res.getString(R.string.reach_call_via_chat_hint), long = true)
                    done(app, err, r.prefs, withNumber = false)
                },
            )
        },
        footer = {
            Row(Modifier.fillMaxWidth().clickable { remember = !remember }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(remember, { remember = it })
                Text(stringResource(R.string.msg_always_use, r.name), style = MaterialTheme.typography.bodyMedium)
            }
            FooterNote(if (r.isPrivate) Icons.Rounded.Lock else Icons.Rounded.Info, stringResource(if (r.isPrivate) R.string.msg_note_private else R.string.msg_note_contact))
        },
    )
}

// ---------------------------------------------------------------- A number

/**
 * A number that may not be saved, in international form (with the SIM's country; the chip changes it), an
 * optional message ("Send my details"), the installed chat apps (last used first) and SMS; "Call on" offers each chat
 * app's own call rows when it has linked this number, otherwise its chat, and other apps' call rows. An unsaved
 * number can be saved as a temporary contact so apps can add call rows once they sync.
 */
@Composable
private fun NumberReach(number: String, accountId: String?, onCall: ((String) -> Unit)?, onLaunched: (MessengerApp?) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val c = context.container
    val store = c.messaging
    val scope = rememberCoroutineScope()
    // The country of the SIM that took the call, which the user can override for this number with the chip.
    val simRegion = remember(accountId) { PhoneEnv.countryIso(context, accountId) }
    var regionOverride by rememberSaveable(number) { mutableStateOf<String?>(null) }
    var pickCountry by remember { mutableStateOf(false) }
    val region = regionOverride ?: simRegion
    val e164 = remember(number, region) { NumberText.toE164(number, region) }
    val unavailable = remember(e164) { MessagingText.unavailable(res, e164) }
    val nationalForm = remember(number) { !PhoneNumbers.clean(number).startsWith("+") }
    val installed = remember { MessengerLauncher.installed(context) }
    val chatApps = remember(installed) { ReachPlan.chatApps(installed, store.lastApp) }
    val callApps = remember(installed) { ReachPlan.chatApps(installed, store.lastCallApp) }
    // "Send my details" uses My card's name and first number.
    val myCard = rememberCardForSending(c.people)
    var draft by rememberSaveable { mutableStateOf("") }
    var editDetails by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    // Re-checked before any offer, so a quick tap before the lookup finishes is harmless. [known] is null until the
    // contact and private lookups finish (the "save for calls" row waits for it).
    var known by remember(number) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(number) {
        known = withContext(Dispatchers.IO) { runCatching { !ChatThenDecide.stillUnknown(c, number) }.getOrDefault(true) }
    }
    var actions by remember(number) { mutableStateOf<List<MessengerAction>>(emptyList()) }
    LaunchedEffect(number, e164) {
        actions = withContext(Dispatchers.IO) { runCatching { Messengers.actionsForNumber(context, e164 ?: number) }.getOrDefault(emptyList()) }
    }
    val calls = remember(actions, callApps, region) {
        ReachPlan.callOn(callApps, ReachGroups.group(actions.map { it.row }) { a, b -> PhoneNumbers.same(a, b, region) })
    }
    val isContact = known == true
    fun toast(text: String) = showMessage(context, text, long = true)

    /** "Send my details" went out (the draft still carries your number): it goes into My card › Shared with (I22). */
    fun sharedDetails() {
        val mine = myCard.firstNumber.orEmpty()
        if (mine.isNotBlank() && draft.contains(mine)) CardSharing.record(c, "", e164 ?: number, ShareMethod.SEND_DETAILS, listOf(mine))
    }

    fun launch(app: MessengerApp) {
        val link = e164?.let { MessengerLinks.build(app, it, draft) } ?: return
        if (draft.isNotBlank() && !app.takesText) {
            val clip = ClipData.newPlainText("message", draft)
            // Keep the draft out of clipboard previews and keyboard suggestions (Android 13+).
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            toast(res.getString(R.string.msg_copied_paste))
        }
        val error = MessengerLauncher.open(context, link, app)
        if (error != null) return toast(error)
        store.lastApp = app.packageName
        store.recordOpened(number, app, app.label, isContact)
        sharedDetails()
        onLaunched(app)
    }
    fun startRow(g: ReachGroup, video: Boolean) {
        val id = (if (video) g.video else g.voice)?.dataId ?: return
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
        toast(res.getString(R.string.reach_call_via_chat_hint))
        store.lastCallApp = app.packageName
        store.recordOpened(number, app, app.label, isContact)
        onLaunched(app)
    }

    ReachLayout(
        callNumber = number, onCall = onCall,
        header = {
            Text(
                Bidi.ltr(e164?.let(NumberText::formatInternational) ?: number),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (nationalForm) {
                // A national number is read with this country; tap to change it (e.g. the call came in abroad).
                AssistChip(
                    onClick = { pickCountry = true },
                    label = { Text(stringResource(if (regionOverride == null) R.string.num_country else R.string.msg_country_changed, countryLabel(region))) },
                    leadingIcon = { Icon(Icons.Rounded.Public, null) },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        },
        beforeMessages = {
            if (unavailable != null) {
                Text(
                    stringResource(R.string.msg_only_sms),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            OutlinedTextField(
                draft, { draft = it },
                label = { Text(stringResource(R.string.msg_optional)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                maxLines = 4,
            )
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {
                        val text = MessagingText.myDetails(res, myCard.name, myCard.firstNumber)
                        if (text == null) editDetails = true else draft = text
                    },
                    label = { Text(stringResource(R.string.msg_send_details)) },
                    leadingIcon = { Icon(Icons.Rounded.Badge, null) },
                )
                if (myCard.name.isNotEmpty() || myCard.firstNumber != null) {
                    TextButton({ editDetails = true }) { Text(stringResource(R.string.msg_edit_details)) }
                }
            }
        },
        messages = {
            if (chatApps.isEmpty()) {
                Text(stringResource(R.string.msg_no_chat_apps), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
            chatApps.forEach { app ->
                // Only enabled when a link can actually be built; otherwise the reason is shown.
                val sub = when {
                    unavailable != null -> unavailable
                    draft.isNotBlank() && !app.takesText -> stringResource(R.string.msg_will_copy)
                    else -> null
                }
                // Telegram rows also open the person's profile (long-press or the ⋮ button).
                val telegram = app.messenger == Messenger.TELEGRAM
                MessageAppItem(
                    app.label, app.packageName, sub, enabled = unavailable == null, usual = false, onClick = { launch(app) },
                    menu = if (telegram) ({ close ->
                        DropdownMenuItem({ Text(stringResource(R.string.msg_open_chat)) }, onClick = { close(); launch(app) })
                        DropdownMenuItem({ Text(stringResource(R.string.msg_open_profile)) }, onClick = {
                            close()
                            val profile = e164?.let { MessengerLinks.telegramProfile(app, it) }
                            // Older Telegram versions ignore "profile" and open the chat instead, which is fine too.
                            val error = if (profile == null) unavailable ?: res.getString(R.string.msg_cant_open_number) else MessengerLauncher.open(context, profile, app)
                            if (error != null) toast(error)
                        })
                    }) else null,
                    menuLabel = stringResource(R.string.msg_chat_or_profile),
                )
            }
            SmsItem(usual = false) {
                val link = MessengerLinks.sms(number, e164, draft, MessengerLauncher.smsPackage(context))
                val error = MessengerLauncher.open(context, link, null)
                if (error != null) {
                    toast(error)
                } else {
                    store.recordOpened(number, null, "SMS", isContact = true)
                    sharedDetails()
                    onLaunched(null)
                }
            }
        },
        calls = {
            CallOnRows(
                calls,
                viaChatEnabled = { unavailable == null },
                viaChatSub = { unavailable },
                usual = { _, _ -> false },
                onStart = ::startRow,
                onViaChat = ::viaChat,
            )
            // Only once the lookup says the number is neither a contact nor a private one.
            if (known == false && callApps.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.reach_save_for_calls, TemporaryContact.DEFAULT_DAYS, TemporaryContact.DEFAULT_DAYS)) },
                    supportingContent = { Text(stringResource(R.string.reach_save_for_calls_sub)) },
                    leadingContent = { Icon(Icons.Rounded.Timer, null, Modifier.padding(horizontal = 8.dp)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button) { askSave = true },
                )
            }
        },
        footer = { FooterNote(Icons.Rounded.Lock, stringResource(MessageOn.PRIVACY_LINE_RES)) },
    )

    if (pickCountry) {
        CountryPickerDialog(selected = region, onDismiss = { pickCountry = false }) { code ->
            pickCountry = false
            regionOverride = code.takeUnless { it == simRegion }
        }
    }
    if (editDetails) {
        MyCardNameNumberDialog(
            card = myCard,
            suggestNumber = { withContext(Dispatchers.IO) { c.sims.ownNumbers().firstOrNull() } },
            onDismiss = { editDetails = false },
        ) { name, number ->
            editDetails = false
            c.people.me.setNameAndNumber(name, number)
            MessagingText.myDetails(res, name, number)?.let { draft = it }
        }
    }
    if (askSave) {
        // Private unless the user ticks "visible"; the notice says plainly that apps only see it when ticked.
        TemporaryNameDialog(
            TemporaryContact.suggestedName(number, null, region), notice = stringResource(R.string.reach_save_for_calls_notice),
            initialVisible = false, onDismiss = { askSave = false },
        ) { name, visible ->
            askSave = false
            scope.launch {
                // Checked again right before saving: never a second copy of a contact or a private person's number.
                val unknown = withContext(Dispatchers.IO) { runCatching { ChatThenDecide.stillUnknown(c, number) && (e164 == null || ChatThenDecide.stillUnknown(c, e164)) }.getOrDefault(false) }
                if (!unknown) return@launch toast(res.getString(R.string.reach_save_already_known))
                val saved = withContext(Dispatchers.IO) { runCatching { TemporaryContact.save(c, e164 ?: number, name, private = !visible) }.getOrNull() }
                toast(TemporaryContact.savedMessage(res, saved))
            }
        }
    }
}
