package app.parley.ui.timemachine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.common.backup.ContactVersion
import app.parley.common.backup.SnapshotDiff
import app.parley.common.backup.Snapshots
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.contact.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.res.Resources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

/** Human-readable one-liner for a stored data row. */
fun describe(res: Resources, row: DataRow): String? {
    val v = row["data1"]?.takeIf { it.isNotBlank() }
    return when (row.mimeType) {
        Mime.NAME -> v?.let { res.getString(R.string.tm_row_name, it) }
        Mime.PHONE -> v?.let { res.getString(R.string.tm_row_phone, app.parley.ui.DataL10n.ltr(it)) }
        Mime.EMAIL -> v?.let { res.getString(R.string.tm_row_email, it) }
        Mime.POSTAL -> v?.let { res.getString(R.string.tm_row_address, it) }
        Mime.ORG -> listOfNotNull(v, row["data4"]).joinToString(", ").takeIf { it.isNotBlank() }?.let { res.getString(R.string.tm_row_work, it) }
        Mime.NOTE -> v?.let { res.getString(R.string.tm_row_note, it.take(60)) }
        Mime.EVENT -> v?.let { res.getString(R.string.tm_row_date, it) }
        Mime.WEBSITE -> v?.let { res.getString(R.string.tm_row_website, it) }
        Mime.NICKNAME -> v?.let { res.getString(R.string.tm_row_nickname, it) }
        Mime.RELATION -> v?.let { res.getString(R.string.tm_row_relation, it) }
        Mime.PHOTO -> res.getString(R.string.tm_row_photo)
        Mime.GROUP -> null
        else -> null
    }
}

private fun lines(res: Resources, r: ContactRecord) = r.raws.flatMap { it.rows }.mapNotNull { describe(res, it) }.distinct()

private fun fieldLabel(res: Resources, field: String): String = when (field) {
    "displayName" -> res.getString(R.string.tm_field_name)
    "starred" -> res.getString(R.string.tm_field_starred)
    "customRingtone" -> res.getString(R.string.tm_field_ringtone)
    "sendToVoicemail" -> res.getString(R.string.tm_field_voicemail)
    "accounts" -> res.getString(R.string.tm_field_accounts)
    else -> field
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionHistoryScreen(vm: AppViewModel, contactId: Long, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val versions by produceState<List<ContactVersion>?>(null, contactId) {
        value = withContext(Dispatchers.IO) {
            val key = vm.c.records.read(contactId, fullPhoto = false)?.key ?: return@withContext emptyList()
            vm.c.timeMachine.snapshotIfDue(minIntervalMs = 0) // make "now" the newest version
            vm.c.timeMachine.history(key).reversed()
        }
    }
    var chosen by remember { mutableStateOf<ContactVersion?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.tm_history_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } })
    }) { p ->
        val list = versions
        when {
            list == null -> CircularProgressIndicator(Modifier.padding(p).padding(32.dp))
            list.size <= 1 -> EmptyState(
                Icons.Rounded.History, stringResource(R.string.tm_no_versions),
                app.parley.data.backup.TimeMachine.KEEP_DAYS.toInt().let { pluralStringResource(R.plurals.tm_no_versions_text, it, it) }, Modifier.padding(p),
                action = stringResource(R.string.ux_empty_back), onAction = back,
            )
            else -> LazyColumn(Modifier.padding(p)) {
                list.forEachIndexed { i, v ->
                    item {
                        val newer = list.getOrNull(i - 1)?.record
                        val rec = v.record
                        val change = if (rec != null && newer != null) Snapshots.diffRecords(rec, newer) else null
                        ListItem(
                            modifier = Modifier.clickable(enabled = rec != null && i > 0) { chosen = v },
                            headlineContent = { Text(if (i == 0) stringResource(R.string.tm_now) else Format.fullDate(context, v.timestamp)) },
                            supportingContent = {
                                Text(
                                    when {
                                        rec == null -> stringResource(R.string.tm_didnt_exist)
                                        change == null -> lines(res, rec).take(2).joinToString(" · ")
                                        else -> change.removedRows.mapNotNull { describe(res, it) }.firstOrNull()
                                            ?.let { stringResource(R.string.tm_later_removed, change.addedRows.size, change.removedRows.size, it) }
                                            ?: stringResource(R.string.tm_later, change.addedRows.size, change.removedRows.size)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    chosen?.record?.let { rec ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(rec.displayName) },
            text = { Column { lines(res, rec).forEach { Text(it) } } },
            confirmButton = {
                TextButton({
                    chosen = null
                    scope.launch {
                        vm.c.contacts.delete(listOf(contactId)) // journaled
                        val id = withContext(Dispatchers.IO) { vm.c.records.insert(rec, target = null) }
                        vm.toast(res.getString(R.string.tm_restored_version))
                        back()
                        id?.let { open(Routes.contact(it)) }
                    }
                }) { Text(stringResource(R.string.tm_restore_version)) }
            },
            dismissButton = {
                TextButton({
                    chosen = null
                    scope.launch {
                        val id = withContext(Dispatchers.IO) { vm.c.records.insert(rec, target = null) }
                        vm.toast(res.getString(R.string.tm_saved_copy))
                        id?.let { open(Routes.contact(it)) }
                    }
                }) { Text(stringResource(R.string.tm_save_copy)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var days by remember { mutableLongStateOf(7L) }
    var round by remember { mutableLongStateOf(0L) }
    val diff by produceState<SnapshotDiff?>(null, days, round) {
        value = null
        value = vm.c.timeMachine.changesSince(System.currentTimeMillis() - days * 86_400_000L)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.tm_changes_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1L to R.string.tm_since_yesterday, 7L to R.string.tm_last_week, 30L to R.string.tm_last_month, 180L to R.string.tm_6_months).forEach { (d, label) ->
                        FilterChip(days == d, { days = d }, label = { Text(stringResource(label)) })
                    }
                }
            }
            val d = diff
            if (d == null) {
                item { CircularProgressIndicator(Modifier.padding(32.dp)) }
                return@LazyColumn
            }
            if (d.isEmpty) item { EmptyState(Icons.Rounded.History, stringResource(R.string.tm_no_changes), stringResource(R.string.tm_no_changes_text)) }
            if (d.removed.isNotEmpty()) {
                item { Section(stringResource(R.string.tm_removed, d.removed.size)) }
                d.removed.forEach { r ->
                    item {
                        ListItem(
                            leadingContent = { Avatar(r.displayName, null) },
                            headlineContent = { Text(r.displayName) },
                            supportingContent = { Text(lines(res, r).take(2).joinToString(" · ")) },
                            trailingContent = {
                                TextButton({ scope.launch { withContext(Dispatchers.IO) { vm.c.records.insert(r, target = null) }; vm.toast(res.getString(R.string.tm_restored_name, r.displayName)); round++ } }) { Text(stringResource(R.string.dc_restore)) }
                            },
                        )
                    }
                }
            }
            if (d.changed.isNotEmpty()) {
                item { Section(stringResource(R.string.tm_changed, d.changed.size)) }
                d.changed.forEach { ch ->
                    item {
                        ListItem(
                            modifier = Modifier.clickable {
                                scope.launch {
                                    val id = withContext(Dispatchers.IO) { vm.c.contacts.contacts.value.orEmpty().firstOrNull { it.lookupKey == ch.after.key }?.id }
                                    id?.let { open(Routes.contact(it)) }
                                }
                            },
                            headlineContent = { Text(ch.after.displayName) },
                            supportingContent = {
                                Text(
                                    (ch.addedRows.mapNotNull { describe(res, it) }.map { "+ $it" } + ch.removedRows.mapNotNull { describe(res, it) }.map { "− $it" }).take(4).joinToString("\n")
                                        .ifEmpty { ch.fields.joinToString { res.getString(R.string.tm_field_changed, fieldLabel(res, it)) } },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
            if (d.added.isNotEmpty()) {
                item { Section(stringResource(R.string.tm_added, d.added.size)) }
                d.added.forEach { r -> item { ListItem(headlineContent = { Text(r.displayName) }, supportingContent = { Text(lines(res, r).take(1).joinToString()) }) } }
            }
        }
    }
}
