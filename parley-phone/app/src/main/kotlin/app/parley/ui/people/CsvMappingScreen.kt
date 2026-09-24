package app.parley.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.TableChart
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.common.vcard.ColumnTarget
import app.parley.common.vcard.CsvColumnMapping
import app.parley.common.vcard.CsvField
import app.parley.common.vcard.ImportReport
import app.parley.data.VCardIO
import app.parley.messaging.MessagingInbox
import app.parley.ui.EmptyState
import app.parley.ui.contact.Section
import app.parley.ui.settings.ImportReportDialog
import kotlinx.coroutines.launch

/**
 * M12: a contact CSV that isn't Parley's own format (Google, Outlook, "Name,Phone", semicolons, tabs, one column).
 * Each column gets a guessed meaning the user can change, with the first contacts previewed as they would be saved;
 * then the normal import runs and its report is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvMappingScreen(vm: AppViewModel, back: () -> Unit) {
    val request = remember { MessagingInbox.csvImport }
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<VCardIO.CsvPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasHeader by remember { mutableStateOf(true) }
    var mapping by remember { mutableStateOf<List<ColumnTarget>>(emptyList()) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var report by remember { mutableStateOf<ImportReport?>(null) }

    fun remap(p: VCardIO.CsvPreview, header: Boolean) {
        val rows = p.rows
        mapping = CsvColumnMapping.guess(if (header) rows.firstOrNull() else null, if (header) rows.drop(1) else rows)
    }

    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        try {
            val p = vm.c.vcards.csvPreview(r.uri)
            if (p == null || p.rows.isEmpty()) {
                error = "This file has no lines Parley can read as a table."
            } else {
                preview = p
                hasHeader = CsvColumnMapping.hasHeader(p.rows.first())
                remap(p, hasHeader)
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't read the file"
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Choose columns") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
    }) { pad ->
        val p = preview
        if (request == null || error != null) {
            EmptyState(Icons.Rounded.TableChart, "Nothing to import", error ?: "Choose a file in Settings › Contacts › Import.", Modifier.padding(pad))
            return@Scaffold
        }
        if (p == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { LinearProgressIndicator() }
            return@Scaffold
        }
        val header = if (hasHeader) p.rows.first() else emptyList()
        val data = if (hasHeader) p.rows.drop(1) else p.rows
        val width = maxOf(mapping.size, p.rows.maxOf { it.size })
        val usable = mapping.any { it.field in setOf(CsvField.FULL_NAME, CsvField.GIVEN, CsvField.FAMILY, CsvField.PHONE, CsvField.EMAIL, CsvField.ORG) }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                val layout = if (hasHeader) CsvColumnMapping.layout(header) else CsvColumnMapping.Layout.OTHER
                val sep = when (p.delimiter) { ';' -> "semicolons"; '\t' -> "tabs"; else -> "commas" }
                Text(
                    "This file isn't in Parley's own format" + (if (layout != CsvColumnMapping.Layout.OTHER) ", it looks like a ${layout.label} export" else "") +
                        ". Its columns are separated by $sep. Check what each column holds; the first contacts below show how they'll be saved.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("First line is a header") },
                    supportingContent = { Text(if (hasHeader) "Column names, not a contact" else "Imported as a contact") },
                    trailingContent = { Switch(hasHeader, { hasHeader = it; remap(p, it) }) },
                    modifier = Modifier.clickable { hasHeader = !hasHeader; remap(p, hasHeader) },
                )
            }
            item { Section("Columns") }
            itemsIndexed((0 until width).toList()) { _, i ->
                val name = header.getOrNull(i)?.trim()?.ifEmpty { null } ?: "Column ${i + 1}"
                val samples = data.mapNotNull { it.getOrNull(i)?.trim()?.takeIf { v -> v.isNotEmpty() } }.take(2).joinToString(" · ")
                ColumnRow(name, samples, mapping.getOrNull(i) ?: ColumnTarget.IGNORED) { t ->
                    mapping = List(width) { k -> if (k == i) t else mapping.getOrNull(k) ?: ColumnTarget.IGNORED }
                }
            }
            item { Section("Preview") }
            val sample = data.take(5).mapNotNull { CsvColumnMapping.toRecord(it, mapping) }
            if (sample.isEmpty()) {
                item { Text("Nothing would be imported with these columns.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            sample.forEach { r ->
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(CsvColumnMapping.describe(r), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
                    Text(
                        "Into ${vm.accountLabel(request.account)}" + if (request.skipDuplicates) " · contacts you already have are skipped" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            progress = 0f
                            scope.launch {
                                report = try {
                                    vm.c.vcards.importMapped(
                                        request.uri, request.account, p.delimiter, mapping, hasHeader,
                                        progress = { done, total -> progress = if (total > 0) done.toFloat() / total else 0f },
                                        skipDuplicates = request.skipDuplicates,
                                    )
                                } catch (e: Exception) {
                                    vm.toast("Import failed: ${e.message}")
                                    null
                                }
                                progress = null
                            }
                        },
                        enabled = usable && progress == null,
                        modifier = Modifier.padding(top = 8.dp).align(Alignment.End),
                    ) { Text("Import") }
                    if (!usable) Text("Choose at least a name, phone, e-mail or company column.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    report?.let { r ->
        ImportReportDialog(r) {
            report = null
            MessagingInbox.csvImport = null
            back()
        }
    }
}

@Composable
private fun ColumnRow(name: String, samples: String, target: ColumnTarget, onPick: (ColumnTarget) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    target.label,
                    color = if (target.field == CsvField.IGNORE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (samples.isNotEmpty()) Text(samples, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            Box {
                Icon(Icons.Rounded.ArrowDropDown, "Change what “$name” holds")
                DropdownMenu(open, { open = false }) {
                    CsvColumnMapping.OPTIONS.forEach { o ->
                        DropdownMenuItem({ Text(o.label) }, onClick = { open = false; onPick(o) })
                    }
                }
            }
        },
        modifier = Modifier.clickable { open = true },
    )
}
