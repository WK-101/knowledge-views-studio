package app.parley.ui.blocking

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.ListMode
import app.parley.common.spam.BuiltInPacks
import app.parley.common.spam.PackOrigin
import app.parley.common.spam.PackState
import app.parley.common.spam.ParsedPack
import app.parley.common.spam.SignatureStatus
import app.parley.data.DryRun
import app.parley.data.SpamListStore
import app.parley.ui.common.Format
import kotlinx.coroutines.launch

/**
 * Spam lists (B4, B5, B7, B13): add a `.parleylist` file or subscribe to a folder (Syncthing, Nextcloud,
 * Downloads); each list warns by default, blocking is opt-in with a score threshold. Before adding a file
 * you see what it would have caught last week.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamListsScreen(vm: AppViewModel, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by vm.c.lists.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<ParsedPack?>(null) }
    var pendingDry by remember { mutableStateOf<DryRun?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val parsed = vm.c.lists.parse(uri)
                pending = parsed
                pendingDry = runCatching { vm.c.screener.dryRun(vm.c.callLog.calls.value.orEmpty(), 7, candidatePack = parsed) }.getOrNull()
            } catch (e: Exception) {
                error = e.message ?: "Not a valid list"
            }
            busy = false
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            vm.c.lists.subscribe(uri)
            busy = false
            vm.toast("Folder subscribed")
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Spam lists") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                val sum = vm.c.lists.summarize(state, now)
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (sum.lists == 0) "No lists yet" else "${sum.lists} ${if (sum.lists == 1) "list" else "lists"} · ${"%,d".format(sum.numbers)} numbers" + (sum.updatedAt?.let { " · updated ${ago(it, now)}" } ?: ""),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Lists are files you add yourself: a regulator's ranges, the US FTC's reported numbers, a list a friend shared. Parley looks numbers up on the phone and never sends them anywhere. Your contacts, allowed numbers and repeat callers always win.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ pickFile.launch(arrayOf("*/*")) }, enabled = !busy) { Icon(Icons.Rounded.Add, null); Text(" Add a list file") }
                            OutlinedButton({ pickFolder.launch(null) }, enabled = !busy) { Icon(Icons.Rounded.Folder, null); Text(" Folder") }
                        }
                    }
                }
            }
            item {
                val folder = state.folderUri
                if (folder != null) {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.Folder, null) },
                        headlineContent = { Text("Subscribed folder") },
                        supportingContent = {
                            Text(
                                (Uri.parse(folder).lastPathSegment ?: folder) + " · checked " + (if (state.folderCheckedAt > 0) ago(state.folderCheckedAt, now) else "never") +
                                    (state.folderError?.let { "\n$it" } ?: "") + "\nNew .parleylist files there are added automatically, and re-checked daily.",
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton({ scope.launch { busy = true; val n = vm.c.lists.refreshFolder(); busy = false; vm.toast(if (n == 0) "No new lists" else "Updated $n") } }) { Icon(Icons.Rounded.Refresh, "Check now") }
                                TextButton({ scope.launch { vm.c.lists.unsubscribe() } }) { Text("Remove") }
                            }
                        },
                    )
                }
            }
            item(key = "updater") { ListsUpdaterSection(vm) }
            val suggested = BuiltInPacks.all.filter { b -> state.packs.none { it.id == b.id } }
            if (suggested.isNotEmpty()) {
                item { app.parley.ui.contact.Section("Built in") }
                items(suggested, key = { "b" + it.id }) { b ->
                    ListItem(
                        headlineContent = { Text(b.name + if (b.country.equals(vm.countryIso, true)) " · for your SIM" else "") },
                        supportingContent = { Text(b.description) },
                        trailingContent = { TextButton({ scope.launch { vm.c.lists.installBuiltIn(b); vm.toast("Added") } }) { Text("Add") } },
                    )
                }
            }
            if (state.packs.isNotEmpty()) item { app.parley.ui.contact.Section("Your lists") }
            items(state.packs, key = { "p" + it.id }) { pk -> PackCard(vm, pk, now) }
        }
    }

    pending?.let { pk ->
        val m = pk.manifest
        val existing = state.packs.firstOrNull { it.id == m.id }
        AlertDialog(
            onDismissRequest = { pending = null; pendingDry = null },
            title = { Text(if (existing != null) "Update ${m.name}?" else "Add ${m.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${"%,d".format(pk.numbers.size / 10)} numbers · ${pk.ranges.size} ranges · version ${m.version}")
                    if (m.publisher.isNotBlank()) Text("From ${m.publisher}" + (m.licence.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
                    if (pk.signature == SignatureStatus.SIGNED) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Verified, null); Text("  Signed · key ${pk.fingerprint}") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Text("  Unsigned: the file is intact, but anyone could have made it.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    pendingDry?.let { d ->
                        val added = d.added
                        Text(
                            "Last 7 days: it would have flagged ${added.size} of ${d.current.unknown.size} calls from unknown numbers" +
                                if (added.isNotEmpty()) " (" + added.take(3).joinToString { Format.number(it.call.number, vm.countryIso) } + if (added.size > 3) "…)" else ")" else "",
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text("New lists only warn (\"Likely spam\" on the call screen). You can let a list block calls afterwards.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        when (val r = vm.c.lists.install(pk, PackOrigin.FILE)) {
                            is SpamListStore.InstallResult.Installed -> vm.toast(if (r.replaced) "Updated" else "Added")
                            is SpamListStore.InstallResult.Older -> vm.toast("You already have a newer version (${r.installed})")
                            is SpamListStore.InstallResult.Failed -> error = r.reason
                        }
                    }
                    pending = null
                    pendingDry = null
                }) { Text(if (existing != null) "Update" else "Add") }
            },
            dismissButton = { TextButton({ pending = null; pendingDry = null }) { Text("Cancel") } },
        )
    }
    error?.let { e ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't add the list") }, text = { Text(e) }, confirmButton = { TextButton({ error = null }) { Text("OK") } })
    }
}

@Composable
private fun PackCard(vm: AppViewModel, pk: PackState, now: Long) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val stale = pk.isStale(now)
    Column {
        ListItem(
            modifier = Modifier.padding(0.dp),
            headlineContent = { Text(pk.name) },
            supportingContent = {
                Column {
                    Text(
                        listOfNotNull(
                            "%,d numbers".format(pk.entries) + if (pk.ranges > 0) " · ${pk.ranges} ranges" else "",
                            if (pk.mode == ListMode.BLOCK) "blocks at score ≥ ${pk.threshold}" else "warns",
                            if (pk.signed) "signed" else if (pk.origin == PackOrigin.BUILTIN) "built in" else "unsigned",
                            "from Parley Lists".takeIf { pk.origin == PackOrigin.UPDATER },
                            "updated ${ago(pk.installedAt, now)}",
                        ).joinToString(" · "),
                    )
                    if (stale) Text("Out of date: no update for more than ${pk.ttlDays} days", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            trailingContent = { Switch(pk.enabled, { v -> scope.launch { vm.c.lists.setPack(pk.id) { it.copy(enabled = v) } } }) },
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ open = !open }) { Text(if (open) "Less" else "Options") }
        }
        if (open) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pk.publisher.isNotBlank() || pk.licence.isNotBlank()) Text(listOf(pk.publisher, pk.licence).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                pk.fingerprint?.let { Text("Publisher key $it", style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(pk.mode == ListMode.WARN, { scope.launch { vm.c.lists.setPack(pk.id) { it.copy(mode = ListMode.WARN) } } }, label = { Text("Warn only") })
                    FilterChip(pk.mode == ListMode.BLOCK, { scope.launch { vm.c.lists.setPack(pk.id) { it.copy(mode = ListMode.BLOCK) } } }, label = { Text("Block") })
                }
                if (pk.mode == ListMode.BLOCK) {
                    var t by remember(pk.threshold) { mutableFloatStateOf(pk.threshold.toFloat()) }
                    Text("Block when the list's score is at least ${t.toInt()} (of 100)")
                    Slider(t, { t = it }, valueRange = 0f..100f, steps = 19, onValueChangeFinished = { scope.launch { vm.c.lists.setPack(pk.id) { it.copy(threshold = t.toInt()) } } })
                    ActionChoice(pk.action, { a -> scope.launch { vm.c.lists.setPack(pk.id) { it.copy(action = a) } } })
                }
                if (pk.ranges > 0) ToggleRow("Match ranges", "Also match whole number blocks, not only listed numbers", pk.useRanges) { v -> scope.launch { vm.c.lists.setPack(pk.id) { it.copy(useRanges = v) } } }
                Text("Notify", style = MaterialTheme.typography.labelLarge)
                NotifyChoice(pk.notify, allowDefault = true) { n -> scope.launch { vm.c.lists.setPack(pk.id) { it.copy(notify = n) } } }
                if (pk.suppressed.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${pk.suppressed.size} marked \"Not spam\"", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton({ scope.launch { vm.c.lists.setPack(pk.id) { it.copy(suppressed = emptyList()) } } }) { Text("Clear") }
                    }
                }
                if (pk.source.isNotBlank()) Text("Source: ${pk.source}", style = MaterialTheme.typography.bodySmall)
                TextButton({ confirmRemove = true }) { Text("Remove list", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${pk.name}?") },
            confirmButton = {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                TextButton({
                    scope.launch {
                        // A list from Parley Lists would come back tomorrow: stop its updates too.
                        if (pk.origin == PackOrigin.UPDATER) app.parley.blocking.ListsUpdaterClient.unsubscribe(ctx, vm.c.lists, pk.id) else vm.c.lists.remove(pk.id)
                    }
                    confirmRemove = false
                }) { Text("Remove") }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
