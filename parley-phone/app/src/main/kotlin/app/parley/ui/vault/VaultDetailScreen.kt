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
import app.parley.ui.home.callTypeIcon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultDetailScreen(vm: AppViewModel, id: Long, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
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

    Scaffold(topBar = {
        TopAppBar(
            title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Lock, null); Text("  Private contact") } },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                if (details != null) {
                    IconButton({ open(Routes.edit(vault = id)) }) { Icon(Icons.Rounded.Edit, "Edit") }
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Move to phone contacts") }, leadingIcon = { Icon(Icons.Rounded.LockOpen, null) }, onClick = {
                            menu = false
                            scope.launchVault(context as? FragmentActivity, { e -> vm.toast("Couldn't move: ${e.message}") }) {
                                val d = details ?: return@launchVault
                                val s = vm.settings.value
                                val account = app.parley.data.AccountRef(s.defaultAccountType, s.defaultAccountName)
                                val newId = vm.c.contacts.save(null, d, account, null, false)
                                if (newId != null) {
                                    vm.c.vault.delete(id)
                                    vm.toast("Moved to phone contacts")
                                    back()
                                    open(Routes.contact(newId))
                                }
                            }
                        })
                        DropdownMenuItem({ Text("Share privately (QR)") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; shareQr = true })
                        DropdownMenuItem({ Text("Expires…") }, leadingIcon = { Icon(Icons.Rounded.Timer, null) }, onClick = { menu = false; expiry = true })
                        DropdownMenuItem({ Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(summary?.name ?: "?", null, 112.dp)
                    Text(summary?.name ?: "", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
                    Text("Only visible in Parley · encrypted", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    summary?.expiresAt?.let { Text("Deletes itself on ${Format.fullDate(context, it)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(12.dp))
                    val first = summary?.numbers?.firstOrNull()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button({ first?.let { vm.requestCall(it, summary.name) } }, enabled = first != null) { Icon(Icons.Rounded.Call, null); Text("  Call") }
                        Button({ first?.let { Intents.sms(context, it) } }, enabled = first != null) { Icon(Icons.AutoMirrored.Rounded.Message, null); Text("  Message") }
                    }
                }
            }
            if (locked) {
                item {
                    ListItem(
                        headlineContent = { Text("Unlock to see all details") },
                        supportingContent = { Text("Names and numbers work without unlocking so calls still show who's calling.") },
                        leadingContent = { Icon(Icons.Rounded.Lock, null) },
                        modifier = Modifier.clickable { unlock() },
                    )
                }
            }
            val d = details
            if (d != null) {
                if (d.phones.isNotEmpty()) item { Section("Phone") }
                d.phones.forEach { ph ->
                    item {
                        ListItem(
                            headlineContent = { Text(Format.number(ph.value, vm.countryIso)) },
                            supportingContent = { Text(Format.phoneType(context.resources, ph.type, ph.label)) },
                            leadingContent = { Icon(Icons.Rounded.Call, null) },
                            modifier = Modifier.clickable { vm.requestCall(ph.value, d.displayName) },
                        )
                    }
                }
                d.emails.forEach { e ->
                    item { ListItem(headlineContent = { Text(e.value) }, leadingContent = { Icon(Icons.Rounded.Email, null) }, modifier = Modifier.clickable { Intents.email(context, e.value) }) }
                }
                if (d.note.isNotBlank()) item { ListItem(headlineContent = { LinkifiedText(d.note) }, supportingContent = { Text("Note") }) }
            }
            val mine = calls.filter { it.vaultId == id }
            if (mine.isNotEmpty()) {
                item { Section("Private call history") }
                mine.forEach { c ->
                    item {
                        val type = app.parley.data.CallLogRepository.mapType(c.type)
                        val (icon, tint) = callTypeIcon(type)
                        ListItem(
                            leadingContent = { Icon(icon, null, tint = tint) },
                            headlineContent = { Text(Format.fullDate(context, c.date)) },
                            supportingContent = { Text(listOf(Format.number(c.number, vm.countryIso), Format.duration(c.durationSec)).filter { it.isNotBlank() }.joinToString(" · ")) },
                        )
                    }
                }
            }
        }
    }

    if (shareQr) details?.let { app.parley.ui.contact.SecureQrDialog(it) { shareQr = false } }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this private contact?") },
            text = { Text("This also deletes its private call history. It can't be undone.") },
            confirmButton = { TextButton({ confirmDelete = false; scope.launch { vm.c.vault.delete(id); back() } }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
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
        title = { Text("Delete automatically after") },
        text = {
            Column {
                listOf(1 to "1 day", 7 to "1 week", 30 to "30 days", 90 to "3 months", 365 to "1 year").forEach { (d, label) ->
                    ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { onPick(d) })
                }
                ListItem(headlineContent = { Text("Never (keep)") }, modifier = Modifier.clickable { onPick(null) })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
