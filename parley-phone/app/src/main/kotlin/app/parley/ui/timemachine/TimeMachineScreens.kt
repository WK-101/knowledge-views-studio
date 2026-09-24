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

/** Human-readable one-liner for a stored data row. */
fun describe(row: DataRow): String? {
    val v = row["data1"]?.takeIf { it.isNotBlank() }
    return when (row.mimeType) {
        Mime.NAME -> v?.let { "Name: $it" }
        Mime.PHONE -> v?.let { "Phone: $it" }
        Mime.EMAIL -> v?.let { "E-mail: $it" }
        Mime.POSTAL -> v?.let { "Address: $it" }
        Mime.ORG -> listOfNotNull(v, row["data4"]).joinToString(", ").takeIf { it.isNotBlank() }?.let { "Work: $it" }
        Mime.NOTE -> v?.let { "Note: " + it.take(60) }
        Mime.EVENT -> v?.let { "Date: $it" }
        Mime.WEBSITE -> v?.let { "Website: $it" }
        Mime.NICKNAME -> v?.let { "Nickname: $it" }
        Mime.RELATION -> v?.let { "Relation: $it" }
        Mime.PHOTO -> "Photo"
        Mime.GROUP -> null
        else -> null
    }
}

private fun lines(r: ContactRecord) = r.raws.flatMap { it.rows }.mapNotNull(::describe).distinct()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionHistoryScreen(vm: AppViewModel, contactId: Long, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
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
        TopAppBar(title = { Text("Version history") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        val list = versions
        when {
            list == null -> CircularProgressIndicator(Modifier.padding(p).padding(32.dp))
            list.size <= 1 -> EmptyState(Icons.Rounded.History, "No earlier versions yet", "Parley keeps a daily snapshot of your contacts for ${app.parley.data.backup.TimeMachine.KEEP_DAYS} days. Earlier versions of this contact will show up here.", Modifier.padding(p))
            else -> LazyColumn(Modifier.padding(p)) {
                list.forEachIndexed { i, v ->
                    item {
                        val newer = list.getOrNull(i - 1)?.record
                        val rec = v.record
                        val change = if (rec != null && newer != null) Snapshots.diffRecords(rec, newer) else null
                        ListItem(
                            modifier = Modifier.clickable(enabled = rec != null && i > 0) { chosen = v },
                            headlineContent = { Text(if (i == 0) "Now" else Format.fullDate(context, v.timestamp)) },
                            supportingContent = {
                                Text(
                                    when {
                                        rec == null -> "Didn't exist"
                                        change == null -> lines(rec).take(2).joinToString(" · ")
                                        else -> "Later: +${change.addedRows.size} / −${change.removedRows.size} details" + (change.removedRows.mapNotNull(::describe).firstOrNull()?.let { " (removed $it)" } ?: "")
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
            text = { Column { lines(rec).forEach { Text(it) } } },
            confirmButton = {
                TextButton({
                    chosen = null
                    scope.launch {
                        vm.c.contacts.delete(listOf(contactId)) // journaled
                        val id = withContext(Dispatchers.IO) { vm.c.records.insert(rec, target = null) }
                        vm.toast("Restored this version")
                        back()
                        id?.let { open(Routes.contact(it)) }
                    }
                }) { Text("Restore this version") }
            },
            dismissButton = {
                TextButton({
                    chosen = null
                    scope.launch {
                        val id = withContext(Dispatchers.IO) { vm.c.records.insert(rec, target = null) }
                        vm.toast("Saved as a separate contact")
                        id?.let { open(Routes.contact(it)) }
                    }
                }) { Text("Save as copy") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var days by remember { mutableLongStateOf(7L) }
    var round by remember { mutableLongStateOf(0L) }
    val diff by produceState<SnapshotDiff?>(null, days, round) {
        value = null
        value = vm.c.timeMachine.changesSince(System.currentTimeMillis() - days * 86_400_000L)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("What changed") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1L to "Since yesterday", 7L to "Last week", 30L to "Last month", 180L to "6 months").forEach { (d, label) ->
                        FilterChip(days == d, { days = d }, label = { Text(label) })
                    }
                }
            }
            val d = diff
            if (d == null) {
                item { CircularProgressIndicator(Modifier.padding(32.dp)) }
                return@LazyColumn
            }
            if (d.isEmpty) item { EmptyState(Icons.Rounded.History, "No changes", "Nothing was added, removed or edited in this period (or no snapshot is old enough yet).") }
            if (d.removed.isNotEmpty()) {
                item { Section("Removed (${d.removed.size})") }
                d.removed.forEach { r ->
                    item {
                        ListItem(
                            leadingContent = { Avatar(r.displayName, null) },
                            headlineContent = { Text(r.displayName) },
                            supportingContent = { Text(lines(r).take(2).joinToString(" · ")) },
                            trailingContent = {
                                TextButton({ scope.launch { withContext(Dispatchers.IO) { vm.c.records.insert(r, target = null) }; vm.toast("Restored ${r.displayName}"); round++ } }) { Text("Restore") }
                            },
                        )
                    }
                }
            }
            if (d.changed.isNotEmpty()) {
                item { Section("Changed (${d.changed.size})") }
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
                                    (ch.addedRows.mapNotNull(::describe).map { "+ $it" } + ch.removedRows.mapNotNull(::describe).map { "− $it" }).take(4).joinToString("\n")
                                        .ifEmpty { ch.fields.joinToString { "$it changed" } },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
            if (d.added.isNotEmpty()) {
                item { Section("Added (${d.added.size})") }
                d.added.forEach { r -> item { ListItem(headlineContent = { Text(r.displayName) }, supportingContent = { Text(lines(r).take(1).joinToString()) }) } }
            }
        }
    }
}
