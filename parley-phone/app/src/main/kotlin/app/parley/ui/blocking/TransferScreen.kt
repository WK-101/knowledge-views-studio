package app.parley.ui.blocking

import android.content.Context
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ExplainedFailure
import app.parley.jobs.UserErrorText
import app.parley.ui.ParleyListItem
import kotlinx.coroutines.CancellationException
import app.parley.common.BlockRule
import app.parley.common.RuleKind
import app.parley.common.RuleTools
import app.parley.common.blocking.ColumnMapping
import app.parley.common.blocking.Csv
import app.parley.common.blocking.ImportPreset
import app.parley.common.blocking.ImportedRule
import app.parley.common.blocking.ListImport
import app.parley.ui.settings.bidiLtrIfNumber
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.ConfirmDialog
import app.parley.ui.InfoDialog

/** [ImportPreset.title] and [ImportPreset.help] in the app's language. */
private fun presetTitleRes(p: ImportPreset) = when (p) {
    ImportPreset.GENERIC -> R.string.blk_preset_csv
    ImportPreset.YACB -> R.string.blk_preset_yacb
    ImportPreset.NO_PHONE_SPAM -> R.string.blk_preset_nps
}

@Composable
private fun ImportPreset.localTitle() = stringResource(presetTitleRes(this))

@Composable
private fun ImportPreset.localHelp() = stringResource(
    when (this) {
        ImportPreset.GENERIC -> R.string.blk_preset_csv_help
        ImportPreset.YACB -> R.string.blk_preset_yacb_help
        ImportPreset.NO_PHONE_SPAM -> R.string.blk_preset_nps_help
    },
)

/** Errors from [ListImport] and file reading in the app's language. */
private fun importError(context: Context, e: Throwable): String = when (e.message) {
    "This file isn't valid JSON" -> context.getString(R.string.blk_import_err_json)
    "Not a Call Blocker backup (.cbbk)" -> context.getString(R.string.blk_import_err_cbbk)
    "Wrong password, or the file is damaged" -> context.getString(R.string.blk_import_err_password)
    // Never the exception's own text: an explained failure's words, or the kind of problem.
    else -> context.getString(R.string.blk_fail_read_file_because, UserErrorText.of(context, e))
}

/** What was read from a file, before the user confirms. */
private class ImportDraft(val source: String, val rows: List<List<String>>?, val preset: ImportPreset, var mapping: ColumnMapping?, val fixed: List<ImportedRule>?)

/**
 * Import Call Blocker (JSON or encrypted .cbbk), YACB, NoPhoneSpam or any CSV with column mapping,
 * and share your own rules as a signed `.parleylist`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    var preset by remember { mutableStateOf(ImportPreset.GENERIC) }
    var draft by remember { mutableStateOf<ImportDraft?>(null) }
    var cbbk by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val defaultShareName = stringResource(R.string.blk_share_default_name)
    var shareName by rememberSaveable { mutableStateOf(defaultShareName) }

    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total >= 20 * 1024 * 1024) throw ExplainedFailure(res.getString(R.string.blk_tpl_err_file_large))
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw ExplainedFailure(res.getString(R.string.blk_tpl_err_open_file))
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
                        ImportDraft(res.getString(presetTitleRes(preset)), rows, preset, ListImport.guessMapping(rows, preset), null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = importError(context, e)
            }
        }
    }
    val pickCallBlocker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = read(uri)
                if (bytes.size >= 4 && bytes.decodeToString(0, 4) == "CBBK") cbbk = bytes
                else draft = ImportDraft("Call Blocker", null, preset, null, ListImport.callBlockerJson(bytes.decodeToString()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = importError(context, e)
            }
        }
    }

    ParleyScaffold(topBar = {
        ParleyTopBar(stringResource(R.string.blk_transfer_title), onBack = back)
    }) { p ->
        Column(Modifier.padding(p).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.blk_transfer_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.blk_transfer_import_help), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ImportPreset.entries.forEach { pr -> FilterChip(preset == pr, { preset = pr }, label = { Text(pr.localTitle()) }) }
            }
            Text(preset.localHelp(), style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ pickList.launch(arrayOf("text/*", "application/*")) }) { Text(stringResource(R.string.blk_choose_file)) }
            ParleyListItem(
                modifier = Modifier.clickable { pickCallBlocker.launch(arrayOf("*/*")) },
                headlineContent = { Text(stringResource(R.string.blk_transfer_call_blocker)) },
                supportingContent = { Text(stringResource(R.string.blk_transfer_call_blocker_help)) },
            )

            Text(stringResource(R.string.blk_transfer_share_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text(
                stringResource(R.string.blk_transfer_share_help, remember { vm.c.lists.shareFingerprint() }),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                shareName, { shareName = it }, label = { Text(stringResource(R.string.blk_list_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton({
                scope.launch {
                    val ex = vm.c.lists.exportRules(rules, shareName.ifBlank { res.getString(R.string.blk_shared_list) }, vm.countryIso)
                    if (ex.numbers + ex.ranges == 0) {
                        vm.toast(res.getString(R.string.blk_nothing_to_share))
                        return@launch
                    }
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "share").apply { mkdirs() }
                        dir.listFiles()?.filter { it.name.endsWith(".parleylist") }?.forEach { it.delete() }
                        File(
                            dir, shareName.filter { it.isLetterOrDigit() || it == ' ' }.trim().ifBlank { "list" }.replace(' ', '-') + ".parleylist",
                        ).apply { writeBytes(ex.bytes) }
                    }
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    val send = Intent(Intent.ACTION_SEND).setType(
                        "application/octet-stream",
                    ).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val what = res.getString(
                        R.string.blk_joined,
                        res.getQuantityString(R.plurals.blk_numbers_count, ex.numbers, ex.numbers.toString()),
                        res.getQuantityString(R.plurals.blk_ranges_count, ex.ranges, ex.ranges),
                    )
                    context.startActivity(Intent.createChooser(send, res.getString(R.string.blk_share_chooser, what)))
                    if (ex.skipped > 0) vm.toast(res.getQuantityString(R.plurals.blk_share_skipped, ex.skipped, ex.skipped))
                }
            }) { Text(stringResource(R.string.blk_share_as_list)) }
        }
    }

    draft?.let { d -> ImportPreviewDialog(vm, d, onDone = { draft = null }) }
    cbbk?.let { bytes ->
        var pw by remember { mutableStateOf("") }
        ConfirmDialog(
            title = stringResource(R.string.blk_cbbk_password_title),
            text = null,
            confirmLabel = stringResource(R.string.blk_open),
            onConfirm = {
                scope.launch {
                    try {
                        val json = withContext(Dispatchers.Default) { ListImport.decryptCbbk(bytes, pw.toCharArray()) }
                        draft = ImportDraft("Call Blocker", null, preset, null, ListImport.callBlockerJson(json))
                        cbbk = null
                    } catch (e: IllegalArgumentException) {
                        error = importError(context, e)
                    }
                }
            },
            onDismiss = { cbbk = null },
            dismissLabel = stringResource(R.string.set_cancel),
            confirmEnabled = pw.isNotEmpty(),
            content = { OutlinedTextField(
                pw,
                { pw = it },
                label = { Text(stringResource(R.string.blk_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            ) },
        )
    }
    error?.let { e ->
        InfoDialog(
            title = stringResource(R.string.blk_cant_import),
            text = e,
            onDismiss = { error = null },
            closeLabel = stringResource(R.string.set_ok),
        )
    }
}

@Composable
private fun ImportPreviewDialog(vm: AppViewModel, d: ImportDraft, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    var mapping by remember { mutableStateOf(d.mapping) }
    val header = d.rows?.firstOrNull().orEmpty()
    val parsed = remember(mapping) { d.fixed ?: d.rows?.let { rows -> mapping?.let { ListImport.rows(rows, it) } }.orEmpty() }
    val checked = remember(parsed) {
        parsed.mapNotNull { r ->
            val c = RuleTools.check(r.pattern, r.type, vm.countryIso)
            if (c.error != null) null else BlockRule(
                pattern = c.pattern, type = r.type, kind = r.kind, note = r.note ?: res.getString(R.string.blk_imported_from, d.source),
            )
        }
    }
    ConfirmDialog(
        title = stringResource(R.string.blk_import_from, d.source),
        text = null,
        confirmLabel = stringResource(R.string.blk_import),
        onConfirm = {
            scope.launch {
                val n = vm.c.blocks.addRules(checked)
                vm.toast(res.getQuantityString(R.plurals.blk_imported_rules, n, n))
            }
            onDone()
        },
        onDismiss = onDone,
        dismissLabel = stringResource(R.string.set_cancel),
        confirmEnabled = checked.isNotEmpty(),
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val m = mapping
                if (d.rows != null && m != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { mapping = m.copy(hasHeader = !m.hasHeader) }) {
                        Checkbox(m.hasHeader, { mapping = m.copy(hasHeader = it) })
                        Text(stringResource(R.string.blk_first_row_header))
                    }
                    ColumnPicker(stringResource(R.string.blk_col_numbers), header, m.number, allowNone = false) { mapping = m.copy(number = it) }
                    ColumnPicker(stringResource(R.string.blk_col_note), header, m.note, allowNone = true) { mapping = m.copy(note = it) }
                    ColumnPicker(stringResource(R.string.blk_col_kind), header, m.kind, allowNone = true) { mapping = m.copy(kind = it) }
                }
                val allows = checked.count { it.kind == RuleKind.ALLOW }
                Text(
                    pluralStringResource(R.plurals.blk_rules_found, checked.size, checked.size) +
                        (if (allows > 0) " " + pluralStringResource(R.plurals.blk_allowed_numbers_paren, allows, allows) else "") +
                        if (parsed.size > checked.size) " · " + (parsed.size - checked.size).let { n ->
                            pluralStringResource(R.plurals.blk_skipped_n, n, n)
                        } else "",
                )
                checked.take(5).forEach {
                    Text("• ${bidiLtrIfNumber(it.pattern)} (${typeLabel(it.type)})", style = MaterialTheme.typography.bodySmall)
                } // l10n-ok: no words
                Text(stringResource(R.string.blk_not_duplicated), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
private fun ColumnPicker(label: String, header: List<String>, selected: Int, allowNone: Boolean, onPick: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (allowNone) FilterChip(selected < 0, { onPick(-1) }, label = { Text(stringResource(R.string.blk_notify_none)) })
        header.forEachIndexed { i, h ->
            FilterChip(selected == i, { onPick(i) }, label = { Text(h.take(16).ifBlank { stringResource(R.string.blk_column_n, i + 1) }) })
        }
    }
}
