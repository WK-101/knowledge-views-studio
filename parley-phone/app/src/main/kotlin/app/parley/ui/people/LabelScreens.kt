package app.parley.ui.people

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.LabelOff
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.StartTab
import app.parley.data.AccountRef
import app.parley.data.people.Label
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.contact.Section
import app.parley.ui.home.ContactRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

/** Settings-like screen listing every label: open, create, rename, delete and merge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageLabelsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val idx by vm.people.index.collectAsStateWithLifecycle()
    val all by vm.contacts.collectAsStateWithLifecycle()
    var labels by remember { mutableStateOf<List<Label>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(all, round) { labels = vm.c.people.labels.labels() }
    var merging by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mergeTarget by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    // U7: scroll-linked top-bar tint.
    val barTint = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        TopAppBar(
            scrollBehavior = barTint,
            title = { Text(if (merging) pluralStringResource(R.plurals.lbl_selected, picked.size, picked.size) else stringResource(R.string.lbl_title)) },
            navigationIcon = {
                if (merging) IconButton({ merging = false; picked = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.lbl_stop_merging)) }
                else IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) }
            },
            actions = {
                if (merging) {
                    TextButton({ mergeTarget = true }, enabled = picked.size >= 2) { Text(stringResource(R.string.lbl_merge)) }
                } else {
                    IconButton({ creating = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.lbl_new)) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.dc_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.lbl_merge_labels)) }, leadingIcon = { Icon(Icons.Rounded.CallMerge, null) }, onClick = { menu = false; merging = true })
                        }
                    }
                }
            },
        )
    }) { p ->
        val list = labels ?: return@Scaffold
        val unlabelled = all.orEmpty().count { idx.extras[it.id]?.labels.isNullOrEmpty() }
        LazyColumn(Modifier.padding(p)) {
            if (merging) item {
                Text(
                    stringResource(R.string.lbl_merge_intro),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!merging) item {
                ListItem(
                    modifier = Modifier.clickable {
                        vm.people.clearFilter()
                        vm.people.setUnlabelled(true)
                        vm.navigate(NavEvent.Tab(StartTab.CONTACTS))
                    },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.LabelOff, null) },
                    headlineContent = { Text(stringResource(R.string.lbl_unlabelled)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.lbl_unlabelled_count, unlabelled, unlabelled)) },
                )
            }
            if (list.isEmpty()) item {
                EmptyState(
                    Icons.AutoMirrored.Rounded.Label, stringResource(R.string.lbl_empty_title), stringResource(R.string.lbl_empty_text), Modifier.padding(top = 32.dp),
                    action = stringResource(R.string.lbl_new), onAction = { creating = true },
                )
            }
            items(list, key = { it.title }) { l ->
                var rowMenu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable {
                        if (merging) picked = if (l.title in picked) picked - l.title else picked + l.title else open(PeopleRoutes.label(l.title))
                    },
                    leadingContent = {
                        if (merging) Checkbox(l.title in picked, { picked = if (it) picked + l.title else picked - l.title })
                        else Icon(Icons.AutoMirrored.Rounded.Label, null)
                    },
                    headlineContent = { Text(l.title) },
                    supportingContent = { (idx.labelCounts[l.title] ?: 0).let { n -> Text(pluralStringResource(R.plurals.lbl_count_accounts, n, n, l.accounts.joinToString { it.displayLabel })) } },
                    trailingContent = if (merging) null else ({
                        Box {
                            IconButton({ rowMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.lbl_more_for, l.title)) }
                            DropdownMenu(rowMenu, { rowMenu = false }) {
                                DropdownMenuItem({ Text(stringResource(R.string.lbl_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { rowMenu = false; renaming = l.title })
                                DropdownMenuItem({ Text(stringResource(R.string.lbl_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { rowMenu = false; deleting = l.title })
                            }
                        }
                    }),
                )
            }
        }
    }

    if (mergeTarget) {
        var target by remember { mutableStateOf(picked.first()) }
        AlertDialog(
            onDismissRequest = { mergeTarget = false },
            title = { Text(stringResource(R.string.lbl_merge_into)) },
            text = {
                Column {
                    picked.sorted().forEach { t ->
                        ListItem(
                            modifier = Modifier.clickable { target = t },
                            leadingContent = { RadioButton(target == t, { target = t }) },
                            headlineContent = { Text(t) },
                            supportingContent = { (idx.labelCounts[t] ?: 0).let { n -> Text(pluralStringResource(R.plurals.lbl_n_contacts, n, n)) } },
                        )
                    }
                    Text(stringResource(R.string.lbl_merge_note, target), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton({
                    mergeTarget = false
                    scope.launch {
                        val n = runCatching { vm.c.people.labels.merge(picked, target) }.getOrElse { vm.toast(res.getString(R.string.lbl_merge_failed, it.message.toString())); return@launch }
                        vm.toast(if (n > 0) res.getQuantityString(R.plurals.lbl_merged_added, n, n, target) else res.getString(R.string.lbl_merged, target))
                        merging = false
                        picked = emptySet()
                        vm.c.contacts.refresh()
                        round++
                    }
                }) { Text(stringResource(R.string.lbl_merge)) }
            },
            dismissButton = { TextButton({ mergeTarget = false }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
    if (creating) CreateLabelDialog(vm, onDismiss = { creating = false }) { round++ }
    renaming?.let { old -> RenameLabelDialog(vm, old, onDismiss = { renaming = null }) { round++ } }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.lbl_delete_title, t)) },
            text = { Text(stringResource(R.string.lbl_delete_text)) },
            confirmButton = {
                TextButton({
                    deleting = null
                    scope.launch {
                        runCatching { vm.c.people.labels.delete(t) }.getOrNull()?.let { vm.toast(it) }
                        vm.c.contacts.refresh()
                        round++
                    }
                }) { Text(stringResource(R.string.dc_delete)) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
}

@Composable
private fun CreateLabelDialog(vm: AppViewModel, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var name by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var account by remember { mutableStateOf<AccountRef?>(null) }
    val idx by vm.people.index.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() }
        val s = vm.settings.value
        account = accounts.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName } ?: accounts.firstOrNull { it.type == "com.google" } ?: accounts.firstOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lbl_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.lbl_name)) }, singleLine = true)
                Text(stringResource(R.string.lbl_saved_in), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                accounts.forEach { a ->
                    ListItem(
                        modifier = Modifier.clickable { account = a },
                        leadingContent = { RadioButton(account == a, { account = a }) },
                        headlineContent = { Text(idx.labelWithCount(a)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton({
                val a = account ?: return@TextButton
                onDismiss()
                scope.launch {
                    if (vm.c.people.labels.create(name, a) != null) { vm.toast(res.getString(R.string.lbl_created, name.trim())); onCreated() } else vm.toast(res.getString(R.string.lbl_create_failed))
                }
            }, enabled = name.isNotBlank() && account != null) { Text(stringResource(R.string.lbl_create)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

@Composable
private fun RenameLabelDialog(vm: AppViewModel, old: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var name by remember { mutableStateOf(old) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lbl_rename_title)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true)
                Text(stringResource(R.string.lbl_rename_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton({
                onDismiss()
                scope.launch {
                    // Its ringtone, rules, limits and off-hours choice follow the label (see LabelReferences).
                    runCatching { vm.c.people.labels.rename(old, name) }.onFailure { vm.toast(res.getString(R.string.lbl_rename_failed, it.message.toString())) }
                    vm.c.contacts.refresh()
                    onDone(name.trim())
                }
            }, enabled = name.isNotBlank() && name.trim() != old) { Text(stringResource(R.string.lbl_rename)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

/** One label: its members and group actions (message all, e-mail all, ringtone, blocking). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelScreen(vm: AppViewModel, title: String, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(title) }
    val idx by vm.people.index.collectAsStateWithLifecycle()
    val all by vm.contacts.collectAsStateWithLifecycle()
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val members = all.orEmpty().filter { current in idx.extras[it.id]?.labels.orEmpty() }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tone = s.labelRingtones[current]
    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            vm.people.update { st -> st.copy(labelRingtones = if (uri == null) st.labelRingtones - current else st.labelRingtones + (current to uri.toString())) }
        }
    }
    fun pickTone() = tonePicker.launch(
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, res.getString(R.string.lbl_ringtone_for, current))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, tone?.let(Uri::parse)),
    )

    // U7: scroll-linked top-bar tint.
    val barTint = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        TopAppBar(
            scrollBehavior = barTint,
            title = { Text(current) },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } },
            actions = {
                IconButton({
                    val numbers = members.mapNotNull { c -> (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull { it.type == 2 } ?: c.phones.firstOrNull())?.number }
                    if (numbers.isEmpty()) vm.toast(res.getString(R.string.lbl_no_numbers))
                    else runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";") { Uri.encode(it) }))) }
                        .onFailure { vm.toast(res.getString(R.string.lbl_no_sms_app)) }
                }) { Icon(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.lbl_message_all)) }
                IconButton({
                    val emails = members.mapNotNull { it.emails.firstOrNull() }
                    if (emails.isEmpty()) vm.toast(res.getString(R.string.lbl_no_emails))
                    else runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + emails.joinToString(",") { Uri.encode(it, "@") }))) }
                        .onFailure { vm.toast(res.getString(R.string.lbl_no_email_app)) }
                }) { Icon(Icons.Rounded.Email, stringResource(R.string.lbl_email_all)) }
                IconButton(::pickTone) { Icon(Icons.Rounded.MusicNote, stringResource(R.string.lbl_ringtone)) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.dc_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        // Screening rules for everyone in this label (block, only-they-ring at night, ringtone).
                        app.parley.ui.blocking.LabelBlockingMenuItem(current) { menu = false }
                        DropdownMenuItem({ Text(stringResource(R.string.lbl_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem({ Text(stringResource(R.string.lbl_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                val name = tone?.let { u -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(u))?.getTitle(context) }.getOrNull() }
                ListItem(
                    modifier = Modifier.clickable(onClick = ::pickTone),
                    leadingContent = { Icon(Icons.Rounded.MusicNote, null) },
                    headlineContent = { Text(name ?: if (tone != null) stringResource(R.string.lbl_custom_ringtone) else stringResource(R.string.lbl_default_ringtone)) },
                    supportingContent = { Text(stringResource(R.string.lbl_ringtone_summary)) },
                    trailingContent = { if (tone != null) TextButton({ vm.people.update { it.copy(labelRingtones = it.labelRingtones - current) } }) { Text(stringResource(R.string.lbl_reset)) } },
                )
            }
            item { Section(pluralStringResource(R.plurals.lbl_n_contacts, members.size, members.size)) }
            if (members.isEmpty()) item {
                Text(stringResource(R.string.lbl_nobody), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(members, key = { it.id }) { c ->
                var rowMenu by remember { mutableStateOf(false) }
                Box {
                    ContactRow(c, onLongClick = { rowMenu = true }) { open(Routes.contact(c.id)) }
                    DropdownMenu(rowMenu, { rowMenu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.lbl_remove_from, current)) }, onClick = {
                            rowMenu = false
                            scope.launch { vm.c.people.labels.removeMembers(current, listOf(c.id)); vm.c.contacts.refresh() }
                        })
                    }
                }
            }
        }
    }
    if (renaming) RenameLabelDialog(vm, current, onDismiss = { renaming = false }) { current = it }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.lbl_delete_title, current)) },
            text = { Text(pluralStringResource(R.plurals.lbl_delete_text_n, members.size, members.size)) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    scope.launch {
                        // Its ringtone, rules and limits go with it; a notice says when off hours had to change.
                        runCatching { vm.c.people.labels.delete(current) }.getOrNull()?.let { vm.toast(it) }
                        vm.c.contacts.refresh()
                        back()
                    }
                }) { Text(stringResource(R.string.dc_delete)) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
}
