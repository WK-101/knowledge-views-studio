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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.GroupInfo
import app.parley.data.PostalItem
import app.parley.ui.Avatar
import app.parley.common.people.LifeEvents
import app.parley.common.people.RelationLinks
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

private val relationTypes = listOf(
    Relation.TYPE_SPOUSE, Relation.TYPE_PARTNER, Relation.TYPE_CHILD, Relation.TYPE_PARENT, Relation.TYPE_MOTHER, Relation.TYPE_FATHER,
    Relation.TYPE_SISTER, Relation.TYPE_BROTHER, Relation.TYPE_FRIEND, Relation.TYPE_MANAGER, Relation.TYPE_ASSISTANT, Relation.TYPE_RELATIVE,
)
private val eventTypes = listOf(Event.TYPE_BIRTHDAY, Event.TYPE_ANNIVERSARY, Event.TYPE_OTHER)

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
                    vm.toast("Unlock the private contact first")
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
            e.events.all { it.date.isBlank() } && e.phoneticGiven.isBlank() && e.phoneticFamily.isBlank()
        // Clearing one copy of a linked contact is allowed: that empty copy is removed and the others stay.
        val orig = original
        if (empty && photo == null && (orig == null || orig.rawContacts.size < 2 || orig.editRawId == null)) {
            vm.toast(if (original == null) "Add a name or a number first" else "Nothing left to save. Delete the contact instead.")
            return
        }
        saving = true
        scope.launch {
            val id = try {
                if (isVault) {
                    val id = vm.c.vault.save(vaultId?.takeIf { it > 0 }, e)
                    -id // negative ids mark vault contacts for the caller
                } else {
                    vm.c.contacts.save(original, e, account, photo, removePhoto).also { saved ->
                        original?.lookupKey?.let { key -> vm.applyBackground(key, bgChange) }
                        if (saved != null) rememberRelations(vm, saved, e)
                    }
                }
            } catch (ex: Exception) {
                vm.toast("Couldn't save: ${ex.message}")
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
                title = { Text(if (isVault) (if ((vaultId ?: 0) > 0) "Edit private contact" else "New private contact") else if (contactId == null) "New contact" else if (rawId != null) "Edit this copy" else "Edit contact") },
                navigationIcon = { IconButton({ if (dirty) confirmDiscard = true else done(null) }) { Icon(Icons.Rounded.Close, "Cancel") } },
                actions = { Button(onClick = ::save, enabled = !saving && d != null, modifier = Modifier.padding(end = 8.dp)) { Text("Save") } },
            )
        },
    ) { padding ->
        if (d == null) return@Scaffold
        fun update(f: (ContactDetails) -> ContactDetails) { draft = f(d) }
        androidx.compose.runtime.CompositionLocalProvider(LocalCountryIso provides vm.countryIso, LocalLocked provides original?.readOnlyDataIds.orEmpty()) {
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isVault) {
                Text(
                    "Private contact: stored encrypted inside Parley only. Other apps can't see it; calls from it still show its name.",
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
            if (!isVault) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val shownPhoto = photo?.toString() ?: d.photoUri.takeUnless { removePhoto }
                Avatar(d.composedName.ifBlank { "?" }, shownPhoto, 104.dp, Modifier.clickable {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                Icon(Icons.Rounded.AddAPhoto, "Change photo", Modifier.align(Alignment.BottomCenter).padding(start = 80.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (!isVault && (photo != null || (d.photoUri != null && !removePhoto))) {
                TextButton({ photo = null; removePhoto = true }, Modifier.align(Alignment.CenterHorizontally)) { Text("Remove photo") }
            }

            if (vaultId != null) {
                // no account for vault contacts
            } else if (original == null) {
                val privateLabel = "Private (only in Parley)"
                Dropdown(
                    "Save to", if (privateNew) privateLabel else account?.let { idx.labelWithCount(it) } ?: "Phone only",
                    listOf(privateLabel) + accounts.map { idx.labelWithCount(it) },
                ) { i -> if (i == 0) privateNew = true else { privateNew = false; account = accounts[i - 1] } }
            } else {
                Text("Saved in ${account?.displayLabel ?: "Phone"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Field("First name", d.given, KeyboardCapitalization.Words, rowId = d.nameId) { v -> update { it.copy(given = v) } }
            Field("Last name", d.family, KeyboardCapitalization.Words, rowId = d.nameId) { v -> update { it.copy(family = v) } }
            Row(Modifier.clickable { moreName = !moreName }, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (moreName) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                Text(if (moreName) "Fewer name fields" else "More name fields", style = MaterialTheme.typography.labelLarge)
            }
            if (moreName) {
                Field("Prefix", d.prefix, KeyboardCapitalization.Words) { v -> update { it.copy(prefix = v) } }
                Field("Middle name", d.middle, KeyboardCapitalization.Words) { v -> update { it.copy(middle = v) } }
                Field("Suffix", d.suffix, KeyboardCapitalization.Words) { v -> update { it.copy(suffix = v) } }
                Field("Phonetic first name", d.phoneticGiven, KeyboardCapitalization.Words) { v -> update { it.copy(phoneticGiven = v) } }
                Field("Phonetic last name", d.phoneticFamily, KeyboardCapitalization.Words) { v -> update { it.copy(phoneticFamily = v) } }
                Field("Nickname", d.nickname, KeyboardCapitalization.Words) { v -> update { it.copy(nickname = v) } }
            }
            Field("Company", d.company, KeyboardCapitalization.Words, rowId = d.orgId) { v -> update { it.copy(company = v) } }
            Field("Title", d.title, KeyboardCapitalization.Words, rowId = d.orgId) { v -> update { it.copy(title = v) } }

            MultiSection(
                "Phone", d.phones, phoneTypes, { Phone.getTypeLabel(res, it, null).toString() }, KeyboardType.Phone,
                onChange = { list -> update { it.copy(phones = list) } }, newItem = { DataItem(type = Phone.TYPE_MOBILE) },
            )
            MultiSection(
                "Email", d.emails, emailTypes, { Email.getTypeLabel(res, it, null).toString() }, KeyboardType.Email,
                onChange = { list -> update { it.copy(emails = list) } }, newItem = { DataItem(type = Email.TYPE_HOME) },
            )

            SectionTitle("Address")
            d.addresses.forEachIndexed { i, a ->
                fun set(n: PostalItem) = update { it.copy(addresses = it.addresses.toMutableList().also { l -> l[i] = n }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Dropdown("Type", StructuredPostal.getTypeLabel(res, a.type, a.label).toString(), postalTypes.map { StructuredPostal.getTypeLabel(res, it, null).toString() }) { t -> set(a.copy(type = postalTypes[t])) }
                    }
                    IconButton({ update { it.copy(addresses = it.addresses.filterIndexed { j, _ -> j != i }) } }) { Icon(Icons.Rounded.Close, "Remove address") }
                }
                Field("Street", a.street, KeyboardCapitalization.Words, rowId = a.id) { set(a.copy(street = it)) }
                // Shown when the address has them, so editing never drops a PO box or neighbourhood (F25).
                if (a.poBox.isNotEmpty() || a.neighborhood.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(0.4f)) { Field("PO box", a.poBox, rowId = a.id) { set(a.copy(poBox = it)) } }
                        Box(Modifier.weight(0.6f)) { Field("Neighbourhood", a.neighborhood, KeyboardCapitalization.Words, rowId = a.id) { set(a.copy(neighborhood = it)) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(0.4f)) { Field("Postcode", a.postcode) { set(a.copy(postcode = it)) } }
                    Box(Modifier.weight(0.6f)) { Field("City", a.city, KeyboardCapitalization.Words) { set(a.copy(city = it)) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field("Region", a.region, KeyboardCapitalization.Words) { set(a.copy(region = it)) } }
                    Box(Modifier.weight(1f)) { Field("Country", a.country, KeyboardCapitalization.Words) { set(a.copy(country = it)) } }
                }
            }
            AddButton("Add address") { update { it.copy(addresses = it.addresses + PostalItem(type = StructuredPostal.TYPE_HOME)) } }

            SectionTitle("Important dates")
            d.events.forEachIndexed { i, ev ->
                fun set(n: EventItem) = update { it.copy(events = it.events.toMutableList().also { l -> l[i] = n }) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(0.45f)) {
                        var customLabel by remember { mutableStateOf(false) }
                        Dropdown(
                            "Type", app.parley.ui.people.eventLabel(res, ev),
                            eventTypes.map { res.getString(Event.getTypeResource(it)) } + LifeEvents.DEATH_LABEL + "Custom…",
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
                            label = { Text("Date") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Box(Modifier.matchParentSize().clickable { picking = true })
                        if (picking) EventDateDialog(ev.date, onDismiss = { picking = false }) { set(ev.copy(date = it)); picking = false }
                    }
                    IconButton({ update { it.copy(events = it.events.filterIndexed { j, _ -> j != i }) } }) { Icon(Icons.Rounded.Close, "Remove date") }
                }
            }
            AddButton("Add date") { update { it.copy(events = it.events + EventItem(type = Event.TYPE_BIRTHDAY)) } }

            MultiSection(
                "Website", d.websites, listOf(Website.TYPE_HOMEPAGE, Website.TYPE_WORK, Website.TYPE_OTHER),
                { t -> when (t) { Website.TYPE_HOMEPAGE -> "Homepage"; Website.TYPE_WORK -> "Work"; else -> "Other" } }, KeyboardType.Uri,
                onChange = { list -> update { it.copy(websites = list) } }, newItem = { DataItem(type = Website.TYPE_HOMEPAGE) },
            )

            MultiSection(
                "Relation", d.relations, relationTypes, { Relation.getTypeLabel(res, it, null).toString() }, KeyboardType.Text,
                onChange = { list -> update { it.copy(relations = list) } }, newItem = { DataItem(type = Relation.TYPE_SPOUSE) },
            )

            val accountGroups = if (isVault) emptyList() else groups.filter { it.account.type == account?.type && it.account.name == account?.name }
            if (accountGroups.isNotEmpty()) {
                SectionTitle("Labels")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accountGroups.forEach { g ->
                        val on = g.id in d.groupIds
                        FilterChip(on, { update { it.copy(groupIds = if (on) it.groupIds - g.id else it.groupIds + g.id) } }, label = { Text(g.title) })
                    }
                }
            }

            OutlinedTextField(
                d.note, { v -> update { it.copy(note = v) } }, label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
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
            title = { Text("Keep this contact?") },
            text = { Text("It was saved as a temporary contact and deletes itself soon. Keep it now that you've added to it?") },
            confirmButton = { TextButton({ askKeep = null; scope.launch { vm.c.temporaries.answerKeep(key, true); done(id) } }) { Text("Keep") } },
            dismissButton = { TextButton({ askKeep = null; scope.launch { vm.c.temporaries.answerKeep(key, false); done(id) } }) { Text("Still delete it") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton({ confirmDiscard = false; done(null) }) { Text("Discard") } },
            dismissButton = { TextButton({ confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/** Remembers which contact each relation names, by lookup key, beside the name-only Data row (F23). */
private suspend fun rememberRelations(vm: AppViewModel, contactId: Long, e: ContactDetails) = withContext(Dispatchers.IO) {
    val key = vm.c.contacts.lookupKeyOf(contactId) ?: return@withContext
    val m = vm.c.meta.meta(key)
    val existing = RelationLinks.decode(m?.relationLinks)
    val names = e.relations.map { it.value }.filter { it.isNotBlank() }
    if (names.isEmpty() && existing.isEmpty()) return@withContext
    val people = vm.c.contacts.snapshot().map { Triple(it.id, it.displayName, it.lookupKey) }
    val links = RelationLinks.update(names, existing, people, self = contactId)
    if (links == existing) return@withContext
    vm.c.meta.setMeta((m ?: app.parley.data.db.ContactMetaEntity(key)).copy(contactId = contactId, relationLinks = RelationLinks.encode(links).ifEmpty { null }))
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick) {
        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/** Fields whose Data row the provider marks read-only (F12): shown, but locked. */
private val LocalLocked = androidx.compose.runtime.staticCompositionLocalOf<Set<Long>> { emptySet() }

@Composable
private fun LockIcon() = Icon(Icons.Rounded.Lock, "Can't be changed here: the account that owns this field keeps it read-only")

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
    items: List<DataItem>,
    types: List<Int>,
    typeLabel: (Int) -> String,
    keyboard: KeyboardType,
    onChange: (List<DataItem>) -> Unit,
    newItem: () -> DataItem,
) {
    SectionTitle(title)
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
                Dropdown("Type", if (item.type == 0) item.label ?: "Custom" else typeLabel(item.type), types.map(typeLabel) + "Custom…") { t ->
                    if (t in types.indices) onChange(items.toMutableList().also { it[i] = item.copy(type = types[t], label = null) }) else customLabel = true
                }
                if (customLabel) CustomLabelDialog(item.label.takeIf { item.type == 0 }, { customLabel = false }) { l ->
                    onChange(items.toMutableList().also { it[i] = item.copy(type = 0, label = l) })
                }
            }
            IconButton({ onChange(items.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove") }
        }
    }
    AddButton("Add ${title.lowercase()}") { onChange(items + newItem()) }
}

/** Free-text label for a phone, e-mail, date… (stored as TYPE_CUSTOM with this label; survives export). */
@Composable
private fun CustomLabelDialog(initial: String?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom label") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("e.g. Boat, Name day") }) },
        confirmButton = { TextButton({ onDismiss(); onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
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
