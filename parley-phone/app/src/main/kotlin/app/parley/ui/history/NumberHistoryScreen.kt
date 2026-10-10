package app.parley.ui.history

import app.parley.common.calls.NetworkName
import app.parley.common.calls.NetworkNameSeen
import app.parley.ui.calls.NetworkNameTag
import app.parley.calls.ExpectedCallHints
import app.parley.calls.NeverCallsYouFacts
import app.parley.common.CallType
import app.parley.common.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.produceState
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
import androidx.compose.material.icons.rounded.AlarmAdd
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
import app.parley.ui.blocking.askToBlock
import app.parley.ui.calls.RemindToCallSheet
import app.parley.ui.blocking.rememberBlocked
import app.parley.ui.blocking.rememberEmergency
import app.parley.ui.blocking.unblockWithUndo
import app.parley.ui.calls.CallFactsHistorySection
import app.parley.ui.calls.RingFactsHistorySection
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.common.rememberNumberLocation
import app.parley.ui.home.CallLengthGlance
import app.parley.messaging.rememberCallAppLabel
import app.parley.ui.home.CallTypeIcon
import app.parley.ui.home.callClassLabel
import app.parley.ui.home.richCalls
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.cases.CaseCard
import app.parley.ui.circle.NumberAgendaBlock
import app.parley.ui.cases.CaseOwner
import app.parley.ui.Spacing
import app.parley.ui.DataL10n
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.LocalSnackbar
import app.parley.ui.ScreenSnackbarHost
import app.parley.common.people.Archive
import app.parley.calls.NetworkNames
import app.parley.ui.Bidi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberHistoryScreen(vm: AppViewModel, number: String, back: () -> Unit, open: (Destination) -> Unit, inPane: Boolean = false) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val calls by vm.c.history.calls.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val index by vm.numberIndex.collectAsStateWithLifecycle()
    val contact = index[number]
    val history = calls.orEmpty().filter { PhoneIdentity.same(it.number, number, vm.countryIso) }
    val blocked = rememberBlocked(vm, listOf(number))
    val emergency = rememberEmergency(vm, listOf(number))
    var messageOn by remember { mutableStateOf(false) }
    if (messageOn) ReachSheet(ReachTarget.Number(number), onDismiss = { messageOn = false }, onCall = { n -> vm.requestCall(n, contact?.displayName) })
    val notes by vm.c.meta.callNotesAny(PhoneIdentity.lookupKeys(number, vm.countryIso)).collectAsStateWithLifecycle(emptyList())
    // A private contact's menu shortcuts are on its own page (which hides them while the vault is locked); their
    // names may hold the private name, so they never show here. Unknown until checked, so hidden until then.
    var privateNumber by remember(number) { mutableStateOf<Boolean?>(null) }
    // Checked again when the private contacts change: a number made private while the page is open loses its
    // network name at once.
    val privateListing by vm.c.vault.listing.collectAsStateWithLifecycle()
    LaunchedEffect(number, privateListing) { privateNumber = runCatching { vm.c.vault.lookup(number, vm.countryIso) != null }.getOrDefault(true) }
    // A saved organisation's first call to you after you had only ever called them (their calls can be faked).
    val firstFromThem by produceState<Long?>(null, number, calls?.size) {
        value = catching { NeverCallsYouFacts.firstFromThem(vm.c, number, vm.countryIso) }.getOrNull()
    }
    val simLabels = sims.associate { it.id to it.label }.takeIf { sims.size > 1 }.orEmpty()
    // An archived contact's calls are still theirs: named here too.
    val archived by vm.c.archive.cards.collectAsStateWithLifecycle()
    val archivedName = remember(archived, number) { if (contact == null) Archive.index(archived, vm.countryIso)[number]?.name else null }
    // The name the network sent with its calls (never a private contact's number), while "Remember names from the
    // network" is on: for a number nobody saved it stands for the name, with the one before it when it changed; under a
    // saved name it is a small second line when it is a different name. Read again when the store changes (a call just
    // ended) or the setting does.
    val namesOn by remember(vm) { vm.settings.map { it.rememberNetworkNames }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
    val networkVersion by vm.c.networkNames.version.collectAsStateWithLifecycle()
    // Read as the SIM of the number's latest call reads it, as the name was written.
    val account = history.firstOrNull()?.accountId
    val kept by produceState(emptyList<NetworkNameSeen>(), number, namesOn, privateNumber, networkVersion, account) {
        value = if (!namesOn || privateNumber != false) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                catching { vm.c.networkNames.forNumber(NetworkNames.line(context, number, account)) }.getOrDefault(emptyList())
            }
        }
    }
    val savedName = contact?.displayName ?: archivedName
    val networkNames = if (savedName == null) kept else emptyList()
    val networkName = NetworkName.latest(networkNames)?.name
    val networkUnder = NetworkName.underSaved(savedName, NetworkName.latest(kept)?.name, NetworkName.Gate(enabled = namesOn))
    val title = contact?.displayName ?: archivedName ?: networkName ?: Format.number(number, vm.countryIso)
    var menu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var rangeDelete by remember { mutableStateOf(false) }
    var remindToCall by remember { mutableStateOf(false) }
    if (remindToCall) RemindToCallSheet(vm, number, contact?.displayName, onDismiss = { remindToCall = false })
    // The app's one snackbar, shown inside this screen's Scaffold.
    val snackbar = LocalSnackbar.current?.state ?: remember { SnackbarHostState() }
    // An export is named after a saved name only: a file named after the network's name would read like a contact's.
    if (exporting) ExportSheet(vm, history, subject = contact?.displayName ?: archivedName ?: Format.number(number, vm.countryIso)) { exporting = false }
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

    // [inPane]: beside Recents on a big screen, where there is nothing to go back to and Home shows the snackbar.
    ParleyScaffold(snackbarHost = { if (!inPane) ScreenSnackbarHost() }, topBar = {
        ParleyTopBar(
            stringResource(R.string.hist_settings_title),
            onBack = back.takeUnless { inPane },
            actions = {
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.dc_more_options)) }
                    DropdownMenu(menu, { menu = false }) {
                        // To call by hand (the same fixed times as Remind me after a call).
                        DropdownMenuItem(
                            { Text(stringResource(R.string.to_call_remind_me_to_call)) },
                            leadingIcon = { Icon(Icons.Rounded.AlarmAdd, null) },
                            onClick = { menu = false; remindToCall = true },
                        )
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
                    // A name the network sent is isolated: nothing in it turns the page's text around.
                    val shownTitle = if (contact == null && archivedName == null && networkName != null) Bidi.isolate(title) else title
                    Text(shownTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
                    // Under a saved name: what the network calls them, when that is a different name (not a verdict).
                    networkUnder?.let { n ->
                        Text(
                            stringResource(R.string.network_name_under, Bidi.isolate(n)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xxs),
                        )
                    }
                    if (contact == null && archivedName == null && networkName != null) {
                        NetworkNameTag(Modifier.padding(top = Spacing.xs))
                        NetworkName.before(networkNames)?.let { earlier ->
                            Text(
                                stringResource(R.string.network_name_before, earlier.name),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.xs),
                            )
                        }
                    }
                    if (contact != null || networkName != null) {
                        Text(DataL10n.ltr(Format.number(number, vm.countryIso)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val where = rememberNumberLocation(number, vm.countryIso)
                    val flag = remember(number) { NumberInfo.flag(NumberInfo.region(number, vm.countryIso)) }
                    if (where != null || flag != null) Text(listOfNotNull(flag, where).joinToString(" "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LastMessagedNote(number)
                    // What Parley remembers about a number that isn't a contact, with its action.
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
                        // An emergency number is never blocked: no Block (or Unblock) for it.
                        if (!emergency) ActionTile(
                            if (blocked) Icons.Rounded.RemoveModerator else Icons.Rounded.Block,
                            stringResource(if (blocked) R.string.hist_action_unblock else R.string.hist_action_block), true, lines = 2, fillHeight = true,
                        ) { if (blocked) unblockWithUndo(vm, listOf(number), contact?.displayName) else askToBlock(listOf(number), contact?.displayName) }
                    }
                }
            }
            item {
                CallInsightsSection(vm, listOf(number) + contact?.phones?.map { it.number }.orEmpty(), title = stringResource(R.string.hist_insights_title))
            }
            // Case files: an organisation's calls, hold times and reference numbers, before you call.
            item(key = "case") {
                val numbers = (listOf(number) + contact?.phones?.map { it.number }.orEmpty()).distinct()
                val owner = CaseOwner(title, numbers, privateNumber != false, contact?.lookupKey)
                CaseCard(vm, owner, open, Modifier.padding(vertical = Spacing.s))
            }
            // Things to talk about with whoever this number is, shown again when you call or they call.
            item(key = "agenda") { NumberAgendaBlock(vm, number, contact?.displayName, notes) }
            item { ReputationHistoryLine(vm, number, isContact = contact != null) }
            item { ScreeningHistorySection(vm, number, contact?.displayName) }
            item { RingFactsHistorySection(vm, number) }
            item { CallFactsHistorySection(vm, number) }
            // Menu shortcuts for this number (the only place for a number that isn't a contact).
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
                                    // A deleted call note no longer expects a call.
                                    runCatching { ExpectedCallHints.noteGone(vm.c, ExpectedCallHints.callNoteKey(n.id)) }
                                }
                            }) { Icon(Icons.Rounded.Delete, stringResource(R.string.hist_delete_note)) }
                        },
                    )
                }
            }
            if (history.isNotEmpty()) item { Section(stringResource(R.string.hist_calls_section)) }
            // Beside "First call from them to you": whether a call faking a saved organisation's number is warned about.
            item(key = "never-calls") { NeverCallsWatchLine(vm, number, calls?.size) }
            items(history, key = { it.id }) { e ->
                ParleyListItem(
                    leadingContent = { CallTypeIcon(e.type, describe = false, durationSec = e.durationSec) },
                    headlineContent = { Text(Format.fullDate(context, e.date)) },
                    supportingContent = {
                        // The rich style names the call class ("No answer" for an outgoing call nobody took).
                        val typeText = if (richCalls()) callClassLabel(CallClass.of(e)) else HistoryText.callType(e.type)
                        val video = if (e.video) stringResource(R.string.recents_video_call) else null
                        val length = Format.duration(e.durationSec).ifBlank { null }
                        val first = if (e.date == firstFromThem && e.type != CallType.OUTGOING) stringResource(R.string.hist_first_call_from_them) else null
                        // A call in an app names the app ("WhatsApp call") where a phone call names its SIM.
                        val appCall = rememberCallAppLabel(e.appPackage)?.let { stringResource(R.string.recents_app_call, it) }
                        val parts = listOfNotNull(stringResource(typeText), video, length, appCall ?: e.accountId?.let { simLabels[it] }, first)
                        Text(parts.joinToString(" · "))
                    },
                    trailingContent = { CallLengthGlance(e) },
                )
            }
        }
    }
}
