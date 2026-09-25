package app.parley.ui.contact

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.PhoneNumbers
import app.parley.common.people.HandleLink
import app.parley.common.people.MessageRoute
import app.parley.common.people.MessengerPrefs
import app.parley.common.people.OtherFields
import app.parley.common.people.RelationTypes
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.OnGroupSurface
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.blended
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.shared
import app.parley.security.launchVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A contact's page. U1: the photo and name dock into the top bar as you scroll ("last talked" shows there once
 * collapsed); U2: grouped sections; U3: labelled Call / Message / Video / Email tiles; M6/M7: "Message on…" with a
 * remembered choice per person; I1 handles, I3 default number or e-mail, I4 other fields, I5 relation types.
 */
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
    var reloads by remember { mutableStateOf(0) }
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
    var messageSheet by remember { mutableStateOf<String?>(null) }
    var videoChooser by remember { mutableStateOf(false) }
    var webLink by remember { mutableStateOf<HandleLink?>(null) }
    val allNotes by vm.c.meta.allCallNotes().collectAsStateWithLifecycle(emptyList())
    var editNote by remember { mutableStateOf(false) }
    var messengers by remember { mutableStateOf<List<app.parley.data.MessengerAction>>(emptyList()) }
    var meta by remember { mutableStateOf<app.parley.data.db.ContactMetaEntity?>(null) }
    var otherFields by remember { mutableStateOf<List<OtherFields.Field>>(emptyList()) }
    LaunchedEffect(contactId, all) {
        messengers = withContext(Dispatchers.IO) { app.parley.data.Messengers.actions(context, contactId) }
    }
    LaunchedEffect(details) { details?.lookupKey?.let { meta = vm.c.meta.meta(it) } }
    fun saveMeta(f: (app.parley.data.db.ContactMetaEntity) -> app.parley.data.db.ContactMetaEntity) {
        val key = details?.lookupKey ?: return
        // The contact id is kept beside the key so the row can follow a key change (F8).
        val next = f(meta ?: app.parley.data.db.ContactMetaEntity(key)).copy(contactId = contactId)
        meta = next
        scope.launch { vm.c.meta.setMeta(next) }
    }
    val prefs = remember(meta) { MessengerPrefs.decode(meta?.preferredMessenger) }
    fun savePrefs(p: MessengerPrefs) = saveMeta { it.copy(preferredMessenger = p.encode()) }
    val temps by vm.c.meta.temporaryContacts().collectAsStateWithLifecycle(emptyList())
    val temp = details?.lookupKey?.let { k -> temps.firstOrNull { it.lookupKey == k } }

    LaunchedEffect(contactId, all, reloads) {
        details = vm.c.contacts.details(contactId)
        loaded = true
    }
    // I4: kinds Parley doesn't edit, read-only, from the lossless record (messenger rows are shown as Messengers).
    LaunchedEffect(contactId, all) {
        otherFields = withContext(Dispatchers.IO) {
            runCatching {
                vm.c.records.read(contactId, fullPhoto = false)?.raws.orEmpty().flatMap { raw ->
                    val messenger = app.parley.common.record.Messengers.isMessengerAccount(raw.accountType)
                    OtherFields.describe(raw.rows) { messenger }
                }.distinctBy { it.label to it.value }
            }.getOrDefault(emptyList())
        }
    }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            scope.launch { vm.c.contacts.setRingtone(contactId, uri?.toString()) }
        }
    }

    val d = details
    // F7: same line by E.164 (read with this phone's country), not by the last 9 digits.
    val history = remember(calls, d?.phones) {
        val phones = d?.phones.orEmpty()
        if (phones.isEmpty()) emptyList() else {
            val mine = PhoneNumbers.LineSet(phones.map { it.value }, app.parley.data.PhoneEnv.countryIso(context))
            calls.orEmpty().filter { e -> e.number in mine }
        }
    }
    val talked = history.firstOrNull { it.durationSec > 0 }
    val lastTalked = if (talked != null) stringResource(R.string.detail_last_talked, android.text.format.DateUtils.getRelativeTimeSpanString(talked.date, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS)) else stringResource(R.string.recents_empty)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // U1: the header has scrolled away once the name is under the top bar.
    val collapseAt = with(density) { 190.dp.toPx() }
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseAt } }
    val headerFraction by remember {
        derivedStateOf { if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / collapseAt).coerceIn(0f, 1f) }
    }
    // U7: the bar takes the scrolled-content tint once content passes under it.
    val barColor by animateColorAsState(
        if (listState.canScrollBackward) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, label = "bar",
    )

    fun reach(dd: ContactDetails) = Reach(
        name = dd.given.ifBlank { dd.displayName },
        numbers = dd.phones.map { it.value to Format.phoneType(resources, it.type, it.label) },
        defaultNumber = (dd.phones.firstOrNull { it.isPrimary } ?: dd.phones.firstOrNull())?.value,
        messengers = messengers,
        prefs = prefs,
    )
    fun message(dd: ContactDetails, number: String? = null) {
        val r = reach(dd).let { if (number != null) it.copy(defaultNumber = number, prefs = it.prefs.copy(number = null)) else it }
        when (val route = ContactMessaging.route(context, r)) {
            MessageRoute.Ask -> messageSheet = number ?: r.defaultNumber ?: ""
            else -> ContactMessaging.open(context, route, r)?.let { vm.toast(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(collapsed && d != null, enter = fadeIn(), exit = fadeOut()) {
                        if (d != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(d.displayName, d.photoUri, 36.dp, isCompany = d.composedName.isBlank() && d.company.isNotBlank())
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(d.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(lastTalked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor, scrolledContainerColor = barColor),
                navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.main_back)) } },
                actions = {
                    if (d != null) {
                        IconButton({ scope.launch { vm.c.contacts.setStarred(contactId, !d.starred) } }) {
                            Icon(if (d.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, stringResource(if (d.starred) R.string.sel_unstar else R.string.sel_star))
                        }
                        IconButton({ open(Routes.edit(id = contactId)) }) { Icon(Icons.Rounded.Edit, stringResource(R.string.main_edit)) }
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.detail_share_file)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                                menu = false; Intents.shareVcard(context, vm.c.contacts.vcardUri(d.lookupKey), d.displayName)
                            })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_show_qr)) }, leadingIcon = { Icon(Icons.Rounded.QrCode2, null) }, onClick = { menu = false; showQr = true })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_share_private)) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; secureQr = true })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_versions)) }, leadingIcon = { Icon(Icons.Rounded.History, null) }, onClick = { menu = false; open(Routes.versions(contactId)) })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_add_home)) }, leadingIcon = { Icon(Icons.Rounded.AddToHomeScreen, null) }, onClick = { menu = false; pinDialog = true })
                            if (d.phones.isNotEmpty()) DropdownMenuItem({ Text(stringResource(R.string.detail_copy_sim)) }, leadingIcon = { Icon(Icons.Rounded.SimCard, null) }, onClick = { menu = false; copyToSim = true })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_set_ringtone)) }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) }, onClick = {
                                menu = false
                                ringtonePicker.launch(
                                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, d.customRingtone?.let(Uri::parse)),
                                )
                            })
                            if (d.phones.isNotEmpty()) {
                                DropdownMenuItem({ Text(stringResource(R.string.detail_block_numbers)) }, leadingIcon = { Icon(Icons.Rounded.Block, null) }, onClick = {
                                    menu = false; d.phones.forEach { vm.blockNumber(it.value) }
                                })
                            }
                            app.parley.ui.blocking.ContactPrefixAllowMenuItem(d.composedName.ifBlank { null }, d.phones.map { it.value }) { menu = false }
                            if (d.rawContacts.size > 1) {
                                DropdownMenuItem({ Text(stringResource(R.string.detail_separate)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallSplit, null) }, onClick = {
                                    menu = false; scope.launch { vm.c.contacts.separate(contactId); back() }
                                })
                            }
                            DropdownMenuItem({ Text(stringResource(R.string.detail_move_vault)) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = {
                                menu = false
                                scope.launchVault(context as? androidx.fragment.app.FragmentActivity, { e -> vm.toast(resources.getString(R.string.detail_move_failed, e.message.orEmpty())) }) {
                                    // I6: the note for calls and the messaging choice go with them (encrypted).
                                    val id = vm.moveToVault(contactId, d.copy(pinnedNote = meta?.pinnedNote.orEmpty(), messengerPrefs = prefs.encode().orEmpty()))
                                    // Now kept encrypted with them: no plaintext copy stays in Parley's metadata.
                                    if (meta != null) vm.c.meta.deleteMeta(d.lookupKey)
                                    vm.toast(resources.getString(R.string.detail_moved_private))
                                    back()
                                    open(Routes.vault(id))
                                }
                            })
                            DropdownMenuItem({ Text(stringResource(if (temp != null) R.string.detail_change_expiry else R.string.detail_delete_after)) }, leadingIcon = { Icon(Icons.Rounded.Timer, null) }, onClick = { menu = false; askExpiry = true })
                            DropdownMenuItem({ Text(stringResource(R.string.main_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (d == null) {
            if (loaded) Text(stringResource(R.string.detail_gone), Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val primary = d.phones.firstOrNull { it.isPrimary } ?: d.phones.firstOrNull()
        val r = reach(d)
        LazyColumn(state = listState, contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // U1: shrinks and fades as it scrolls under the bar, where the small avatar and name appear.
                    Column(
                        Modifier.graphicsLayer {
                            val s = 1f - 0.25f * headerFraction
                            scaleX = s
                            scaleY = s
                            alpha = 1f - headerFraction
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(
                            d.displayName, d.photoUri, 120.dp,
                            Modifier.shared("avatar-$contactId").clickable(enabled = d.photoUri != null, onClickLabel = stringResource(R.string.detail_view_photo)) { showPhoto = true },
                            isCompany = d.composedName.isBlank() && d.company.isNotBlank(),
                        )
                        Text(d.displayName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).shared("name-$contactId", bounds = true))
                    }
                    val sub = listOf(d.nickname, listOf(d.title, d.company).filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }
                    if (sub.isNotEmpty()) Text(sub.joinToString(stringResource(R.string.main_separator)), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Text(lastTalked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    temp?.let { Text(stringResource(R.string.detail_deletes_on, Format.fullDate(context, it.expiresAt)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    app.parley.ui.people.AccountChips(vm, d, open) { newId ->
                        if (newId != null && newId != contactId) { back(); open(Routes.contact(newId)) }
                        else reloads++
                    }
                    Spacer(Modifier.height(16.dp))
                    // U3: labelled tiles.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val preferredCall = messengers.firstOrNull { it.accountType == prefs.call && it.isCall && !it.isVideo }
                        ActionTile(Icons.Rounded.Call, if (preferredCall != null) preferredCall.appName else stringResource(R.string.main_call), primary != null || preferredCall != null) {
                            if (preferredCall != null) ContactMessaging.start(context, preferredCall.intent(), preferredCall.appName)?.let { vm.toast(it) }
                            else primary?.let { vm.requestCall(it.value, d.displayName) }
                        }
                        val messageApp = prefs.message?.let { p -> if (p == MessengerPrefs.SMS) stringResource(R.string.detail_sms) else messengers.firstOrNull { it.accountType == p }?.appName ?: app.parley.common.MessengerApp.forPackage(p)?.label }
                        ActionTile(
                            Icons.AutoMirrored.Rounded.Message, messageApp ?: stringResource(R.string.main_message), primary != null || r.linked.isNotEmpty(),
                            onLongClick = { messageSheet = primary?.value.orEmpty() }, longClickLabel = stringResource(R.string.detail_choose_message),
                        ) { message(d) }
                        if (r.videoRows.isNotEmpty()) {
                            val preferredVideo = r.videoRows.firstOrNull { it.accountType == prefs.video }
                            ActionTile(
                                Icons.Rounded.Videocam, preferredVideo?.appName ?: stringResource(R.string.detail_video), true,
                                onLongClick = { videoChooser = true }, longClickLabel = stringResource(R.string.detail_choose_video),
                            ) {
                                val only = r.videoRows.distinctBy { it.accountType }.singleOrNull()
                                val target = preferredVideo ?: only
                                if (target != null) ContactMessaging.start(context, target.intent(), target.appName)?.let { vm.toast(it) } else videoChooser = true
                            }
                        }
                        ActionTile(Icons.Rounded.Email, stringResource(R.string.detail_email), d.emails.isNotEmpty()) {
                            (d.emails.firstOrNull { it.isPrimary } ?: d.emails.firstOrNull())?.let { Intents.email(context, it.value) }
                        }
                    }
                }
            }
            item(key = "note") {
                val note = meta?.pinnedNote
                SegmentedGroup {
                    item {
                        ListItem(
                            modifier = Modifier.clickable { editNote = true },
                            colors = groupRowColors(),
                            leadingContent = { Icon(Icons.Rounded.PushPin, null, tint = if (note != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                            headlineContent = { Text(note ?: stringResource(R.string.detail_add_note)) },
                            supportingContent = { Text(stringResource(if (note != null) R.string.detail_note_shown else R.string.detail_note_hint)) },
                        )
                    }
                }
            }
            if (d.phones.isNotEmpty()) item(key = "phones") {
                SegmentedGroup(stringResource(R.string.detail_phone)) {
                    d.phones.forEachIndexed { i, p ->
                        item {
                            val pinned = simPrefs.firstOrNull { it.matchKey == PhoneNumbers.matchKey(p.value) }?.phoneAccountId
                            PhoneRow(
                                vm, p, first = i == 0,
                                label = listOfNotNull(Format.phoneType(resources, p.type, p.label), pinned?.let { id -> sims.firstOrNull { it.id == id }?.label?.let { resources.getString(R.string.detail_always_sim, it) } }).joinToString(resources.getString(R.string.main_separator)),
                                canDefault = d.phones.size > 1 && p.id != null,
                                multiSim = sims.size > 1,
                                onCall = { vm.requestCall(p.value, d.displayName) },
                                onMessage = { message(d, p.value) },
                                onMessageOn = { messageSheet = p.value },
                                onSim = { simFor = p.value },
                                onDefault = { on -> scope.launch { setDefault(vm, contactId, p, Phone.CONTENT_ITEM_TYPE, on); reloads++ } },
                            )
                        }
                    }
                }
            }
            if (d.emails.isNotEmpty()) item(key = "emails") {
                SegmentedGroup(stringResource(R.string.detail_email)) {
                    d.emails.forEachIndexed { i, e ->
                        item {
                            GroupDataRow(
                                Icons.Rounded.Email, i == 0, e.value, Format.emailType(resources, e.type, e.label), onClick = { Intents.email(context, e.value) },
                                trailing = if (e.isPrimary && d.emails.size > 1) ({ Icon(Icons.Rounded.Star, stringResource(R.string.detail_default_email), tint = MaterialTheme.colorScheme.primary) }) else null,
                                menu = if (d.emails.size > 1 && e.id != null) ({ close ->
                                    DefaultMenuItem(e.isPrimary) { on -> close(); scope.launch { setDefault(vm, contactId, e, Email.CONTENT_ITEM_TYPE, on); reloads++ } }
                                }) else null,
                            )
                        }
                    }
                }
            }
            if (d.addresses.isNotEmpty()) item(key = "addresses") {
                SegmentedGroup(stringResource(R.string.detail_address)) {
                    d.addresses.forEachIndexed { i, a ->
                        item { GroupDataRow(Icons.Rounded.LocationOn, i == 0, a.formatted, StructuredPostal.getTypeLabel(resources, a.type, a.label).toString(), onClick = { Intents.map(context, a.formatted) }) }
                    }
                }
            }
            val chatRows = messengers
            if (d.handles.isNotEmpty() || chatRows.isNotEmpty()) item(key = "messengers") {
                SegmentedGroup(stringResource(R.string.detail_messengers)) {
                    // I1: handles typed into the contact (Matrix, Threema, Signal username…).
                    handleRows(d.handles, Icons.Rounded.Forum, onWeb = { webLink = it })
                    // What messenger apps added themselves (WhatsApp, Signal, Telegram…).
                    chatRows.forEachIndexed { i, m ->
                        item {
                            val preferred = (m.isCall && prefs.call == m.accountType && !m.isVideo) || (m.isVideo && prefs.video == m.accountType) ||
                                (!m.isCall && !m.isVideo && prefs.message == m.accountType)
                            GroupDataRow(
                                if (m.isVideo) Icons.Rounded.Videocam else if (m.isCall) Icons.Rounded.Call else Icons.AutoMirrored.Rounded.Chat,
                                showIcon = true, text = m.label, label = if (preferred) resources.getString(R.string.detail_usual_choice, m.appName) else m.appName,
                                onClick = { ContactMessaging.start(context, m.intent(), m.appName)?.let { vm.toast(it) } },
                                trailing = if (preferred) ({ Icon(Icons.Rounded.Star, stringResource(R.string.detail_usual), tint = MaterialTheme.colorScheme.primary) }) else null,
                                menu = { close ->
                                    DropdownMenuItem(
                                        { Text(stringResource(if (preferred) R.string.detail_dont_use_default else R.string.detail_use_default)) },
                                        leadingIcon = { Icon(if (preferred) Icons.Rounded.StarOutline else Icons.Rounded.Star, null) },
                                        onClick = {
                                            close()
                                            savePrefs(
                                                when {
                                                    m.isVideo -> prefs.copy(video = m.accountType.takeUnless { preferred })
                                                    m.isCall -> prefs.copy(call = m.accountType.takeUnless { preferred })
                                                    else -> prefs.copy(message = m.accountType.takeUnless { preferred })
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            if (d.events.isNotEmpty() || d.websites.isNotEmpty() || d.note.isNotBlank() || d.relations.isNotEmpty()) item(key = "about") {
                SegmentedGroup(stringResource(R.string.detail_about, d.given.ifBlank { d.displayName })) {
                    d.events.forEachIndexed { i, ev ->
                        item { GroupDataRow(Icons.Rounded.Cake, i == 0, app.parley.ui.people.describeLifeEvent(resources, d, ev), app.parley.ui.people.eventLabel(resources, ev), onClick = {}) }
                    }
                    d.websites.forEachIndexed { i, w -> item { GroupDataRow(Icons.Rounded.Language, i == 0, w.value, resources.getString(R.string.detail_website), onClick = { Intents.web(context, w.value) }) } }
                    d.relations.forEachIndexed { i, rel ->
                        item {
                            val label = RelationTypes.fromAndroid(rel.type, rel.label)?.let { app.parley.ui.people.RelationText.label(resources, it) }
                                ?: android.provider.ContactsContract.CommonDataKinds.Relation.getTypeLabel(resources, rel.type, rel.label).toString()
                            GroupDataRow(Icons.Rounded.People, i == 0, rel.value, label, onClick = {
                                // By the remembered lookup key first, then by name; several namesakes: ask (F23).
                                scope.launch {
                                    val link = app.parley.common.people.RelationLinks.decode(meta?.relationLinks)[app.parley.common.people.RelationLinks.nameKey(rel.value)]
                                    val target = withContext(Dispatchers.IO) {
                                        app.parley.common.people.RelationLinks.resolve(
                                            rel.value, link, { l -> vm.c.contacts.currentOf(l.lookupKey, l.contactId)?.first },
                                            all.orEmpty().map { it.id to it.displayName }, self = contactId,
                                        )
                                    }
                                    when (target) {
                                        is app.parley.common.people.RelationLinks.Target.Contact -> open(Routes.contact(target.id))
                                        is app.parley.common.people.RelationLinks.Target.Choose -> relationChoice = target.ids
                                        app.parley.common.people.RelationLinks.Target.None -> vm.toast(resources.getString(R.string.detail_no_contact_named, rel.value))
                                    }
                                }
                            })
                        }
                    }
                    if (d.note.isNotBlank()) item {
                        GroupDataRow(Icons.AutoMirrored.Rounded.Notes, true, d.note, resources.getString(R.string.detail_note), onClick = {}, headline = { LinkifiedText(d.note) })
                    }
                }
            }
            if (otherFields.isNotEmpty()) item(key = "other") {
                SegmentedGroup(stringResource(R.string.detail_other_fields)) {
                    otherFields.forEachIndexed { i, f -> item { GroupDataRow(Icons.Rounded.Info, i == 0, f.value, f.label, onClick = {}) } }
                }
                GroupNote(stringResource(R.string.detail_other_fields_note))
            }
            item(key = "settings") {
                SegmentedGroup(stringResource(R.string.home_settings)) {
                    item {
                        ListItem(
                            colors = groupRowColors(),
                            leadingContent = { Icon(Icons.Rounded.Voicemail, null) },
                            headlineContent = { Text(stringResource(R.string.detail_send_to_voicemail)) },
                            trailingContent = { Switch(d.sendToVoicemail, { v -> scope.launch { vm.c.contacts.setSendToVoicemail(contactId, v); reloads++ } }) },
                        )
                    }
                    item {
                        val every = meta?.reachOutDays
                        ListItem(
                            modifier = Modifier.clickable { reachOut = true },
                            colors = groupRowColors(),
                            leadingContent = { Icon(Icons.Rounded.NotificationsActive, null) },
                            headlineContent = { Text(stringResource(R.string.detail_keep_in_touch)) },
                            supportingContent = { Text(every?.let { pluralStringResource(R.plurals.detail_not_talked_days, it, it) } ?: stringResource(R.string.detail_off)) },
                        )
                    }
                    blended { app.parley.ui.calltime.ContactCallTimeRows(vm, d.lookupKey, d.displayName, d.starred) }
                    item {
                        val tone = d.customRingtone?.let { runCatching { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }.getOrNull() }
                        GroupDataRow(Icons.Rounded.MusicNote, true, tone ?: resources.getString(R.string.detail_default_ringtone), resources.getString(R.string.detail_ringtone), onClick = {
                            ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE))
                        })
                    }
                    item {
                        GroupDataRow(Icons.Rounded.Sync, true, d.rawContacts.joinToString("\n") { it.account.displayLabel }, if (d.rawContacts.size > 1) resources.getQuantityString(R.plurals.detail_linked_from, d.rawContacts.size, d.rawContacts.size) else resources.getString(R.string.detail_saved_in), onClick = {})
                    }
                    blended { app.parley.ui.people.ProvenanceRow(vm, contactId, d, open) }
                    blended { app.parley.ui.people.CallBackgroundInfoRow(vm, d) { open(Routes.edit(id = contactId)) } }
                }
            }
            val keys = d.phones.map { PhoneNumbers.matchKey(it.value) }.toSet()
            val notes = allNotes.filter { it.numberKey in keys }
            if (notes.isNotEmpty()) item(key = "callnotes") {
                SegmentedGroup(stringResource(R.string.detail_call_notes)) {
                    notes.take(10).forEachIndexed { i, n ->
                        item { GroupDataRow(Icons.AutoMirrored.Rounded.Notes, i == 0, n.text, Format.fullDate(context, n.callDate), onClick = {}, headline = { LinkifiedText(n.text) }) }
                    }
                }
            }
            item(key = "insights") { OnGroupSurface { app.parley.ui.history.CallInsightsSection(vm, d.phones.map { it.value }) } }
            if (history.isNotEmpty()) item(key = "recent") {
                SegmentedGroup(stringResource(R.string.detail_recent_calls)) {
                    history.take(5).forEachIndexed { _, e ->
                        item {
                            ListItem(
                                colors = groupRowColors(),
                                leadingContent = { app.parley.ui.home.CallTypeIcon(e.type) },
                                headlineContent = { Text(Format.fullDate(context, e.date)) },
                                supportingContent = { Text(listOf(Bidi.ltr(Format.number(e.number, vm.countryIso)), Format.duration(e.durationSec)).filter { it.isNotBlank() }.joinToString(stringResource(R.string.main_separator))) },
                            )
                        }
                    }
                }
                if (history.size > 5 && primary != null) {
                    TextButton({ open(Routes.history(primary.value)) }, Modifier.padding(start = 16.dp)) { Text(pluralStringResource(R.plurals.detail_see_all_calls, history.size, history.size)) }
                }
            }
        }

        messageSheet?.let { n ->
            ContactMessageSheet(
                r.copy(defaultNumber = n.ifEmpty { r.defaultNumber }),
                onDismiss = { messageSheet = null },
                onRemember = { p -> savePrefs(p) },
            )
        }
        if (videoChooser) VideoChooser(r, onDismiss = { videoChooser = false }) { p -> savePrefs(p) }
        webLink?.let { l -> ConfirmWebLink(l) { webLink = null } }
        if (showQr) QrDialog(d) { showQr = false }
        if (secureQr) SecureQrDialog(d) { secureQr = false }
        if (copyToSim) app.parley.ui.people.CopyToSimDialog(vm, d) { copyToSim = false }
        if (editNote) {
            var text by remember { mutableStateOf(meta?.pinnedNote.orEmpty()) }
            AlertDialog(
                onDismissRequest = { editNote = false },
                title = { Text(stringResource(R.string.detail_note_title)) },
                text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, placeholder = { Text(stringResource(R.string.detail_note_placeholder)) }, minLines = 2) },
                confirmButton = { TextButton({ editNote = false; saveMeta { it.copy(pinnedNote = text.trim().ifEmpty { null }) } }) { Text(stringResource(R.string.main_save)) } },
                dismissButton = { TextButton({ editNote = false }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
        if (reachOut) {
            AlertDialog(
                onDismissRequest = { reachOut = false },
                title = { Text(stringResource(R.string.detail_keep_in_touch_title)) },
                text = {
                    Column {
                        app.parley.ui.history.RhythmSuggestion(vm, d.phones.map { it.value }) { days -> reachOut = false; saveMeta { it.copy(reachOutDays = days, lastNudgedAt = null) } }
                        listOf(
                            null to stringResource(R.string.detail_off), 7 to stringResource(R.string.detail_every_week), 14 to stringResource(R.string.detail_every_2_weeks),
                            30 to stringResource(R.string.detail_every_month), 90 to stringResource(R.string.detail_every_3_months), 180 to stringResource(R.string.detail_every_6_months),
                        ).forEach { (days, label) ->
                            ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { reachOut = false; saveMeta { it.copy(reachOutDays = days, lastNudgedAt = null) } })
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ reachOut = false }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
        if (pinDialog) {
            AlertDialog(
                onDismissRequest = { pinDialog = false },
                title = { Text(stringResource(R.string.detail_add_home)) },
                text = {
                    Column {
                        d.phones.forEach { p ->
                            ListItem(headlineContent = { Text(stringResource(R.string.main_call_who, Bidi.ltr(Format.number(p.value, vm.countryIso)))) }, leadingContent = { Icon(Icons.Rounded.Call, null) }, modifier = Modifier.clickable {
                                pinDialog = false
                                app.parley.shortcuts.Shortcuts.pin(context, app.parley.shortcuts.Shortcuts.Kind.CALL, d.displayName, p.value, contactId, d.photoUri)
                            })
                            ListItem(headlineContent = { Text(stringResource(R.string.main_message_who, Bidi.ltr(Format.number(p.value, vm.countryIso)))) }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, modifier = Modifier.clickable {
                                pinDialog = false
                                app.parley.shortcuts.Shortcuts.pin(context, app.parley.shortcuts.Shortcuts.Kind.MESSAGE, d.displayName, p.value, contactId, d.photoUri)
                            })
                        }
                        ListItem(headlineContent = { Text(stringResource(R.string.main_open_contact)) }, leadingContent = { Icon(Icons.Rounded.Person, null) }, modifier = Modifier.clickable {
                            pinDialog = false
                            app.parley.shortcuts.Shortcuts.pin(context, app.parley.shortcuts.Shortcuts.Kind.OPEN, d.displayName, null, contactId, d.photoUri, d.lookupKey)
                        })
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ pinDialog = false }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
        relationChoice?.let { ids ->
            AlertDialog(
                onDismissRequest = { relationChoice = null },
                title = { Text(stringResource(R.string.detail_which_contact)) },
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
                                supportingContent = { ct.phones.firstOrNull()?.let { Text(Bidi.ltr(Format.number(it.number, vm.countryIso))) } },
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ relationChoice = null }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
        if (askExpiry) app.parley.ui.vault.ExpiryDialog(onDismiss = { askExpiry = false }) { days ->
            askExpiry = false
            scope.launch {
                if (days == null) vm.c.temporaries.clear(d.lookupKey) else vm.c.temporaries.mark(contactId, days, purgeHistory = true)
                vm.toast(if (days == null) resources.getString(R.string.detail_kept) else resources.getQuantityString(R.plurals.detail_deletes_in_days, days, days))
            }
        }
        d.photoUri?.takeIf { showPhoto }?.let { PhotoViewer(it) { showPhoto = false } }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.detail_delete_title, d.displayName)) },
                text = { Text(stringResource(R.string.detail_delete_body)) },
                confirmButton = { TextButton({ confirmDelete = false; vm.deleteContacts(listOf(contactId)); back() }) { Text(stringResource(R.string.main_delete)) } },
                dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
        simFor?.let { number ->
            AlertDialog(
                onDismissRequest = { simFor = null },
                title = { Text(stringResource(R.string.detail_sim_for, Bidi.ltr(Format.number(number, vm.countryIso)))) },
                text = {
                    Column {
                        ListItem(headlineContent = { Text(stringResource(R.string.detail_sim_ask)) }, modifier = Modifier.clickable { scope.launch { vm.c.prefs.setSimFor(number, null) }; simFor = null })
                        sims.forEach { s ->
                            ListItem(headlineContent = { Text(stringResource(R.string.detail_always_sim, s.label)) }, leadingContent = { Icon(Icons.Rounded.SimCard, null) }, modifier = Modifier.clickable {
                                scope.launch { vm.c.prefs.setSimFor(number, s.id) }; simFor = null
                            })
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ simFor = null }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
    }
}

/** I3: makes [item] the default of its kind, or clears the default. */
private suspend fun setDefault(vm: AppViewModel, contactId: Long, item: DataItem, mime: String, on: Boolean) {
    val id = item.id ?: return
    val ok = if (on) vm.c.contacts.setDefault(id) else vm.c.contacts.clearDefault(contactId, mime)
    val app = vm.getApplication<android.app.Application>()
    vm.toast(
        when {
            !ok -> app.getString(R.string.detail_default_failed)
            on -> app.getString(R.string.detail_default_set)
            else -> app.getString(R.string.detail_default_removed)
        },
    )
}

@Composable
private fun DefaultMenuItem(isDefault: Boolean, onSet: (Boolean) -> Unit) {
    DropdownMenuItem(
        { Text(stringResource(if (isDefault) R.string.detail_remove_default else R.string.detail_set_default)) },
        leadingIcon = { Icon(if (isDefault) Icons.Rounded.StarOutline else Icons.Rounded.Star, null) },
        onClick = { onSet(!isDefault) },
    )
}

/** One number: tap calls; the chat icon messages it (M6/M7); long-press: copy, default (I3), message on…, SIM. */
@Composable
private fun PhoneRow(
    vm: AppViewModel,
    p: DataItem,
    first: Boolean,
    label: String,
    canDefault: Boolean,
    multiSim: Boolean,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onMessageOn: () -> Unit,
    onSim: () -> Unit,
    onDefault: (Boolean) -> Unit,
) {
    GroupDataRow(
        Icons.Rounded.Call, first, p.value, label, onClick = onCall,
        headline = { Text(Bidi.ltr(Format.number(p.value, vm.countryIso))) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.isPrimary && canDefault) Icon(Icons.Rounded.Star, stringResource(R.string.detail_default_number), tint = MaterialTheme.colorScheme.primary)
                if (multiSim) IconButton(onSim) { Icon(Icons.Rounded.SimCard, stringResource(R.string.detail_choose_sim_number)) }
                IconButton(onMessage) { Icon(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.detail_message_number)) }
            }
        },
        menu = { close ->
            if (canDefault) DefaultMenuItem(p.isPrimary) { on -> close(); onDefault(on) }
            DropdownMenuItem({ Text(stringResource(R.string.missed_message_on)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, onClick = { close(); onMessageOn() })
            DropdownMenuItem({ Text(stringResource(R.string.detail_edit_before_call)) }, leadingIcon = { Icon(Icons.Rounded.Dialpad, null) }, onClick = {
                close(); vm.navigate(app.parley.NavEvent.Tab(app.parley.common.StartTab.KEYPAD, dial = p.value))
            })
            if (multiSim) DropdownMenuItem({ Text(stringResource(R.string.detail_choose_sim)) }, leadingIcon = { Icon(Icons.Rounded.SimCard, null) }, onClick = { close(); onSim() })
        },
    )
}

@Composable
fun Section(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    }
}
