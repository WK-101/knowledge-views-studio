package app.parley.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Timer
import androidx.fragment.app.FragmentActivity
import app.parley.common.ContactSummary
import app.parley.common.people.BulkAction
import app.parley.common.people.BulkActions
import app.parley.data.AccountRef
import app.parley.security.launchVault
import app.parley.ui.startOrSay
import app.parley.ui.vault.ExpiryDialog
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.jobs.UserErrorText
import app.parley.jobs.UserJobs
import app.parley.common.ux.BackupNudge
import app.parley.data.GroupInfo
import app.parley.messaging.IntroduceStart
import app.parley.ui.backup.rememberBackupFirst
import app.parley.ui.contact.madeVisibleText
import app.parley.ui.contact.makeVisibleBody
import app.parley.ui.people.CopyAsTextMenuItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.ParleyDialog
import app.parley.ui.ConfirmDialog
import androidx.compose.material.icons.automirrored.rounded.MergeType

/** Top bar shown while contacts are selected: bulk actions. */
@Composable
fun SelectionBar(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val all by vm.people.filtered.collectAsStateWithLifecycle()
    val chosen = all.orEmpty().filter { it.id in selection }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmPrivate by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var askExpiry by remember { mutableStateOf(false) }
    var labelPicker by remember { mutableStateOf<List<GroupInfo>?>(null) }
    val res = LocalResources.current
    // "Back up first?" before merging or deleting many contacts.
    val backupFirst = rememberBackupFirst(vm)
    // Device and private contacts mixed: most actions work on both, the few that would copy a private contact out of
    // Parley act on the device ones and say how many private ones they left out.
    val bulk = remember(vm) { BulkContactActions(vm.c) }
    val ids = chosen.map { it.id }
    fun targets(a: BulkAction): List<ContactSummary> = BulkActions.targets(a, ids).ids.toSet().let { t -> chosen.filter { it.id in t } }
    fun noteSkipped(a: BulkAction) {
        val n = BulkActions.targets(a, ids).skippedPrivate
        if (n > 0) vm.toast(res.getQuantityString(R.plurals.sel_private_skipped, n, n))
    }
    val hasPrivate = ids.any { BulkActions.isPrivate(it) }
    val hasDevice = ids.any { !BulkActions.isPrivate(it) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        if (uri != null) {
            val chosen = targets(BulkAction.EXPORT)
            noteSkipped(BulkAction.EXPORT)
            // An app job: clearing the selection or leaving the tab doesn't stop the file half way.
            vm.jobs.start(
                UserJobs.Kind.EXPORT, res.getString(R.string.set_exporting),
                { e -> res.getString(R.string.hist_export_failed, UserErrorText.of(context, e)) },
                output = uri.toString(),
            ) { p ->
                val n = vm.c.vcards.export(uri, chosen) { done, total -> p.update(done, total) }.exported
                res.getQuantityString(R.plurals.sel_exported, n, n)
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        // Same room for the status bar and cutout as the header it replaces.
        @OptIn(ExperimentalMaterial3Api::class)
        val insets = TopAppBarDefaults.windowInsets
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(insets).heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ vm.selection.value = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.sel_clear)) }
            Text(
                pluralStringResource(R.plurals.sel_count, selection.size, selection.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton({ vm.selection.value = all.orEmpty().map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.home_select_all)) }
            val allStarred = chosen.isNotEmpty() && chosen.all { it.starred }
            IconButton({
                scope.launch { bulk.star(ids, !allStarred) }
            }) { Icon(if (allStarred) Icons.Rounded.Star else Icons.Rounded.StarOutline, stringResource(if (allStarred) R.string.sel_unstar else R.string.sel_star)) }
            // A vCard file is handed to another app: device contacts only.
            IconButton({
                val shared = targets(BulkAction.SHARE)
                if (shared.isNotEmpty()) {
                    val uri = vm.c.contacts.multiVcardUri(shared.map { it.lookupKey }.filter { it.isNotEmpty() })
                    val i = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startOrSay(Intent.createChooser(i, res.getQuantityString(R.plurals.sel_share_title, shared.size, shared.size)))
                }
                noteSkipped(BulkAction.SHARE)
            }) { Icon(Icons.Rounded.Share, stringResource(R.string.main_share)) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more_actions)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.sel_add_to_label)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Label, null) }, onClick = {
                        menu = false
                        // Private contacts join a label as Parley's own membership (one per label title), device ones by account.
                        scope.launch {
                            val groups = withContext(Dispatchers.IO) { vm.c.contacts.groups() }
                            labelPicker = if (hasDevice) groups else groups.distinctBy { it.title.trim() }
                        }
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.sel_message_all)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, onClick = {
                        menu = false
                        val numbers = chosen.mapNotNull { c -> (c.phones.firstOrNull { it.type == 2 } ?: c.phones.firstOrNull())?.number }
                        if (numbers.isEmpty()) vm.toast(res.getString(R.string.sel_no_numbers))
                        else context.startOrSay(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";"))))
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.sel_introduce)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, onClick = {
                        menu = false
                        // One prefilled chat at a time; you press Send yourself.
                        if (!IntroduceStart.fromContacts(vm, chosen)) vm.toast(res.getString(R.string.sel_no_numbers))
                    })
                    // Merging makes one address-book contact: only device contacts, and only two or more of them.
                    if (BulkActions.available(BulkAction.MERGE, ids)) {
                        val merged = targets(BulkAction.MERGE)
                        DropdownMenuItem({ Text(stringResource(R.string.sel_merge)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MergeType, null) }, onClick = {
                            menu = false
                            backupFirst.ask(merged.size, 1) {
                                scope.launch {
                                    vm.c.contacts.join(merged.map { it.id })
                                    vm.selection.value = emptySet()
                                    vm.toast(res.getQuantityString(R.plurals.sel_merged, merged.size, merged.size))
                                    noteSkipped(BulkAction.MERGE)
                                }
                            }
                        })
                    }
                    // The clipboard and a .vcf file are readable by other apps: device contacts only.
                    if (hasDevice) CopyAsTextMenuItem(targets(BulkAction.COPY_AS_TEXT)) { menu = false; noteSkipped(BulkAction.COPY_AS_TEXT) }
                    if (hasDevice) DropdownMenuItem(
                        { Text(stringResource(R.string.sel_export_vcf)) }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                        onClick = {
                            menu = false
                            exporter.launch("contacts-${targets(BulkAction.EXPORT).size}.vcf")
                        },
                    )
                    DropdownMenuItem(
                        { Text(stringResource(R.string.contact_make_temporary)) }, leadingIcon = { Icon(Icons.Rounded.Timer, null) },
                        onClick = { menu = false; askExpiry = true },
                    )
                    if (hasDevice) {
                        DropdownMenuItem(
                            { Text(stringResource(R.string.sel_move_private)) },
                            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                            onClick = { menu = false; confirmPrivate = true },
                        )
                    }
                    if (hasPrivate) {
                        DropdownMenuItem(
                            { Text(stringResource(R.string.contact_make_visible)) }, leadingIcon = { Icon(Icons.Rounded.LockOpen, null) },
                            onClick = { menu = false; confirmVisible = true },
                        )
                    }
                    DropdownMenuItem(
                        { Text(stringResource(R.string.main_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmPrivate) {
        // The private ones already are: only the device contacts move.
        val moving = BulkActions.targets(BulkAction.MAKE_PRIVATE, ids).ids
        MoveToPrivateDialog(vm, moving, chosen.associate { it.id to it.displayName }, onDismiss = { confirmPrivate = false })
    }
    if (confirmVisible) {
        val visible = BulkActions.targets(BulkAction.MAKE_VISIBLE, ids).ids
        ConfirmDialog(
            title = pluralStringResource(R.plurals.sel_make_visible_title, visible.size, visible.size),
            text = makeVisibleBody(vm.c.contacts),
            confirmLabel = stringResource(R.string.contact_make_visible_confirm),
            onConfirm = {
                confirmVisible = false
                val s = vm.settings.value
                // Asks for the vault's unlock first when needed; nothing changes before it succeeds.
                scope.launchVault(context as? FragmentActivity, { vm.toast(res.getString(R.string.vault_move_failed, UserErrorText.of(context, it))) }) {
                    val made = bulk.makeVisible(visible, AccountRef(s.defaultAccountType, s.defaultAccountName))
                    vm.selection.value = emptySet()
                    vm.toast(madeVisibleText(res, made.made, made.redirectedTo))
                }
            },
            onDismiss = { confirmVisible = false },
            dismissLabel = stringResource(R.string.main_cancel),
        )
    }
    if (askExpiry) {
        ExpiryDialog(onDismiss = { askExpiry = false }) { days ->
            askExpiry = false
            scope.launch {
                bulk.setExpiry(ids, days)
                vm.selection.value = emptySet()
                vm.toast(if (days == null) res.getString(R.string.detail_kept) else res.getQuantityString(R.plurals.detail_deletes_in_days, days, days))
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.sel_delete_title, chosen.size, chosen.size),
            text = stringResource(R.string.sel_delete_body),
            confirmLabel = stringResource(R.string.main_delete),
            onConfirm = {
                confirmDelete = false
                val ids = chosen.map { it.id }
                backupFirst.ask(ids.size, BackupNudge.LARGE_DELETE) {
                    vm.deleteContacts(ids)
                    vm.selection.value = emptySet()
                }
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
            dismissLabel = stringResource(R.string.main_cancel),
        )
    }
    labelPicker?.let { groups ->
        ParleyDialog(
            onDismissRequest = { labelPicker = null },
            title = { Text(stringResource(R.string.sel_add_to_label)) },
            text = {
                Column {
                    if (groups.isEmpty()) Text(stringResource(R.string.sel_no_labels))
                    groups.forEach { g ->
                        ListItem(
                            headlineContent = { Text(g.title) },
                            supportingContent = { Text(g.account.displayLabel) },
                            modifier = Modifier.clickable {
                                labelPicker = null
                                scope.launch {
                                    // Private contacts are never shared: a shared label refuses them, and says why.
                                    vm.c.sharedLabels.load()
                                    val refused = vm.c.sharedLabels.refusedPrivate(g.title, ids)
                                    val skipped = bulk.addToLabel(ids - refused, g)
                                    vm.toast(
                                        when {
                                            refused.isNotEmpty() -> res.getQuantityString(R.plurals.shl_private_refused, refused.size, refused.size)
                                            skipped == 0 -> res.getString(R.string.sel_added_to, g.title)
                                            else -> res.getQuantityString(R.plurals.sel_added_skipped, skipped, skipped)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ labelPicker = null }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
}
