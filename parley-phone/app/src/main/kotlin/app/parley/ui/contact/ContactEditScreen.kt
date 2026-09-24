package app.parley.ui.contact

import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.HandleService
import app.parley.common.people.Handles
import app.parley.common.people.LifeEvents
import app.parley.common.people.RelationLinks
import app.parley.common.people.RelationType
import app.parley.common.people.RelationTypes
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.GroupInfo
import app.parley.data.HandleItem
import app.parley.data.PostalItem
import app.parley.ui.Avatar
import app.parley.ui.CallColors
import app.parley.ui.people.applyBackground
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val phoneTypes = listOf(Phone.TYPE_MOBILE, Phone.TYPE_HOME, Phone.TYPE_WORK, Phone.TYPE_MAIN, Phone.TYPE_FAX_WORK, Phone.TYPE_OTHER)
private val emailTypes = listOf(Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_MOBILE, Email.TYPE_OTHER)
private val postalTypes = listOf(StructuredPostal.TYPE_HOME, StructuredPostal.TYPE_WORK, StructuredPostal.TYPE_OTHER)
/** Country used to interpret phone numbers typed in the editor. */
val LocalCountryIso = androidx.compose.runtime.staticCompositionLocalOf { "US" }

private val eventTypes = listOf(Event.TYPE_BIRTHDAY, Event.TYPE_ANNIVERSARY, Event.TYPE_OTHER)

/** Sections that are hidden until they have content or are added from "More fields" (U5). */
private enum class Extra(val label: Int) {
    ADDRESS(R.string.detail_address), DATE(R.string.edit_date), WEBSITE(R.string.detail_website), RELATION(R.string.edit_relation),
    HANDLE(R.string.edit_handle), NOTE(R.string.edit_notes), NAME(R.string.edit_name_details),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactEditScreen(
    vm: AppViewModel,
    contactId: Long?,
    prefillName: String,
    prefillPhone: String,
    prefillEmail: String,
    addPhone: String,
    prefill: ContactDetails? = null,
    /** Private vault mode: 0 = new vault contact, > 0 = edit that vault contact. */
    vaultId: Long? = null,
    /** Edit one specific copy (raw contact) of the contact ("Edit this copy" on the contact page). */
    rawId: Long? = null,
    done: (Long?) -> Unit,
) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<ContactDetails?>(null) }
    var draft by remember { mutableStateOf<ContactDetails?>(null) }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var account by remember { mutableStateOf<AccountRef?>(null) }
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var moreName by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var askKeep by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var start by remember { mutableStateOf<ContactDetails?>(null) }
    var revealed by remember { mutableStateOf(emptySet<Extra>()) }
    // I5: relations whose contact was chosen with the picker (name key → that contact).
    var pickedLinks by remember { mutableStateOf(emptyMap<String, RelationLinks.Link>()) }
    // New contacts go to the private vault when "Private by default" is on (the Save-to menu can change it).
    var privateNew by remember { mutableStateOf(false) }
    val isVault = vaultId != null || privateNew
    var bgChange by remember { mutableStateOf<app.parley.ui.people.BackgroundChange>(app.parley.ui.people.BackgroundChange.None) }
    val idx by vm.people.index.collectAsStateWithLifecycle()

    LaunchedEffect(contactId) {
        if (contactId == null && vaultId == null) privateNew = vm.c.people.prefs.current().privateByDefault
        accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() }
        groups = withContext(Dispatchers.IO) { vm.c.contacts.groups() }
        val s = vm.settings.value
        account = accounts.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName }
            ?: accounts.firstOrNull { it.type == "com.google" } ?: accounts.firstOrNull()
        if (vaultId != null) {
            var e = if (vaultId > 0) {
                try {
                    vm.c.vault.details(vaultId)
                } catch (_: app.parley.data.vault.VaultCrypto.LockedException) {
                    vm.toast(res.getString(R.string.edit_unlock_first))
                    done(null)
                    return@LaunchedEffect
                } ?: ContactDetails()
            } else {
                prefill ?: ContactDetails()
            }
            if (e.phones.isEmpty()) e = e.copy(phones = listOf(DataItem(type = Phone.TYPE_MOBILE)))
            draft = e
            start = e
            return@LaunchedEffect
        }
        if (contactId != null) {
            val d = if (rawId != null) vm.c.contacts.editableRaw(contactId, rawId) else vm.c.contacts.editable(contactId)
            original = d
            var e = d ?: ContactDetails()
            if (addPhone.isNotBlank()) e = e.copy(phones = e.phones + DataItem(value = addPhone, type = Phone.TYPE_MOBILE))
            if (prefill != null) e = app.parley.InsertPrefill.appendTo(e, prefill)
            if (e.phones.isEmpty()) e = e.copy(phones = listOf(DataItem(type = Phone.TYPE_MOBILE)))
            draft = e
            account = d?.rawContacts?.firstOrNull { it.id == d.editRawId }?.account ?: AccountRef(null, null)
        } else {
            if (prefill != null) {
                draft = if (prefill.phones.isEmpty()) prefill.copy(phones = listOf(DataItem(type = Phone.TYPE_MOBILE))) else prefill
                start = null
                return@LaunchedEffect
            }
            val parts = prefillName.trim().split(Regex("\\s+"), limit = 2)
            draft = ContactDetails(
                given = parts.getOrElse(0) { "" },
                family = parts.getOrElse(1) { "" },
                phones = listOf(DataItem(value = prefillPhone, type = Phone.TYPE_MOBILE)),
                emails = if (prefillEmail.isNotBlank()) listOf(DataItem(value = prefillEmail, type = Email.TYPE_HOME)) else emptyList(),
            )
        }
        start = draft
    }

    // The system photo picker needs no storage permission (also for private contacts' encrypted photos, I6).
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { photo = uri; removePhoto = false }
    }
    val d = draft
    val dirty = d != start || photo != null || removePhoto || bgChange != app.parley.ui.people.BackgroundChange.None
    BackHandler(enabled = dirty) { confirmDiscard = true }

    fun save() {
        val e = draft ?: return
        // A contact holding only an address, a note or a website is fine (F24); a completely empty one is not.
        val empty = e.composedName.isBlank() && e.nickname.isBlank() && e.company.isBlank() && e.title.isBlank() && e.note.isBlank() &&
            (e.phones + e.emails + e.websites + e.relations).all { it.value.isBlank() } && e.addresses.all { it.isBlank } &&
            e.events.all { it.date.isBlank() } && e.phoneticGiven.isBlank() && e.phoneticFamily.isBlank() && e.handles.all { it.value.isBlank() }
        // Clearing one copy of a linked contact is allowed: that empty copy is removed and the others stay.
        val orig = original
        if (empty && photo == null && (orig == null || orig.rawContacts.size < 2 || orig.editRawId == null)) {
            vm.toast(res.getString(if (original == null) R.string.edit_add_name_first else R.string.edit_nothing_left))
            return
        }
        saving = true
        scope.launch {
            val id = try {
                if (isVault) {
                    val existing = vaultId?.takeIf { it > 0 }
                    val cleaned = e.copy(handles = e.handles.filter { it.value.isNotBlank() })
                    val id = vm.c.vault.save(existing, cleaned)
                    // I6: the encrypted caller photo.
                    val picked = photo
                    if (picked != null) {
                        val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(picked)?.use { it.readBytes() } }.getOrNull() }
                        if (bytes == null || !vm.c.vault.setPhoto(id, bytes)) vm.toast(res.getString(R.string.edit_photo_failed))
                    } else if (removePhoto) {
                        vm.c.vault.removePhoto(id)
                    }
                    -id // negative ids mark vault contacts for the caller
                } else {
                    vm.c.contacts.save(original, e, account, photo, removePhoto)?.contactId.also { saved ->
                        original?.lookupKey?.let { key -> vm.applyBackground(key, bgChange) }
                        if (saved != null) rememberRelations(vm, saved, e, pickedLinks)
                    }
                }
            } catch (ex: Exception) {
                vm.toast(res.getString(R.string.edit_save_failed, ex.message.orEmpty()))
                null
            }
            saving = false
            if (id == null) return@launch
            // A temporary contact the user just edited for real: ask once whether to keep it (F2).
            val key = original?.lookupKey
            if (!isVault && !key.isNullOrEmpty() && vm.c.temporaries.needsKeepPrompt(key)) askKeep = key to id else done(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isVault) (if ((vaultId ?: 0) > 0) R.string.edit_title_private else R.string.edit_title_new_private)
                            else if (contactId == null) R.string.edit_title_new else if (rawId != null) R.string.edit_title_copy else R.string.edit_title_edit,
                        ),
                    )
                },
                navigationIcon = { IconButton({ if (dirty) confirmDiscard = true else done(null) }) { Icon(Icons.Rounded.Close, stringResource(R.string.main_cancel)) } },
                actions = { Button(onClick = ::save, enabled = !saving && d != null, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.main_save)) } },
            )
        },
    ) { padding ->
        if (d == null) return@Scaffold
        fun update(f: (ContactDetails) -> ContactDetails) { draft = f(d) }
        fun shown(x: Extra, has: Boolean) = has || x in revealed
        androidx.compose.runtime.CompositionLocalProvider(LocalCountryIso provides vm.countryIso, LocalLocked provides original?.readOnlyDataIds.orEmpty()) {
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isVault) {
                Text(
                    stringResource(R.string.edit_private_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (contactId == null && vaultId == null) {
                app.parley.ui.people.DuplicateWarning(vm, d, onOpen = { id -> vm.navigate(app.parley.NavEvent.Contact(id)) }) { id ->
                    // "Add these details to her": continue in the existing contact's editor with this draft appended.
                    vm.pendingPrefill = d
                    done(null)
                    vm.navigate(app.parley.NavEvent.Route(app.parley.ui.Routes.edit(id = id, prefill = true)))
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val shownPhoto = photo?.toString() ?: d.photoUri.takeUnless { removePhoto }
                Avatar(d.composedName.ifBlank { "?" }, shownPhoto, 104.dp, Modifier.clickable(onClickLabel = stringResource(R.string.edit_choose_photo)) {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                Icon(Icons.Rounded.AddAPhoto, stringResource(R.string.edit_change_photo), Modifier.align(Alignment.BottomCenter).padding(start = 80.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (photo != null || (d.photoUri != null && !removePhoto)) {
                TextButton({ photo = null; removePhoto = true }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.edit_remove_photo)) }
            }
            if (isVault && (photo != null || d.photoUri != null)) {
                Text(
                    stringResource(R.string.edit_private_photo),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            if (vaultId != null) {
                // no account for vault contacts
            } else if (original == null) {
                val privateLabel = stringResource(R.string.edit_private_only)
                Dropdown(
                    stringResource(R.string.edit_save_to), if (privateNew) privateLabel else account?.let { idx.labelWithCount(it) } ?: stringResource(R.string.edit_phone_only),
                    listOf(privateLabel) + accounts.map { idx.labelWithCount(it) },
                ) { i -> if (i == 0) privateNew = true else { privateNew = false; account = accounts[i - 1] } }
            } else {
                Text(stringResource(R.string.edit_saved_in, account?.displayLabel ?: stringResource(R.string.detail_phone)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            EditorGroup(stringResource(R.string.edit_name)) {
                Field(stringResource(R.string.edit_first_name), d.given, KeyboardCapitalization.Words, rowId = d.nameId) { v -> update { it.copy(given = v) } }
                Field(stringResource(R.string.edit_last_name), d.family, KeyboardCapitalization.Words, rowId = d.nameId) { v -> update { it.copy(family = v) } }
                val nameDetails = moreName || Extra.NAME in revealed || listOf(d.prefix, d.middle, d.suffix, d.phoneticGiven, d.phoneticFamily, d.nickname).any { it.isNotBlank() }
                if (nameDetails) {
                    Field(stringResource(R.string.edit_prefix), d.prefix, KeyboardCapitalization.Words) { v -> update { it.copy(prefix = v) } }
                    Field(stringResource(R.string.edit_middle_name), d.middle, KeyboardCapitalization.Words) { v -> update { it.copy(middle = v) } }
                    Field(stringResource(R.string.edit_suffix), d.suffix, KeyboardCapitalization.Words) { v -> update { it.copy(suffix = v) } }
                    Field(stringResource(R.string.edit_phonetic_first), d.phoneticGiven, KeyboardCapitalization.Words) { v -> update { it.copy(phoneticGiven = v) } }
                    Field(stringResource(R.string.edit_phonetic_last), d.phoneticFamily, KeyboardCapitalization.Words) { v -> update { it.copy(phoneticFamily = v) } }
                    Field(stringResource(R.string.edit_nickname), d.nickname, KeyboardCapitalization.Words) { v -> update { it.copy(nickname = v) } }
                } else {
                    Row(Modifier.clickable { moreName = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (moreName) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                        Text(stringResource(R.string.edit_more_name), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Field(stringResource(R.string.edit_company), d.company, KeyboardCapitalization.Words, rowId = d.orgId) { v -> update { it.copy(company = v) } }
                Field(stringResource(R.string.edit_job_title), d.title, KeyboardCapitalization.Words, rowId = d.orgId) { v -> update { it.copy(title = v) } }
            }

            MultiSection(
                stringResource(R.string.detail_phone), stringResource(R.string.edit_add_phone), d.phones, phoneTypes, { Phone.getTypeLabel(res, it, null).toString() }, KeyboardType.Phone,
                onChange = { list -> update { it.copy(phones = list) } }, newItem = { DataItem(type = Phone.TYPE_MOBILE) },
            )
            MultiSection(
                stringResource(R.string.detail_email), stringResource(R.string.edit_add_email), d.emails, emailTypes, { Email.getTypeLabel(res, it, null).toString() }, KeyboardType.Email,
                onChange = { list -> update { it.copy(emails = list) } }, newItem = { DataItem(type = Email.TYPE_HOME) },
            )

            if (shown(Extra.HANDLE, d.handles.isNotEmpty())) {
                HandlesSection(d.handles) { list -> update { it.copy(handles = list) } }
            }

            if (shown(Extra.ADDRESS, d.addresses.isNotEmpty())) EditorGroup(stringResource(R.string.detail_address)) {
                d.addresses.forEachIndexed { i, a ->
                    fun set(n: PostalItem) = update { it.copy(addresses = it.addresses.toMutableList().also { l -> l[i] = n }) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            Dropdown(stringResource(R.string.edit_type), StructuredPostal.getTypeLabel(res, a.type, a.label).toString(), postalTypes.map { StructuredPostal.getTypeLabel(res, it, null).toString() }) { t -> set(a.copy(type = postalTypes[t])) }
                        }
                        RemoveButton(stringResource(R.string.edit_remove_address)) { update { it.copy(addresses = it.addresses.filterIndexed { j, _ -> j != i }) } }
                    }
                    Field(stringResource(R.string.edit_street), a.street, KeyboardCapitalization.Words, rowId = a.id) { set(a.copy(street = it)) }
                    // Shown when the address has them, so editing never drops a PO box or neighbourhood (F25).
                    if (a.poBox.isNotEmpty() || a.neighborhood.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(0.4f)) { Field(stringResource(R.string.edit_po_box), a.poBox, rowId = a.id) { set(a.copy(poBox = it)) } }
                            Box(Modifier.weight(0.6f)) { Field(stringResource(R.string.edit_neighbourhood), a.neighborhood, KeyboardCapitalization.Words, rowId = a.id) { set(a.copy(neighborhood = it)) } }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(0.4f)) { Field(stringResource(R.string.edit_postcode), a.postcode) { set(a.copy(postcode = it)) } }
                        Box(Modifier.weight(0.6f)) { Field(stringResource(R.string.edit_city), a.city, KeyboardCapitalization.Words) { set(a.copy(city = it)) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Field(stringResource(R.string.edit_region), a.region, KeyboardCapitalization.Words) { set(a.copy(region = it)) } }
                        Box(Modifier.weight(1f)) { Field(stringResource(R.string.edit_country), a.country, KeyboardCapitalization.Words) { set(a.copy(country = it)) } }
                    }
                }
                AddButton(stringResource(R.string.edit_add_address)) { update { it.copy(addresses = it.addresses + PostalItem(type = StructuredPostal.TYPE_HOME)) } }
            }

            if (shown(Extra.DATE, d.events.isNotEmpty())) EditorGroup(stringResource(R.string.edit_important_dates)) {
                d.events.forEachIndexed { i, ev ->
                    fun set(n: EventItem) = update { it.copy(events = it.events.toMutableList().also { l -> l[i] = n }) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(0.45f)) {
                            var customLabel by remember { mutableStateOf(false) }
                            Dropdown(
                                stringResource(R.string.edit_type), app.parley.ui.people.eventLabel(res, ev),
                                eventTypes.map { res.getString(Event.getTypeResource(it)) } + stringResource(R.string.edit_event_death) + stringResource(R.string.edit_custom_more),
                            ) { t ->
                                when (t) {
                                    in eventTypes.indices -> set(ev.copy(type = eventTypes[t], label = null))
                                    eventTypes.size -> set(ev.copy(type = Event.TYPE_CUSTOM, label = LifeEvents.DEATH_LABEL))
                                    else -> customLabel = true
                                }
                            }
                            if (customLabel) CustomLabelDialog(ev.label.takeIf { ev.type == Event.TYPE_CUSTOM }, { customLabel = false }) { set(ev.copy(type = Event.TYPE_CUSTOM, label = it)) }
                        }
                        Box(Modifier.weight(0.55f)) {
                            var picking by remember { mutableStateOf(false) }
                            OutlinedTextField(
                                if (ev.date.isBlank()) "" else describeEvent(ev.date, false).substringBefore(" ·"), {}, readOnly = true,
                                label = { Text(stringResource(R.string.edit_date)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            Box(Modifier.matchParentSize().clickable { picking = true })
                            if (picking) EventDateDialog(ev.date, onDismiss = { picking = false }) { set(ev.copy(date = it)); picking = false }
                        }
                        RemoveButton(stringResource(R.string.edit_remove_date)) { update { it.copy(events = it.events.filterIndexed { j, _ -> j != i }) } }
                    }
                }
                AddButton(stringResource(R.string.edit_add_date)) { update { it.copy(events = it.events + EventItem(type = Event.TYPE_BIRTHDAY)) } }
            }

            if (shown(Extra.WEBSITE, d.websites.isNotEmpty())) {
                MultiSection(
                    stringResource(R.string.detail_website), stringResource(R.string.edit_add_website), d.websites, listOf(Website.TYPE_HOMEPAGE, Website.TYPE_WORK, Website.TYPE_OTHER),
                    { t -> res.getString(when (t) { Website.TYPE_HOMEPAGE -> R.string.edit_web_homepage; Website.TYPE_WORK -> R.string.edit_web_work; else -> R.string.edit_web_other }) }, KeyboardType.Uri,
                    onChange = { list -> update { it.copy(websites = list) } }, newItem = { DataItem(type = Website.TYPE_HOMEPAGE) },
                )
            }

            if (shown(Extra.RELATION, d.relations.isNotEmpty())) {
                RelationsSection(vm, d.relations, onChange = { list -> update { it.copy(relations = list) } }) { name, link ->
                    pickedLinks = pickedLinks + (RelationLinks.nameKey(name) to link)
                }
            }

            val accountGroups = if (isVault) emptyList() else groups.filter { it.account.type == account?.type && it.account.name == account?.name }
            if (accountGroups.isNotEmpty()) EditorGroup(stringResource(R.string.home_labels)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accountGroups.forEach { g ->
                        val on = g.id in d.groupIds
                        FilterChip(on, { update { it.copy(groupIds = if (on) it.groupIds - g.id else it.groupIds + g.id) } }, label = { Text(g.title) })
                    }
                }
            }

            if (shown(Extra.NOTE, d.note.isNotBlank() || original != null)) EditorGroup(stringResource(R.string.edit_notes)) {
                OutlinedTextField(
                    d.note, { v -> update { it.copy(note = v) } }, label = { Text(stringResource(R.string.edit_notes)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            if (isVault) EditorGroup(stringResource(R.string.edit_when_they_call)) {
                // I6: shown on the call screen (and, outside discreet mode, a missed-call notification).
                OutlinedTextField(
                    d.context, { v -> update { it.copy(context = v.take(120)) } }, label = { Text(stringResource(R.string.edit_who_is_this)) },
                    placeholder = { Text(stringResource(R.string.edit_who_placeholder)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.edit_who_support)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    d.pinnedNote, { v -> update { it.copy(pinnedNote = v) } }, label = { Text(stringResource(R.string.detail_note_title)) },
                    placeholder = { Text(stringResource(R.string.detail_note_placeholder)) }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Text(
                    stringResource(R.string.edit_private_call_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // U5: fields that aren't shown yet, revealed inline.
            val hidden = buildList {
                if (!shown(Extra.HANDLE, d.handles.isNotEmpty())) add(Extra.HANDLE)
                if (!shown(Extra.ADDRESS, d.addresses.isNotEmpty())) add(Extra.ADDRESS)
                if (!shown(Extra.DATE, d.events.isNotEmpty())) add(Extra.DATE)
                if (!shown(Extra.WEBSITE, d.websites.isNotEmpty())) add(Extra.WEBSITE)
                if (!shown(Extra.RELATION, d.relations.isNotEmpty())) add(Extra.RELATION)
                if (!shown(Extra.NOTE, d.note.isNotBlank() || original != null)) add(Extra.NOTE)
            }
            if (hidden.isNotEmpty()) EditorGroup(stringResource(R.string.edit_more_fields)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hidden.forEach { x ->
                        AssistChip(
                            onClick = {
                                revealed = revealed + x
                                when (x) {
                                    Extra.HANDLE -> update { it.copy(handles = it.handles + HandleItem()) }
                                    Extra.ADDRESS -> update { it.copy(addresses = it.addresses + PostalItem(type = StructuredPostal.TYPE_HOME)) }
                                    Extra.DATE -> update { it.copy(events = it.events + EventItem(type = Event.TYPE_BIRTHDAY)) }
                                    Extra.WEBSITE -> update { it.copy(websites = it.websites + DataItem(type = Website.TYPE_HOMEPAGE)) }
                                    Extra.RELATION -> update { it.copy(relations = it.relations + DataItem(type = Relation.TYPE_SPOUSE)) }
                                    else -> Unit
                                }
                            },
                            label = { Text(stringResource(x.label)) },
                            leadingIcon = { Icon(Icons.Rounded.AddCircle, null, tint = CallColors.Accept, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            val key = original?.lookupKey
            if (!isVault && !key.isNullOrEmpty()) {
                app.parley.ui.people.CallBackgroundEditor(vm, key, bgChange) { bgChange = it }
            }
            Spacer(Modifier.height(48.dp))
        }
        }
    }

    askKeep?.let { (key, id) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.edit_keep_title)) },
            text = { Text(stringResource(R.string.edit_keep_body)) },
            confirmButton = { TextButton({ askKeep = null; scope.launch { vm.c.temporaries.answerKeep(key, true); done(id) } }) { Text(stringResource(R.string.edit_keep)) } },
            dismissButton = { TextButton({ askKeep = null; scope.launch { vm.c.temporaries.answerKeep(key, false); done(id) } }) { Text(stringResource(R.string.edit_still_delete)) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            confirmButton = { TextButton({ confirmDiscard = false; done(null) }) { Text(stringResource(R.string.edit_discard)) } },
            dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) } },
        )
    }
}

/** Remembers which contact each relation names, by lookup key, beside the name-only Data row (F23, I5). */
private suspend fun rememberRelations(vm: AppViewModel, contactId: Long, e: ContactDetails, picked: Map<String, RelationLinks.Link>) = withContext(Dispatchers.IO) {
    val key = vm.c.contacts.lookupKeyOf(contactId) ?: return@withContext
    val m = vm.c.meta.meta(key)
    val existing = RelationLinks.decode(m?.relationLinks)
    val names = e.relations.map { it.value }.filter { it.isNotBlank() }
    if (names.isEmpty() && existing.isEmpty()) return@withContext
    val people = vm.c.contacts.snapshot().map { Triple(it.id, it.displayName, it.lookupKey) }
    val links = RelationLinks.update(names, existing, people, self = contactId, picked = picked)
    if (links == existing) return@withContext
    vm.c.meta.setMeta((m ?: app.parley.data.db.ContactMetaEntity(key)).copy(contactId = contactId, relationLinks = RelationLinks.encode(links).ifEmpty { null }))
}

/** U2: one titled card of the editor. */
@Composable
private fun EditorGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/** U5: "+ Add …" at the end of a group. */
@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    TextButton(onClick) {
        Icon(Icons.Rounded.AddCircle, null, Modifier.size(20.dp), tint = CallColors.Accept)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** U5: a tinted "−" that removes one row. */
@Composable
private fun RemoveButton(description: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(Icons.Rounded.RemoveCircle, description, tint = MaterialTheme.colorScheme.error) }
}

/** Fields whose Data row the provider marks read-only (F12): shown, but locked. */
private val LocalLocked = androidx.compose.runtime.staticCompositionLocalOf<Set<Long>> { emptySet() }

@Composable
private fun LockIcon() = Icon(Icons.Rounded.Lock, stringResource(R.string.edit_locked))

@Composable
private fun Field(
    label: String,
    value: String,
    cap: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboard: KeyboardType = KeyboardType.Text,
    rowId: Long? = null,
    onChange: (String) -> Unit,
) {
    val locked = rowId != null && rowId in LocalLocked.current
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        readOnly = locked, trailingIcon = if (locked) { { LockIcon() } } else null,
        keyboardOptions = KeyboardOptions(capitalization = cap, keyboardType = keyboard),
    )
}

@Composable
private fun MultiSection(
    title: String,
    addLabel: String,
    items: List<DataItem>,
    types: List<Int>,
    typeLabel: (Int) -> String,
    keyboard: KeyboardType,
    onChange: (List<DataItem>) -> Unit,
    newItem: () -> DataItem,
) {
    EditorGroup(title) {
        items.forEachIndexed { i, item ->
            val locked = item.id != null && item.id in LocalLocked.current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val iso = LocalCountryIso.current
                val flag = if (keyboard == KeyboardType.Phone && item.value.length >= 6) remember(item.value) { app.parley.data.NumberInfo.flag(app.parley.data.NumberInfo.region(item.value, iso)) } else null
                OutlinedTextField(
                    item.value, { v -> onChange(items.toMutableList().also { it[i] = item.copy(value = v) }) },
                    label = { Text(title) }, singleLine = true, modifier = Modifier.weight(0.6f),
                    prefix = flag?.let { f -> { Text("$f ") } },
                    readOnly = locked, trailingIcon = if (locked) { { LockIcon() } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                )
                if (locked) {
                    Text(typeLabel(item.type).takeIf { item.type != 0 } ?: item.label.orEmpty(), Modifier.weight(0.4f).padding(start = 4.dp))
                    return@Row
                }
                Box(Modifier.weight(0.4f)) {
                    var customLabel by remember { mutableStateOf(false) }
                    Dropdown(stringResource(R.string.edit_type), if (item.type == 0) item.label ?: stringResource(R.string.edit_custom) else typeLabel(item.type), types.map(typeLabel) + stringResource(R.string.edit_custom_more)) { t ->
                        if (t in types.indices) onChange(items.toMutableList().also { it[i] = item.copy(type = types[t], label = null) }) else customLabel = true
                    }
                    if (customLabel) CustomLabelDialog(item.label.takeIf { item.type == 0 }, { customLabel = false }) { l ->
                        onChange(items.toMutableList().also { it[i] = item.copy(type = 0, label = l) })
                    }
                }
                RemoveButton(stringResource(R.string.main_remove)) { onChange(items.filterIndexed { j, _ -> j != i }) }
            }
        }
        AddButton(addLabel) { onChange(items + newItem()) }
    }
}

/** I1: the "Handles" group: service, handle with a per-service hint, and a warning when it doesn't look right. */
@Composable
private fun HandlesSection(handles: List<HandleItem>, onChange: (List<HandleItem>) -> Unit) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val services = HandleService.common + HandleService.entries.filter { it !in HandleService.common }
    EditorGroup(stringResource(R.string.edit_handles)) {
        handles.forEachIndexed { i, h ->
            val locked = h.id != null && h.id in LocalLocked.current
            fun set(n: HandleItem) = onChange(handles.toMutableList().also { it[i] = n })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    if (locked) {
                        Text(app.parley.ui.people.HandleText.label(res, h.handle), Modifier.padding(8.dp))
                    } else {
                        Dropdown(stringResource(R.string.edit_service), app.parley.ui.people.HandleText.label(res, h.handle), services.map { app.parley.ui.people.HandleText.service(res, it) }) { t -> set(h.copy(service = services[t], customProtocol = if (services[t] == HandleService.OTHER) h.customProtocol else null)) }
                    }
                }
                if (!locked) RemoveButton(stringResource(R.string.edit_remove_handle)) { onChange(handles.filterIndexed { j, _ -> j != i }) }
            }
            if (h.service == HandleService.OTHER && !locked) {
                OutlinedTextField(
                    h.customProtocol.orEmpty(), { set(h.copy(customProtocol = it)) }, label = { Text(stringResource(R.string.edit_service_name)) },
                    placeholder = { Text(stringResource(R.string.edit_service_placeholder)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            val problem = Handles.problem(h.service, h.value)
            OutlinedTextField(
                h.value, { set(h.copy(value = it)) }, label = { Text(app.parley.ui.people.HandleText.label(res, h.handle)) },
                placeholder = h.service.placeholder.takeIf { it.isNotEmpty() }?.let { p -> { Text(p) } },
                supportingText = { Text(problem?.let { app.parley.ui.people.HandleText.problem(res, it) } ?: app.parley.ui.people.HandleText.hint(res, h.service)) }, isError = problem != null,
                singleLine = true, modifier = Modifier.fillMaxWidth(), readOnly = locked, trailingIcon = if (locked) { { LockIcon() } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = if (h.service == HandleService.XMPP || h.service == HandleService.SIP) KeyboardType.Email else KeyboardType.Text),
            )
        }
        AddButton(stringResource(R.string.edit_add_handle)) { onChange(handles + HandleItem()) }
    }
}

/** I5: relations with the vCard 4.0 types (searchable) and a contact picker that remembers who was chosen. */
@Composable
private fun RelationsSection(vm: AppViewModel, items: List<DataItem>, onChange: (List<DataItem>) -> Unit, onPicked: (String, RelationLinks.Link) -> Unit) {
    val res = androidx.compose.ui.platform.LocalResources.current
    var typeFor by remember { mutableStateOf<Int?>(null) }
    var pickFor by remember { mutableStateOf<Int?>(null) }
    fun label(item: DataItem) = RelationTypes.fromAndroid(item.type, item.label)?.let { app.parley.ui.people.RelationText.label(res, it) }
        ?: if (item.type == 0) item.label ?: res.getString(R.string.edit_custom) else Relation.getTypeLabel(res, item.type, null).toString()
    EditorGroup(stringResource(R.string.edit_relations)) {
        items.forEachIndexed { i, item ->
            val locked = item.id != null && item.id in LocalLocked.current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    item.value, { v -> onChange(items.toMutableList().also { it[i] = item.copy(value = v) }) },
                    label = { Text(label(item)) }, singleLine = true, modifier = Modifier.weight(1f), readOnly = locked,
                    trailingIcon = if (locked) { { LockIcon() } } else { { IconButton({ pickFor = i }) { Icon(Icons.Rounded.PersonSearch, stringResource(R.string.edit_choose_contact)) } } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                if (!locked) RemoveButton(stringResource(R.string.edit_remove_relation)) { onChange(items.filterIndexed { j, _ -> j != i }) }
            }
            if (!locked) {
                TextButton({ typeFor = i }) { Text(stringResource(R.string.edit_relation_type, label(item))) }
            }
        }
        AddButton(stringResource(R.string.edit_add_relation)) { onChange(items + DataItem(type = Relation.TYPE_SPOUSE)) }
    }
    typeFor?.let { i ->
        RelationTypeDialog(onDismiss = { typeFor = null }) { t ->
            typeFor = null
            if (t != null) {
                val (type, lbl) = RelationTypes.toAndroid(t)
                onChange(items.toMutableList().also { it[i] = it[i].copy(type = type, label = lbl) })
            }
        }
    }
    pickFor?.let { i ->
        ContactChooserDialog(vm, onDismiss = { pickFor = null }) { id, name, key ->
            pickFor = null
            onChange(items.toMutableList().also { it[i] = it[i].copy(value = name) })
            onPicked(name, RelationLinks.Link(key, id))
        }
    }
}

@Composable
private fun RelationTypeDialog(onDismiss: () -> Unit, onPick: (RelationType?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(false) }
    val res = androidx.compose.ui.platform.LocalResources.current
    val shown = remember(query, res) { app.parley.ui.people.RelationText.search(res, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_relation)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.main_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.key }) { t ->
                        ListItem(
                            headlineContent = { Text(app.parley.ui.people.RelationText.label(res, t)) },
                            supportingContent = { Text(app.parley.ui.people.RelationText.group(res, t.group)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(t) },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.edit_custom_more)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { custom = true },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
    if (custom) CustomLabelDialog(query.ifBlank { null }, { custom = false }) { l -> custom = false; onPick(RelationType(key = "custom", label = l)) }
}

/** I5: pick the related person from your contacts (their lookup key is remembered, so renames don't break it). */
@Composable
fun ContactChooserDialog(vm: AppViewModel, onDismiss: () -> Unit, onPick: (id: Long, name: String, lookupKey: String) -> Unit) {
    val all by vm.contacts.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val shown = remember(all, query) { all.orEmpty().filter { app.parley.common.TextSearch.matches(query, it.displayName, it.phones.map { p -> p.number }) }.take(200) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_choose_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.main_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { c ->
                        ListItem(
                            leadingContent = { Avatar(c.displayName, c.photoUri, 36.dp) },
                            headlineContent = { Text(c.displayName) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(c.id, c.displayName, c.lookupKey) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

/** Free-text label for a phone, e-mail, date… (stored as TYPE_CUSTOM with this label; survives export). */
@Composable
private fun CustomLabelDialog(initial: String?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_custom_label)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(stringResource(R.string.edit_custom_placeholder)) }) },
        confirmButton = { TextButton({ onDismiss(); onDone(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.main_ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

@Composable
private fun Dropdown(label: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value, {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Rounded.ExpandMore, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}
