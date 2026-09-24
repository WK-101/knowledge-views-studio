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
import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.DataL10n

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
                val ctx = vm.c.appContext
                vm.c.temporaries.expire(System.currentTimeMillis() + 1).forEach { n ->
                    val name = n.name ?: ctx.getString(R.string.work_temp_someone)
                    vm.toast(ctx.getString(if (n.keptDetails) R.string.work_temp_expired_kept else R.string.work_temp_expired_merged, name))
                }
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
    val fallback = stringResource(R.string.temp_fallback_name)
    return remember(temps, contacts, vault, fallback) {
        val byId = contacts.orEmpty().associateBy { it.id }
        val byKey = contacts.orEmpty().associateBy { it.lookupKey }
        val phone = temps.map { t ->
            val c = byKey[t.lookupKey] ?: byId[t.contactId]
            TemporaryItem(c?.displayName ?: t.name ?: fallback, c?.phones?.firstOrNull()?.number, t.expiresAt, c?.id ?: t.contactId, t.lookupKey, null, t.purgeHistory)
        }
        val private = vault.filter { it.expiresAt != null }.map { v ->
            TemporaryItem(v.name, v.numbers.firstOrNull(), v.expiresAt!!, null, null, v.id, v.purgeHistory)
        }
        (phone + private).sortedBy { it.expiresAt }
    }
}

/** "3 days left", "5 hours left", "Deletes today". */
fun timeLeft(res: Resources, expiresAt: Long, now: Long = System.currentTimeMillis()): String {
    val ms = expiresAt - now
    return when {
        ms <= 0 -> res.getString(R.string.temp_deletes_soon)
        ms >= DAY_MS -> (ms / DAY_MS).toInt().let { res.getQuantityString(R.plurals.temp_days_left, it, it) }
        ms >= 2 * 3_600_000L -> (ms / 3_600_000L).toInt().let { res.getQuantityString(R.plurals.temp_hours_left, it, it) }
        else -> res.getString(R.string.temp_within_hour)
    }
}

/** Contacts › Temporary contacts: time left, extend, keep permanently or delete now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemporaryContactsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val items = rememberTemporaryItems(vm)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var extendFor by remember { mutableStateOf<TemporaryItem?>(null) }
    var deleteFor by remember { mutableStateOf<TemporaryItem?>(null) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.temp_title)) },
                navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { p ->
        if (items.isEmpty()) {
            EmptyState(
                Icons.Rounded.AutoDelete, stringResource(R.string.temp_empty_title),
                stringResource(R.string.temp_empty_text),
                Modifier.padding(p),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    stringResource(R.string.temp_intro),
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
                        onKeep = { scope.launch { TemporaryContactActions.keep(vm, t); vm.toast(context.getString(R.string.temp_will_be_kept, t.name)) } },
                        onDelete = { deleteFor = t },
                    )
                }
            }
        }
    }
    extendFor?.let { t ->
        DurationDialog(title = stringResource(R.string.temp_keep_for, t.name), onDismiss = { extendFor = null }) { days ->
            extendFor = null
            scope.launch { TemporaryContactActions.extend(vm, t, days); vm.toast(context.resources.getQuantityString(R.plurals.temp_deletes_in_days, days, days)) }
        }
    }
    deleteFor?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.temp_delete_now_title, t.name)) },
            text = {
                Text(
                    when {
                        t.purgeHistory -> stringResource(R.string.temp_delete_with_history)
                        else -> stringResource(R.string.temp_delete_keep_history)
                    } + if (t.vaultId == null) " " + stringResource(R.string.temp_delete_restore_hint) else "",
                )
            },
            confirmButton = { TextButton({ deleteFor = null; scope.launch { TemporaryContactActions.deleteNow(vm, t) } }) { Text(stringResource(R.string.dc_delete)) } },
            dismissButton = { TextButton({ deleteFor = null }) { Text(stringResource(R.string.dc_cancel)) } },
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
                if (t.vaultId != null) Icon(Icons.Rounded.Lock, stringResource(R.string.temp_private), Modifier.padding(end = 4.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t.name)
            }
        },
        supportingContent = {
            Text(listOfNotNull(timeLeft(LocalContext.current.resources, t.expiresAt), t.number?.let { DataL10n.ltr(Format.number(it, countryIso)) }).joinToString(" · "))
        },
        trailingContent = {
            Row {
                IconButton(onExtend) { Icon(Icons.Rounded.MoreTime, stringResource(R.string.temp_keep_longer, t.name)) }
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.temp_more_actions, t.name)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.temp_extend)) }, leadingIcon = { Icon(Icons.Rounded.MoreTime, null) }, onClick = { menu = false; onExtend() })
                    DropdownMenuItem({ Text(stringResource(R.string.temp_keep_permanently)) }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) }, onClick = { menu = false; onKeep() })
                    DropdownMenuItem({ Text(stringResource(R.string.temp_delete_now)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

private val presetDays = listOf(1, 7, 30)

/** Picks 1, 7 or 30 days, or a custom number of days. */
@Composable
private fun DurationPicker(days: Int?, custom: String, onPreset: (Int) -> Unit, onCustom: (String) -> Unit) {
    Column(Modifier.selectableGroup()) {
        presetDays.forEach { d ->
            Row(
                Modifier.fillMaxWidth().selectable(days == d, role = Role.RadioButton) { onPreset(d) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(days == d, onClick = null)
                Text(pluralStringResource(R.plurals.temp_n_days, d, d), Modifier.padding(start = 12.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().selectable(days == null, role = Role.RadioButton) { onCustom(custom.ifEmpty { "14" }) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(days == null, onClick = null)
            Text(stringResource(R.string.temp_custom), Modifier.padding(start = 12.dp))
        }
        if (days == null) {
            OutlinedTextField(
                custom, { v -> onCustom(v.filter(Char::isDigit).take(4)) },
                label = { Text(stringResource(R.string.temp_days)) },
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
        confirmButton = { TextButton({ chosen?.let(onPick) }, enabled = chosen != null) { Text(stringResource(R.string.dc_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
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
        title = { Text(stringResource(R.string.temp_save_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (visible) stringResource(R.string.temp_save_visible_text, DataL10n.ltr(number))
                    else stringResource(R.string.temp_save_private_text, DataL10n.ltr(number)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.temp_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.temp_delete_after), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                DurationPicker(days, custom, { days = it }, { custom = it; days = null })
                Row(
                    Modifier.fillMaxWidth().toggleable(deleteHistory, role = Role.Checkbox) { deleteHistory = it }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(deleteHistory, onCheckedChange = null)
                    Text(stringResource(R.string.temp_also_history), Modifier.padding(start = 12.dp))
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(visible, role = Role.Checkbox) { visible = it }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(visible, onCheckedChange = null)
                    Text(stringResource(R.string.temp_visible), Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton({ chosen?.let { onSave(name, it, deleteHistory, visible) } }, enabled = chosen != null) { Text(if (visible) stringResource(R.string.dc_save) else stringResource(R.string.temp_save_privately)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}
