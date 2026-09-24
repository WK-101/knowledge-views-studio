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
        busy = "Reading the file…"
        error = null
        scope.launch {
            try {
                plan = vm.c.history.planImport(u, mapping, dayFirst)
            } catch (e: Exception) {
                plan = null
                error = e.message ?: "Couldn't read the file"
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
        TopAppBar(title = { Text("Import call history") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Text(
                    "Adds calls from a CSV file to your call history: Parley's own export, Logger's export or any spreadsheet with a number, " +
                        "a type and a date. You see what would happen before anything is written. Calls already in your history are skipped, " +
                        "and imported calls never show up as new missed calls.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!isDefault) item {
                Text("Make Parley your default phone app first: Android only lets the phone app write call history.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
            item {
                OutlinedButton({ picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream", "*/*")) }, Modifier.padding(16.dp)) {
                    Icon(Icons.Rounded.FileOpen, null)
                    Text(if (uri == null) "  Choose a CSV file" else "  Choose another file")
                }
            }
            busy?.let { b -> item { Column(Modifier.padding(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(b) } } }
            error?.let { e -> item { Text(e, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
            val pl = plan
            if (pl != null) {
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Dry run · ${pl.source.label}", style = MaterialTheme.typography.titleMedium)
                            Text("${pl.rowsRead} rows read")
                            Text(pl.summary(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (pl.source == ImportSource.GENERIC && pl.header.isNotEmpty()) {
                    item { Section("Columns") }
                    item { MappingEditor(pl.header, pl.mapping) { replan(it) } }
                    item {
                        ListItem(
                            modifier = Modifier.clickable { dayFirst = !dayFirst; replan(pl.mapping) },
                            headlineContent = { Text("Dates are day first") },
                            supportingContent = { Text(if (dayFirst) "03/04/2025 is 3 April" else "03/04/2025 is 4 March") },
                            trailingContent = { Switch(dayFirst, { dayFirst = it; replan(pl.mapping) }) },
                        )
                    }
                }
                if (pl.toInsert.isNotEmpty()) {
                    item { Section("First calls to import") }
                    pl.toInsert.take(5).forEach { c ->
                        item {
                            ListItem(
                                headlineContent = { Text(c.name ?: Format.number(c.number, vm.countryIso).ifBlank { "Private number" }) },
                                supportingContent = { Text(listOf(typeName(c.type), Format.fullDate(context, c.date), Format.duration(c.durationSec)).filter { it.isNotBlank() }.joinToString(" · ")) },
                            )
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                            Button(
                                onClick = {
                                    busy = "Importing…"
                                    scope.launch {
                                        val n = vm.c.history.runImport(pl)
                                        busy = null
                                        report = if (n == 0) "Nothing was imported. Android didn't accept the calls: check that Parley is your default phone app."
                                        else buildList {
                                            add("Imported $n calls")
                                            if (pl.duplicates > 0) add("${pl.duplicates} already in your history, skipped")
                                            if (pl.problems.isNotEmpty()) add("${pl.problems.size} unreadable rows skipped")
                                        }.joinToString("\n")
                                        plan = null
                                    }
                                },
                                enabled = busy == null && isDefault,
                            ) { Text("Import ${pl.toInsert.size} calls") }
                        }
                    }
                }
                if (pl.problems.isNotEmpty()) {
                    item { Section("Rows that can't be imported") }
                    pl.problems.take(50).forEach { pr ->
                        item { Text("Line ${pr.line}: ${pr.reason}", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (pl.problems.size > 50) item { Text("…and ${pl.problems.size - 50} more", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    report?.let { r ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Import finished") },
            text = { Text(r) },
            confirmButton = { TextButton({ report = null; back() }) { Text("OK") } },
        )
    }
}

private fun typeName(t: Int) = when (t) {
    ProviderColumns.INCOMING -> "Incoming"
    ProviderColumns.OUTGOING -> "Outgoing"
    ProviderColumns.MISSED -> "Missed"
    ProviderColumns.VOICEMAIL -> "Voicemail"
    ProviderColumns.REJECTED -> "Rejected"
    ProviderColumns.BLOCKED -> "Blocked"
    ProviderColumns.ANSWERED_EXTERNALLY -> "Answered elsewhere"
    else -> "Call"
}

/** Lets the user say which column holds what, for CSVs we don't recognise. */
@Composable
private fun MappingEditor(header: List<String>, mapping: ColumnMapping, onChange: (ColumnMapping) -> Unit) {
    val fields: List<Triple<String, Int?, (Int?) -> ColumnMapping>> = listOf(
        Triple("Number", mapping.number) { i -> mapping.copy(number = i) },
        Triple("Call type", mapping.type) { i -> mapping.copy(type = i) },
        Triple("Date (or date and time)", mapping.date) { i -> mapping.copy(date = i) },
        Triple("Time", mapping.time) { i -> mapping.copy(time = i) },
        Triple("Timestamp (Unix)", mapping.timestamp) { i -> mapping.copy(timestamp = i) },
        Triple("Duration", mapping.duration) { i -> mapping.copy(duration = i) },
        Triple("Name", mapping.name) { i -> mapping.copy(name = i) },
        Triple("SIM", mapping.sim) { i -> mapping.copy(sim = i) },
    )
    Column {
        fields.forEach { (label, current, set) ->
            var open by remember { mutableStateOf(false) }
            ListItem(
                modifier = Modifier.clickable { open = true },
                headlineContent = { Text(label) },
                supportingContent = { Text(current?.let { header.getOrNull(it) } ?: "Not in this file") },
                trailingContent = {
                    Row {
                        DropdownMenu(open, { open = false }) {
                            DropdownMenuItem({ Text("Not in this file") }, onClick = { open = false; onChange(set(null)) })
                            header.forEachIndexed { i, h -> DropdownMenuItem({ Text(h.ifBlank { "Column ${i + 1}" }) }, onClick = { open = false; onChange(set(i)) }) }
                        }
                    }
                },
            )
        }
    }
}
