package app.parley.messaging

import app.parley.ui.Clipboard
import app.parley.ui.Destination
import app.parley.common.catching
import app.parley.jobs.UserErrorText
import android.content.res.Resources
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
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
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.NumberText
import app.parley.common.PhoneIdentity
import app.parley.common.messaging.BulkAdd
import app.parley.common.messaging.IntroQueue
import app.parley.data.AccountRef
import app.parley.ui.ParleyListItem
import app.parley.ui.common.AccountRefSaver
import app.parley.ui.common.BooleanListSaver
import app.parley.data.GroupInfo
import app.parley.data.NumberInfo
import app.parley.data.PhoneEnv
import app.parley.data.messaging.BulkAddStore
import app.parley.data.messaging.BulkBatch
import app.parley.data.messaging.BulkDestination
import app.parley.data.messaging.BulkItem
import app.parley.ui.Bidi
import app.parley.ui.CallColors
import app.parley.ui.people.accountLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.LocalSnackbar
import app.parley.ui.ConfirmDialog
import app.parley.ui.ListSectionHeader
import app.parley.ui.Spacing

private enum class Where { CONTACTS, PRIVATE, TEMPORARY }

/**
 * "Add several numbers…" (Contacts ⋮, and "Save all…" on the number sheet): paste or share text, review every
 * number found (already a contact, already private, repeated, invalid), name them with a pattern and save them to a
 * label in an account, privately, or as temporary contacts. One batch, one "Undo this batch"; the batch is kept for
 * 30 days so it can still be deleted later, and the people can be greeted with "Introduce myself…".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkAddScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val c = vm.c
    val scope = rememberCoroutineScope()
    // The app's one snackbar, shown inside this screen's Scaffold.
    val snackbar = LocalSnackbar.current?.state ?: remember { SnackbarHostState() }
    val region = remember { PhoneEnv.countryIso(context) }
    val rs = LocalResources.current
    var text by rememberSaveable { mutableStateOf(MessagingInbox.bulkText.orEmpty().also { MessagingInbox.bulkText = null }) }
    var candidates by remember { mutableStateOf<List<BulkAdd.Candidate>?>(null) }
    // Which numbers are ticked, and whether the text was reviewed: after rotation or process death the review runs
    // again from the saved text and the ticks are put back.
    var checked by rememberSaveable(stateSaver = BooleanListSaver) { mutableStateOf<List<Boolean>>(emptyList()) }
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var result by remember { mutableStateOf<Pair<BulkAddStore.Result, List<BulkItem>>?>(null) }

    // Naming
    var pattern by rememberSaveable { mutableStateOf(BulkAdd.Pattern.NUMBERED) }
    var prefix by rememberSaveable { mutableStateOf(rs.getString(R.string.bulk_default_prefix)) }
    var custom by rememberSaveable { mutableStateOf("{prefix} {n}") }
    // Destination
    var where by rememberSaveable { mutableStateOf(Where.CONTACTS) }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var account by rememberSaveable(stateSaver = AccountRefSaver) { mutableStateOf<AccountRef?>(null) }
    var labels by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    var label by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf(7) }
    var tempPrivate by rememberSaveable { mutableStateOf(true) }
    val batches by c.bulkAdd.batches.collectAsStateWithLifecycle()
    var deleteBatch by remember { mutableStateOf<BulkBatch?>(null) }
    // The batch being (or already) removed: its Undo / Delete run once, whether from the snackbar or the buttons.
    var removing by remember { mutableStateOf<String?>(null) }
    // The batch whose "saved" snackbar is showing: only then is Undo a plain undo without a Recently deleted copy.
    var undoWindow by remember { mutableStateOf<String?>(null) }

    /**
     * Removes [batch] once. Right after saving (while its snackbar shows) nothing was edited yet, so no copies are
     * kept; later ([journal]) the contacts may have been edited, so each one goes to Recently deleted.
     */
    fun removeBatch(batch: BulkBatch, journal: Boolean = undoWindow != batch.tag) {
        if (removing == batch.tag) return
        removing = batch.tag
        scope.launch {
            c.bulkAdd.remove(batch, journal = journal)
            if (result?.first?.batch?.tag == batch.tag) result = null
            snackbar.showSnackbar(rs.getString(if (journal) R.string.bulk_batch_deleted else R.string.bulk_batch_undone))
        }
    }

    LaunchedEffect(Unit) {
        val (accs, groups) = withContext(Dispatchers.IO) { c.contacts.accounts() to runCatching { c.contacts.groups() }.getOrDefault(emptyList()) }
        accounts = accs
        labels = groups
        val s = vm.settings.value
        if (account == null) account = accs.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName } ?: accs.firstOrNull()
    }

    fun review(keepTicks: Boolean = false) {
        busy = true
        scope.launch {
            val list = withContext(Dispatchers.Default) {
                val found = NumberText.find(text.take(MAX_TEXT), region, distinct = false)
                val contacts = HashMap<String, String?>()
                val privates = HashMap<String, String?>()
                withContext(Dispatchers.IO) {
                    found.take(BulkAdd.MAX_NUMBERS).forEach { f ->
                        val n = f.e164 ?: PhoneIdentity.clean(f.raw)
                        if (n in contacts) return@forEach
                        contacts[n] = runCatching { c.contacts.lookup(n)?.name }.getOrNull()
                        privates[n] = if (contacts[n] == null) runCatching { c.vault.lookup(n)?.second?.name }.getOrNull() else null
                    }
                }
                BulkAdd.review(found, region, { contacts[it] }, { privates[it] })
            }
            candidates = list
            checked = if (keepTicks && checked.size == list.size) checked else list.map { it.checked }
            reviewed = list.isNotEmpty()
            busy = false
            if (list.isEmpty()) snackbar.showSnackbar(rs.getString(R.string.bulk_none_found))
        }
    }

    // Restored after rotation or process death: review the saved text again, with the saved ticks.
    LaunchedEffect(Unit) { if (reviewed && candidates == null && !busy) review(keepTicks = true) }

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
                BulkDestination.Label(acc, label.trim().ifEmpty { null }) to (label.trim().takeIf { it.isNotEmpty() }?.let { rs.getString(R.string.bulk_where_label, vm.accountLabel(acc), it) } ?: vm.accountLabel(acc))
            }
            Where.PRIVATE -> BulkDestination.Private to rs.getString(R.string.bulk_private_contacts)
            Where.TEMPORARY -> BulkDestination.Temporary(days, tempPrivate) to rs.getQuantityString(if (tempPrivate) R.plurals.bulk_where_temp_private else R.plurals.bulk_where_temp, days, days)
        }
        progress = 0f
        scope.launch {
            val r = catching { c.bulkAdd.save(items, dest, desc, progress = { done, total -> progress = done.toFloat() / total }) }
            progress = null
            r.onFailure { snackbar.showSnackbar(rs.getString(R.string.edit_save_failed, UserErrorText.of(context, it))) }
            r.onSuccess { res ->
                result = res to items
                candidates = null
                reviewed = false
                undoWindow = res.batch.tag
                val shown = try {
                    snackbar.showSnackbar(
                        rs.getQuantityString(R.plurals.bulk_saved, res.saved, res.saved),
                        actionLabel = rs.getString(R.string.bulk_undo_batch),
                        duration = SnackbarDuration.Long,
                    )
                } finally {
                    if (undoWindow == res.batch.tag) undoWindow = null
                }
                if (shown == SnackbarResult.ActionPerformed && result?.first?.batch?.tag == res.batch.tag) removeBatch(res.batch, journal = false)
            }
        }
    }

    ParleyScaffold(
        topBar = {
            ParleyTopBar(stringResource(R.string.bulk_title), onBack = back)
        },
    ) { p ->
        val res = result
        val list = candidates
        LazyColumn(Modifier.fillMaxSize().padding(p)) {
            when {
                progress != null -> item {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.bulk_saving), style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
                res != null -> resultItems(res.first, res.second, removable = removing != res.first.batch.tag, onUndo = {
                    removeBatch(res.first.batch)
                }, onDelete = { deleteBatch = res.first.batch }, onIntroduce = {
                    val targets = res.second.mapNotNull { i -> NumberText.toE164(i.number, region)?.let { IntroQueue.Target(i.name, it) } }
                    IntroduceStart.fromList(targets, open)
                }, onMore = { result = null; text = "" }, onDone = back)
                list == null -> {
                    item {
                        Text(
                            stringResource(R.string.bulk_intro),
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item {
                        OutlinedTextField(
                            text, { text = it.take(MAX_TEXT) },
                            label = { Text(stringResource(R.string.bulk_numbers_or_text)) },
                            minLines = 4, maxLines = 10,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    item {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(
                                onClick = {
                                    // Read only on this tap.
                                    val clip = Clipboard.readText(context)
                                    if (!clip.isNullOrBlank()) text = (if (text.isBlank()) clip else text + "\n" + clip).take(MAX_TEXT)
                                },
                                label = { Text(stringResource(R.string.keypad_paste)) },
                                leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                            )
                            Box(Modifier.weight(1f))
                            Button(::review, enabled = text.isNotBlank() && !busy) { Text(stringResource(R.string.bulk_find_numbers)) }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    }
                    if (batches.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.bulk_recent_batches)) }
                        batches.forEach { b ->
                            item(key = b.tag) {
                                ParleyListItem(
                                    headlineContent = { Text("${b.count}" + stringResource(R.string.main_separator) + b.where, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(DateUtils.getRelativeTimeSpanString(b.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()) },
                                    trailingContent = { IconButton({ deleteBatch = b }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.bulk_delete_batch)) } },
                                )
                            }
                        }
                    }
                }
                else -> {
                    item {
                        Text(bulkSummary(rs, list), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    itemsIndexed(list) { i, cand ->
                        CandidateRow(cand, checked.getOrElse(i) { false }, region, onCall = { n -> vm.requestCall(n, cand.existingName) }) { v -> checked = checked.toMutableList().also { it[i] = v } }
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item { SectionTitle(stringResource(R.string.bulk_names)) }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BulkAdd.Pattern.entries.forEach { pt ->
                                Row(
                                    Modifier.fillMaxWidth().selectable(pattern == pt, role = Role.RadioButton) { pattern = pt },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(pattern == pt, onClick = null)
                                    Text(stringResource(pt.labelRes) + if (pt.template.isNotEmpty()) "  (${pt.template})" else "", Modifier.padding(start = 8.dp))
                                }
                            }
                            OutlinedTextField(prefix, { prefix = it.take(40) }, label = { Text(stringResource(R.string.edit_prefix)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (pattern == BulkAdd.Pattern.CUSTOM) {
                                OutlinedTextField(
                                    custom, { custom = it.take(80) },
                                    label = { Text(stringResource(R.string.bulk_name_pattern)) },
                                    supportingText = { Text(stringResource(R.string.bulk_pattern_hint)) },
                                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            val first = itemsToSave().firstOrNull()
                            if (first != null) Text(stringResource(R.string.bulk_first, first.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item { SectionTitle(stringResource(R.string.edit_save_to)) }
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
                            TextButton({ candidates = null; reviewed = false }) { Text(stringResource(R.string.bulk_back_to_text)) }
                            Button(::save, enabled = n > 0 && (where != Where.CONTACTS || account != null)) { Text(pluralStringResource(R.plurals.bulk_save_n, n, n)) }
                        }
                    }
                }
            }
        }
    }
    deleteBatch?.let { b ->
        ConfirmDialog(
            title = stringResource(R.string.bulk_delete_title),
            text = pluralStringResource(R.plurals.bulk_delete_body, b.count, b.count, b.where),
            confirmLabel = stringResource(R.string.main_delete),
            onConfirm = {
                deleteBatch = null
                removeBatch(b, journal = true)
            },
            onDismiss = { deleteBatch = null },
            destructive = true,
            dismissLabel = stringResource(R.string.main_cancel),
        )
    }
}

private const val MAX_TEXT = 100_000

/** Status of a reviewed number ([BulkAdd.Status.label] is the English original). */
private val BulkAdd.Status.labelRes: Int
    get() = when (this) {
        BulkAdd.Status.NEW -> R.string.bulk_status_new
        BulkAdd.Status.CONTACT -> R.string.bulk_status_contact
        BulkAdd.Status.PRIVATE -> R.string.bulk_status_private
        BulkAdd.Status.DUPLICATE -> R.string.bulk_status_duplicate
        BulkAdd.Status.INVALID -> R.string.bulk_status_invalid
    }

private val BulkAdd.Pattern.labelRes: Int
    get() = when (this) {
        BulkAdd.Pattern.NUMBERED -> R.string.bulk_pattern_numbered
        BulkAdd.Pattern.WITH_NUMBER -> R.string.bulk_pattern_with_number
        BulkAdd.Pattern.CUSTOM -> R.string.edit_custom
    }

/** "3 new · 1 already a contact · 1 repeated" ([BulkAdd.summary]). */
private fun bulkSummary(res: Resources, list: List<BulkAdd.Candidate>): String =
    BulkAdd.Status.entries.mapNotNull { s -> list.count { it.status == s }.takeIf { it > 0 }?.let { n -> res.getString(R.string.bulk_summary_item, n, res.getString(s.labelRes).lowercase()) } }
        .joinToString(res.getString(R.string.main_separator))

@Composable
private fun SectionTitle(text: String) {
    ListSectionHeader(text, bottom = Spacing.s)
}

@Composable
private fun CandidateRow(c: BulkAdd.Candidate, checked: Boolean, region: String, onCall: (String) -> Unit, onChange: (Boolean) -> Unit) {
    val shown = c.e164?.let(NumberText::formatInternational) ?: c.raw
    val status = when (c.status) {
        BulkAdd.Status.CONTACT -> stringResource(R.string.bulk_already_contact, c.existingName.orEmpty())
        BulkAdd.Status.PRIVATE -> stringResource(R.string.bulk_already_private, c.existingName.orEmpty())
        else -> stringResource(c.status.labelRes)
    }
    ParleyListItem(
        headlineContent = { Text(Bidi.ltr(shown)) },
        supportingContent = {
            val where = c.e164?.let { NumberInfo.location(it, region) }
            Text(listOfNotNull(status, where, c.raw.takeIf { it != shown }?.let { "“$it”" }).joinToString(stringResource(R.string.main_separator)), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Checkbox(checked, onCheckedChange = null, enabled = c.selectable) },
        // Any number found can be called before (or instead of) saving it.
        trailingContent = {
            IconButton({ onCall(c.e164 ?: c.raw) }) {
                Icon(
                    Icons.Rounded.Call, stringResource(R.string.reach_call_number, Bidi.ltr(shown)),
                    tint = CallColors.Accept,
                )
            }
        },
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
        option(Where.CONTACTS, stringResource(R.string.tab_contacts), stringResource(R.string.bulk_contacts_sub))
        if (where == Where.CONTACTS) {
            var accMenu by remember { mutableStateOf(false) }
            var labelMenu by remember { mutableStateOf(false) }
            Box {
                ParleyListItem(
                    headlineContent = { Text(account?.let { vm.accountLabel(it) } ?: stringResource(R.string.bulk_no_account)) },
                    supportingContent = { Text(stringResource(R.string.bulk_account)) },
                    trailingContent = { Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.bulk_choose_account)) },
                    modifier = Modifier.clickable(enabled = accounts.size > 1) { accMenu = true },
                )
                DropdownMenu(accMenu, { accMenu = false }) {
                    accounts.forEach { a -> DropdownMenuItem({ Text(vm.accountLabel(a)) }, onClick = { accMenu = false; onAccount(a) }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    label, { onLabel(it.take(60)) },
                    label = { Text(stringResource(R.string.bulk_label_optional)) },
                    supportingText = { Text(stringResource(R.string.bulk_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (labels.isNotEmpty()) {
                    Box {
                        IconButton({ labelMenu = true }) { Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.bulk_choose_label)) }
                        DropdownMenu(labelMenu, { labelMenu = false }, Modifier.heightIn(max = 320.dp)) {
                            labels.forEach { g -> DropdownMenuItem({ Text(g.title) }, onClick = { labelMenu = false; onLabel(g.title) }) }
                        }
                    }
                }
            }
        }
        option(Where.PRIVATE, stringResource(R.string.bulk_private_contacts), stringResource(R.string.bulk_private_sub))
        option(Where.TEMPORARY, stringResource(R.string.home_temporary), stringResource(R.string.bulk_temporary_sub))
        if (where == Where.TEMPORARY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30).forEach { d -> FilterChip(days == d, { onDays(d) }, label = { Text(pluralStringResource(R.plurals.bulk_days, d, d)) }) }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(tempPrivate, role = Role.Switch, onValueChange = onTempPrivate),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bulk_private))
                    Text(
                        stringResource(if (tempPrivate) R.string.bulk_private_on else R.string.bulk_private_off),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(tempPrivate, onCheckedChange = null)
            }
        }
    }
}

private fun LazyListScope.resultItems(
    r: BulkAddStore.Result,
    items: List<BulkItem>,
    removable: Boolean,
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
                Text(stringResource(R.string.bulk_saved_of, r.saved, items.size, r.batch.where), style = MaterialTheme.typography.titleMedium)
            }
            r.failed.take(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (r.failed.size > 20) Text(stringResource(R.string.bulk_and_more, r.failed.size - 20), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.bulk_remembered),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item {
        ParleyListItem(
            headlineContent = { Text(stringResource(R.string.sel_introduce)) },
            supportingContent = { Text(stringResource(R.string.bulk_introduce_sub)) },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) },
            modifier = Modifier.clickable(onClick = onIntroduce),
        )
    }
    item {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onUndo, enabled = removable) { Text(stringResource(R.string.bulk_undo_batch)) }
            OutlinedButton(onDelete, enabled = removable) { Text(stringResource(R.string.bulk_delete_batch)) }
        }
    }
    item {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onMore) { Text(stringResource(R.string.bulk_add_more)) }
            Button(onDone) { Text(stringResource(R.string.main_done)) }
        }
    }
}
