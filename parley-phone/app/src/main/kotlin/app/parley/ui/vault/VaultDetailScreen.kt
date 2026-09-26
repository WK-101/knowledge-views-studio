package app.parley.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.data.ContactDetails
import app.parley.data.vault.VaultCrypto
import app.parley.security.AppLock
import app.parley.security.launchVault
import app.parley.ui.Avatar
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.contact.LinkifiedText
import app.parley.ui.contact.Section
import app.parley.ui.SegmentedGroup
import app.parley.ui.contact.ActionTile
import app.parley.ui.contact.handleRows
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PushPin
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.DataL10n

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultDetailScreen(vm: AppViewModel, id: Long, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val summaries by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val calls by vm.c.vault.privateCalls.collectAsStateWithLifecycle()
    val summary = summaries.firstOrNull { it.id == id }
    var details by remember { mutableStateOf<ContactDetails?>(null) }
    var locked by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf(false) }
    var shareQr by remember { mutableStateOf(false) }

    LaunchedEffect(id, attempt, summaries) {
        try {
            details = vm.c.vault.details(id)
            locked = false
        } catch (_: VaultCrypto.LockedException) {
            locked = true
        }
    }
    fun unlock() = (context as? FragmentActivity)?.let { AppLock.authenticateForVault(it) { ok -> if (ok) attempt++ } }

    val card by androidx.compose.runtime.produceState<app.parley.data.vault.VaultCallerCard?>(null, id, summaries) { value = vm.c.vault.callerCard(id) }
    var messageSheet by remember { mutableStateOf<String?>(null) }
    var webLink by remember { mutableStateOf<app.parley.common.people.HandleLink?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // U7: scroll-linked tint.
    val barColor by androidx.compose.animation.animateColorAsState(
        if (listState.canScrollBackward) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, label = "bar",
    )
    val prefs = remember(details) { app.parley.common.people.MessengerPrefs.decode(details?.messengerPrefs) }
    fun reach(): app.parley.ui.contact.Reach {
        val d = details
        val numbers = d?.phones?.filter { it.value.isNotBlank() }?.map { it.value to Format.phoneType(context.resources, it.type, it.label) }
            ?: summary?.numbers.orEmpty().map { it to "" }
        return app.parley.ui.contact.Reach(
            name = d?.given?.ifBlank { null } ?: summary?.name.orEmpty(), numbers = numbers,
            defaultNumber = numbers.firstOrNull()?.first, messengers = emptyList(), prefs = prefs, isPrivate = true,
        )
    }
    // M7 for private contacts: the choice is kept in their encrypted record (needs the unlocked details).
    fun savePrefs(p: app.parley.common.people.MessengerPrefs) {
        val d = details ?: return vm.toast(res.getString(R.string.vault_unlock_to_remember))
        val next = d.copy(messengerPrefs = p.encode().orEmpty())
        details = next
        scope.launch { runCatching { vm.c.vault.save(id, next) } }
    }
    fun message(number: String? = null) {
        val r = reach().let { if (number != null) it.copy(defaultNumber = number, prefs = it.prefs.copy(number = null)) else it }
        when (val route = app.parley.ui.contact.ContactMessaging.route(context, r)) {
            app.parley.common.people.MessageRoute.Ask -> messageSheet = number ?: r.defaultNumber.orEmpty()
            else -> app.parley.ui.contact.ContactMessaging.open(context, route, r)?.let { vm.toast(it) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Lock, null); Text("  " + stringResource(R.string.vault_title)) } },
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = barColor, scrolledContainerColor = barColor),
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } },
            actions = {
                if (details != null) {
                    IconButton({ open(Routes.edit(vault = id)) }) { Icon(Icons.Rounded.Edit, stringResource(R.string.dc_edit)) }
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.dc_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.vault_move_out)) }, leadingIcon = { Icon(Icons.Rounded.LockOpen, null) }, onClick = {
                            menu = false
                            scope.launchVault(context as? FragmentActivity, { e -> vm.toast(res.getString(R.string.vault_move_failed, e.message.orEmpty())) }) {
                                val d = details ?: return@launchVault
                                val s = vm.settings.value
                                val account = app.parley.data.AccountRef(s.defaultAccountType, s.defaultAccountName)
                                // Restores the original contact losslessly when the vault kept its record (F4).
                                val newId = vm.c.vaultMoves.moveOut(id, d, account)
                                if (newId != null) {
                                    // I6: the note for calls and the messaging choice follow them into Parley's metadata.
                                    if (d.pinnedNote.isNotBlank() || d.messengerPrefs.isNotBlank()) {
                                        vm.c.contacts.lookupKeyOf(newId)?.let { key ->
                                            val m = vm.c.meta.meta(key) ?: app.parley.data.db.ContactMetaEntity(key)
                                            vm.c.meta.setMeta(
                                                m.copy(
                                                    contactId = newId,
                                                    pinnedNote = d.pinnedNote.ifBlank { null } ?: m.pinnedNote,
                                                    preferredMessenger = d.messengerPrefs.ifBlank { null } ?: m.preferredMessenger,
                                                ),
                                            )
                                        }
                                    }
                                    vm.toast(res.getString(R.string.vault_moved_out))
                                    back()
                                    open(Routes.contact(newId))
                                }
                            }
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.vault_share_qr)) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; shareQr = true })
                        DropdownMenuItem({ Text(stringResource(R.string.vault_expires)) }, leadingIcon = { Icon(Icons.Rounded.Timer, null) }, onClick = { menu = false; expiry = true })
                        DropdownMenuItem({ Text(stringResource(R.string.dc_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            },
        )
    }) { p ->
        LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(top = p.calculateTopPadding(), bottom = p.calculateBottomPadding() + 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(summary?.name ?: "?", card?.photoUri, 112.dp)
                    Text(summary?.name ?: "", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
                    card?.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    card?.context?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
                    Text(stringResource(R.string.vault_only_in_parley), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    summary?.expiresAt?.let { Text(stringResource(R.string.vault_deletes_on, Format.fullDate(context, it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(16.dp))
                    val first = summary?.numbers?.firstOrNull()
                    // U3 tiles; M6 "Message on…" for private contacts too.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile(Icons.Rounded.Call, stringResource(R.string.vault_call), first != null) { first?.let { vm.requestCall(it, summary.name) } }
                        val sms = stringResource(R.string.vault_sms)
                        val usual = prefs.message?.let { m -> if (m == app.parley.common.people.MessengerPrefs.SMS) sms else app.parley.common.MessengerApp.forPackage(m)?.label }
                        ActionTile(Icons.AutoMirrored.Rounded.Message, usual ?: stringResource(R.string.vault_message), first != null, onLongClick = { messageSheet = first.orEmpty() }, longClickLabel = stringResource(R.string.vault_choose_message)) { message() }
                        val email = details?.emails?.firstOrNull()?.value
                        ActionTile(Icons.Rounded.Email, stringResource(R.string.vault_email), email != null) { email?.let { Intents.email(context, it) } }
                    }
                }
            }
            card?.note?.let { note ->
                item {
                    SegmentedGroup {
                        item {
                            ListItem(
                                colors = app.parley.ui.contact.groupRowColors(),
                                leadingContent = { Icon(Icons.Rounded.PushPin, null, tint = MaterialTheme.colorScheme.primary) },
                                headlineContent = { Text(note) },
                                supportingContent = { Text(stringResource(R.string.vault_shown_when_call)) },
                            )
                        }
                    }
                }
            }
            if (locked) {
                item {
                    SegmentedGroup {
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.vault_unlock_all)) },
                                supportingContent = { Text(stringResource(R.string.vault_unlock_all_summary)) },
                                leadingContent = { Icon(Icons.Rounded.Lock, null) },
                                colors = app.parley.ui.contact.groupRowColors(),
                                modifier = Modifier.clickable { unlock() },
                            )
                        }
                    }
                }
            }
            val d = details
            if (d != null) {
                val phones = d.phones.filter { it.value.isNotBlank() }
                if (phones.isNotEmpty()) item {
                    SegmentedGroup(stringResource(R.string.vault_phone)) {
                        phones.forEachIndexed { i, ph ->
                            item {
                                app.parley.ui.contact.GroupDataRow(
                                    Icons.Rounded.Call, i == 0, ph.value, Format.phoneType(context.resources, ph.type, ph.label),
                                    onClick = { vm.requestCall(ph.value, d.displayName) },
                                    headline = { Text(DataL10n.ltr(Format.number(ph.value, vm.countryIso))) },
                                    trailing = { IconButton({ message(ph.value) }) { Icon(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.vault_message_number)) } },
                                    menu = { close ->
                                        DropdownMenuItem({ Text(stringResource(R.string.vault_message_on)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, onClick = { close(); messageSheet = ph.value })
                                    },
                                )
                            }
                        }
                    }
                }
                if (d.emails.isNotEmpty()) item {
                    SegmentedGroup(stringResource(R.string.vault_email)) {
                        d.emails.forEachIndexed { i, e -> item { app.parley.ui.contact.GroupDataRow(Icons.Rounded.Email, i == 0, e.value, null, onClick = { Intents.email(context, e.value) }) } }
                    }
                }
                if (d.handles.isNotEmpty()) item {
                    SegmentedGroup(stringResource(R.string.vault_messengers)) {
                        handleRows(d.handles, Icons.Rounded.Forum, onWeb = { webLink = it })
                    }
                }
                if (d.addresses.isNotEmpty() || d.note.isNotBlank() || d.websites.isNotEmpty()) item {
                    val addressLabel = stringResource(R.string.vault_address)
                    val websiteLabel = stringResource(R.string.vault_website)
                    val noteLabel = stringResource(R.string.vault_note)
                    SegmentedGroup(stringResource(R.string.vault_about)) {
                        d.addresses.forEachIndexed { i, a -> item { app.parley.ui.contact.GroupDataRow(Icons.Rounded.LocationOn, i == 0, a.formatted, addressLabel, onClick = { Intents.map(context, a.formatted) }) } }
                        d.websites.forEachIndexed { i, w -> item { app.parley.ui.contact.GroupDataRow(Icons.Rounded.Language, i == 0, w.value, websiteLabel, onClick = { Intents.web(context, w.value) }) } }
                        if (d.note.isNotBlank()) item { app.parley.ui.contact.GroupDataRow(Icons.AutoMirrored.Rounded.Notes, true, d.note, noteLabel, onClick = {}, headline = { LinkifiedText(d.note) }) }
                    }
                }
            }
            val mine = calls.filter { it.vaultId == id }
            if (mine.isNotEmpty()) {
                item {
                    SegmentedGroup(stringResource(R.string.vault_call_history)) {
                        mine.forEach { c ->
                            item {
                                val type = app.parley.data.CallLogRepository.mapType(c.type)
                                ListItem(
                                    colors = app.parley.ui.contact.groupRowColors(),
                                    leadingContent = { app.parley.ui.home.CallTypeIcon(type, durationSec = c.durationSec) },
                                    headlineContent = { Text(Format.fullDate(context, c.date)) },
                                    supportingContent = { Text(listOf(DataL10n.ltr(Format.number(c.number, vm.countryIso)), Format.duration(c.durationSec)).filter { it.isNotBlank() }.joinToString(" · ")) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    messageSheet?.let { n ->
        val r = reach()
        app.parley.ui.contact.ContactMessageSheet(
            r.copy(defaultNumber = n.ifEmpty { r.defaultNumber }),
            onDismiss = { messageSheet = null },
            onCall = { num -> vm.requestCall(num, summary?.name ?: r.name) },
        ) { p -> savePrefs(p) }
    }
    webLink?.let { l -> app.parley.ui.contact.ConfirmWebLink(l) { webLink = null } }
    if (shareQr) details?.let { app.parley.ui.contact.SecureQrDialog(it) { shareQr = false } }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.vault_delete_title)) },
            text = { Text(stringResource(R.string.vault_delete_text)) },
            confirmButton = { TextButton({ confirmDelete = false; scope.launch { vm.c.vault.delete(id); back() } }) { Text(stringResource(R.string.dc_delete)) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
    if (expiry) {
        ExpiryDialog(onDismiss = { expiry = false }) { days ->
            expiry = false
            scope.launch { vm.c.vault.setExpiry(id, days?.let { System.currentTimeMillis() + it * 86_400_000L }) }
        }
    }
}

/** "Delete after…" choice used for temporary contacts and vault entries. */
@Composable
fun ExpiryDialog(onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_expiry_title)) },
        text = {
            Column {
                listOf(1 to R.string.vault_expiry_1_day, 7 to R.string.vault_expiry_1_week, 30 to R.string.vault_expiry_30_days, 90 to R.string.vault_expiry_3_months, 365 to R.string.vault_expiry_1_year).forEach { (d, label) ->
                    ListItem(headlineContent = { Text(stringResource(label)) }, modifier = Modifier.clickable { onPick(d) })
                }
                ListItem(headlineContent = { Text(stringResource(R.string.vault_expiry_never)) }, modifier = Modifier.clickable { onPick(null) })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}
