package app.parley.ui.contact

import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val phoneTypes = listOf(Phone.TYPE_MOBILE, Phone.TYPE_HOME, Phone.TYPE_WORK, Phone.TYPE_MAIN, Phone.TYPE_FAX_WORK, Phone.TYPE_OTHER)
private val emailTypes = listOf(Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_MOBILE, Email.TYPE_OTHER)
private val postalTypes = listOf(StructuredPostal.TYPE_HOME, StructuredPostal.TYPE_WORK, StructuredPostal.TYPE_OTHER)
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
    var start by remember { mutableStateOf<ContactDetails?>(null) }

    LaunchedEffect(contactId) {
        accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() }
        groups = withContext(Dispatchers.IO) { vm.c.contacts.groups() }
        val s = vm.settings.value
        account = accounts.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName }
            ?: accounts.firstOrNull { it.type == "com.google" } ?: accounts.firstOrNull()
        if (contactId != null) {
            val d = vm.c.contacts.editable(contactId)
            original = d
            var e = d ?: ContactDetails()
            if (addPhone.isNotBlank()) e = e.copy(phones = e.phones + DataItem(value = addPhone, type = Phone.TYPE_MOBILE))
            if (prefill != null) e = app.parley.InsertPrefill.appendTo(e, prefill)
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
    val dirty = d != start || photo != null || removePhoto
    BackHandler(enabled = dirty) { confirmDiscard = true }

    fun save() {
        val e = draft ?: return
        if (e.composedName.isBlank() && e.phones.all { it.value.isBlank() } && e.emails.all { it.value.isBlank() } && e.company.isBlank()) {
            vm.toast("Add a name or a number first")
            return
        }
        saving = true
        scope.launch {
            val id = try {
                vm.c.contacts.save(original, e, account, photo, removePhoto)
            } catch (ex: Exception) {
                vm.toast("Couldn't save: ${ex.message}")
                null
            }
            saving = false
            if (id != null) done(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (contactId == null) "New contact" else "Edit contact") },
                navigationIcon = { IconButton({ if (dirty) confirmDiscard = true else done(null) }) { Icon(Icons.Rounded.Close, "Cancel") } },
                actions = { Button(onClick = ::save, enabled = !saving && d != null, modifier = Modifier.padding(end = 8.dp)) { Text("Save") } },
            )
        },
    ) { padding ->
        if (d == null) return@Scaffold
        fun update(f: (ContactDetails) -> ContactDetails) { draft = f(d) }
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val shownPhoto = photo?.toString() ?: d.photoUri.takeUnless { removePhoto }
                Avatar(d.composedName.ifBlank { "?" }, shownPhoto, 104.dp, Modifier.clickable {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                Icon(Icons.Rounded.AddAPhoto, "Change photo", Modifier.align(Alignment.BottomCenter).padding(start = 80.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (photo != null || (d.photoUri != null && !removePhoto)) {
                TextButton({ photo = null; removePhoto = true }, Modifier.align(Alignment.CenterHorizontally)) { Text("Remove photo") }
            }

            if (original == null) {
                Dropdown("Save to", account?.displayLabel ?: "Phone only", accounts.map { it.displayLabel }) { i -> account = accounts[i] }
            } else {
                Text("Saved in ${account?.displayLabel ?: "Phone"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Field("First name", d.given, KeyboardCapitalization.Words) { v -> update { it.copy(given = v) } }
            Field("Last name", d.family, KeyboardCapitalization.Words) { v -> update { it.copy(family = v) } }
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
            Field("Company", d.company, KeyboardCapitalization.Words) { v -> update { it.copy(company = v) } }
            Field("Title", d.title, KeyboardCapitalization.Words) { v -> update { it.copy(title = v) } }

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
                Field("Street", a.street, KeyboardCapitalization.Words) { set(a.copy(street = it)) }
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
                        Dropdown("Type", res.getString(Event.getTypeResource(ev.type)), eventTypes.map { res.getString(Event.getTypeResource(it)) }) { t -> set(ev.copy(type = eventTypes[t])) }
                    }
                    Box(Modifier.weight(0.55f)) { Field("YYYY-MM-DD", ev.date, keyboard = KeyboardType.Number) { set(ev.copy(date = it)) } }
                    IconButton({ update { it.copy(events = it.events.filterIndexed { j, _ -> j != i }) } }) { Icon(Icons.Rounded.Close, "Remove date") }
                }
            }
            AddButton("Add date") { update { it.copy(events = it.events + EventItem(type = Event.TYPE_BIRTHDAY)) } }

            MultiSection(
                "Website", d.websites, listOf(Website.TYPE_HOMEPAGE, Website.TYPE_WORK, Website.TYPE_OTHER),
                { t -> when (t) { Website.TYPE_HOMEPAGE -> "Homepage"; Website.TYPE_WORK -> "Work"; else -> "Other" } }, KeyboardType.Uri,
                onChange = { list -> update { it.copy(websites = list) } }, newItem = { DataItem(type = Website.TYPE_HOMEPAGE) },
            )

            val accountGroups = groups.filter { it.account.type == account?.type && it.account.name == account?.name }
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
            Spacer(Modifier.height(48.dp))
        }
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

@Composable
private fun Field(
    label: String,
    value: String,
    cap: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                item.value, { v -> onChange(items.toMutableList().also { it[i] = item.copy(value = v) }) },
                label = { Text(title) }, singleLine = true, modifier = Modifier.weight(0.6f),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            )
            Box(Modifier.weight(0.4f)) {
                Dropdown("Type", if (item.type == 0) item.label ?: "Custom" else typeLabel(item.type), types.map(typeLabel)) { t ->
                    onChange(items.toMutableList().also { it[i] = item.copy(type = types[t], label = null) })
                }
            }
            IconButton({ onChange(items.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove") }
        }
    }
    AddButton("Add ${title.lowercase()}") { onChange(items + newItem()) }
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
