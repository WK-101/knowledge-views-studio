package app.parley.ui.cases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.cases.CaseFile
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseState
import app.parley.common.cases.CaseStatus
import app.parley.common.catching
import app.parley.ui.Banner
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.EmptyState
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.Section
import app.parley.ui.Spacing
import app.parley.ui.common.Format
import app.parley.ui.history.HistoryRoutes
import kotlinx.coroutines.launch

/**
 * Tools › Case files: every case kept, the newest activity first (resolved ones last, under their own heading), each
 * with where it stands, its last call and its open promises. ⋮ › "Stop keeping case files for all" stops them and
 * keeps organisations' calls from starting new ones; "Start again" undoes the second part. What a duress unlock or
 * discreet mode hides stays hidden here too ([app.parley.data.cases.CaseFileStore.shown]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseListScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val store = vm.cases
    LaunchedEffect(Unit) { catching { store.load() } }
    val state by store.shown.collectAsStateWithLifecycle(CaseState())
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    val listed = remember(state) { CaseFiles.listed(state) }
    val (active, resolved) = remember(listed) { listed.partition { it.status != CaseStatus.RESOLVED } }
    var menu by remember { mutableStateOf(false) }
    var stopping by rememberSaveable { mutableStateOf(false) }
    // A duress unlock hides case files: nothing done then may change them.
    val changeable = privacy.notesShown
    ParleyScaffold(topBar = {
        ParleyTopBar(
            stringResource(R.string.case_list_title), onBack = back,
            actions = {
                if (changeable && listed.isNotEmpty()) {
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.case_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.case_list_stop_all)) }, leadingIcon = { Icon(Icons.Rounded.FolderOff, null) },
                                onClick = { menu = false; stopping = true },
                            )
                        }
                    }
                }
            },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item(key = "explain") {
                Text(
                    stringResource(R.string.case_list_explain), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
            if (changeable && !state.autoStart) {
                item(key = "off") {
                    Banner(
                        stringResource(R.string.case_list_off), action = stringResource(R.string.case_list_turn_on),
                        onAction = { vm.viewModelScope.launch { store.update(CaseFiles::startAgain) } },
                    )
                }
            }
            if (listed.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.FolderOpen, stringResource(R.string.case_list_empty), stringResource(R.string.case_list_empty_body),
                        Modifier.padding(top = Spacing.xxl),
                    )
                }
            }
            items(active, key = { it.id }) { c -> CaseRow(vm, c) { open(HistoryRoutes.Case(c.id)) } }
            if (resolved.isNotEmpty()) {
                item(key = "resolved") { Section(stringResource(R.string.case_list_resolved)) }
                items(resolved, key = { it.id }) { c -> CaseRow(vm, c) { open(HistoryRoutes.Case(c.id)) } }
            }
        }
    }
    if (stopping) StopAllDialog(vm) { stopping = false }
}

/** One case: its organisation, where it stands (unless open), its last call and its open promises. */
@Composable
private fun CaseRow(vm: AppViewModel, case: CaseFile, onOpen: () -> Unit) {
    val context = LocalContext.current
    val sep = stringResource(R.string.main_separator)
    val ownerKey by produceState<String?>(null, case.numbers) { value = CaseData.ownerKey(vm, case.numbers) }
    val timeline = rememberCaseTimeline(vm, case, CaseOwner(case.name, case.numbers, case.private, ownerKey))
    val summary = timeline?.summary
    val status = stringResource(CaseStatusText.of(case.status)).takeIf { case.status != CaseStatus.OPEN }
    val last = summary?.lastCallAt?.let { Format.shortWhen(context, it) }
        ?.let { pluralStringResource(R.plurals.case_calls_last, summary.calls, summary.calls, it) }
    val promises = summary?.openPromises?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.case_open_promises, it, it) }
    val line = listOfNotNull(status, last, promises).joinToString(sep)
    ParleyListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.case_open), onClick = onOpen),
        leadingContent = { Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(Bidi.isolate(case.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = line.takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
    )
}

@Composable
private fun StopAllDialog(vm: AppViewModel, onDone: () -> Unit) {
    val res = LocalResources.current
    ConfirmDialog(
        title = stringResource(R.string.case_list_stop_all_title), text = stringResource(R.string.case_list_stop_all_body),
        confirmLabel = stringResource(R.string.case_stop_confirm), destructive = true,
        onConfirm = {
            onDone()
            vm.viewModelScope.launch { if (vm.cases.update(CaseFiles::stopAll) != null) vm.toast(res.getString(R.string.case_list_stopped_all)) }
        },
        onDismiss = onDone,
    )
}
