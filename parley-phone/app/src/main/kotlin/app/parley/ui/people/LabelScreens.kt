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

/** Settings-like screen listing every label: open, create, rename, delete and merge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageLabelsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
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
            title = { Text(if (merging) "${picked.size} selected" else "Labels") },
            navigationIcon = {
                if (merging) IconButton({ merging = false; picked = emptySet() }) { Icon(Icons.Rounded.Close, "Stop merging") }
                else IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            },
            actions = {
                if (merging) {
                    TextButton({ mergeTarget = true }, enabled = picked.size >= 2) { Text("Merge") }
                } else {
                    IconButton({ creating = true }) { Icon(Icons.Rounded.Add, "New label") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Merge labels") }, leadingIcon = { Icon(Icons.Rounded.CallMerge, null) }, onClick = { menu = false; merging = true })
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
                    "Choose the labels to combine. Everyone in them ends up in one label; no contact is removed.",
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
                    headlineContent = { Text("Unlabelled") },
                    supportingContent = { Text("$unlabelled contacts without a label") },
                )
            }
            if (list.isEmpty()) item {
                EmptyState(Icons.AutoMirrored.Rounded.Label, "No labels yet", "Labels group contacts (Family, Work…). They sync with your Google or CardDAV account.", Modifier.padding(top = 32.dp))
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
                    supportingContent = { Text("${idx.labelCounts[l.title] ?: 0} contacts · " + l.accounts.joinToString { it.displayLabel }) },
                    trailingContent = if (merging) null else ({
                        Box {
                            IconButton({ rowMenu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${l.title}") }
                            DropdownMenu(rowMenu, { rowMenu = false }) {
                                DropdownMenuItem({ Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { rowMenu = false; renaming = l.title })
                                DropdownMenuItem({ Text("Delete label") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { rowMenu = false; deleting = l.title })
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
            title = { Text("Merge into") },
            text = {
                Column {
                    picked.sorted().forEach { t ->
                        ListItem(
                            modifier = Modifier.clickable { target = t },
                            leadingContent = { RadioButton(target == t, { target = t }) },
                            headlineContent = { Text(t) },
                            supportingContent = { Text("${idx.labelCounts[t] ?: 0} contacts") },
                        )
                    }
                    Text("The other labels are removed after their contacts join “$target”.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton({
                    mergeTarget = false
                    scope.launch {
                        val n = runCatching { vm.c.people.labels.merge(picked, target) }.getOrElse { vm.toast("Couldn't merge: ${it.message}"); return@launch }
                        vm.toast("Merged into $target" + if (n > 0) " · $n added" else "")
                        merging = false
                        picked = emptySet()
                        vm.c.contacts.refresh()
                        round++
                    }
                }) { Text("Merge") }
            },
            dismissButton = { TextButton({ mergeTarget = false }) { Text("Cancel") } },
        )
    }
    if (creating) CreateLabelDialog(vm, onDismiss = { creating = false }) { round++ }
    renaming?.let { old -> RenameLabelDialog(vm, old, onDismiss = { renaming = null }) { round++ } }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “$t”?") },
            text = { Text("The label is removed from every account. The contacts in it are kept.") },
            confirmButton = {
                TextButton({
                    deleting = null
                    scope.launch {
                        runCatching { vm.c.people.labels.delete(t) }.getOrNull()?.let { vm.toast(it) }
                        vm.c.contacts.refresh()
                        round++
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CreateLabelDialog(vm: AppViewModel, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
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
        title = { Text("New label") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Saved in", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
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
                    if (vm.c.people.labels.create(name, a) != null) { vm.toast("Label “${name.trim()}” created"); onCreated() } else vm.toast("Couldn't create the label")
                }
            }, enabled = name.isNotBlank() && account != null) { Text("Create") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameLabelDialog(vm: AppViewModel, old: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(old) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename label") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true)
                Text("If another label already has this name, the two are merged.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton({
                onDismiss()
                scope.launch {
                    // Its ringtone, rules, limits and off-hours choice follow the label (see LabelReferences).
                    runCatching { vm.c.people.labels.rename(old, name) }.onFailure { vm.toast("Couldn't rename: ${it.message}") }
                    vm.c.contacts.refresh()
                    onDone(name.trim())
                }
            }, enabled = name.isNotBlank() && name.trim() != old) { Text("Rename") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** One label: its members and group actions (message all, e-mail all, ringtone, blocking). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelScreen(vm: AppViewModel, title: String, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
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
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Ringtone for $current")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, tone?.let(Uri::parse)),
    )

    // U7: scroll-linked top-bar tint.
    val barTint = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        TopAppBar(
            scrollBehavior = barTint,
            title = { Text(current) },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                IconButton({
                    val numbers = members.mapNotNull { c -> (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull { it.type == 2 } ?: c.phones.firstOrNull())?.number }
                    if (numbers.isEmpty()) vm.toast("No phone numbers in this label")
                    else runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";") { Uri.encode(it) }))) }
                        .onFailure { vm.toast("No messaging app available") }
                }) { Icon(Icons.AutoMirrored.Rounded.Message, "Message all") }
                IconButton({
                    val emails = members.mapNotNull { it.emails.firstOrNull() }
                    if (emails.isEmpty()) vm.toast("No e-mail addresses in this label")
                    else runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + emails.joinToString(",") { Uri.encode(it, "@") }))) }
                        .onFailure { vm.toast("No e-mail app available") }
                }) { Icon(Icons.Rounded.Email, "Email all") }
                IconButton(::pickTone) { Icon(Icons.Rounded.MusicNote, "Ringtone for this label") }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        // Screening rules for everyone in this label (block, only-they-ring at night, ringtone).
                        app.parley.ui.blocking.LabelBlockingMenuItem(current) { menu = false }
                        DropdownMenuItem({ Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem({ Text("Delete label") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
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
                    headlineContent = { Text(name ?: if (tone != null) "Custom ringtone" else "Default ringtone") },
                    supportingContent = { Text("Plays for people in this label, unless they have their own ringtone") },
                    trailingContent = { if (tone != null) TextButton({ vm.people.update { it.copy(labelRingtones = it.labelRingtones - current) } }) { Text("Reset") } },
                )
            }
            item { Section("${members.size} contacts") }
            if (members.isEmpty()) item {
                Text("Nobody has this label yet. Select contacts in the Contacts tab and choose “Add to label”.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(members, key = { it.id }) { c ->
                var rowMenu by remember { mutableStateOf(false) }
                Box {
                    ContactRow(c, onLongClick = { rowMenu = true }) { open(Routes.contact(c.id)) }
                    DropdownMenu(rowMenu, { rowMenu = false }) {
                        DropdownMenuItem({ Text("Remove from $current") }, onClick = {
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
            title = { Text("Delete “$current”?") },
            text = { Text("The label is removed from every account. The ${members.size} contacts in it are kept.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    scope.launch {
                        // Its ringtone, rules and limits go with it; a notice says when off hours had to change.
                        runCatching { vm.c.people.labels.delete(current) }.getOrNull()?.let { vm.toast(it) }
                        vm.c.contacts.refresh()
                        back()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
