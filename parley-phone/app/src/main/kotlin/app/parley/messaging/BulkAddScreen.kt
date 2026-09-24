package app.parley.messaging

import android.content.ClipboardManager
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.NumberText
import app.parley.common.PhoneNumbers
import app.parley.common.messaging.BulkAdd
import app.parley.common.messaging.IntroQueue
import app.parley.data.AccountRef
import app.parley.data.GroupInfo
import app.parley.data.PhoneEnv
import app.parley.data.messaging.BulkAddStore
import app.parley.data.messaging.BulkBatch
import app.parley.data.messaging.BulkDestination
import app.parley.data.messaging.BulkItem
import app.parley.ui.people.accountLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Where { CONTACTS, PRIVATE, TEMPORARY }

/**
 * M11 "Add several numbers…" (Contacts ⋮, and "Save all…" on the number sheet): paste or share text, review every
 * number found (already a contact, already private, repeated, invalid), name them with a pattern and save them to a
 * label in an account, privately, or as temporary contacts. One batch, one "Undo this batch"; the batch is kept for
 * 30 days so it can still be deleted later, and the people can be greeted with "Introduce myself…".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkAddScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val c = vm.c
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val region = remember { PhoneEnv.countryIso(context) }
    var text by rememberSaveable { mutableStateOf(MessagingInbox.bulkText.orEmpty().also { MessagingInbox.bulkText = null }) }
    var candidates by remember { mutableStateOf<List<BulkAdd.Candidate>?>(null) }
    var checked by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var result by remember { mutableStateOf<Pair<BulkAddStore.Result, List<BulkItem>>?>(null) }

    // Naming
    var pattern by rememberSaveable { mutableStateOf(BulkAdd.Pattern.NUMBERED) }
    var prefix by rememberSaveable { mutableStateOf("Contact") }
    var custom by rememberSaveable { mutableStateOf("{prefix} {n}") }
    // Destination
    var where by rememberSaveable { mutableStateOf(Where.CONTACTS) }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var account by remember { mutableStateOf<AccountRef?>(null) }
    var labels by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    var label by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf(7) }
    var tempPrivate by rememberSaveable { mutableStateOf(true) }
    val batches by c.bulkAdd.batches.collectAsStateWithLifecycle()
    var deleteBatch by remember { mutableStateOf<BulkBatch?>(null) }

    LaunchedEffect(Unit) {
        val (accs, groups) = withContext(Dispatchers.IO) { c.contacts.accounts() to runCatching { c.contacts.groups() }.getOrDefault(emptyList()) }
        accounts = accs
        labels = groups
        val s = vm.settings.value
        account = accs.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName } ?: accs.firstOrNull()
    }

    fun review() {
        busy = "Looking for numbers…"
        scope.launch {
            val list = withContext(Dispatchers.Default) {
                val found = NumberText.find(text.take(MAX_TEXT), region, distinct = false)
                val contacts = HashMap<String, String?>()
                val privates = HashMap<String, String?>()
                withContext(Dispatchers.IO) {
                    found.take(BulkAdd.MAX_NUMBERS).forEach { f ->
                        val n = f.e164 ?: PhoneNumbers.clean(f.raw)
                        if (n in contacts) return@forEach
                        contacts[n] = runCatching { c.contacts.lookup(n)?.name }.getOrNull()
                        privates[n] = if (contacts[n] == null) runCatching { c.vault.lookup(n)?.second?.name }.getOrNull() else null
                    }
                }
                BulkAdd.review(found, region, { contacts[it] }, { privates[it] })
            }
            candidates = list
            checked = list.map { it.checked }
            busy = null
            if (list.isEmpty()) snackbar.showSnackbar("No phone numbers found in this text")
        }
    }

    fun itemsToSave(): List<BulkItem> {
        val list = candidates.orEmpty()
        val picked = list.indices.filter { checked.getOrElse(it) { false } && list[it].selectable }
        val template = BulkAdd.template(pattern, custom)
        return picked.mapIndexed { k, i ->
            val cand = list[i]
            val shown = cand.e164?.let(NumberText::formatInternational) ?: cand.number
            BulkItem(BulkAdd.name(template, prefix, k + 1, picked.size, shown), cand.e164 ?: cand.number)
        }
    }

    fun save() {
        val items = itemsToSave()
        if (items.isEmpty()) return
        val acc = account
        val (dest, desc) = when (where) {
            Where.CONTACTS -> {
                if (acc == null) return
                BulkDestination.Label(acc, label.trim().ifEmpty { null }) to (vm.accountLabel(acc) + (label.trim().takeIf { it.isNotEmpty() }?.let { " · label $it" } ?: ""))
            }
            Where.PRIVATE -> BulkDestination.Private to "Private contacts"
            Where.TEMPORARY -> BulkDestination.Temporary(days, tempPrivate) to "Temporary for $days days" + if (tempPrivate) ", private" else ""
        }
        progress = 0f
        scope.launch {
            val r = runCatching { c.bulkAdd.save(items, dest, desc, progress = { done, total -> progress = done.toFloat() / total }) }
            progress = null
            r.onFailure { snackbar.showSnackbar("Couldn't save: ${it.message ?: "error"}") }
            r.onSuccess { res ->
                result = res to items
                candidates = null
                val shown = snackbar.showSnackbar(
                    "Saved ${res.saved} " + if (res.saved == 1) "contact" else "contacts",
                    actionLabel = "Undo this batch",
                    duration = SnackbarDuration.Long,
                )
                if (shown == SnackbarResult.ActionPerformed && result?.first?.batch?.tag == res.batch.tag) {
                    c.bulkAdd.remove(res.batch, journal = false)
                    result = null
                    snackbar.showSnackbar("Batch undone")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add several numbers") },
                navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { p ->
        val res = result
        val list = candidates
        LazyColumn(Modifier.fillMaxSize().padding(p)) {
            when {
                progress != null -> item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Saving…", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
                res != null -> resultItems(res.first, res.second, onUndo = {
                    scope.launch { c.bulkAdd.remove(res.first.batch, journal = false); result = null; snackbar.showSnackbar("Batch undone") }
                }, onDelete = { deleteBatch = res.first.batch }, onIntroduce = {
                    val targets = res.second.mapNotNull { i -> NumberText.toE164(i.number, region)?.let { IntroQueue.Target(i.name, it) } }
                    IntroduceStart.fromList(targets, open)
                }, onMore = { result = null; text = "" }, onDone = back)
                list == null -> {
                    item {
                        Text(
                            "Paste text with phone numbers: a list, a message, a spreadsheet column. You'll see each number before anything is saved.",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item {
                        OutlinedTextField(
                            text, { text = it.take(MAX_TEXT) },
                            label = { Text("Numbers or text") },
                            minLines = 4, maxLines = 10,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    item {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(
                                onClick = {
                                    // Read only on this tap.
                                    val clip = runCatching {
                                        context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                                    }.getOrNull()
                                    if (!clip.isNullOrBlank()) text = (if (text.isBlank()) clip else text + "\n" + clip).take(MAX_TEXT)
                                },
                                label = { Text("Paste") },
                                leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                            )
                            Box(Modifier.weight(1f))
                            Button(::review, enabled = text.isNotBlank() && busy == null) { Text("Find numbers") }
                        }
                        busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
                    }
                    if (batches.isNotEmpty()) {
                        item { SectionTitle("Recent batches") }
                        batches.forEach { b ->
                            item(key = b.tag) {
                                ListItem(
                                    headlineContent = { Text("${b.count} · ${b.where}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(DateUtils.getRelativeTimeSpanString(b.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()) },
                                    trailingContent = { IconButton({ deleteBatch = b }) { Icon(Icons.Rounded.DeleteOutline, "Delete this batch") } },
                                )
                            }
                        }
                    }
                }
                else -> {
                    item {
                        Text(BulkAdd.summary(list), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    itemsIndexed(list) { i, cand ->
                        CandidateRow(cand, checked.getOrElse(i) { false }, region) { v -> checked = checked.toMutableList().also { it[i] = v } }
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item { SectionTitle("Names") }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BulkAdd.Pattern.entries.forEach { pt ->
                                Row(
                                    Modifier.fillMaxWidth().selectable(pattern == pt, role = Role.RadioButton) { pattern = pt },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(pattern == pt, onClick = null)
                                    Text(pt.label + if (pt.template.isNotEmpty()) "  (${pt.template})" else "", Modifier.padding(start = 8.dp))
                                }
                            }
                            OutlinedTextField(prefix, { prefix = it.take(40) }, label = { Text("Prefix") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (pattern == BulkAdd.Pattern.CUSTOM) {
                                OutlinedTextField(
                                    custom, { custom = it.take(80) },
                                    label = { Text("Name pattern") },
                                    supportingText = { Text("Use {prefix}, {n} and {number}") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            val first = itemsToSave().firstOrNull()
                            if (first != null) Text("First: ${first.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item { SectionTitle("Save to") }
                    item {
                        DestinationPicker(
                            vm, where, { where = it }, accounts, account, { account = it; label = "" },
                            labels.filter { it.account == account }, label, { label = it },
                            days, { days = it }, tempPrivate, { tempPrivate = it },
                        )
                    }
                    item {
                        val n = itemsToSave().size
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton({ candidates = null }) { Text("Back to text") }
                            Button(::save, enabled = n > 0 && (where != Where.CONTACTS || account != null)) { Text(if (n == 1) "Save 1 number" else "Save $n numbers") }
                        }
                    }
                }
            }
        }
    }
    deleteBatch?.let { b ->
        AlertDialog(
            onDismissRequest = { deleteBatch = null },
            title = { Text("Delete this batch?") },
            text = {
                Text(
                    "Deletes the ${b.count} contacts this batch created (${b.where}), even if you edited them since. " +
                        "Contacts in your address book can be restored from Recently deleted for 30 days; private ones can't.",
                )
            },
            confirmButton = {
                TextButton({
                    deleteBatch = null
                    scope.launch {
                        c.bulkAdd.remove(b, journal = true)
                        if (result?.first?.batch?.tag == b.tag) result = null
                        snackbar.showSnackbar("Batch deleted")
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ deleteBatch = null }) { Text("Cancel") } },
        )
    }
}

private const val MAX_TEXT = 100_000

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun CandidateRow(c: BulkAdd.Candidate, checked: Boolean, region: String, onChange: (Boolean) -> Unit) {
    val shown = c.e164?.let(NumberText::formatInternational) ?: c.raw
    val status = when (c.status) {
        BulkAdd.Status.CONTACT -> "Already a contact: ${c.existingName}"
        BulkAdd.Status.PRIVATE -> "Already a private contact: ${c.existingName}"
        else -> c.status.label
    }
    ListItem(
        headlineContent = { Text(shown) },
        supportingContent = {
            val where = c.e164?.let { app.parley.data.NumberInfo.location(it, region) }
            Text(listOfNotNull(status, where, c.raw.takeIf { it != shown }?.let { "“$it”" }).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Checkbox(checked, onCheckedChange = null, enabled = c.selectable) },
        modifier = Modifier.toggleable(checked, enabled = c.selectable, role = Role.Checkbox, onValueChange = onChange),
    )
}

@Composable
private fun DestinationPicker(
    vm: AppViewModel,
    where: Where, onWhere: (Where) -> Unit,
    accounts: List<AccountRef>, account: AccountRef?, onAccount: (AccountRef) -> Unit,
    labels: List<GroupInfo>, label: String, onLabel: (String) -> Unit,
    days: Int, onDays: (Int) -> Unit,
    tempPrivate: Boolean, onTempPrivate: (Boolean) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable
        fun option(w: Where, title: String, sub: String) {
            Row(Modifier.fillMaxWidth().selectable(where == w, role = Role.RadioButton) { onWhere(w) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(where == w, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        option(Where.CONTACTS, "Contacts", "In an account, optionally in a label")
        if (where == Where.CONTACTS) {
            var accMenu by remember { mutableStateOf(false) }
            var labelMenu by remember { mutableStateOf(false) }
            Box {
                ListItem(
                    headlineContent = { Text(account?.let { vm.accountLabel(it) } ?: "No account to save to") },
                    supportingContent = { Text("Account") },
                    trailingContent = { Icon(Icons.Rounded.ArrowDropDown, "Choose the account") },
                    modifier = Modifier.clickable(enabled = accounts.size > 1) { accMenu = true },
                )
                DropdownMenu(accMenu, { accMenu = false }) {
                    accounts.forEach { a -> DropdownMenuItem({ Text(vm.accountLabel(a)) }, onClick = { accMenu = false; onAccount(a) }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    label, { onLabel(it.take(60)) },
                    label = { Text("Label (optional)") },
                    supportingText = { Text("An existing label, or a new one") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (labels.isNotEmpty()) {
                    Box {
                        IconButton({ labelMenu = true }) { Icon(Icons.Rounded.ArrowDropDown, "Choose a label") }
                        DropdownMenu(labelMenu, { labelMenu = false }, Modifier.heightIn(max = 320.dp)) {
                            labels.forEach { g -> DropdownMenuItem({ Text(g.title) }, onClick = { labelMenu = false; onLabel(g.title) }) }
                        }
                    }
                }
            }
        }
        option(Where.PRIVATE, "Private contacts", "Encrypted in Parley, invisible to other apps (WhatsApp included)")
        option(Where.TEMPORARY, "Temporary contacts", "They delete themselves, with their call history")
        if (where == Where.TEMPORARY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30).forEach { d -> FilterChip(days == d, { onDays(d) }, label = { Text(if (d == 1) "1 day" else "$d days") }) }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(tempPrivate, role = Role.Switch, onValueChange = onTempPrivate),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Private")
                    Text(
                        if (tempPrivate) "Other apps can't see them" else "Saved on this phone, visible to apps that read contacts",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(tempPrivate, onCheckedChange = null)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resultItems(
    r: BulkAddStore.Result,
    items: List<BulkItem>,
    onUndo: () -> Unit,
    onDelete: () -> Unit,
    onIntroduce: () -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
) {
    item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text("Saved ${r.saved} of ${items.size} · ${r.batch.where}", style = MaterialTheme.typography.titleMedium)
            }
            r.failed.take(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (r.failed.size > 20) Text("…and ${r.failed.size - 20} more", style = MaterialTheme.typography.bodySmall)
            Text(
                "This batch is remembered for 30 days: you can delete it from “Recent batches” on this screen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item {
        ListItem(
            headlineContent = { Text("Introduce myself…") },
            supportingContent = { Text("Open each chat with your details filled in; you press Send") },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) },
            modifier = Modifier.clickable(onClick = onIntroduce),
        )
    }
    item {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onUndo) { Text("Undo this batch") }
            OutlinedButton(onDelete) { Text("Delete this batch") }
        }
    }
    item {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onMore) { Text("Add more") }
            Button(onDone) { Text("Done") }
        }
    }
}
