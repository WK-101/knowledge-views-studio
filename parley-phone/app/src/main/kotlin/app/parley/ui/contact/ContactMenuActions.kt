package app.parley.ui.contact

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseMode
import app.parley.common.people.ContactCapability
import app.parley.common.ux.ContactMenu
import app.parley.common.ux.MenuEntry
import app.parley.ui.Routes
import app.parley.ui.blocking.BlockingDialog
import app.parley.ui.blocking.BlockingDialogs
import app.parley.ui.blocking.askToBlock
import app.parley.ui.blocking.unblockWithUndo
import app.parley.ui.common.MenuGroupSheet
import app.parley.ui.common.MenuItems
import app.parley.ui.common.MenuLabel
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * The top bar's actions on a contact's page: star, edit and ⋮. The ⋮ menu has at most seven items, the rarer ones
 * under Share… and More… (ContactMenu); a group opens as its own sheet. How the contact is kept is the page's "Kept
 * as" row.
 */
@Composable
internal fun ContactBarActions(ctx: ContactPageContext, dialog: ContactDialog, blocked: Boolean, onlyEmergency: Boolean) {
    val d = ctx.d
    val contactId = ctx.contactId
    IconButton({ ctx.page.setStarred(!d.starred) }) {
        Icon(if (d.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, stringResource(if (d.starred) R.string.sel_unstar else R.string.sel_star))
    }
    IconButton({ ctx.open(if (ctx.isPrivate) Routes.edit(vault = -contactId) else Routes.edit(id = contactId)) }) {
        Icon(Icons.Rounded.Edit, stringResource(R.string.me_edit_short))
    }
    IconButton({ ctx.show(ContactDialog.Menu) }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.contact_page_sec_more)) }
    val entries = ContactMenu.build(
        ContactMenu.Facts(
            canShareFile = ctx.can(ContactCapability.SHARE_VCARD_FILE),
            canSeeVersions = ctx.can(ContactCapability.VERSION_HISTORY),
            canAddToHomeScreen = ctx.can(ContactCapability.HOME_SCREEN_SHORTCUT),
            hasNumbers = d.phones.isNotEmpty(),
            canCopyToSim = ctx.can(ContactCapability.COPY_TO_SIM),
            linked = d.rawContacts.size > 1,
            // "Log a chat or visit" is the FAB for Circle contacts.
            inCircle = ctx.inCircle,
            blocked = blocked,
            onlyEmergency = onlyEmergency,
            caseShown = ctx.case.shown,
            isCompany = d.composedName.isBlank() && d.company.isNotBlank(),
        ),
    )
    val run: (ContactMenu.Action) -> Unit = { a -> runContactMenu(ctx, a) }
    val label: @Composable (ContactMenu.Action) -> MenuLabel = { contactMenuLabel(it) }
    val close = { if (dialog == ContactDialog.Menu) ctx.show(ContactDialog.None) }
    DropdownMenu(dialog == ContactDialog.Menu, close) {
        MenuItems(entries, label, close = close, onGroup = { ctx.show(ContactDialog.MenuSheet(it.group)) }, onAction = run)
    }
    if (dialog is ContactDialog.MenuSheet) {
        entries.filterIsInstance<MenuEntry.Group<ContactMenu.Action>>().firstOrNull { it.group == dialog.group }?.let { g ->
            MenuGroupSheet(g, label, onDismiss = { ctx.show(ContactDialog.None) }, onAction = run)
        }
    }
}

/** What a ⋮ action does: most open one of the page's dialogs. */
@Suppress("CyclomaticComplexMethod") // One branch per action.
private fun runContactMenu(ctx: ContactPageContext, a: ContactMenu.Action) {
    val d = ctx.d
    when (a) {
        // To call by hand (the same fixed times as Remind me after a call).
        ContactMenu.Action.REMIND_TO_CALL -> ctx.show(ContactDialog.RemindToCall)
        ContactMenu.Action.SHARE_FILE -> ctx.shareFile()
        // A private contact's plain code is shown after saying what the scanner gets.
        ContactMenu.Action.SHOW_QR -> ctx.show(if (ctx.isPrivate) ContactDialog.PrivateQrWarning else ContactDialog.Qr)
        ContactMenu.Action.SHARE_ENCRYPTED_QR -> ctx.show(ContactDialog.SecureQr)
        ContactMenu.Action.VERSION_HISTORY -> ctx.open(Routes.versions(ctx.contactId))
        // The one Block (a question, then Undo); Unblock once any of the numbers is blocked.
        ContactMenu.Action.BLOCK_NUMBERS -> askToBlock(d.phones.map { it.value }, d.displayName)
        ContactMenu.Action.UNBLOCK_NUMBERS -> unblockWithUndo(ctx.vm, d.phones.map { it.value }, d.displayName)
        ContactMenu.Action.LOG_CHAT_OR_VISIT -> ctx.show(ContactDialog.LogInteraction)
        // The case card then shows on the page; the case file fills with the next call.
        ContactMenu.Action.CASE_FILE -> keepCaseFile(ctx)
        ContactMenu.Action.ADD_TO_HOME_SCREEN -> ctx.show(ContactDialog.AddToHomeScreen)
        ContactMenu.Action.COPY_TO_SIM -> ctx.show(ContactDialog.CopyToSim)
        ContactMenu.Action.ALLOW_SIMILAR_NUMBERS ->
            BlockingDialogs.show(BlockingDialog.PrefixAllow(d.composedName.ifBlank { null }, d.phones.map { it.value }))
        ContactMenu.Action.SEPARATE -> ctx.page.separate(ctx.back)
        ContactMenu.Action.DELETE -> ctx.show(ContactDialog.ConfirmDelete)
    }
}

/** The words and icon of a contact page ⋮ action. */
@Composable
private fun contactMenuLabel(a: ContactMenu.Action): MenuLabel = MenuLabel(stringResource(contactMenuText(a)), contactMenuIcon(a))

@Suppress("CyclomaticComplexMethod") // One label per action.
private fun contactMenuText(a: ContactMenu.Action): Int = when (a) {
    ContactMenu.Action.SHARE_FILE -> R.string.me_share_file
    ContactMenu.Action.SHOW_QR -> R.string.detail_show_qr
    ContactMenu.Action.SHARE_ENCRYPTED_QR -> R.string.detail_share_private
    ContactMenu.Action.VERSION_HISTORY -> R.string.tm_history_title
    ContactMenu.Action.REMIND_TO_CALL -> R.string.to_call_remind_me_to_call
    ContactMenu.Action.BLOCK_NUMBERS -> R.string.detail_block_numbers
    ContactMenu.Action.UNBLOCK_NUMBERS -> R.string.detail_unblock_numbers
    ContactMenu.Action.LOG_CHAT_OR_VISIT -> R.string.circle_log_interaction
    ContactMenu.Action.CASE_FILE -> R.string.case_keep
    ContactMenu.Action.ADD_TO_HOME_SCREEN -> R.string.detail_add_home
    ContactMenu.Action.COPY_TO_SIM -> R.string.sim_copy_title
    ContactMenu.Action.ALLOW_SIMILAR_NUMBERS -> R.string.blk_prefix_title
    ContactMenu.Action.SEPARATE -> R.string.detail_separate
    ContactMenu.Action.DELETE -> R.string.blk_delete
}

/**
 * The icon of a contact page ⋮ action: one per action, never repeated within the menu or one of its sheets (a test
 * holds them to that). The encrypted code is a key, not a padlock: the padlock means a private contact.
 */
@Suppress("CyclomaticComplexMethod") // One icon per action.
internal fun contactMenuIcon(a: ContactMenu.Action): ImageVector = when (a) {
    ContactMenu.Action.SHARE_FILE -> Icons.Rounded.Share
    ContactMenu.Action.SHOW_QR -> Icons.Rounded.QrCode2
    ContactMenu.Action.SHARE_ENCRYPTED_QR -> Icons.Rounded.Key
    ContactMenu.Action.VERSION_HISTORY -> Icons.Rounded.History
    ContactMenu.Action.REMIND_TO_CALL -> Icons.Rounded.AlarmAdd
    ContactMenu.Action.BLOCK_NUMBERS -> Icons.Rounded.Block
    ContactMenu.Action.UNBLOCK_NUMBERS -> Icons.Rounded.RemoveModerator
    ContactMenu.Action.LOG_CHAT_OR_VISIT -> Icons.Rounded.Handshake
    ContactMenu.Action.CASE_FILE -> Icons.Rounded.FolderOpen
    ContactMenu.Action.ADD_TO_HOME_SCREEN -> Icons.Rounded.AddToHomeScreen
    ContactMenu.Action.COPY_TO_SIM -> Icons.Rounded.SimCard
    ContactMenu.Action.ALLOW_SIMILAR_NUMBERS -> Icons.Rounded.Business
    ContactMenu.Action.SEPARATE -> Icons.Rounded.LinkOff
    ContactMenu.Action.DELETE -> Icons.Rounded.Delete
}

/** "Keep a case file": for any contact, by its numbers (a private contact's hides with it). */
private fun keepCaseFile(ctx: ContactPageContext) {
    val owner = ctx.caseOwner
    val vm = ctx.vm
    ctx.scope.launch {
        val done = vm.c.cases.update {
            val now = System.currentTimeMillis()
            CaseFiles.setMode(it, owner.name, owner.numbers, owner.private, CaseMode.ON, now, vm.countryIso, UUID.randomUUID().toString())
        }
        if (done != null) vm.toast(vm.c.appContext.getString(R.string.case_kept))
    }
}
