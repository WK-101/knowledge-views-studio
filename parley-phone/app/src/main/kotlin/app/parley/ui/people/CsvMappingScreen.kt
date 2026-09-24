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
import android.content.res.Resources
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.parley.R

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
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
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
                error = res.getString(R.string.csv_no_table)
            } else {
                preview = p
                hasHeader = CsvColumnMapping.hasHeader(p.rows.first())
                remap(p, hasHeader)
            }
        } catch (e: Exception) {
            error = e.message ?: res.getString(R.string.csv_read_failed)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.csv_title)) },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } },
        )
    }) { pad ->
        val p = preview
        if (request == null || error != null) {
            EmptyState(Icons.Rounded.TableChart, stringResource(R.string.csv_nothing_title), error ?: stringResource(R.string.csv_nothing_text), Modifier.padding(pad))
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
                val sep = stringResource(when (p.delimiter) { ';' -> R.string.csv_sep_semicolons; '\t' -> R.string.csv_sep_tabs; else -> R.string.csv_sep_commas })
                val layoutName = when (layout) {
                    CsvColumnMapping.Layout.PARLEY -> stringResource(R.string.csv_layout_parley)
                    CsvColumnMapping.Layout.GOOGLE -> stringResource(R.string.csv_layout_google)
                    CsvColumnMapping.Layout.OUTLOOK -> stringResource(R.string.csv_layout_outlook)
                    CsvColumnMapping.Layout.OTHER -> null
                }
                Text(
                    if (layoutName != null) stringResource(R.string.csv_intro_layout, layoutName, sep) else stringResource(R.string.csv_intro, sep),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.csv_header)) },
                    supportingContent = { Text(if (hasHeader) stringResource(R.string.csv_header_on) else stringResource(R.string.csv_header_off)) },
                    trailingContent = { Switch(hasHeader, { hasHeader = it; remap(p, it) }) },
                    modifier = Modifier.clickable { hasHeader = !hasHeader; remap(p, hasHeader) },
                )
            }
            item { Section(stringResource(R.string.csv_columns)) }
            itemsIndexed((0 until width).toList()) { _, i ->
                val name = header.getOrNull(i)?.trim()?.ifEmpty { null } ?: stringResource(R.string.csv_column_n, i + 1)
                val samples = data.mapNotNull { it.getOrNull(i)?.trim()?.takeIf { v -> v.isNotEmpty() } }.take(2).joinToString(" · ")
                ColumnRow(name, samples, mapping.getOrNull(i) ?: ColumnTarget.IGNORED) { t ->
                    mapping = List(width) { k -> if (k == i) t else mapping.getOrNull(k) ?: ColumnTarget.IGNORED }
                }
            }
            item { Section(stringResource(R.string.csv_preview)) }
            val sample = data.take(5).mapNotNull { CsvColumnMapping.toRecord(it, mapping) }
            if (sample.isEmpty()) {
                item { Text(stringResource(R.string.csv_nothing_with_columns), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            sample.forEach { r ->
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(CsvColumnMapping.describe(r, stringResource(R.string.csv_no_name)) { res.getString(R.string.csv_labels, it) }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
                    Text(
                        stringResource(if (request.skipDuplicates) R.string.csv_into_skip else R.string.csv_into, vm.accountLabel(request.account)),
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
                                    vm.toast(res.getString(R.string.csv_import_failed, e.message.toString()))
                                    null
                                }
                                progress = null
                            }
                        },
                        enabled = usable && progress == null,
                        modifier = Modifier.padding(top = 8.dp).align(Alignment.End),
                    ) { Text(stringResource(R.string.csv_import)) }
                    if (!usable) Text(stringResource(R.string.csv_need_column), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
    val res = androidx.compose.ui.platform.LocalResources.current
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    targetLabel(res, target),
                    color = if (target.field == CsvField.IGNORE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (samples.isNotEmpty()) Text(samples, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            Box {
                Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.csv_change_column, name))
                DropdownMenu(open, { open = false }) {
                    CsvColumnMapping.OPTIONS.forEach { o ->
                        DropdownMenuItem({ Text(targetLabel(res, o)) }, onClick = { open = false; onPick(o) })
                    }
                }
            }
        },
        modifier = Modifier.clickable { open = true },
    )
}

/** "Phone (mobile)", "First name"…: phone and e-mail types use Android's own (localised) type names. */
private fun targetLabel(res: Resources, t: ColumnTarget): String {
    val field = res.getString(
        when (t.field) {
            CsvField.IGNORE -> R.string.csv_field_ignore
            CsvField.FULL_NAME -> R.string.csv_field_full_name
            CsvField.PREFIX -> R.string.csv_field_prefix
            CsvField.GIVEN -> R.string.csv_field_given
            CsvField.MIDDLE -> R.string.csv_field_middle
            CsvField.FAMILY -> R.string.csv_field_family
            CsvField.SUFFIX -> R.string.csv_field_suffix
            CsvField.NICKNAME -> R.string.csv_field_nickname
            CsvField.PHONE -> R.string.csv_field_phone
            CsvField.PHONE_LABEL -> R.string.csv_field_phone_label
            CsvField.EMAIL -> R.string.csv_field_email
            CsvField.EMAIL_LABEL -> R.string.csv_field_email_label
            CsvField.ORG -> R.string.csv_field_org
            CsvField.TITLE -> R.string.csv_field_title
            CsvField.ADDRESS -> R.string.csv_field_address
            CsvField.WEBSITE -> R.string.csv_field_website
            CsvField.BIRTHDAY -> R.string.csv_field_birthday
            CsvField.NOTES -> R.string.csv_field_notes
            CsvField.LABELS -> R.string.csv_field_labels
        },
    )
    val type = t.type ?: return field
    return when (t.field) {
        CsvField.PHONE -> res.getString(R.string.csv_typed, field, Phone.getTypeLabel(res, type, null).toString())
        CsvField.EMAIL -> res.getString(R.string.csv_typed, field, Email.getTypeLabel(res, type, null).toString())
        else -> field
    }
}
