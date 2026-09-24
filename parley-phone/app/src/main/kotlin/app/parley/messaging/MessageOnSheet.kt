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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.common.MessageDrafts
import app.parley.common.MessengerApp
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.common.PhoneNumbers
import app.parley.container
import app.parley.data.PhoneEnv
import app.parley.data.messaging.MyDetails
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

    const val PRIVACY_LINE = "Opens the app directly, not through a browser. The messenger itself checks whether this number is registered."
}

/** Bottom sheet listing the installed messengers for [number]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageOnSheet(number: String, onDismiss: () -> Unit, accountId: String? = null, onLaunched: (MessengerApp?) -> Unit = { onDismiss() }) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        MessageOnContent(number, accountId, onLaunched)
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
 * messengers (last used first), SMS, and the privacy line. [onLaunched] runs after an app was opened.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageOnContent(number: String, accountId: String? = null, onLaunched: (MessengerApp?) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val store = c.messaging
    // F19: the country of the SIM that took the call (when the number comes from Recents or a notification), which
    // the user can override for this number with the country chip.
    val simRegion = remember(accountId) { PhoneEnv.countryIso(context, accountId) }
    var regionOverride by rememberSaveable(number) { mutableStateOf<String?>(null) }
    var pickCountry by remember { mutableStateOf(false) }
    val region = regionOverride ?: simRegion
    val e164 = remember(number, region) { NumberText.toE164(number, region) }
    val unavailable = remember(e164) { MessengerLinks.unavailableReason(e164) }
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
            Toast.makeText(context, "Message copied. Paste it in the chat.", Toast.LENGTH_LONG).show()
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
        Text("Message on…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            e164?.let(NumberText::formatInternational) ?: number,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (nationalForm) {
            // F19: a national number is read with this country; tap to change it (e.g. the call came in abroad).
            AssistChip(
                onClick = { pickCountry = true },
                label = { Text("Country: " + countryLabel(region) + if (regionOverride == null) "" else " (changed)") },
                leadingIcon = { Icon(Icons.Rounded.Public, null) },
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        if (unavailable != null) {
            Text(
                "Chat apps need a full number with its country code. Only SMS is available for this number.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        OutlinedTextField(
            draft, { draft = it },
            label = { Text("Message (optional)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            maxLines = 4,
        )
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {
                    val text = MessageDrafts.myDetails(details.name, details.number)
                    if (text == null) editDetails = true else draft = text
                },
                label = { Text("Send my details") },
                leadingIcon = { Icon(Icons.Rounded.Badge, null) },
            )
            if (details.name.isNotEmpty() || details.number.isNotEmpty()) {
                TextButton({ editDetails = true }) { Text("Edit my details") }
            }
        }
        if (rows.isEmpty()) {
            Text(
                "No chat apps found. Install or enable WhatsApp, Signal, Telegram or Viber to message from here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        rows.forEach { row ->
            // F19: only enabled when a link can actually be built; otherwise the reason is shown.
            val enabled = unavailable == null
            val chosen = row.apps.firstOrNull { it.packageName == store.whatsappChoice }
            ListItem(
                headlineContent = { Text(row.label) },
                supportingContent = when {
                    unavailable != null -> ({ Text(unavailable) })
                    row.apps.size > 1 && chosen != null -> ({ Text("${chosen.label} · long-press to change") })
                    draft.isNotBlank() && !row.apps[0].takesText -> ({ Text("Your message will be copied for pasting") })
                    else -> null
                },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                colors = if (enabled) ListItemDefaults.colors() else ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
                modifier = Modifier.combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { launchRow(row) },
                    onLongClick = if (row.apps.size > 1) ({ launchRow(row, forceAsk = true) }) else null,
                    onLongClickLabel = if (row.apps.size > 1) "Choose WhatsApp or WhatsApp Business" else null,
                ),
            )
        }
        ListItem(
            headlineContent = { Text("Text message (SMS)") },
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
            Text(MessageOn.PRIVACY_LINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (askWhatsApp) {
        val wa = rows.firstOrNull { it.apps.size > 1 }?.apps.orEmpty()
        AlertDialog(
            onDismissRequest = { askWhatsApp = false },
            title = { Text("Open with") },
            text = {
                Column {
                    Text("Parley will remember your choice. Long-press WhatsApp to change it.", style = MaterialTheme.typography.bodyMedium)
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
            dismissButton = { TextButton({ askWhatsApp = false }) { Text("Cancel") } },
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
        title = { Text("Country of this number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search countries or +code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.code }) { r ->
                        ListItem(
                            headlineContent = { Text(r.name) },
                            supportingContent = { Text("+${r.callingCode} · ${r.code}") },
                            trailingContent = if (r.code == selected) ({ Icon(Icons.Rounded.Check, "Selected") }) else null,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(role = Role.Button, onClick = { onPick(r.code) }),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
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
        title = { Text("My details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Used only to fill in “Send my details”. Stays on this phone.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text("Your number") }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onSave(MyDetails(name, number)) }, enabled = name.isNotBlank() || number.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
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
        "Last messaged via ${last.label} · $ago",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
