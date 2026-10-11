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
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import app.parley.ui.circle.CircleSnack
import app.parley.ui.circle.CircleSnacks
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Timer
import androidx.activity.ComponentActivity
import app.parley.common.ContactSummary
import app.parley.common.people.BulkAction
import app.parley.common.people.BulkActions
import app.parley.data.AccountRef
import app.parley.security.launchVault
import app.parley.ui.ParleyListItem
import app.parley.ui.startOrSay
import app.parley.ui.vault.ExpiryDialog
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.parley.ui.backup.rememberBackupFirst
import app.parley.ui.contact.madeVisibleText
import app.parley.ui.people.archive.ArchiveSelectionDialog
import app.parley.ui.contact.makeVisibleBody
import app.parley.ui.people.copyAsText
import app.parley.common.ux.MenuEntry
import app.parley.common.ux.SelectionMenu
import app.parley.ui.common.MenuGroupSheet
import app.parley.ui.common.MenuItems
import app.parley.ui.common.MenuLabel
import androidx.compose.material.icons.rounded.ContentCopy
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
    var menuGroup by remember { mutableStateOf<MenuEntry.Group<SelectionMenu.Action>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmPrivate by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var askExpiry by remember { mutableStateOf(false) }
    var labelPicker by remember { mutableStateOf<List<GroupInfo>?>(null) }
    var editSheet by remember { mutableStateOf(false) }
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

    // At most seven items: sharing and privacy under Share… and Privacy… (SelectionMenu). Share… is the one Share.
    val menuEntries = SelectionMenu.build(SelectionMenu.Facts(hasDevice, hasPrivate, BulkActions.available(BulkAction.MERGE, ids)))
    fun runMenu(a: SelectionMenu.Action) {
        when (a) {
            // Labels, ringtone, SIM and account for all of them at once (BulkEditSheet, which also adds to a label).
            SelectionMenu.Action.EDIT -> editSheet = true
            SelectionMenu.Action.MESSAGE_ALL -> {
                val numbers = chosen.mapNotNull { c -> (c.phones.firstOrNull { it.type == 2 } ?: c.phones.firstOrNull())?.number }
                if (numbers.isEmpty()) vm.toast(res.getString(R.string.sel_no_numbers))
                else context.startOrSay(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";"))))
            }
            // A vCard file handed to another app, the clipboard and a .vcf file are readable by other apps: device
            // contacts only.
            SelectionMenu.Action.SHARE_FILE -> {
                val shared = targets(BulkAction.SHARE)
                if (shared.isNotEmpty()) {
                    val uri = vm.c.contacts.multiVcardUri(shared.map { it.lookupKey }.filter { it.isNotEmpty() })
                    val i = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startOrSay(Intent.createChooser(i, res.getQuantityString(R.plurals.sel_share_title, shared.size, shared.size)))
                }
                noteSkipped(BulkAction.SHARE)
            }
            SelectionMenu.Action.COPY_AS_TEXT -> {
                copyAsText(context, targets(BulkAction.COPY_AS_TEXT))
                noteSkipped(BulkAction.COPY_AS_TEXT)
            }
            SelectionMenu.Action.EXPORT_VCF -> exporter.launch("contacts-${targets(BulkAction.EXPORT).size}.vcf")
            // Merging makes one address-book contact: only device contacts, and only two or more of them.
            SelectionMenu.Action.MERGE -> {
                val merged = targets(BulkAction.MERGE)
                backupFirst.ask(merged.size, 1) {
                    scope.launch {
                        vm.c.contacts.join(merged.map { it.id })
                        vm.selection.value = emptySet()
                        vm.toast(res.getQuantityString(R.plurals.sel_merged, merged.size, merged.size))
                        noteSkipped(BulkAction.MERGE)
                    }
                }
            }
            SelectionMenu.Action.DELETE_AUTOMATICALLY -> askExpiry = true
            SelectionMenu.Action.MAKE_PRIVATE -> confirmPrivate = true
            SelectionMenu.Action.MAKE_VISIBLE -> confirmVisible = true
            SelectionMenu.Action.ARCHIVE -> confirmArchive = true
            SelectionMenu.Action.DELETE -> confirmDelete = true
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
            IconButton({ vm.selection.value = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.blk_clear_selection)) }
            Text(
                pluralStringResource(R.plurals.sel_count, selection.size, selection.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton({ vm.selection.value = all.orEmpty().map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll,
                stringResource(R.string.watch_select_all)) }
            val allStarred = chosen.isNotEmpty() && chosen.all { it.starred }
            IconButton({
                scope.launch { bulk.star(ids, !allStarred) }
            }) { Icon(if (allStarred) Icons.Rounded.Star else Icons.Rounded.StarOutline, stringResource(if (allStarred) R.string.sel_unstar else R.string.sel_star)) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more_actions)) }
                DropdownMenu(menu, { menu = false }) {
                    MenuItems(menuEntries, { selectionMenuLabel(it) }, close = { menu = false }, onGroup = { menuGroup = it }, onAction = ::runMenu)
                }
            }
        }
    }
    menuGroup?.let { g -> MenuGroupSheet(g, { selectionMenuLabel(it) }, onDismiss = { menuGroup = null }, onAction = ::runMenu) }

    if (editSheet) {
        BulkEditSheet(vm, chosen, onAddToLabel = {
            // Private contacts join a label as Parley's own membership (one per label title), device ones by account.
            scope.launch {
                val groups = withContext(Dispatchers.IO) { vm.c.contacts.groups() }
                labelPicker = if (hasDevice) groups else groups.distinctBy { it.title.trim() }
            }
        }) { editSheet = false }
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
                scope.launchVault(context as? ComponentActivity, { vm.toast(res.getString(R.string.vault_move_failed, UserErrorText.of(context, it))) }) {
                    val made = bulk.makeVisible(visible, AccountRef(s.defaultAccountType, s.defaultAccountName))
                    vm.selection.value = emptySet()
                    vm.toast(madeVisibleText(res, made.made, made.redirectedTo))
                }
            },
            onDismiss = { confirmVisible = false },
            dismissLabel = stringResource(R.string.dc_cancel),
        )
    }
    if (confirmArchive) {
        ArchiveSelectionDialog(vm, ids, onDismiss = { confirmArchive = false }, onArchived = { vm.selection.value = emptySet() })
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
            confirmLabel = stringResource(R.string.blk_delete),
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
            dismissLabel = stringResource(R.string.dc_cancel),
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
                        ParleyListItem(
                            headlineContent = { Text(g.title) },
                            supportingContent = { Text(g.account.displayLabel) },
                            modifier = Modifier.clickable {
                                labelPicker = null
                                scope.launch {
                                    // Private contacts are never shared: a shared label refuses them, and says why.
                                    vm.c.sharedLabels.load()
                                    val refused = vm.c.sharedLabels.refusedPrivate(g.title, ids)
                                    val (skipped, joined) = bulk.joinLabel(ids - refused.toSet(), g)
                                    val text = when {
                                        refused.isNotEmpty() -> res.getQuantityString(R.plurals.shl_private_refused, refused.size, refused.size)
                                        skipped == 0 -> res.getString(R.string.sel_added_to, g.title)
                                        else -> res.getQuantityString(R.plurals.sel_added_skipped, skipped, skipped)
                                    }
                                    // Undo takes out only the ones this added.
                                    CircleSnacks.show(CircleSnack(text, joined.takeIf { it.isNotEmpty() }?.let { j -> { bulk.unjoinLabel(j, g.title) } }))
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ labelPicker = null }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
}

/** The words and icon of a selection ⋮ action. */
@Composable
private fun selectionMenuLabel(a: SelectionMenu.Action): MenuLabel = MenuLabel(
    stringResource(
        when (a) {
            SelectionMenu.Action.EDIT -> R.string.be_menu
            SelectionMenu.Action.MESSAGE_ALL -> R.string.lbl_message_all
            SelectionMenu.Action.SHARE_FILE -> R.string.me_share_file
            SelectionMenu.Action.COPY_AS_TEXT -> R.string.ppl_copy_as_text
            SelectionMenu.Action.EXPORT_VCF -> R.string.sel_export_vcf
            SelectionMenu.Action.MERGE -> R.string.sel_merge
            SelectionMenu.Action.DELETE_AUTOMATICALLY -> R.string.contact_make_temporary
            SelectionMenu.Action.MAKE_PRIVATE -> R.string.sel_move_private
            SelectionMenu.Action.MAKE_VISIBLE -> R.string.contact_make_visible
            SelectionMenu.Action.ARCHIVE -> R.string.archive_action
            SelectionMenu.Action.DELETE -> R.string.blk_delete
        },
    ),
    selectionMenuIcon(a),
)

/** The icon of a selection ⋮ action: never repeated within the menu or one of its sheets (a test holds them to that). */
internal fun selectionMenuIcon(a: SelectionMenu.Action): ImageVector = when (a) {
    SelectionMenu.Action.EDIT -> Icons.Rounded.Edit
    SelectionMenu.Action.MESSAGE_ALL -> Icons.AutoMirrored.Rounded.Message
    SelectionMenu.Action.SHARE_FILE -> Icons.Rounded.Share
    SelectionMenu.Action.COPY_AS_TEXT -> Icons.Rounded.ContentCopy
    SelectionMenu.Action.EXPORT_VCF -> Icons.Rounded.FileDownload
    SelectionMenu.Action.MERGE -> Icons.AutoMirrored.Rounded.MergeType
    SelectionMenu.Action.DELETE_AUTOMATICALLY -> Icons.Rounded.Timer
    SelectionMenu.Action.MAKE_PRIVATE -> Icons.Rounded.Lock
    SelectionMenu.Action.MAKE_VISIBLE -> Icons.Rounded.LockOpen
    SelectionMenu.Action.ARCHIVE -> Icons.Rounded.Archive
    SelectionMenu.Action.DELETE -> Icons.Rounded.Delete
}
