package app.parley.messaging

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Public
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.common.MessageDrafts
import app.parley.common.MessengerApp
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.common.PhoneNumbers
import app.parley.container
import app.parley.data.PhoneEnv
import app.parley.data.messaging.MyDetails
import app.parley.ui.Bidi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry points for "Message on…" (M2). In-app screens show [MessageOnSheet]; code outside the app's UI (the in-call
 * screen, notifications) starts [intent], which opens the same sheet over whatever is on screen.
 */
object MessageOn {
    /**
     * Opens the "Message on…" sheet for [number] in its own small window. [accountId] is the SIM that handled the call
     * the number comes from, so a national number is read with that SIM's country (F19).
     */
    fun intent(context: Context, number: String, accountId: String? = null): Intent = Intent(context, NumberActionActivity::class.java)
        .setAction(NumberActionActivity.ACTION_MESSAGE_ON)
        .putExtra(NumberActionActivity.EXTRA_NUMBER, number)
        .apply { if (accountId != null) putExtra(NumberActionActivity.EXTRA_ACCOUNT_ID, accountId) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Shows the sheet for [number] from any context (for the in-call screen's caller card). */
    fun open(context: Context, number: String, accountId: String? = null) {
        if (number.isNotBlank()) context.startActivity(intent(context, number, accountId))
    }

    /** The privacy line under the messengers. */
    val PRIVACY_LINE_RES = R.string.msg_privacy_line
}

/** Bottom sheet listing the installed messengers for [number]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageOnSheet(
    number: String,
    onDismiss: () -> Unit,
    accountId: String? = null,
    onCall: ((String) -> Unit)? = null,
    onLaunched: (MessengerApp?) -> Unit = { onDismiss() },
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // C2: calling closes the sheet (the call screen or the call's questions take over).
        MessageOnContent(number, accountId, onCall = onCall?.let { call -> { n -> onDismiss(); call(n) } }, onLaunched = onLaunched)
    }
}

private data class MessengerRow(val label: String, val apps: List<MessengerApp>)

/** Groups installed apps into rows: one WhatsApp row (asks between WhatsApp and Business once), one Telegram… */
private fun rows(installed: List<MessengerApp>, lastApp: String?): List<MessengerRow> {
    val out = ArrayList<MessengerRow>()
    val wa = installed.filter { it == MessengerApp.WHATSAPP || it == MessengerApp.WHATSAPP_BUSINESS }
    if (wa.isNotEmpty()) out += MessengerRow(if (wa.size == 1) wa[0].label else "WhatsApp", wa)
    installed.firstOrNull { it == MessengerApp.SIGNAL }?.let { out += MessengerRow(it.label, listOf(it)) }
    installed.firstOrNull { it == MessengerApp.MOLLY }?.let { out += MessengerRow(it.label, listOf(it)) }
    installed.firstOrNull { it == MessengerApp.TELEGRAM || it == MessengerApp.TELEGRAM_WEB }?.let { out += MessengerRow(it.label, listOf(it)) }
    installed.firstOrNull { it == MessengerApp.TELEGRAM_X }?.let { out += MessengerRow(it.label, listOf(it)) }
    installed.firstOrNull { it == MessengerApp.VIBER }?.let { out += MessengerRow(it.label, listOf(it)) }
    return out.sortedByDescending { r -> r.apps.any { it.packageName == lastApp } }
}

/**
 * The sheet's content: the number in international form, an optional message ("Send my details"), installed
 * messengers (last used first), SMS, and the privacy line. [onLaunched] runs after an app was opened. [onCall]: C2, a
 * direct Call shown first (null where calling makes no sense, e.g. during a call).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageOnContent(number: String, accountId: String? = null, onCall: ((String) -> Unit)? = null, onLaunched: (MessengerApp?) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val c = context.container
    val store = c.messaging
    // F19: the country of the SIM that took the call (when the number comes from Recents or a notification), which
    // the user can override for this number with the country chip.
    val simRegion = remember(accountId) { PhoneEnv.countryIso(context, accountId) }
    var regionOverride by rememberSaveable(number) { mutableStateOf<String?>(null) }
    var pickCountry by remember { mutableStateOf(false) }
    val region = regionOverride ?: simRegion
    val e164 = remember(number, region) { NumberText.toE164(number, region) }
    val unavailable = remember(e164) { MessagingText.unavailable(res, e164) }
    val nationalForm = remember(number) { !PhoneNumbers.clean(number).startsWith("+") }
    val installed = remember { MessengerLauncher.installed(context) }
    val rows = remember(installed) { rows(installed, store.lastApp) }
    val details by store.myDetails.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var askWhatsApp by remember { mutableStateOf(false) }
    var editDetails by remember { mutableStateOf(false) }
    // Re-checked before any offer, so a quick tap before the lookup finishes is harmless.
    var isContact by remember { mutableStateOf(false) }
    LaunchedEffect(number) {
        isContact = withContext(Dispatchers.IO) { c.contacts.lookup(number) != null || c.vault.lookup(number) != null }
    }

    fun launch(app: MessengerApp) {
        val link = e164?.let { MessengerLinks.build(app, it, draft) } ?: return
        if (draft.isNotBlank() && !app.takesText) {
            val clip = ClipData.newPlainText("message", draft)
            // F19: keep the draft out of clipboard previews and keyboard suggestions (Android 13+).
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            Toast.makeText(context, res.getString(R.string.msg_copied_paste), Toast.LENGTH_LONG).show()
        }
        val error = MessengerLauncher.open(context, link, app)
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            return
        }
        store.lastApp = app.packageName
        store.recordOpened(number, app, app.label, isContact)
        onLaunched(app)
    }

    fun launchRow(row: MessengerRow, forceAsk: Boolean = false) {
        if (row.apps.size == 1) return launch(row.apps[0])
        val remembered = row.apps.firstOrNull { it.packageName == store.whatsappChoice }
        if (remembered != null && !forceAsk) launch(remembered) else askWhatsApp = true
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(stringResource(R.string.missed_message_on), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            Bidi.ltr(e164?.let(NumberText::formatInternational) ?: number),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (nationalForm) {
            // F19: a national number is read with this country; tap to change it (e.g. the call came in abroad).
            AssistChip(
                onClick = { pickCountry = true },
                label = { Text(stringResource(if (regionOverride == null) R.string.num_country else R.string.msg_country_changed, countryLabel(region))) },
                leadingIcon = { Icon(Icons.Rounded.Public, null) },
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        // C2: calling is the first, primary action; the number is dialled as given (the SIM's country applies).
        if (onCall != null) CallFirstButton(number) { onCall(number) }
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
                    val text = MessagingText.myDetails(res, details.name, details.number)
                    if (text == null) editDetails = true else draft = text
                },
                label = { Text(stringResource(R.string.msg_send_details)) },
                leadingIcon = { Icon(Icons.Rounded.Badge, null) },
            )
            if (details.name.isNotEmpty() || details.number.isNotEmpty()) {
                TextButton({ editDetails = true }) { Text(stringResource(R.string.msg_edit_details)) }
            }
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.msg_no_chat_apps),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        rows.forEach { row ->
            // F19: only enabled when a link can actually be built; otherwise the reason is shown.
            val enabled = unavailable == null
            val chosen = row.apps.firstOrNull { it.packageName == store.whatsappChoice }
            // M13: Telegram rows also open the person's profile (long-press or the ⋮ button).
            val telegram = row.apps[0].messenger == app.parley.common.Messenger.TELEGRAM
            var rowMenu by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(row.label) },
                supportingContent = when {
                    unavailable != null -> ({ Text(unavailable) })
                    row.apps.size > 1 && chosen != null -> ({ Text(stringResource(R.string.msg_long_press_change, chosen.label)) })
                    draft.isNotBlank() && !row.apps[0].takesText -> ({ Text(stringResource(R.string.msg_will_copy)) })
                    else -> null
                },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                trailingContent = if (telegram && enabled) ({
                    Box {
                        IconButton({ rowMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.msg_more_actions_for, row.label)) }
                        DropdownMenu(rowMenu, { rowMenu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.msg_open_chat)) }, onClick = { rowMenu = false; launchRow(row) })
                            DropdownMenuItem({ Text(stringResource(R.string.msg_open_profile)) }, onClick = {
                                rowMenu = false
                                val profile = e164?.let { MessengerLinks.telegramProfile(row.apps[0], it) }
                                // Older Telegram versions ignore "profile" and open the chat instead, which is fine too.
                                val error = if (profile == null) unavailable ?: res.getString(R.string.msg_cant_open_number) else MessengerLauncher.open(context, profile, row.apps[0])
                                if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                            })
                        }
                    }
                }) else null,
                colors = if (enabled) ListItemDefaults.colors() else ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
                modifier = Modifier.combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { launchRow(row) },
                    onLongClick = when {
                        row.apps.size > 1 -> ({ launchRow(row, forceAsk = true) })
                        telegram -> ({ rowMenu = true })
                        else -> null
                    },
                    onLongClickLabel = when {
                        row.apps.size > 1 -> stringResource(R.string.msg_choose_whatsapp)
                        telegram -> stringResource(R.string.msg_chat_or_profile)
                        else -> null
                    },
                ),
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.msg_sms)) },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) },
            modifier = Modifier.combinedClickable(role = Role.Button, onClick = {
                val link = MessengerLinks.sms(number, e164, draft, MessengerLauncher.smsPackage(context))
                val error = MessengerLauncher.open(context, link, null)
                if (error != null) {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                } else {
                    store.recordOpened(number, null, "SMS", isContact = true)
                    onLaunched(null)
                }
            }),
        )
        Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(MessageOn.PRIVACY_LINE_RES), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (askWhatsApp) {
        val wa = rows.firstOrNull { it.apps.size > 1 }?.apps.orEmpty()
        AlertDialog(
            onDismissRequest = { askWhatsApp = false },
            title = { Text(stringResource(R.string.msg_open_with)) },
            text = {
                Column {
                    Text(stringResource(R.string.msg_remember_whatsapp), style = MaterialTheme.typography.bodyMedium)
                    wa.forEach { app ->
                        ListItem(
                            headlineContent = { Text(app.label) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(role = Role.Button, onClick = {
                                askWhatsApp = false
                                store.whatsappChoice = app.packageName
                                launch(app)
                            }),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ askWhatsApp = false }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
    if (pickCountry) {
        CountryPickerDialog(selected = region, onDismiss = { pickCountry = false }) { code ->
            pickCountry = false
            regionOverride = code.takeUnless { it == simRegion }
        }
    }
    if (editDetails) {
        MyDetailsDialog(
            initial = details,
            suggestNumber = { withContext(Dispatchers.IO) { c.sims.ownNumbers().firstOrNull() } },
            onDismiss = { editDetails = false },
        ) { d ->
            editDetails = false
            store.setMyDetails(d)
            MessageDrafts.myDetails(d.name, d.number)?.let { draft = it }
        }
    }
}

internal fun countryLabel(code: String): String {
    val name = java.util.Locale("", code).displayCountry.ifBlank { code }
    return "$name ($code)"
}

/** F19: searchable list of every country libphonenumber knows, with its calling code. */
@Composable
fun CountryPickerDialog(selected: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val all = remember { NumberText.regions() }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query) { NumberText.searchRegions(all, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_country_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.msg_search_countries)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.code }) { r ->
                        ListItem(
                            headlineContent = { Text(r.name) },
                            supportingContent = { Text(Bidi.ltr("+${r.callingCode}") + stringResource(R.string.main_separator) + r.code) },
                            trailingContent = if (r.code == selected) ({ Icon(Icons.Rounded.Check, stringResource(R.string.contacts_selected)) }) else null,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(role = Role.Button, onClick = { onPick(r.code) }),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

/** Your name and number for "Send my details". Nothing is read without asking: the number is only a suggestion. */
@Composable
fun MyDetailsDialog(initial: MyDetails, suggestNumber: suspend () -> String?, onDismiss: () -> Unit, onSave: (MyDetails) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var number by rememberSaveable { mutableStateOf(initial.number) }
    LaunchedEffect(Unit) {
        if (number.isEmpty()) suggestNumber()?.let { if (number.isEmpty()) number = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_my_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.msg_my_details_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.msg_your_name)) }, singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.msg_your_number)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onSave(MyDetails(name, number)) }, enabled = name.isNotBlank() || number.isNotBlank()) { Text(stringResource(R.string.main_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

/** "Last messaged via Signal · 2 days ago" (M5), from Parley's own record; nothing if never. */
@Composable
fun LastMessagedNote(number: String, modifier: Modifier = Modifier) {
    val store = LocalContext.current.container.messaging
    val all by store.lastMessaged.collectAsStateWithLifecycle()
    val last = remember(all, number) { store.lastMessaged(number) } ?: return
    val ago = DateUtils.getRelativeTimeSpanString(last.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    Text(
        stringResource(R.string.msg_last_messaged, last.label, ago),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
