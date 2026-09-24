package app.parley.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.PhoneNumbers
import app.parley.ui.Avatar
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.home.callTypeIcon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberHistoryScreen(vm: AppViewModel, number: String, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calls by vm.c.history.calls.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val index by vm.numberIndex.collectAsStateWithLifecycle()
    val contact = index[PhoneNumbers.matchKey(number)]
    val history = calls.orEmpty().filter { PhoneNumbers.same(it.number, number, vm.countryIso) }
    var blocked by remember { mutableStateOf(false) }
    var messageOn by remember { mutableStateOf(false) }
    if (messageOn) app.parley.messaging.MessageOnSheet(number, onDismiss = { messageOn = false })
    val notes by vm.c.meta.callNotes(PhoneNumbers.matchKey(number)).collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(number) { blocked = vm.c.blocks.isSystemBlocked(number) }
    val simLabels = sims.associate { it.id to it.label }.takeIf { sims.size > 1 }.orEmpty()
    val title = contact?.displayName ?: Format.number(number, vm.countryIso)
    var menu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var rangeDelete by remember { mutableStateOf(false) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    if (exporting) ExportSheet(vm, history, subject = title) { exporting = false }
    if (rangeDelete) {
        RangeDeleteDialog(vm, number, onDismiss = { rangeDelete = false }, onDeleted = { batch, n ->
            scope.launch {
                val r = snackbar.showSnackbar("Deleted $n ${if (n == 1) "call" else "calls"}", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.c.history.undoDelete(batch)
            }
        })
    }

    Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }, topBar = {
        TopAppBar(
            title = { Text("Call history") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                androidx.compose.foundation.layout.Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
                    androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem({ Text("Export…") }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null) }, onClick = { menu = false; exporting = true }, enabled = history.isNotEmpty())
                        androidx.compose.material3.DropdownMenuItem({ Text("Delete calls…") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; rangeDelete = true }, enabled = history.isNotEmpty())
                    }
                }
            },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(title, contact?.photoUri, 96.dp)
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
                    if (contact != null) Text(Format.number(number, vm.countryIso), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val where = remember(number) { app.parley.data.NumberInfo.location(number, vm.countryIso) }
                    val flag = remember(number) { app.parley.data.NumberInfo.flag(app.parley.data.NumberInfo.region(number, vm.countryIso)) }
                    if (where != null || flag != null) Text(listOfNotNull(flag, where).joinToString(" "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    app.parley.messaging.LastMessagedNote(number)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ vm.requestCall(number, contact?.displayName) }, { Text("Call") }, leadingIcon = { Icon(Icons.Rounded.Call, null) })
                        AssistChip({ Intents.sms(context, number) }, { Text("Message") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Message, null) })
                        AssistChip({ Intents.copy(context, number) }, { Text("Copy") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ messageOn = true }, { Text("Message on…") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Chat, null) })
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (contact == null) {
                            AssistChip({ open(Routes.edit(phone = number)) }, { Text("New contact") }, leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) })
                            AssistChip({ open(Routes.pick(number)) }, { Text("Add to contact") }, leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) })
                        } else {
                            AssistChip({ open(Routes.contact(contact.id)) }, { Text("View contact") })
                        }
                        AssistChip(
                            { if (blocked) vm.unblockNumber(number) else vm.blockNumber(number); blocked = !blocked },
                            { Text(if (blocked) "Unblock" else "Block") },
                            leadingIcon = { Icon(Icons.Rounded.Block, null) },
                        )
                    }
                }
            }
            item { CallInsightsSection(vm, listOf(number) + contact?.phones?.map { it.number }.orEmpty(), title = "Insights") }
            if (notes.isNotEmpty()) {
                item { app.parley.ui.contact.Section("Call notes") }
                items(notes, key = { "n" + it.id }) { n ->
                    ListItem(
                        headlineContent = { Text(n.text) },
                        supportingContent = { Text(Format.fullDate(context, n.callDate)) },
                        trailingContent = { IconButton({ scope.launch { vm.c.meta.deleteCallNote(n.id) } }) { Icon(Icons.Rounded.Delete, "Delete note") } },
                    )
                }
            }
            if (history.isNotEmpty()) item { app.parley.ui.contact.Section("Calls") }
            items(history, key = { it.id }) { e ->
                val (icon, tint) = callTypeIcon(e.type)
                ListItem(
                    leadingContent = { Icon(icon, null, tint = tint) },
                    headlineContent = { Text(Format.fullDate(context, e.date)) },
                    supportingContent = {
                        Text(listOfNotNull(e.type.name.lowercase().replaceFirstChar { it.uppercase() }, Format.duration(e.durationSec).ifBlank { null }, e.accountId?.let { simLabels[it] }).joinToString(" · "))
                    },
                )
            }
        }
    }
}
