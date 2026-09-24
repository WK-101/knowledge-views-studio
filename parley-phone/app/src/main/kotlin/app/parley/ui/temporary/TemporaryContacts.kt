package app.parley.ui.temporary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.segmentShape
import kotlinx.coroutines.launch

private const val DAY_MS = 86_400_000L

/** A contact that deletes itself: a phone contact with an expiry, or a private (vault) contact with one. */
data class TemporaryItem(
    val name: String,
    val number: String?,
    val expiresAt: Long,
    /** Phone contact (null for a private one). */
    val contactId: Long?,
    val lookupKey: String?,
    /** Private contact (null for a phone one). */
    val vaultId: Long?,
    val purgeHistory: Boolean,
) {
    val key: String get() = vaultId?.let { "v$it" } ?: "c$lookupKey"
}

/**
 * Everything the UI does with temporary contacts, in one place. Saving goes through the data layer's
 * [app.parley.data.TemporaryContacts] facade (the same one "Chat, then decide" and "Save as a temporary contact"
 * use): private (vault) by default, or a phone-only contact when the user asks for it to be visible to other apps.
 */
object TemporaryContactActions {
    suspend fun save(vm: AppViewModel, number: String, name: String, days: Int, deleteHistory: Boolean, visible: Boolean): app.parley.data.TemporaryContacts.Saved? =
        runCatching {
            app.parley.data.TemporaryContacts.save(vm.c, name, number, days, private = !visible, purgeHistory = deleteHistory)
        }.getOrNull()

    /** Visible ones go through [app.parley.data.people.TemporaryContactStore]; private ones are vault entries with an expiry. */
    suspend fun extend(vm: AppViewModel, item: TemporaryItem, days: Int) {
        when {
            item.vaultId != null -> vm.c.vault.setExpiry(item.vaultId, System.currentTimeMillis() + days * DAY_MS)
            item.contactId != null -> vm.c.temporaries.mark(item.contactId, days, item.purgeHistory)
        }
    }

    suspend fun keep(vm: AppViewModel, item: TemporaryItem) {
        when {
            item.vaultId != null -> vm.c.vault.setExpiry(item.vaultId, null)
            item.lookupKey != null -> vm.c.temporaries.clear(item.lookupKey)
        }
    }

    /**
     * Deletes it now, the same way expiry would: a visible one expires through the store (only its own raw contacts,
     * journaled so Recently deleted can undo it; call history only for numbers no other contact uses); a private one
     * leaves the vault with its call history when it was saved that way.
     */
    suspend fun deleteNow(vm: AppViewModel, item: TemporaryItem) {
        when {
            item.vaultId != null -> {
                val numbers = vm.c.vault.contacts.value.firstOrNull { it.id == item.vaultId }?.numbers ?: listOfNotNull(item.number)
                numbers.forEach { n ->
                    if (item.purgeHistory) runCatching { vm.c.history.purgeNumber(n) }
                    runCatching { vm.c.messaging.forget(n) }
                }
                vm.c.vault.delete(item.vaultId)
            }
            item.contactId != null -> {
                vm.c.temporaries.mark(item.contactId, 0, item.purgeHistory)
                vm.c.temporaries.expire(System.currentTimeMillis() + 1).forEach { vm.toast(it.text) }
            }
        }
    }
}

/** All temporary contacts, visible and private, soonest to expire first. */
@Composable
fun rememberTemporaryItems(vm: AppViewModel): List<TemporaryItem> {
    val tempsFlow = remember(vm) { vm.c.temporaries.all }
    val temps by tempsFlow.collectAsStateWithLifecycle(emptyList())
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
    return remember(temps, contacts, vault) {
        val byId = contacts.orEmpty().associateBy { it.id }
        val byKey = contacts.orEmpty().associateBy { it.lookupKey }
        val phone = temps.map { t ->
            val c = byKey[t.lookupKey] ?: byId[t.contactId]
            TemporaryItem(c?.displayName ?: t.name ?: "Temporary contact", c?.phones?.firstOrNull()?.number, t.expiresAt, c?.id ?: t.contactId, t.lookupKey, null, t.purgeHistory)
        }
        val private = vault.filter { it.expiresAt != null }.map { v ->
            TemporaryItem(v.name, v.numbers.firstOrNull(), v.expiresAt!!, null, null, v.id, v.purgeHistory)
        }
        (phone + private).sortedBy { it.expiresAt }
    }
}

/** "3 days left", "5 hours left", "Deletes today". */
fun timeLeft(expiresAt: Long, now: Long = System.currentTimeMillis()): String {
    val ms = expiresAt - now
    return when {
        ms <= 0 -> "Deletes soon"
        ms >= 2 * DAY_MS -> "${ms / DAY_MS} days left"
        ms >= DAY_MS -> "1 day left"
        ms >= 2 * 3_600_000L -> "${ms / 3_600_000L} hours left"
        else -> "Deletes within the hour"
    }
}

/** Contacts › Temporary contacts: time left, extend, keep permanently or delete now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemporaryContactsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val items = rememberTemporaryItems(vm)
    val scope = rememberCoroutineScope()
    var extendFor by remember { mutableStateOf<TemporaryItem?>(null) }
    var deleteFor by remember { mutableStateOf<TemporaryItem?>(null) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Temporary contacts") },
                navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { p ->
        if (items.isEmpty()) {
            EmptyState(
                Icons.Rounded.AutoDelete, "No temporary contacts",
                "Save a number for a while: the plumber, a delivery, a seller. Type it on the keypad and choose “Save temporary contact”, or pick “Delete automatically” on a contact's page.",
                Modifier.padding(p),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    "These contacts delete themselves when their time is up" + ", with their call history if you chose so.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                )
            }
            itemsIndexed(items, key = { _, it -> it.key }) { i, t ->
                Surface(shape = segmentShape(i, items.size), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                    TemporaryRow(
                        t, vm.countryIso,
                        onOpen = { if (t.vaultId != null) open(Routes.vault(t.vaultId)) else t.contactId?.let { open(Routes.contact(it)) } },
                        onExtend = { extendFor = t },
                        onKeep = { scope.launch { TemporaryContactActions.keep(vm, t); vm.toast("${t.name} will be kept") } },
                        onDelete = { deleteFor = t },
                    )
                }
            }
        }
    }
    extendFor?.let { t ->
        DurationDialog(title = "Keep ${t.name} for", onDismiss = { extendFor = null }) { days ->
            extendFor = null
            scope.launch { TemporaryContactActions.extend(vm, t, days); vm.toast("Deletes itself in $days days") }
        }
    }
    deleteFor?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${t.name} now?") },
            text = {
                Text(
                    when {
                        t.purgeHistory -> "The contact and its call history are deleted."
                        else -> "The contact is deleted; its call history stays."
                    } + if (t.vaultId == null) " You can restore the contact from Recently deleted for 30 days." else "",
                )
            },
            confirmButton = { TextButton({ deleteFor = null; scope.launch { TemporaryContactActions.deleteNow(vm, t) } }) { Text("Delete") } },
            dismissButton = { TextButton({ deleteFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TemporaryRow(t: TemporaryItem, countryIso: String, onOpen: () -> Unit, onExtend: () -> Unit, onKeep: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Avatar(t.name, null, 40.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.vaultId != null) Icon(Icons.Rounded.Lock, "Private", Modifier.padding(end = 4.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t.name)
            }
        },
        supportingContent = {
            Text(listOfNotNull(timeLeft(t.expiresAt), t.number?.let { Format.number(it, countryIso) }).joinToString(" · "))
        },
        trailingContent = {
            Row {
                IconButton(onExtend) { Icon(Icons.Rounded.MoreTime, "Keep ${t.name} longer") }
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More actions for ${t.name}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Extend…") }, leadingIcon = { Icon(Icons.Rounded.MoreTime, null) }, onClick = { menu = false; onExtend() })
                    DropdownMenuItem({ Text("Keep permanently") }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) }, onClick = { menu = false; onKeep() })
                    DropdownMenuItem({ Text("Delete now") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

private val presetDays = listOf(1 to "1 day", 7 to "7 days", 30 to "30 days")

/** Picks 1, 7 or 30 days, or a custom number of days. */
@Composable
private fun DurationPicker(days: Int?, custom: String, onPreset: (Int) -> Unit, onCustom: (String) -> Unit) {
    Column(Modifier.selectableGroup()) {
        presetDays.forEach { (d, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(days == d, role = Role.RadioButton) { onPreset(d) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(days == d, onClick = null)
                Text(label, Modifier.padding(start = 12.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().selectable(days == null, role = Role.RadioButton) { onCustom(custom.ifEmpty { "14" }) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(days == null, onClick = null)
            Text("Custom", Modifier.padding(start = 12.dp))
        }
        if (days == null) {
            OutlinedTextField(
                custom, { v -> onCustom(v.filter(Char::isDigit).take(4)) },
                label = { Text("Days") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.padding(start = 36.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun DurationDialog(title: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var days by rememberSaveable { mutableStateOf<Int?>(7) }
    var custom by rememberSaveable { mutableStateOf("") }
    val chosen = days ?: custom.toIntOrNull()?.takeIf { it in 1..3650 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { DurationPicker(days, custom, { days = it }, { custom = it; days = null }) },
        confirmButton = { TextButton({ chosen?.let(onPick) }, enabled = chosen != null) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/**
 * "Save temporary contact" for a typed number: name, how long (1 / 7 / 30 days or custom) and whether its call
 * history goes too.
 */
@Composable
fun SaveTemporaryDialog(number: String, suggestedName: String, onDismiss: () -> Unit, onSave: (name: String, days: Int, deleteHistory: Boolean, visible: Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    var days by rememberSaveable { mutableStateOf<Int?>(app.parley.data.TemporaryContacts.DEFAULT_DAYS) }
    var custom by rememberSaveable { mutableStateOf("") }
    var deleteHistory by rememberSaveable { mutableStateOf(true) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val chosen = days ?: custom.toIntOrNull()?.takeIf { it in 1..3650 }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoDelete, null) },
        title = { Text("Save temporary contact") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (visible) "$number is saved in your contacts on this phone only, where apps that can read contacts (WhatsApp included) see it, and deletes itself when the time is up."
                    else "$number is saved privately in Parley, where other apps can't see it, and deletes itself when the time is up.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Delete after", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                DurationPicker(days, custom, { days = it }, { custom = it; days = null })
                Row(
                    Modifier.fillMaxWidth().toggleable(deleteHistory, role = Role.Checkbox) { deleteHistory = it }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(deleteHistory, onCheckedChange = null)
                    Text("Also delete its call history", Modifier.padding(start = 12.dp))
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(visible, role = Role.Checkbox) { visible = it }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(visible, onCheckedChange = null)
                    Text("Save visible to other apps", Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton({ chosen?.let { onSave(name, it, deleteHistory, visible) } }, enabled = chosen != null) { Text(if (visible) "Save" else "Save privately") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
