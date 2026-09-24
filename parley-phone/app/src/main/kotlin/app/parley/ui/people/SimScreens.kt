package app.parley.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.DuplicateIndex
import app.parley.common.PhoneEntry
import app.parley.common.people.SimEntry
import app.parley.common.people.SimFit
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.people.SimCard
import app.parley.ui.common.Format
import app.parley.ui.contact.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.people.SimIssue
import app.parley.common.people.SimWarning
import app.parley.ui.DataL10n

@Composable
private fun simWarningText(w: SimWarning): String = when (w.issue) {
    SimIssue.NO_NUMBER -> stringResource(R.string.sim_issue_no_number)
    SimIssue.NUMBER_INVALID -> stringResource(R.string.sim_issue_invalid)
    SimIssue.NUMBER_TOO_LONG -> pluralStringResource(R.plurals.sim_issue_too_long, w.count, w.count)
    SimIssue.OTHER_NUMBERS_LEFT_OUT -> pluralStringResource(R.plurals.sim_issue_left_out, w.count, w.count)
    SimIssue.NAME_SHORTENED -> stringResource(R.string.sim_issue_shortened, w.text)
    SimIssue.DETAILS_STAY -> stringResource(R.string.sim_issue_details)
}

/** Contact overflow › "Copy to SIM": shows exactly what fits before writing. */
@Composable
fun CopyToSimDialog(vm: AppViewModel, d: ContactDetails, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var cards by remember { mutableStateOf<List<SimCard>?>(null) }
    var card by remember { mutableStateOf<SimCard?>(null) }
    LaunchedEffect(Unit) {
        cards = vm.c.people.sim.cards()
        card = cards?.firstOrNull()
    }
    val chosen = card
    val others = d.emails.size + d.addresses.size + d.websites.size + d.events.size + (if (d.note.isNotBlank()) 1 else 0) + (if (d.photoUri != null) 1 else 0)
    val fit = remember(chosen, d) {
        SimFit.fit(
            d.displayName, d.phones.map { PhoneEntry(it.value, it.type, it.label, it.isPrimary) }, others,
            chosen?.nameMax ?: SimFit.DEFAULT_NAME_MAX, chosen?.numberMax ?: SimFit.DEFAULT_NUMBER_MAX, vm.c.people.sim::encodedLength,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SimCard, null) },
        title = { Text(stringResource(R.string.sim_copy_title)) },
        text = {
            Column {
                when {
                    cards == null -> CircularProgressIndicator()
                    cards!!.isEmpty() -> Text(stringResource(R.string.sim_none_see))
                    else -> {
                        Text(stringResource(R.string.sim_warning), style = MaterialTheme.typography.bodyMedium)
                        if (cards!!.size > 1) cards!!.forEach { c ->
                            ListItem(
                                modifier = Modifier.clickable { card = c },
                                leadingContent = { RadioButton(card == c, { card = c }) },
                                headlineContent = { Text(c.label) },
                                supportingContent = { if (c.free >= 0) Text(pluralStringResource(R.plurals.sim_free, c.free, c.free)) },
                            )
                        }
                        fit.entry?.let { e ->
                            ListItem(headlineContent = { Text(e.name) }, supportingContent = { Text(DataL10n.ltr(Format.number(e.number, vm.countryIso))) }, leadingContent = { Icon(Icons.Rounded.SimCard, null) })
                        }
                        fit.warnings.forEach { Text("• " + simWarningText(it), style = MaterialTheme.typography.bodySmall) }
                        if (chosen?.free == 0) Text(stringResource(R.string.sim_full), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val e = fit.entry ?: return@TextButton
                val c = chosen ?: return@TextButton
                onDismiss()
                scope.launch {
                    val err = vm.c.people.sim.write(c, e)
                    if (err != null) runCatching { vm.c.people.diagnostics.record("Copy to SIM", IllegalStateException(err)) }
                    vm.toast(err ?: context.getString(R.string.sim_copied, c.label))
                }
            }, enabled = fit.entry != null && chosen != null && chosen.free != 0) { Text(stringResource(R.string.sim_copy)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

/** Import screen for the SIM phonebook: choose entries and the account, skip ones you already have. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimImportScreen(vm: AppViewModel, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var cards by remember { mutableStateOf<List<SimCard>?>(null) }
    var card by remember { mutableStateOf<SimCard?>(null) }
    var entries by remember { mutableStateOf<List<SimEntry>?>(null) }
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var chooseAccount by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cards = vm.c.people.sim.cards()
        card = cards?.firstOrNull()
        accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() }
    }
    val existing = remember(contacts) { DuplicateIndex().apply { contacts.orEmpty().forEach { add(it) } } }
    fun recordOf(e: SimEntry) = ContactRecord(
        key = "", displayName = e.name,
        raws = listOf(RawRecord(null, null, rows = listOf(DataRow(Mime.NAME, mapOf(Col.D1 to e.name)), DataRow(Mime.PHONE, mapOf(Col.D1 to e.number, Col.D2 to "2"))))),
    )
    LaunchedEffect(card) {
        val c = card ?: return@LaunchedEffect
        entries = null
        val list = vm.c.people.sim.read(c)
        entries = list
        picked = list.indices.filter { !existing.matches(recordOf(list[it])) }.toSet()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.sim_import_title)) },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } },
            actions = { Button({ chooseAccount = true }, enabled = picked.isNotEmpty() && !busy, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.sim_import_n, picked.size)) } },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item { Text(stringResource(R.string.sim_import_intro), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            val cs = cards
            if (cs == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            else if (cs.isEmpty()) item { Text(stringResource(R.string.sim_none_read), Modifier.padding(16.dp)) }
            else if (cs.size > 1) {
                item { Section(stringResource(R.string.sim_section)) }
                cs.forEach { c ->
                    item { ListItem(modifier = Modifier.clickable { card = c }, leadingContent = { RadioButton(card == c, { card = c }) }, headlineContent = { Text(c.label) }) }
                }
            }
            val list = entries
            if (cs?.isNotEmpty() == true && list == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            if (list != null) {
                item { Section(pluralStringResource(R.plurals.sim_entries, list.size, list.size)) }
                if (list.isEmpty()) item { Text(stringResource(R.string.sim_empty), Modifier.padding(16.dp)) }
                itemsIndexed(list) { i, e ->
                    val have = existing.matches(recordOf(e))
                    ListItem(
                        modifier = Modifier.clickable { picked = if (i in picked) picked - i else picked + i },
                        leadingContent = { Checkbox(i in picked, { picked = if (it) picked + i else picked - i }) },
                        headlineContent = { Text(e.name) },
                        supportingContent = {
                            val n = DataL10n.ltr(Format.number(e.number, vm.countryIso))
                            Text(if (have) stringResource(R.string.sim_already, n) else n)
                        },
                    )
                }
            }
        }
    }
    if (chooseAccount) {
        AlertDialog(
            onDismissRequest = { chooseAccount = false },
            title = { Text(stringResource(R.string.sim_import_into)) },
            text = {
                Column {
                    accounts.forEach { a ->
                        ListItem(headlineContent = { Text(vm.accountLabel(a)) }, modifier = Modifier.clickable {
                            chooseAccount = false
                            val chosen = entries.orEmpty().filterIndexed { i, _ -> i in picked }
                            busy = true
                            scope.launch {
                                val results = withContext(Dispatchers.IO) { vm.c.records.insertAll(chosen.map(::recordOf), a) }
                                val ok = results.count { it.contactId != null }
                                vm.c.contacts.refresh()
                                busy = false
                                vm.toast(context.getString(R.string.sim_imported, ok, chosen.size, a.displayLabel))
                                if (ok > 0) back()
                            }
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ chooseAccount = false }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
}
