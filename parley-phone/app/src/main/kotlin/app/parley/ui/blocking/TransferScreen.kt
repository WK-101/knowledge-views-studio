package app.parley.ui.blocking

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.BlockRule
import app.parley.common.RuleKind
import app.parley.common.RuleTools
import app.parley.common.blocking.ColumnMapping
import app.parley.common.blocking.Csv
import app.parley.common.blocking.ImportPreset
import app.parley.common.blocking.ImportedRule
import app.parley.common.blocking.ListImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What was read from a file, before the user confirms. */
private class ImportDraft(val source: String, val rows: List<List<String>>?, val preset: ImportPreset, var mapping: ColumnMapping?, val fixed: List<ImportedRule>?)

/**
 * B7/B26: import Call Blocker (JSON or encrypted .cbbk), YACB, NoPhoneSpam or any CSV with column mapping,
 * and share your own rules as a signed `.parleylist`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    var preset by remember { mutableStateOf(ImportPreset.GENERIC) }
    var draft by remember { mutableStateOf<ImportDraft?>(null) }
    var cbbk by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var shareName by remember { mutableStateOf("My blocked numbers") }

    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                require(total < 20 * 1024 * 1024) { "The file is too large" }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("Couldn't open the file")
    }

    val pickList = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = read(uri)
                val text = bytes.decodeToString()
                draft = when (preset) {
                    ImportPreset.NO_PHONE_SPAM -> ImportDraft("NoPhoneSpam", null, preset, null, ListImport.plainLines(text))
                    else -> {
                        val rows = Csv.parse(text)
                        ImportDraft(preset.title, rows, preset, ListImport.guessMapping(rows, preset), null)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't read the file"
            }
        }
    }
    val pickCallBlocker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = read(uri)
                if (bytes.size >= 4 && bytes.decodeToString(0, 4) == "CBBK") cbbk = bytes
                else draft = ImportDraft("Call Blocker", null, preset, null, ListImport.callBlockerJson(bytes.decodeToString()))
            } catch (e: Exception) {
                error = e.message ?: "Couldn't read the file"
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Import & share") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        Column(Modifier.padding(p).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import a block list", style = MaterialTheme.typography.titleMedium)
            Text("Your own lists only. Parley never imports crowd databases whose terms forbid it.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ImportPreset.entries.forEach { pr -> FilterChip(preset == pr, { preset = pr }, label = { Text(pr.title) }) }
            }
            Text(preset.help, style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ pickList.launch(arrayOf("text/*", "application/*")) }) { Text("Choose file…") }
            ListItem(
                modifier = Modifier.clickable { pickCallBlocker.launch(arrayOf("*/*")) },
                headlineContent = { Text("Import from Call Blocker") },
                supportingContent = { Text("Its JSON export, or an encrypted .cbbk backup with your password") },
            )

            Text("Share your rules", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text(
                "Makes a signed .parleylist from your block rules (numbers and prefixes) that family can add under Spam lists, or put in a synced folder. Your key: ${remember { vm.c.lists.shareFingerprint() }}. Allowed numbers and contacts are never included.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(shareName, { shareName = it }, label = { Text("List name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton({
                scope.launch {
                    val ex = vm.c.lists.exportRules(rules, shareName.ifBlank { "Shared list" }, vm.countryIso)
                    if (ex.numbers + ex.ranges == 0) {
                        vm.toast("No number or prefix rules to share")
                        return@launch
                    }
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "share").apply { mkdirs() }
                        dir.listFiles()?.filter { it.name.endsWith(".parleylist") }?.forEach { it.delete() }
                        File(dir, shareName.filter { it.isLetterOrDigit() || it == ' ' }.trim().ifBlank { "list" }.replace(' ', '-') + ".parleylist").apply { writeBytes(ex.bytes) }
                    }
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Share ${ex.numbers} numbers, ${ex.ranges} ranges"))
                    if (ex.skipped > 0) vm.toast("${ex.skipped} rules can't travel in a list (patterns, names, schedules) and were left out")
                }
            }) { Text("Share as a list…") }
        }
    }

    draft?.let { d -> ImportPreviewDialog(vm, d, onDone = { draft = null }) }
    cbbk?.let { bytes ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { cbbk = null },
            title = { Text("Call Blocker backup password") },
            text = { OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = {
                TextButton({
                    scope.launch {
                        try {
                            val json = withContext(Dispatchers.Default) { ListImport.decryptCbbk(bytes, pw.toCharArray()) }
                            draft = ImportDraft("Call Blocker", null, preset, null, ListImport.callBlockerJson(json))
                            cbbk = null
                        } catch (e: IllegalArgumentException) {
                            error = e.message
                        }
                    }
                }, enabled = pw.isNotEmpty()) { Text("Open") }
            },
            dismissButton = { TextButton({ cbbk = null }) { Text("Cancel") } },
        )
    }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't import") }, text = { Text(e) }, confirmButton = { TextButton({ error = null }) { Text("OK") } }) }
}

@Composable
private fun ImportPreviewDialog(vm: AppViewModel, d: ImportDraft, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mapping by remember { mutableStateOf(d.mapping) }
    val header = d.rows?.firstOrNull().orEmpty()
    val parsed = remember(mapping) { d.fixed ?: d.rows?.let { rows -> mapping?.let { ListImport.rows(rows, it) } }.orEmpty() }
    val checked = remember(parsed) {
        parsed.mapNotNull { r ->
            val c = RuleTools.check(r.pattern, r.type, vm.countryIso)
            if (c.error != null) null else BlockRule(pattern = c.pattern, type = r.type, kind = r.kind, note = r.note ?: "Imported from ${d.source}")
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Import from ${d.source}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val m = mapping
                if (d.rows != null && m != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { mapping = m.copy(hasHeader = !m.hasHeader) }) {
                        Checkbox(m.hasHeader, { mapping = m.copy(hasHeader = it) })
                        Text("First row is a header")
                    }
                    ColumnPicker("Numbers", header, m.number, allowNone = false) { mapping = m.copy(number = it) }
                    ColumnPicker("Note", header, m.note, allowNone = true) { mapping = m.copy(note = it) }
                    ColumnPicker("Allow/block", header, m.kind, allowNone = true) { mapping = m.copy(kind = it) }
                }
                val allows = checked.count { it.kind == RuleKind.ALLOW }
                Text("${checked.size} rules found" + (if (allows > 0) " ($allows allowed numbers)" else "") + if (parsed.size > checked.size) " · ${parsed.size - checked.size} skipped" else "")
                checked.take(5).forEach { Text("• ${it.pattern} (${it.type.name.lowercase()})", style = MaterialTheme.typography.bodySmall) }
                Text("Rules you already have are not duplicated.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    val n = vm.c.blocks.addRules(checked)
                    vm.toast("Imported $n rules")
                }
                onDone()
            }, enabled = checked.isNotEmpty()) { Text("Import") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}

@Composable
private fun ColumnPicker(label: String, header: List<String>, selected: Int, allowNone: Boolean, onPick: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (allowNone) FilterChip(selected < 0, { onPick(-1) }, label = { Text("None") })
        header.forEachIndexed { i, h -> FilterChip(selected == i, { onPick(i) }, label = { Text(h.take(16).ifBlank { "Column ${i + 1}" }) }) }
    }
}
