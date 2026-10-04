package app.parley.ui.cases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.cases.CaseEntry
import app.parley.common.cases.CaseFile
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseReference
import app.parley.common.cases.CaseState
import app.parley.common.cases.CaseTimeline
import app.parley.ui.Bidi
import app.parley.ui.Clipboard
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.ScreenSnackbarHost
import app.parley.ui.Section
import app.parley.ui.Spacing
import app.parley.ui.common.Format
import kotlinx.coroutines.launch

/**
 * One organisation's case file: the summary, the reference numbers (hidden until shown, one at a time), the open
 * promises and every call and note, newest first. Export as PDF (for a complaint) and Stop keeping are in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseScreen(vm: AppViewModel, id: String, back: () -> Unit) {
    val store = vm.c.cases
    LaunchedEffect(Unit) { runCatching { store.load() } }
    val state by store.shown.collectAsStateWithLifecycle(CaseState())
    val case = CaseFiles.byId(state, id)?.takeIf { it.kept }
    val ownerKey by produceState<String?>(null, case?.numbers) { value = case?.let { CaseData.ownerKey(vm, it.numbers) } }
    val owner = case?.let { CaseOwner(it.name, it.numbers, it.private, ownerKey) }
    val timeline = owner?.let { rememberCaseTimeline(vm, case, it) }
    var menu by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val res = LocalResources.current
    ParleyScaffold(
        snackbarHost = { ScreenSnackbarHost() },
        topBar = {
            ParleyTopBar(
                case?.let { stringResource(R.string.case_title_who, it.name) } ?: stringResource(R.string.case_title),
                onBack = back,
                actions = {
                    if (case != null) {
                        IconButton({ exporting = true }, enabled = timeline != null) { Icon(Icons.Rounded.PictureAsPdf, stringResource(R.string.case_export)) }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.case_more)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.case_stop)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { menu = false; stopping = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { p ->
        if (case == null || owner == null) {
            Text(stringResource(R.string.case_gone), Modifier.padding(p).padding(Spacing.xl))
            return@ParleyScaffold
        }
        LazyColumn(Modifier.padding(p)) {
            item {
                Text(
                    stringResource(R.string.case_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
            if (timeline != null) summary(timeline)
            references(vm, case) { adding = true }
            if (timeline != null) timeline(timeline)
        }
    }
    if (case != null && adding) AddReferenceDialog(vm, case) { adding = false }
    if (case != null && stopping) {
        ConfirmDialog(
            title = stringResource(R.string.case_stop_title), text = stringResource(R.string.case_stop_body),
            confirmLabel = stringResource(R.string.case_stop_confirm), destructive = true,
            onConfirm = {
                stopping = false
                vm.viewModelScope.launch { if (store.update { CaseFiles.stop(it, case.id) } != null) vm.toast(res.getString(R.string.case_stopped)) }
                back()
            },
            onDismiss = { stopping = false },
        )
    }
    if (case != null && timeline != null && exporting) CaseExportSheet(vm, case, timeline) { exporting = false }
}

private fun LazyListScope.summary(t: CaseTimeline) {
    item(key = "summary") {
        val context = LocalContext.current
        val res = LocalResources.current
        val s = t.summary
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            Text(stringResource(R.string.case_summary), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            summaryLines(context, res, s).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (s.heldCalls > 1) {
                Text(
                    stringResource(
                        R.string.case_hold_detail, Format.duration(s.totalHoldSec), Format.duration(s.averageHoldSec), Format.duration(s.longestHoldSec),
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (t.promises.isNotEmpty()) {
        item(key = "promises") { Section(stringResource(R.string.case_promises_title)) }
        items(t.promises, key = { "p" + it.line + it.text }) { pr ->
            ParleyListItem(leadingContent = { Icon(Icons.Rounded.CheckBoxOutlineBlank, null) }, headlineContent = { Text(pr.text) })
        }
    }
}

private fun LazyListScope.references(vm: AppViewModel, case: CaseFile, onAdd: () -> Unit) {
    item(key = "refs") { Section(stringResource(R.string.case_references_title)) }
    items(case.references.sortedByDescending { it.at }, key = { "r" + it.id }) { r -> ReferenceRow(vm, case, r) }
    if (case.references.size < CaseFiles.MAX_REFERENCES) {
        item(key = "add_ref") {
            ParleyListItem(
                leadingContent = { Icon(Icons.Rounded.Add, null) },
                headlineContent = { Text(stringResource(R.string.case_add_reference)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onAdd),
            )
        }
    }
}

/** A reference number: hidden ("•••• 4821" once shown before) until Show, with Copy and Delete. */
@Composable
private fun ReferenceRow(vm: AppViewModel, case: CaseFile, r: CaseReference) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var shown by remember(r.id) { mutableStateOf<String?>(null) }
    var deleting by remember(r.id) { mutableStateOf(false) }
    val label = r.label.ifEmpty { stringResource(R.string.case_reference_unnamed) }
    suspend fun opened(): String? = vm.c.cases.openReference(r).also { if (it == null) vm.toast(res.getString(R.string.case_reference_unreadable)) }
    ParleyListItem(
        leadingContent = { Icon(Icons.Rounded.Bookmark, null) },
        headlineContent = { Text(shown?.let { Bidi.ltr(it) } ?: label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parts = listOfNotNull(
                label.takeIf { shown != null },
                Format.fullDate(context, r.at),
                stringResource(R.string.case_reference_typed).takeIf { r.typed },
            )
            Text(parts.joinToString(stringResource(R.string.main_separator)))
        },
        trailingContent = {
            Row {
                IconButton({ if (shown != null) shown = null else scope.launch { shown = opened() } }) {
                    Icon(
                        if (shown != null) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        stringResource(if (shown != null) R.string.case_reference_hide else R.string.case_reference_show, label),
                    )
                }
                IconButton({
                    scope.launch {
                        opened()?.let { Clipboard.copy(context, it, sensitive = true, confirm = res.getString(R.string.case_reference_copied), label = label) }
                    }
                }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.case_reference_copy, label)) }
                IconButton({ deleting = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.case_reference_delete, label)) }
            }
        },
    )
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.case_reference_delete, label), text = null, confirmLabel = stringResource(R.string.main_delete), destructive = true,
            onConfirm = {
                deleting = false
                vm.viewModelScope.launch { vm.c.cases.update { CaseFiles.removeReference(it, case.id, r.id) } }
            },
            onDismiss = { deleting = false },
        )
    }
}

private fun LazyListScope.timeline(t: CaseTimeline) {
    item(key = "timeline") { Section(stringResource(R.string.case_timeline_title)) }
    if (t.entries.isEmpty()) {
        item(key = "timeline_empty") {
            Text(
                stringResource(R.string.case_timeline_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
        }
        return
    }
    items(t.entries, key = { e -> entryKey(e) }) { e -> EntryRow(e) }
}

private fun entryKey(e: CaseEntry): String = when (e) {
    is CaseEntry.Call -> "c${e.at}"
    is CaseEntry.Note -> "n${e.at}${e.text.hashCode()}"
    is CaseEntry.Reference -> "r${e.id}"
}

@Composable
private fun EntryRow(e: CaseEntry) {
    val context = LocalContext.current
    val date = Format.fullDate(context, e.at)
    when (e) {
        is CaseEntry.Call -> ParleyListItem(
            leadingContent = { Icon(Icons.Rounded.Call, null) },
            headlineContent = { Text(callLine(context, e)) },
            supportingContent = { Text(date) },
        )
        is CaseEntry.Note -> ParleyListItem(
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Notes, null) },
            overlineContent = { Text(stringResource(R.string.case_note)) },
            headlineContent = { Text(e.text) },
            supportingContent = { Text(date) },
        )
        is CaseEntry.Reference -> ParleyListItem(
            leadingContent = { Icon(Icons.Rounded.Bookmark, null) },
            headlineContent = { Text(stringResource(R.string.case_reference_added_on, e.label.ifEmpty { stringResource(R.string.case_reference_unnamed) })) },
            supportingContent = { Text(date) },
        )
    }
}

/** "Add a reference number": what it is for (optional) and the number, sealed as soon as it is kept. */
@Composable
private fun AddReferenceDialog(vm: AppViewModel, case: CaseFile, onDone: () -> Unit) {
    val res = LocalResources.current
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(R.string.case_add_reference), text = null, confirmLabel = stringResource(R.string.main_save),
        confirmEnabled = CaseFiles.cleanReference(value) != null,
        onConfirm = {
            onDone()
            val l = label
            val v = value
            vm.viewModelScope.launch {
                val ok = vm.c.cases.addReference(case.id, l, v, typed = false)
                vm.toast(res.getString(if (ok) R.string.case_reference_added else R.string.case_reference_failed))
            }
        },
        onDismiss = onDone,
        content = {
            OutlinedTextField(
                value = value, onValueChange = { value = it.take(CaseFiles.MAX_REFERENCE) }, singleLine = true,
                label = { Text(stringResource(R.string.case_reference_value)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
            )
            OutlinedTextField(
                value = label, onValueChange = { label = it.take(CaseFiles.MAX_LABEL) }, singleLine = true,
                label = { Text(stringResource(R.string.case_reference_label)) },
                placeholder = { Text(stringResource(R.string.case_reference_label_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
            )
        },
    )
}
