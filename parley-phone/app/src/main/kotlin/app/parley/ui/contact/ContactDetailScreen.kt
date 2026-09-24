package app.parley.ui.contact

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.PhoneNumbers
import app.parley.data.ContactDetails
import app.parley.ui.Avatar
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.home.callTypeIcon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(vm: AppViewModel, contactId: Long, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val all by vm.contacts.collectAsStateWithLifecycle()
    val calls by vm.c.callLog.calls.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val simPrefs by vm.c.prefs.numberSims.collectAsStateWithLifecycle(emptyList())
    var details by remember { mutableStateOf<ContactDetails?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var simFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(contactId, all) {
        details = vm.c.contacts.details(contactId)
        loaded = true
    }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            scope.launch { vm.c.contacts.setRingtone(contactId, uri?.toString()) }
        }
    }

    val d = details
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (d != null) {
                        IconButton({ scope.launch { vm.c.contacts.setStarred(contactId, !d.starred) } }) {
                            Icon(if (d.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (d.starred) "Remove from favorites" else "Add to favorites")
                        }
                        IconButton({ open(Routes.edit(id = contactId)) }) { Icon(Icons.Rounded.Edit, "Edit") }
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Share as file") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                                menu = false; Intents.shareVcard(context, vm.c.contacts.vcardUri(d.lookupKey), d.displayName)
                            })
                            DropdownMenuItem({ Text("Show QR code") }, leadingIcon = { Icon(Icons.Rounded.QrCode2, null) }, onClick = { menu = false; showQr = true })
                            DropdownMenuItem({ Text("Set ringtone") }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) }, onClick = {
                                menu = false
                                ringtonePicker.launch(
                                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, d.customRingtone?.let(Uri::parse)),
                                )
                            })
                            d.phones.firstOrNull()?.let { p ->
                                DropdownMenuItem({ Text("Block numbers") }, leadingIcon = { Icon(Icons.Rounded.Block, null) }, onClick = {
                                    menu = false; d.phones.forEach { vm.blockNumber(it.value) }
                                })
                            }
                            if (d.rawContacts.size > 1) {
                                DropdownMenuItem({ Text("Separate linked contacts") }, leadingIcon = { Icon(Icons.Rounded.CallSplit, null) }, onClick = {
                                    menu = false; scope.launch { vm.c.contacts.separate(contactId); back() }
                                })
                            }
                            DropdownMenuItem({ Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (d == null) {
            if (loaded) Text("This contact no longer exists.", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val primary = d.phones.firstOrNull { it.isPrimary } ?: d.phones.firstOrNull()
        val history = calls.orEmpty().filter { e -> d.phones.any { PhoneNumbers.matchKey(it.value) == PhoneNumbers.matchKey(e.number) } }
        LazyColumn(Modifier.padding(padding)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(d.displayName, d.photoUri, 120.dp)
                    Text(d.displayName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                    val sub = listOf(d.nickname, listOf(d.title, d.company).filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }
                    if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        QuickAction(Icons.Rounded.Call, "Call", primary != null) { primary?.let { vm.requestCall(it.value, d.displayName) } }
                        QuickAction(Icons.AutoMirrored.Rounded.Message, "Message", primary != null) { primary?.let { Intents.sms(context, it.value) } }
                        QuickAction(Icons.Rounded.Email, "Email", d.emails.isNotEmpty()) { d.emails.firstOrNull()?.let { Intents.email(context, it.value) } }
                    }
                }
            }
            if (d.phones.isNotEmpty()) item { Section("Phone") }
            d.phones.forEach { p ->
                item {
                    val pinned = simPrefs.firstOrNull { it.matchKey == PhoneNumbers.matchKey(p.value) }?.phoneAccountId
                    ListItem(
                        modifier = Modifier.clickable { vm.requestCall(p.value, d.displayName) },
                        leadingContent = { Icon(Icons.Rounded.Call, null) },
                        headlineContent = { Text(Format.number(p.value, vm.countryIso)) },
                        supportingContent = {
                            Text(listOfNotNull(Format.phoneType(context.resources, p.type, p.label), pinned?.let { id -> sims.firstOrNull { it.id == id }?.label?.let { "Always $it" } }).joinToString(" · "))
                        },
                        trailingContent = {
                            Row {
                                if (sims.size > 1) IconButton({ simFor = p.value }) { Icon(Icons.Rounded.SimCard, "Choose SIM for this number") }
                                IconButton({ Intents.sms(context, p.value) }) { Icon(Icons.AutoMirrored.Rounded.Message, "Message") }
                            }
                        },
                    )
                }
            }
            if (d.emails.isNotEmpty()) item { Section("Email") }
            d.emails.forEach { e ->
                item {
                    Row0(Icons.Rounded.Email, e.value, Format.emailType(context.resources, e.type, e.label)) { Intents.email(context, e.value) }
                }
            }
            if (d.addresses.isNotEmpty()) item { Section("Address") }
            d.addresses.forEach { a ->
                item {
                    Row0(Icons.Rounded.LocationOn, a.formatted, StructuredPostal.getTypeLabel(context.resources, a.type, a.label).toString()) { Intents.map(context, a.formatted) }
                }
            }
            if (d.events.isNotEmpty() || d.websites.isNotEmpty() || d.note.isNotBlank()) item { Section("About") }
            d.events.forEach { ev ->
                item { Row0(Icons.Rounded.Cake, ev.date, resources.getString(Event.getTypeResource(ev.type))) {} }
            }
            d.websites.forEach { w -> item { Row0(Icons.Rounded.Language, w.value, "Website") { Intents.web(context, w.value) } } }
            if (d.note.isNotBlank()) item { Row0(Icons.Rounded.Notes, d.note, "Note") {} }

            item { Section("Settings") }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Voicemail, null) },
                    headlineContent = { Text("Send calls to voicemail") },
                    trailingContent = { Switch(d.sendToVoicemail, { v -> scope.launch { vm.c.contacts.setSendToVoicemail(contactId, v); details = vm.c.contacts.details(contactId) } }) },
                )
            }
            item {
                val tone = d.customRingtone?.let { runCatching { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }.getOrNull() }
                Row0(Icons.Rounded.MusicNote, tone ?: "Default ringtone", "Ringtone") {
                    ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE))
                }
            }
            item {
                Row0(Icons.Rounded.Sync, d.rawContacts.joinToString("\n") { it.account.displayLabel }, if (d.rawContacts.size > 1) "Linked from ${d.rawContacts.size} sources" else "Saved in") {}
            }
            if (history.isNotEmpty()) {
                item { Section("Recent calls") }
                history.take(5).forEach { e ->
                    item {
                        val (icon, tint) = callTypeIcon(e.type)
                        ListItem(
                            leadingContent = { Icon(icon, null, tint = tint) },
                            headlineContent = { Text(Format.fullDate(context, e.date)) },
                            supportingContent = { Text(listOf(Format.number(e.number, vm.countryIso), Format.duration(e.durationSec)).filter { it.isNotBlank() }.joinToString(" · ")) },
                        )
                    }
                }
                if (history.size > 5 && primary != null) {
                    item { TextButton({ open(Routes.history(primary.value)) }, Modifier.padding(start = 8.dp)) { Text("See all ${history.size} calls") } }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }

        if (showQr) QrDialog(d) { showQr = false }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete ${d.displayName}?") },
                text = { Text("This removes the contact from every account it is saved in.") },
                confirmButton = { TextButton({ confirmDelete = false; scope.launch { vm.c.contacts.delete(listOf(contactId)); back() } }) { Text("Delete") } },
                dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
            )
        }
        simFor?.let { number ->
            AlertDialog(
                onDismissRequest = { simFor = null },
                title = { Text("SIM for ${Format.number(number, vm.countryIso)}") },
                text = {
                    Column {
                        ListItem(headlineContent = { Text("Ask / use default") }, modifier = Modifier.clickable { scope.launch { vm.c.prefs.setSimFor(number, null) }; simFor = null })
                        sims.forEach { s ->
                            ListItem(headlineContent = { Text("Always ${s.label}") }, leadingContent = { Icon(Icons.Rounded.SimCard, null) }, modifier = Modifier.clickable {
                                scope.launch { vm.c.prefs.setSimFor(number, s.id) }; simFor = null
                            })
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ simFor = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick, enabled = enabled, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(20.dp)) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Section(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    }
}

@Composable
private fun Row0(icon: ImageVector, text: String, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(text) },
        supportingContent = { Text(label) },
    )
}
