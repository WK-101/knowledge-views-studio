package app.parley.ui.people

import app.parley.ui.Destination
import app.parley.common.catching
import app.parley.jobs.UserErrorText
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
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.LabelOff
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.StartTab
import app.parley.data.AccountRef
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.common.AccountRefSaver
import app.parley.ui.common.StringSetSaver
import app.parley.data.people.Label
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.blocking.LabelBlockingMenuItem
import app.parley.ui.contact.CallerTuneRow
import app.parley.ui.contact.CallerTunes
import app.parley.ui.extras.LabelPolicySection
import app.parley.ui.family.SafeWordSection
import app.parley.ui.startOrSay
import app.parley.ui.sync.shared.SharedLabelRoutes
import app.parley.ui.sync.shared.SharedLabelSection
import app.parley.ui.home.ContactRow
import app.parley.ui.people.chapters.ChapterSection
import app.parley.ui.people.chapters.chapterLeft
import app.parley.data.vault.VaultCrypto
import app.parley.security.AppLock
import androidx.activity.ComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.ux.Tips
import app.parley.ui.common.tipPending
import app.parley.ui.common.CoachMark
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.BackButton
import app.parley.ui.ConfirmDialog

/** Settings-like screen listing every label: open, create, rename, delete and merge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageLabelsScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    val idx by vm.people.index.collectAsStateWithLifecycle()
    // Private contacts too: their labels are in the index (kept by Parley, never in the address book).
    val all by vm.everyone.collectAsStateWithLifecycle()
    var labels by remember { mutableStateOf<List<Label>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(all, round) { labels = vm.c.people.labels.labels() }
    var merging by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf<Set<String>>(emptySet()) }
    var mergeTarget by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val chapters by vm.c.extras.chapters.collectAsStateWithLifecycle()
    val now = remember(chapters) { System.currentTimeMillis() }

    // Scroll-linked top-bar tint.
    val barTint = TopAppBarDefaults.pinnedScrollBehavior()
    ParleyScaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        ParleyTopBar(
            scrollBehavior = barTint,
            title = { Text(if (merging) pluralStringResource(R.plurals.lbl_selected, picked.size, picked.size) else stringResource(R.string.lbl_title)) },
            navigationIcon = {
                if (merging) IconButton({ merging = false; picked = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.lbl_stop_merging)) }
                else BackButton(back)
            },
            actions = {
                if (merging) {
                    TextButton({ mergeTarget = true }, enabled = picked.size >= 2) { Text(stringResource(R.string.lbl_merge)) }
                } else {
                    IconButton({ creating = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.lbl_new)) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.dc_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.lbl_merge_labels)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MergeType, null) },
                                onClick = { menu = false; merging = true },
                            )
                        }
                    }
                }
            },
        )
    }) { p ->
        val list = labels ?: return@ParleyScaffold
        val unlabelled = all.orEmpty().count { idx.extras[it.id]?.labels.isNullOrEmpty() }
        val labelsTip = tipPending(Tips.CONCEPT_LABELS)
        LazyColumn(Modifier.padding(p)) {
            // P18: what labels are, the first time this screen lists some.
            if (!merging && list.isNotEmpty() && labelsTip) item(key = "tip") {
                CoachMark(Tips.CONCEPT_LABELS, stringResource(R.string.tip_concept_labels))
            }
            if (merging) item {
                Text(
                    stringResource(R.string.lbl_merge_intro),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!merging) item {
                ParleyListItem(
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
                    Icons.AutoMirrored.Rounded.Label,
                    stringResource(R.string.lbl_empty_title),
                    stringResource(R.string.lbl_empty_text),
                    Modifier.padding(top = 32.dp),
                    action = stringResource(R.string.lbl_new), onAction = { creating = true },
                )
            }
            items(list, key = { it.title }) { l ->
                var rowMenu by remember { mutableStateOf(false) }
                ParleyListItem(
                    modifier = Modifier.clickable {
                        if (merging) picked = if (l.title in picked) picked - l.title else picked + l.title else open(PeopleRoutes.label(l.title))
                    },
                    leadingContent = {
                        if (merging) Checkbox(l.title in picked, { picked = if (it) picked + l.title else picked - l.title })
                        else Icon(Icons.AutoMirrored.Rounded.Label, null)
                    },
                    headlineContent = { Text(l.title) },
                    supportingContent = {
                        (idx.labelCounts[l.title] ?: 0).let { n ->
                            val count = pluralStringResource(R.plurals.lbl_count_accounts, n, n, l.accounts.joinToString { it.displayLabel })
                            // A chapter says what is left of it beside the count.
                            val left = chapters[l.title]?.let { chapterLeft(res, it, now) }
                            Text(if (left == null) count else stringResource(R.string.chapter_in_list, count, left))
                        }
                    },
                    trailingContent = if (merging) null else ({
                        Box {
                            IconButton({ rowMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.lbl_more_for, l.title)) }
                            DropdownMenu(rowMenu, { rowMenu = false }) {
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.lbl_rename)) },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { rowMenu = false; renaming = l.title },
                                )
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.lbl_delete)) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { rowMenu = false; deleting = l.title },
                                )
                            }
                        }
                    }),
                )
            }
        }
    }

    if (mergeTarget) {
        var target by rememberSaveable { mutableStateOf(picked.first()) }
        ConfirmDialog(
            title = stringResource(R.string.lbl_merge_into),
            text = null,
            confirmLabel = stringResource(R.string.lbl_merge),
            onConfirm = {
                mergeTarget = false
                scope.launch {
                    val n = catching { vm.c.people.labels.merge(picked, target) }.getOrElse {
                        vm.toast(res.getString(R.string.lbl_merge_failed, UserErrorText.of(context, it)))
                        return@launch
                    }
                    vm.toast(if (n > 0) res.getQuantityString(R.plurals.lbl_merged_added, n, n, target) else res.getString(R.string.lbl_merged, target))
                    merging = false
                    picked = emptySet()
                    vm.c.contacts.refresh()
                    round++
                }
            },
            onDismiss = { mergeTarget = false },
            dismissLabel = stringResource(R.string.dc_cancel),
            content = {
                Column {
                    picked.sorted().forEach { t ->
                        ParleyListItem(
                            modifier = Modifier.clickable { target = t },
                            leadingContent = { RadioButton(target == t, { target = t }) },
                            headlineContent = { Text(t) },
                            supportingContent = { (idx.labelCounts[t] ?: 0).let { n -> Text(pluralStringResource(R.plurals.lbl_n_contacts, n, n)) } },
                        )
                    }
                    Text(stringResource(R.string.lbl_merge_note, target), style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
    if (creating) CreateLabelDialog(vm, onDismiss = { creating = false }) { round++ }
    renaming?.let { old -> RenameLabelDialog(vm, old, onDismiss = { renaming = null }) { round++ } }
    deleting?.let { t ->
        ConfirmDialog(
            title = stringResource(R.string.lbl_delete_title, t),
            text = stringResource(R.string.lbl_delete_text),
            confirmLabel = stringResource(R.string.dc_delete),
            onConfirm = {
                deleting = null
                scope.launch {
                    deleteLabelWithUndo(vm, t)
                    round++
                }
            },
            onDismiss = { deleting = null },
            destructive = true,
            dismissLabel = stringResource(R.string.dc_cancel),
        )
    }
}

@Composable
private fun CreateLabelDialog(vm: AppViewModel, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    var name by rememberSaveable { mutableStateOf("") }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var account by rememberSaveable(stateSaver = AccountRefSaver) { mutableStateOf<AccountRef?>(null) }
    val idx by vm.people.index.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() }
        val s = vm.settings.value
        if (account == null) account = accounts.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName } ?: accounts.firstOrNull { it.type == "com.google" } ?: accounts.firstOrNull()
    }
    ConfirmDialog(
        title = stringResource(R.string.lbl_new),
        text = null,
        confirmLabel = stringResource(R.string.lbl_create),
        onConfirm = {
            val a = account ?: return@ConfirmDialog
            onDismiss()
            scope.launch {
                if (vm.c.people.labels.create(name, a) != null) { vm.toast(res.getString(R.string.lbl_created, name.trim())); onCreated() } else vm.toast(
                    res.getString(R.string.lbl_create_failed),
                )
            }
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        confirmEnabled = name.isNotBlank() && account != null,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.lbl_name)) }, singleLine = true)
                Text(stringResource(R.string.lbl_saved_in), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                accounts.forEach { a ->
                    ParleyListItem(
                        modifier = Modifier.clickable { account = a },
                        leadingContent = { RadioButton(account == a, { account = a }) },
                        headlineContent = { Text(idx.labelWithCount(a)) },
                    )
                }
            }
        },
    )
}

@Composable
private fun RenameLabelDialog(vm: AppViewModel, old: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    var name by rememberSaveable { mutableStateOf(old) }
    ConfirmDialog(
        title = stringResource(R.string.lbl_rename_title),
        text = null,
        confirmLabel = stringResource(R.string.lbl_rename),
        onConfirm = {
            onDismiss()
            scope.launch {
                // M4: renaming onto another label's name merges the two; while either is shared, that would share
                // every contact of the merged label, so it is refused.
                if (vm.c.sharedLabels.renameWouldMerge(old, name)) {
                    vm.toast(res.getString(R.string.shl_rename_would_merge, name.trim()))
                    return@launch
                }
                // Its ringtone, rules, limits and off-hours choice follow the label (see LabelReferences).
                catching { vm.c.people.labels.rename(old, name) }
                    // A shared label follows its rename on this phone (the others keep their own label's name).
                    .onSuccess { vm.c.sharedLabels.renamed(old, name.trim()) }
                    .onFailure { vm.toast(res.getString(R.string.lbl_rename_failed, UserErrorText.of(context, it))) }
                vm.c.contacts.refresh()
                onDone(name.trim())
            }
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        confirmEnabled = name.isNotBlank() && name.trim() != old,
        content = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true)
                Text(stringResource(R.string.lbl_rename_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
    )
}

/** One label: its members and group actions (message all, e-mail all, ringtone, blocking). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelScreen(vm: AppViewModel, title: String, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var current by rememberSaveable { mutableStateOf(title) }
    val idx by vm.people.index.collectAsStateWithLifecycle()
    // Everyone Parley lists, private contacts among them (with their lock badge).
    val all by vm.everyone.collectAsStateWithLifecycle()
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val members = all.orEmpty().filter { current in idx.extras[it.id]?.labels.orEmpty() }

    /**
     * Emails everyone in the label. A private contact's emails are in its sealed details: the vault's own unlock is
     * asked for first when it is locked ([unlocked] after it succeeded), and a contact that still can't be read is left out.
     */
    fun emailAll(unlocked: Boolean = false) {
        scope.launch {
            val private = members.filter { it.id < 0 }
            val privateEmails = ArrayList<String>()
            var locked = false
            for (m in private) {
                val d = runCatching { vm.c.vault.details(-m.id) }
                if (d.exceptionOrNull() is VaultCrypto.LockedException) {
                    locked = true
                    break
                }
                d.getOrNull()?.emails?.firstOrNull { it.value.isNotBlank() }?.value?.let { privateEmails += it }
            }
            val activity = context as? ComponentActivity
            if (locked && !unlocked && activity != null) {
                AppLock.authenticateForVault(activity) { ok -> if (ok) emailAll(unlocked = true) }
                return@launch
            }
            val emails = members.filter { it.id > 0 }.mapNotNull { it.emails.firstOrNull() } + privateEmails
            if (emails.isEmpty()) vm.toast(res.getString(R.string.lbl_no_emails))
            else if (!context.startOrSay(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + emails.joinToString(",") { Uri.encode(it, "@") })))) {
                vm.toast(res.getString(R.string.lbl_no_email_app))
            }
        }
    }
    var menu by remember { mutableStateOf(false) }
    val sharedStates by vm.c.sharedLabels.states.collectAsStateWithLifecycle()
    val sharedTitles = sharedStates.map { it.title }
    LaunchedEffect(Unit) { vm.c.sharedLabels.load() }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val tone = s.labelRingtones[current]
    // A tune made from a name that was replaced or reset here goes once nothing else uses it (its grants with it).
    var shownTone by remember(current) { mutableStateOf(tone) }
    LaunchedEffect(tone) {
        val before = shownTone
        shownTone = tone
        if (before != tone && CallerTunes.isOurs(context, before)) CallerTunes.sweep(vm.c)
    }
    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            vm.people.update { st ->
                st.copy(labelRingtones = if (uri == null) st.labelRingtones - current else st.labelRingtones + (current to uri.toString()))
            }
        }
    }
    fun pickTone() = tonePicker.launch(
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, res.getString(R.string.lbl_ringtone_for, current))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, tone?.let(Uri::parse)),
    )

    // Scroll-linked top-bar tint.
    val barTint = TopAppBarDefaults.pinnedScrollBehavior()
    ParleyScaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        ParleyTopBar(
            current,
            onBack = back,
            actions = {
                IconButton({
                    val numbers = members.mapNotNull { c ->
                        (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull { it.type == 2 } ?: c.phones.firstOrNull())?.number
                    }
                    if (numbers.isEmpty()) vm.toast(res.getString(R.string.lbl_no_numbers))
                    else if (!context.startOrSay(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";") { Uri.encode(it) })))) {
                        vm.toast(res.getString(R.string.lbl_no_sms_app))
                    }
                }) { Icon(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.lbl_message_all)) }
                IconButton({ emailAll() }) { Icon(Icons.Rounded.Email, stringResource(R.string.lbl_email_all)) }
                IconButton(::pickTone) { Icon(Icons.Rounded.MusicNote, stringResource(R.string.lbl_ringtone)) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.dc_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        // Screening rules for everyone in this label (block, only-they-ring at night, ringtone).
                        LabelBlockingMenuItem(current) { menu = false }
                        // A family phonebook: this label kept the same on other people's phones.
                        if (sharedTitles.none { it == current }) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.shl_share_menu)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                onClick = { menu = false; open(SharedLabelRoutes.Share(current)) },
                            )
                        }
                        DropdownMenuItem(
                            { Text(stringResource(R.string.lbl_rename)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.lbl_delete)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            },
            scrollBehavior = barTint,
        )
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            // A chapter: an end for a period of life, and the one question when it comes.
            item {
                ChapterSection(vm, current, members) {
                    scope.launch {
                        deleteLabelWithUndo(vm, current)
                        back()
                    }
                }
            }
            item {
                val name = tone?.let { u ->
                    if (CallerTunes.isOurs(context, u)) stringResource(R.string.caller_tune_made_for, current)
                    else runCatching { RingtoneManager.getRingtone(context, Uri.parse(u))?.getTitle(context) }.getOrNull()
                }
                ParleyListItem(
                    modifier = Modifier.clickable(onClick = ::pickTone),
                    leadingContent = { Icon(Icons.Rounded.MusicNote, null) },
                    headlineContent = {
                        Text(name ?: if (tone != null) stringResource(R.string.lbl_custom_ringtone) else stringResource(R.string.lbl_default_ringtone))
                    },
                    supportingContent = { Text(stringResource(R.string.lbl_ringtone_summary)) },
                    trailingContent = {
                        if (tone != null) TextButton({ vm.people.update { it.copy(labelRingtones = it.labelRingtones - current) } }) {
                            Text(stringResource(R.string.lbl_reset))
                        }
                    },
                )
            }
            // Sonic caller ID for the label: Parley's ringer plays label ringtones, so the tune is read from its own files.
            item {
                CallerTuneRow(current, stringResource(R.string.caller_tune_label_summary)) { uri ->
                    vm.people.update { it.copy(labelRingtones = it.labelRingtones + (current to uri.toString())) }
                    vm.toast(res.getString(R.string.caller_tune_set, current))
                }
            }
            // SIM, Circle rhythm and Do Not Disturb for this label.
            item { LabelPolicySection(vm, current, members) }
            // I4: the label's safe word (asks who it is before showing or changing it).
            item { SafeWordSection(vm, current) }
            // Shared with other people's phones: status, members and who changed what.
            item { SharedLabelSection(vm, current, open) }
            item { Section(pluralStringResource(R.plurals.lbl_n_contacts, members.size, members.size)) }
            if (members.isEmpty()) item {
                Text(stringResource(R.string.lbl_nobody), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(members, key = { it.id }) { c ->
                var rowMenu by remember { mutableStateOf(false) }
                // No selection here: the row's actions are its trailing ⋮, never a long-press.
                ContactRow(c, menu = {
                    Box {
                        IconButton({ rowMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more_actions)) }
                        DropdownMenu(rowMenu, { rowMenu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.lbl_remove_from, current)) }, onClick = {
                                rowMenu = false
                                scope.launch { vm.c.people.labels.removeMembers(current, listOf(c.id)); vm.c.contacts.refresh() }
                            })
                        }
                    }
                }) { open(Routes.contact(c.id)) }
            }
        }
    }
    if (renaming) RenameLabelDialog(vm, current, onDismiss = { renaming = false }) { current = it }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.lbl_delete_title, current),
            text = pluralStringResource(R.plurals.lbl_delete_text_n, members.size, members.size),
            confirmLabel = stringResource(R.string.dc_delete),
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    deleteLabelWithUndo(vm, current)
                    back()
                }
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
            dismissLabel = stringResource(R.string.dc_cancel),
        )
    }
}

/**
 * Deletes a label everywhere, with Undo: its ringtone, SIM, rhythm, safe word, rules and limits go with it and come
 * back with its members. When off hours had to change, the Undo message says so instead.
 */
private suspend fun deleteLabelWithUndo(vm: AppViewModel, title: String) {
    val res = vm.getApplication<android.app.Application>().resources
    val (said, deleted) = catching { vm.c.people.labels.deleteForUndo(title) }.getOrDefault(null to null)
    vm.c.contacts.refresh()
    when {
        deleted != null -> vm.offerUndo(said ?: res.getString(R.string.lbl_deleted, title)) {
            vm.c.people.labels.restore(deleted)
            vm.c.contacts.refresh()
        }
        said != null -> vm.toast(said)
    }
}
