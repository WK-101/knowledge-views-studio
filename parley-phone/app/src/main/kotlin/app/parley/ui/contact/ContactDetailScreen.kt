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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timer
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
import app.parley.ui.shared
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.home.callTypeIcon
import app.parley.security.launchVault
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ContactDetailScreen(vm: AppViewModel, contactId: Long, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val all by vm.contacts.collectAsStateWithLifecycle()
    val calls by vm.c.history.calls.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val simPrefs by vm.c.prefs.numberSims.collectAsStateWithLifecycle(emptyList())
    var details by remember { mutableStateOf<ContactDetails?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var simFor by remember { mutableStateOf<String?>(null) }
    var showPhoto by remember { mutableStateOf(false) }
    var askExpiry by remember { mutableStateOf(false) }
    var relationChoice by remember { mutableStateOf<List<Long>?>(null) }
    var pinDialog by remember { mutableStateOf(false) }
    var reachOut by remember { mutableStateOf(false) }
    var secureQr by remember { mutableStateOf(false) }
    var copyToSim by remember { mutableStateOf(false) }
    val allNotes by vm.c.meta.allCallNotes().collectAsStateWithLifecycle(emptyList())
    var editNote by remember { mutableStateOf(false) }
    var messengers by remember { mutableStateOf<List<app.parley.data.MessengerAction>>(emptyList()) }
    var meta by remember { mutableStateOf<app.parley.data.db.ContactMetaEntity?>(null) }
    LaunchedEffect(contactId, all) {
        messengers = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.parley.data.Messengers.actions(context, contactId) }
    }
    LaunchedEffect(details) { details?.lookupKey?.let { meta = vm.c.meta.meta(it) } }
    fun saveMeta(f: (app.parley.data.db.ContactMetaEntity) -> app.parley.data.db.ContactMetaEntity) {
        val key = details?.lookupKey ?: return
        // The contact id is kept beside the key so the row can follow a key change (F8).
        val next = f(meta ?: app.parley.data.db.ContactMetaEntity(key)).copy(contactId = contactId)
        meta = next
        scope.launch { vm.c.meta.setMeta(next) }
    }
    val temps by vm.c.meta.temporaryContacts().collectAsStateWithLifecycle(emptyList())
    val temp = details?.lookupKey?.let { k -> temps.firstOrNull { it.lookupKey == k } }

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
                            DropdownMenuItem({ Text("Share privately (encrypted QR)") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; secureQr = true })
                            DropdownMenuItem({ Text("Version history") }, leadingIcon = { Icon(Icons.Rounded.History, null) }, onClick = { menu = false; open(Routes.versions(contactId)) })
                            DropdownMenuItem({ Text("Add to home screen") }, leadingIcon = { Icon(Icons.Rounded.AddToHomeScreen, null) }, onClick = { menu = false; pinDialog = true })
                            if (d.phones.isNotEmpty()) DropdownMenuItem({ Text("Copy to SIM") }, leadingIcon = { Icon(Icons.Rounded.SimCard, null) }, onClick = { menu = false; copyToSim = true })
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
                            app.parley.ui.blocking.ContactPrefixAllowMenuItem(d.composedName.ifBlank { null }, d.phones.map { it.value }) { menu = false }
                            if (d.rawContacts.size > 1) {
                                DropdownMenuItem({ Text("Separate linked contacts") }, leadingIcon = { Icon(Icons.Rounded.CallSplit, null) }, onClick = {
                                    menu = false; scope.launch { vm.c.contacts.separate(contactId); back() }
                                })
                            }
                            DropdownMenuItem({ Text("Move to private vault") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = {
                                menu = false
                                scope.launchVault(context as? androidx.fragment.app.FragmentActivity, { e -> vm.toast("Couldn't move: ${e.message}") }) {
                                    val id = vm.moveToVault(contactId, d)
                                    vm.toast("Moved to your private contacts")
                                    back()
                                    open(Routes.vault(id))
                                }
                            })
                            DropdownMenuItem({ Text(if (temp != null) "Change auto-delete" else "Delete after…") }, leadingIcon = { Icon(Icons.Rounded.Timer, null) }, onClick = { menu = false; askExpiry = true })
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
        // F7: same line by E.164 (read with this phone's country), not by the last 9 digits.
        val history = remember(calls, d.phones) {
            val mine = PhoneNumbers.LineSet(d.phones.map { it.value }, app.parley.data.PhoneEnv.countryIso(context))
            calls.orEmpty().filter { e -> e.number in mine }
        }
        LazyColumn(Modifier.padding(padding)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(
                        d.displayName, d.photoUri, 120.dp,
                        Modifier.shared("avatar-$contactId").clickable(enabled = d.photoUri != null, onClickLabel = "View photo") { showPhoto = true },
                        isCompany = d.composedName.isBlank() && d.company.isNotBlank(),
                    )
                    Text(d.displayName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).shared("name-$contactId", bounds = true))
                    val sub = listOf(d.nickname, listOf(d.title, d.company).filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }
                    if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val talked = history.firstOrNull { it.durationSec > 0 }
                    Text(
                        if (talked != null) "Last talked ${android.text.format.DateUtils.getRelativeTimeSpanString(talked.date, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS)}" else "No calls yet",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                    temp?.let { Text("Deletes itself on ${Format.fullDate(context, it.expiresAt)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    app.parley.ui.people.AccountChips(vm, d, open) { newId ->
                        if (newId != null && newId != contactId) { back(); open(Routes.contact(newId)) }
                        else scope.launch { details = vm.c.contacts.details(contactId) }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        val preferredCall = messengers.firstOrNull { it.accountType == meta?.preferredMessenger && it.isCall && !it.isVideo }
                        QuickAction(Icons.Rounded.Call, if (preferredCall != null) "Call (${preferredCall.appName})" else "Call", primary != null || preferredCall != null) {
                            if (preferredCall != null) runCatching { context.startActivity(preferredCall.intent()) } else primary?.let { vm.requestCall(it.value, d.displayName) }
                        }
                        QuickAction(Icons.AutoMirrored.Rounded.Message, "Message", primary != null) { primary?.let { Intents.sms(context, it.value) } }
                        QuickAction(Icons.Rounded.Email, "Email", d.emails.isNotEmpty()) { d.emails.firstOrNull()?.let { Intents.email(context, it.value) } }
                    }
                }
            }
            item {
                val note = meta?.pinnedNote
                ListItem(
                    modifier = Modifier.clickable { editNote = true },
                    leadingContent = { Icon(Icons.Rounded.PushPin, null, tint = if (note != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(note ?: "Add a note for calls") },
                    supportingContent = { Text(if (note != null) "Shown when they call" else "Private reminder shown on the call screen") },
                )
            }
            if (d.phones.isNotEmpty()) item { Section("Phone") }
            d.phones.forEach { p ->
                item {
                    val pinned = simPrefs.firstOrNull { it.matchKey == PhoneNumbers.matchKey(p.value) }?.phoneAccountId
                    ListItem(
                        modifier = Modifier.combinedClickable(onClick = { vm.requestCall(p.value, d.displayName) }, onLongClick = { Intents.copy(context, p.value) }, onLongClickLabel = "Copy"),
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
            if (d.events.isNotEmpty() || d.websites.isNotEmpty() || d.note.isNotBlank() || d.relations.isNotEmpty()) item { Section("About ${d.given.ifBlank { d.displayName }}") }
            d.events.forEach { ev ->
                item { Row0(Icons.Rounded.Cake, app.parley.ui.people.describeLifeEvent(d, ev), app.parley.ui.people.eventLabel(resources, ev)) {} }
            }
            d.websites.forEach { w -> item { Row0(Icons.Rounded.Language, w.value, "Website") { Intents.web(context, w.value) } } }
            d.relations.forEach { r ->
                item {
                    Row0(Icons.Rounded.People, r.value, android.provider.ContactsContract.CommonDataKinds.Relation.getTypeLabel(resources, r.type, r.label).toString()) {
                        // By the remembered lookup key first, then by name; several namesakes: ask (F23).
                        scope.launch {
                            val link = app.parley.common.people.RelationLinks.decode(meta?.relationLinks)[app.parley.common.people.RelationLinks.nameKey(r.value)]
                            val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                app.parley.common.people.RelationLinks.resolve(
                                    r.value, link, { l -> vm.c.contacts.currentOf(l.lookupKey, l.contactId)?.first },
                                    all.orEmpty().map { it.id to it.displayName }, self = contactId,
                                )
                            }
                            when (target) {
                                is app.parley.common.people.RelationLinks.Target.Contact -> open(Routes.contact(target.id))
                                is app.parley.common.people.RelationLinks.Target.Choose -> relationChoice = target.ids
                                app.parley.common.people.RelationLinks.Target.None -> vm.toast("No contact named ${r.value}")
                            }
                        }
                    }
                }
            }
            if (d.note.isNotBlank()) item {
                ListItem(
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { Intents.copy(context, d.note) }),
                    leadingContent = { Icon(Icons.Rounded.Notes, null) },
                    headlineContent = { LinkifiedText(d.note) },
                    supportingContent = { Text("Note") },
                )
            }

            if (messengers.isNotEmpty()) {
                item { Section("Messengers") }
                messengers.forEach { m ->
                    item {
                        val preferred = meta?.preferredMessenger == m.accountType && (m.isCall || m.isVideo)
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = { runCatching { context.startActivity(m.intent()) }.onFailure { vm.toast("${m.appName} isn't available") } },
                                onLongClick = { if (m.isCall) saveMeta { it.copy(preferredMessenger = if (preferred) null else m.accountType) } },
                                onLongClickLabel = "Set as preferred way to call",
                            ),
                            leadingContent = { Icon(if (m.isVideo) Icons.Rounded.Videocam else if (m.isCall) Icons.Rounded.Call else Icons.AutoMirrored.Rounded.Message, null) },
                            headlineContent = { Text(m.label) },
                            supportingContent = { Text(m.appName + if (preferred) " · preferred for calls" else "") },
                            trailingContent = { if (preferred) Icon(Icons.Rounded.Star, "Preferred", tint = MaterialTheme.colorScheme.primary) },
                        )
                    }
                }
            }
            item { Section("Settings") }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Voicemail, null) },
                    headlineContent = { Text("Send calls to voicemail") },
                    trailingContent = { Switch(d.sendToVoicemail, { v -> scope.launch { vm.c.contacts.setSendToVoicemail(contactId, v); details = vm.c.contacts.details(contactId) } }) },
                )
            }
            item {
                val every = meta?.reachOutDays
                ListItem(
                    modifier = Modifier.clickable { reachOut = true },
                    leadingContent = { Icon(Icons.Rounded.NotificationsActive, null) },
                    headlineContent = { Text("Remind me to keep in touch") },
                    supportingContent = { Text(every?.let { "If you haven't talked in $it days" } ?: "Off") },
                )
            }
            item { app.parley.ui.calltime.ContactCallTimeRows(vm, d.lookupKey, d.displayName, d.starred) }
            item {
                val tone = d.customRingtone?.let { runCatching { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }.getOrNull() }
                Row0(Icons.Rounded.MusicNote, tone ?: "Default ringtone", "Ringtone") {
                    ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE))
                }
            }
            item {
                Row0(Icons.Rounded.Sync, d.rawContacts.joinToString("\n") { it.account.displayLabel }, if (d.rawContacts.size > 1) "Linked from ${d.rawContacts.size} sources" else "Saved in") {}
            }
            item { app.parley.ui.people.ProvenanceRow(vm, contactId, d, open) }
            item { app.parley.ui.people.CallBackgroundInfoRow(vm, d) { open(Routes.edit(id = contactId)) } }
            val keys = d.phones.map { PhoneNumbers.matchKey(it.value) }.toSet()
            val notes = allNotes.filter { it.numberKey in keys }
            if (notes.isNotEmpty()) {
                item { Section("Call notes") }
                notes.take(10).forEach { n ->
                    item { ListItem(headlineContent = { LinkifiedText(n.text) }, supportingContent = { Text(Format.fullDate(context, n.callDate)) }, leadingContent = { Icon(Icons.Rounded.Notes, null) }) }
                }
            }
            item { app.parley.ui.history.CallInsightsSection(vm, d.phones.map { it.value }) }
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
        if (secureQr) SecureQrDialog(d) { secureQr = false }
        if (copyToSim) app.parley.ui.people.CopyToSimDialog(vm, d) { copyToSim = false }
        if (editNote) {
            var text by remember { mutableStateOf(meta?.pinnedNote.orEmpty()) }
            AlertDialog(
                onDismissRequest = { editNote = false },
                title = { Text("Note for calls") },
                text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, placeholder = { Text("e.g. Ask about the invoice") }, minLines = 2) },
                confirmButton = { TextButton({ editNote = false; saveMeta { it.copy(pinnedNote = text.trim().ifEmpty { null }) } }) { Text("Save") } },
                dismissButton = { TextButton({ editNote = false }) { Text("Cancel") } },
            )
        }
        if (reachOut) {
            AlertDialog(
                onDismissRequest = { reachOut = false },
                title = { Text("Keep in touch") },
                text = {
                    Column {
                        app.parley.ui.history.RhythmSuggestion(vm, d.phones.map { it.value }) { days -> reachOut = false; saveMeta { it.copy(reachOutDays = days, lastNudgedAt = null) } }
                        listOf(null to "Off", 7 to "Every week", 14 to "Every 2 weeks", 30 to "Every month", 90 to "Every 3 months", 180 to "Every 6 months").forEach { (days, label) ->
                            ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { reachOut = false; saveMeta { it.copy(reachOutDays = days, lastNudgedAt = null) } })
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ reachOut = false }) { Text("Cancel") } },
            )
        }
        if (pinDialog) {
            AlertDialog(
                onDismissRequest = { pinDialog = false },
                title = { Text("Add to home screen") },
                text = {
                    Column {
                        d.phones.forEach { p ->
                            ListItem(headlineContent = { Text("Call ${Format.number(p.value, vm.countryIso)}") }, leadingContent = { Icon(Icons.Rounded.Call, null) }, modifier = Modifier.clickable {
                                pinDialog = false
                                app.parley.shortcuts.Shortcuts.pin(context, app.parley.shortcuts.Shortcuts.Kind.CALL, d.displayName, p.value, contactId, d.photoUri)
                            })
                            ListItem(headlineContent = { Text("Message ${Format.number(p.value, vm.countryIso)}") }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, modifier = Modifier.clickable {
                                pinDialog = false
                                app.parley.shortcuts.Shortcuts.pin(context, app.parley.shortcuts.Shortcuts.Kind.MESSAGE, d.displayName, p.value, contactId, d.photoUri)
                            })
                        }
                        ListItem(headlineContent = { Text("Open contact") }, leadingContent = { Icon(Icons.Rounded.Person, null) }, modifier = Modifier.clickable {
                            pinDialog = false
                            app.parley.shortcuts.Shortcuts.pin(context, app.parley.shortcuts.Shortcuts.Kind.OPEN, d.displayName, null, contactId, d.photoUri, d.lookupKey)
                        })
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ pinDialog = false }) { Text("Cancel") } },
            )
        }
        relationChoice?.let { ids ->
            AlertDialog(
                onDismissRequest = { relationChoice = null },
                title = { Text("Which contact?") },
                text = {
                    Column {
                        ids.forEach { id ->
                            val ct = all.orEmpty().firstOrNull { it.id == id } ?: return@forEach
                            ListItem(
                                modifier = Modifier.clickable {
                                    relationChoice = null
                                    open(Routes.contact(id))
                                },
                                headlineContent = { Text(ct.displayName) },
                                supportingContent = { ct.phones.firstOrNull()?.let { Text(Format.number(it.number, vm.countryIso)) } },
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ relationChoice = null }) { Text("Cancel") } },
            )
        }
        if (askExpiry) app.parley.ui.vault.ExpiryDialog(onDismiss = { askExpiry = false }) { days ->
            askExpiry = false
            scope.launch {
                if (days == null) vm.c.temporaries.clear(d.lookupKey) else vm.c.temporaries.mark(contactId, days, purgeHistory = true)
                vm.toast(if (days == null) "Contact will be kept" else "Contact deletes itself in $days days")
            }
        }
        d.photoUri?.takeIf { showPhoto }?.let { PhotoViewer(it) { showPhoto = false } }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete ${d.displayName}?") },
                text = { Text("This removes the contact from every account it is saved in. You can restore it from Recently deleted for 30 days.") },
                confirmButton = { TextButton({ confirmDelete = false; vm.deleteContacts(listOf(contactId)); back() }) { Text("Delete") } },
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Row0(icon: ImageVector, text: String, label: String, onClick: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { Intents.copy(context, text) }, onLongClickLabel = "Copy"),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(text) },
        supportingContent = { Text(label) },
    )
}
