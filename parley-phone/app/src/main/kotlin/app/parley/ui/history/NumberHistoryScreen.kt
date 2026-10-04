package app.parley.ui.history

import app.parley.calls.ExpectedCallHints
import app.parley.ui.Clipboard
import app.parley.ui.Destination
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Sms
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.contact.ActionTile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.LocalResources
import app.parley.common.PhoneIdentity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.parley.common.ux.CallClass
import app.parley.data.NumberInfo
import app.parley.messaging.LastMessagedNote
import app.parley.messaging.ReachSheet
import app.parley.messaging.ReachTarget
import app.parley.ui.Avatar
import app.parley.ui.Routes
import app.parley.ui.blocking.ReputationHistoryLine
import app.parley.ui.blocking.ScreeningHistorySection
import app.parley.ui.calls.CallFactsHistorySection
import app.parley.ui.calls.RingFactsHistorySection
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.common.rememberNumberLocation
import app.parley.ui.home.CallLengthGlance
import app.parley.ui.home.CallTypeIcon
import app.parley.ui.home.callClassLabel
import app.parley.ui.home.richCalls
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.DataL10n
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.LocalSnackbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberHistoryScreen(vm: AppViewModel, number: String, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val calls by vm.c.history.calls.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val index by vm.numberIndex.collectAsStateWithLifecycle()
    val contact = index[number]
    val history = calls.orEmpty().filter { PhoneIdentity.same(it.number, number, vm.countryIso) }
    var blocked by remember { mutableStateOf(false) }
    var messageOn by remember { mutableStateOf(false) }
    if (messageOn) ReachSheet(ReachTarget.Number(number), onDismiss = { messageOn = false }, onCall = { n -> vm.requestCall(n, contact?.displayName) })
    val notes by vm.c.meta.callNotesAny(PhoneIdentity.lookupKeys(number, vm.countryIso)).collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(number) { blocked = vm.c.blocks.isSystemBlocked(number) }
    // L6: a private contact's menu shortcuts are on its own page (which hides them while the vault is locked); their
    // names may hold the private name, so they never show here. Unknown until checked, so hidden until then.
    var privateNumber by remember(number) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(number) { privateNumber = runCatching { vm.c.vault.lookup(number, vm.countryIso) != null }.getOrDefault(true) }
    val simLabels = sims.associate { it.id to it.label }.takeIf { sims.size > 1 }.orEmpty()
    val title = contact?.displayName ?: Format.number(number, vm.countryIso)
    var menu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var rangeDelete by remember { mutableStateOf(false) }
    // The app's one snackbar, shown inside this screen's Scaffold.
    val snackbar = LocalSnackbar.current?.state ?: remember { SnackbarHostState() }
    if (exporting) ExportSheet(vm, history, subject = title) { exporting = false }
    if (rangeDelete) {
        RangeDeleteDialog(vm, number, onDismiss = { rangeDelete = false }, onDeleted = { batch, n ->
            scope.launch {
                val r = snackbar.showSnackbar(
                    res.getQuantityString(R.plurals.hist_deleted_calls, n, n), actionLabel = res.getString(R.string.dc_undo), duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) vm.c.history.undoDelete(batch)
            }
        })
    }

    ParleyScaffold(topBar = {
        ParleyTopBar(
            stringResource(R.string.hist_settings_title),
            onBack = back,
            actions = {
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.dc_more_options)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            { Text(stringResource(R.string.hist_export_menu)) },
                            leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                            onClick = { menu = false; exporting = true },
                            enabled = history.isNotEmpty(),
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.hist_delete_calls_menu)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; rangeDelete = true },
                            enabled = history.isNotEmpty(),
                        )
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
                    if (contact != null) Text(DataL10n.ltr(Format.number(number, vm.countryIso)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val where = rememberNumberLocation(number, vm.countryIso)
                    val flag = remember(number) { NumberInfo.flag(NumberInfo.region(number, vm.countryIso)) }
                    if (where != null || flag != null) Text(listOfNotNull(flag, where).joinToString(" "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LastMessagedNote(number)
                    // I1: what Parley remembers about a number that isn't a contact, with its action.
                    if (contact == null) app.parley.ui.memory.HistoryNumberMemory(vm, number, open)
                    // The same tiles as a contact's page: one icon per action, even widths, labels that wrap
                    // rather than break mid-word.
                    val messageOnLabel = stringResource(R.string.reach_message_or_call_on)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ActionTile(Icons.Rounded.Call, stringResource(R.string.hist_action_call), true, fillHeight = true) {
                            vm.requestCall(number, contact?.displayName)
                        }
                        ActionTile(Icons.Rounded.Sms, stringResource(R.string.hist_action_message), true, fillHeight = true) {
                            Intents.sms(context, number)
                        }
                        ActionTile(
                            Icons.Rounded.Apps, stringResource(R.string.hist_action_other_apps), true, lines = 2, fillHeight = true,
                            description = messageOnLabel,
                        ) { messageOn = true }
                        ActionTile(Icons.Rounded.ContentCopy, stringResource(R.string.hist_action_copy), true, fillHeight = true) {
                            Clipboard.copy(context, number)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (contact == null) {
                            ActionTile(Icons.Rounded.PersonAdd, stringResource(R.string.hist_action_new_contact), true, lines = 2, fillHeight = true) {
                                open(Routes.edit(phone = number))
                            }
                            ActionTile(Icons.Rounded.PersonSearch, stringResource(R.string.hist_action_add_to_contact), true, lines = 2, fillHeight = true) {
                                open(Routes.pick(number))
                            }
                        } else {
                            ActionTile(Icons.Rounded.AccountCircle, stringResource(R.string.hist_action_view_contact), true, lines = 2, fillHeight = true) {
                                open(Routes.contact(contact.id))
                            }
                        }
                        ActionTile(
                            if (blocked) Icons.Rounded.RemoveModerator else Icons.Rounded.Block,
                            stringResource(if (blocked) R.string.hist_action_unblock else R.string.hist_action_block), true, lines = 2, fillHeight = true,
                        ) { if (blocked) vm.unblockNumber(number) else vm.blockNumber(number); blocked = !blocked }
                    }
                }
            }
            item {
                CallInsightsSection(vm, listOf(number) + contact?.phones?.map { it.number }.orEmpty(), title = stringResource(R.string.hist_insights_title))
            }
            item { ReputationHistoryLine(vm, number, isContact = contact != null) }
            item { ScreeningHistorySection(vm, number, contact?.displayName) }
            item { RingFactsHistorySection(vm, number) }
            item { CallFactsHistorySection(vm, number) }
            // I6: menu shortcuts for this number (the only place for a number that isn't a contact).
            if (privateNumber == false) item { app.parley.ui.menus.MenuShortcutsBlock(vm, listOf(number), contact?.displayName ?: number, contact?.photoUri) }
            if (notes.isNotEmpty()) {
                item { Section(stringResource(R.string.hist_call_notes)) }
                items(notes, key = { "n" + it.id }) { n ->
                    ParleyListItem(
                        headlineContent = { Text(n.text) },
                        supportingContent = { Text(Format.fullDate(context, n.callDate)) },
                        trailingContent = {
                            IconButton({
                                scope.launch {
                                    vm.c.meta.deleteCallNote(n.id)
                                    // I7: a deleted call note no longer expects a call.
                                    runCatching { ExpectedCallHints.noteGone(vm.c, ExpectedCallHints.callNoteKey(n.id)) }
                                }
                            }) { Icon(Icons.Rounded.Delete, stringResource(R.string.hist_delete_note)) }
                        },
                    )
                }
            }
            if (history.isNotEmpty()) item { Section(stringResource(R.string.hist_calls_section)) }
            items(history, key = { it.id }) { e ->
                ParleyListItem(
                    leadingContent = { CallTypeIcon(e.type, describe = false, durationSec = e.durationSec) },
                    headlineContent = { Text(Format.fullDate(context, e.date)) },
                    supportingContent = {
                        // The rich style names the call class ("No answer" for an outgoing call nobody took).
                        val typeText = if (richCalls()) callClassLabel(CallClass.of(e)) else HistoryText.callType(e.type)
                        val video = if (e.video) stringResource(R.string.recents_video_call) else null
                        val length = Format.duration(e.durationSec).ifBlank { null }
                        val parts = listOfNotNull(stringResource(typeText), video, length, e.accountId?.let { simLabels[it] })
                        Text(parts.joinToString(" · "))
                    },
                    trailingContent = { CallLengthGlance(e) },
                )
            }
        }
    }
}
