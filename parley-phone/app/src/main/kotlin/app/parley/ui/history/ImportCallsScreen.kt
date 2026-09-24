package app.parley.ui.history

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import app.parley.common.history.ColumnMapping
import app.parley.common.history.ImportPlan
import app.parley.common.history.ImportSource
import app.parley.common.history.ProviderColumns
import app.parley.ui.common.Format
import app.parley.ui.contact.Section
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

/** H8: import call history from a CSV (Parley, Logger or any spreadsheet) with a dry run first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportCallsScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dayFirst by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<String?>(null) }

    fun replan(mapping: ColumnMapping? = null) {
        val u = uri ?: return
        busy = context.getString(R.string.hist_import_reading)
        error = null
        scope.launch {
            try {
                plan = vm.c.history.planImport(u, mapping, dayFirst)
            } catch (e: Exception) {
                plan = null
                error = e.message ?: context.getString(R.string.hist_import_read_failed)
            } finally {
                busy = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            uri = u
            replan()
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.hist_import_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Text(
                    stringResource(R.string.hist_import_explain),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!isDefault) item {
                Text(stringResource(R.string.hist_import_need_default), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
            item {
                OutlinedButton({ picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream", "*/*")) }, Modifier.padding(16.dp)) {
                    Icon(Icons.Rounded.FileOpen, null)
                    Text("  " + if (uri == null) stringResource(R.string.hist_import_choose) else stringResource(R.string.hist_import_choose_another))
                }
            }
            busy?.let { b -> item { Column(Modifier.padding(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(b) } } }
            error?.let { e -> item { Text(e, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
            val pl = plan
            if (pl != null) {
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.hist_import_dry_run, stringResource(HistoryText.source(pl.source))), style = MaterialTheme.typography.titleMedium)
                            Text(pluralStringResource(R.plurals.hist_import_rows_read, pl.rowsRead, pl.rowsRead))
                            Text(
                                buildList {
                                    add(pluralStringResource(R.plurals.hist_import_new_calls, pl.toInsert.size, pl.toInsert.size))
                                    if (pl.duplicates > 0) add(pluralStringResource(R.plurals.hist_import_already, pl.duplicates, pl.duplicates))
                                    if (pl.problems.isNotEmpty()) add(pluralStringResource(R.plurals.hist_import_rows_skipped, pl.problems.size, pl.problems.size))
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                if (pl.source == ImportSource.GENERIC && pl.header.isNotEmpty()) {
                    item { Section(stringResource(R.string.hist_import_columns)) }
                    item { MappingEditor(pl.header, pl.mapping) { replan(it) } }
                    item {
                        ListItem(
                            modifier = Modifier.clickable { dayFirst = !dayFirst; replan(pl.mapping) },
                            headlineContent = { Text(stringResource(R.string.hist_import_day_first)) },
                            supportingContent = { Text(if (dayFirst) stringResource(R.string.hist_import_day_first_on) else stringResource(R.string.hist_import_day_first_off)) },
                            trailingContent = { Switch(dayFirst, { dayFirst = it; replan(pl.mapping) }) },
                        )
                    }
                }
                if (pl.toInsert.isNotEmpty()) {
                    item { Section(stringResource(R.string.hist_import_first_calls)) }
                    pl.toInsert.take(5).forEach { c ->
                        item {
                            ListItem(
                                headlineContent = { Text(c.name ?: Format.number(c.number, vm.countryIso).ifBlank { stringResource(R.string.hist_private_number) }) },
                                supportingContent = { Text(listOf(stringResource(typeName(c.type)), Format.fullDate(context, c.date), Format.duration(c.durationSec)).filter { it.isNotBlank() }.joinToString(" · ")) },
                            )
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                            Button(
                                onClick = {
                                    busy = context.getString(R.string.hist_importing)
                                    scope.launch {
                                        val n = vm.c.history.runImport(pl)
                                        busy = null
                                        val res = context.resources
                                        report = if (n == 0) context.getString(R.string.hist_import_nothing)
                                        else buildList {
                                            add(res.getQuantityString(R.plurals.hist_import_done, n, n))
                                            if (pl.duplicates > 0) add(res.getQuantityString(R.plurals.hist_import_done_dupes, pl.duplicates, pl.duplicates))
                                            if (pl.problems.isNotEmpty()) add(res.getQuantityString(R.plurals.hist_import_done_problems, pl.problems.size, pl.problems.size))
                                        }.joinToString("\n")
                                        plan = null
                                    }
                                },
                                enabled = busy == null && isDefault,
                            ) { Text(pluralStringResource(R.plurals.hist_import_button, pl.toInsert.size, pl.toInsert.size)) }
                        }
                    }
                }
                if (pl.problems.isNotEmpty()) {
                    item { Section(stringResource(R.string.hist_import_problems)) }
                    pl.problems.take(50).forEach { pr ->
                        item { Text(stringResource(R.string.hist_import_line, pr.line, HistoryText.problem(context, pr)), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (pl.problems.size > 50) item { Text(stringResource(R.string.hist_import_more, pl.problems.size - 50), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    report?.let { r ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text(stringResource(R.string.hist_import_finished)) },
            text = { Text(r) },
            confirmButton = { TextButton({ report = null; back() }) { Text(stringResource(R.string.dc_ok)) } },
        )
    }
}

private fun typeName(t: Int) = when (t) {
    ProviderColumns.INCOMING -> R.string.hist_type_incoming
    ProviderColumns.OUTGOING -> R.string.hist_type_outgoing
    ProviderColumns.MISSED -> R.string.hist_type_missed
    ProviderColumns.VOICEMAIL -> R.string.hist_type_voicemail
    ProviderColumns.REJECTED -> R.string.hist_type_rejected
    ProviderColumns.BLOCKED -> R.string.hist_type_blocked
    ProviderColumns.ANSWERED_EXTERNALLY -> R.string.hist_type_answered_elsewhere
    else -> R.string.hist_call
}

/** Lets the user say which column holds what, for CSVs we don't recognise. */
@Composable
private fun MappingEditor(header: List<String>, mapping: ColumnMapping, onChange: (ColumnMapping) -> Unit) {
    val fields: List<Triple<String, Int?, (Int?) -> ColumnMapping>> = listOf(
        Triple(stringResource(R.string.hist_col_number), mapping.number) { i -> mapping.copy(number = i) },
        Triple(stringResource(R.string.hist_col_type), mapping.type) { i -> mapping.copy(type = i) },
        Triple(stringResource(R.string.hist_col_date), mapping.date) { i -> mapping.copy(date = i) },
        Triple(stringResource(R.string.hist_col_time), mapping.time) { i -> mapping.copy(time = i) },
        Triple(stringResource(R.string.hist_col_timestamp), mapping.timestamp) { i -> mapping.copy(timestamp = i) },
        Triple(stringResource(R.string.hist_col_duration), mapping.duration) { i -> mapping.copy(duration = i) },
        Triple(stringResource(R.string.hist_col_name), mapping.name) { i -> mapping.copy(name = i) },
        Triple(stringResource(R.string.hist_col_sim), mapping.sim) { i -> mapping.copy(sim = i) },
    )
    Column {
        fields.forEach { (label, current, set) ->
            var open by remember { mutableStateOf(false) }
            ListItem(
                modifier = Modifier.clickable { open = true },
                headlineContent = { Text(label) },
                supportingContent = { Text(current?.let { header.getOrNull(it) } ?: stringResource(R.string.hist_col_missing)) },
                trailingContent = {
                    Row {
                        DropdownMenu(open, { open = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.hist_col_missing)) }, onClick = { open = false; onChange(set(null)) })
                            header.forEachIndexed { i, h -> DropdownMenuItem({ Text(h.ifBlank { stringResource(R.string.hist_col_n, i + 1) }) }, onClick = { open = false; onChange(set(i)) }) }
                        }
                    }
                },
            )
        }
    }
}
