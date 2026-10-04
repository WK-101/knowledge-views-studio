package app.parley.ui.contact

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.parley.R
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

/**
 * The top bar's actions on a contact's page: star, edit and ⋮. The ⋮ menu has at most seven items, the rarer ones
 * under Share…, Privacy… and More… (ContactMenu); a group opens as its own sheet.
 */
@Composable
internal fun ContactBarActions(ctx: ContactPageContext, dialog: ContactDialog, blocked: Boolean, onlyEmergency: Boolean) {
    val d = ctx.d
    val contactId = ctx.contactId
    IconButton({ ctx.page.setStarred(!d.starred) }) {
        Icon(if (d.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, stringResource(if (d.starred) R.string.sel_unstar else R.string.sel_star))
    }
    IconButton({ ctx.open(if (ctx.isPrivate) Routes.edit(vault = -contactId) else Routes.edit(id = contactId)) }) {
        Icon(Icons.Rounded.Edit, stringResource(R.string.main_edit))
    }
    IconButton({ ctx.show(ContactDialog.Menu) }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more)) }
    val entries = ContactMenu.build(
        ContactMenu.Facts(
            isPrivate = ctx.isPrivate,
            canShareFile = ctx.can(ContactCapability.SHARE_VCARD_FILE),
            canSeeVersions = ctx.can(ContactCapability.VERSION_HISTORY),
            canAddToHomeScreen = ctx.can(ContactCapability.HOME_SCREEN_SHORTCUT),
            hasNumbers = d.phones.isNotEmpty(),
            canCopyToSim = ctx.can(ContactCapability.COPY_TO_SIM),
            canSetRingtone = ctx.can(ContactCapability.RINGTONE),
            linked = d.rawContacts.size > 1,
            // "Log a chat or visit" is the FAB for Circle contacts.
            inCircle = ctx.inCircle,
            blocked = blocked,
            onlyEmergency = onlyEmergency,
        ),
    )
    val run: (ContactMenu.Action) -> Unit = { a -> runContactMenu(ctx, a) }
    val label: @Composable (ContactMenu.Action) -> MenuLabel = { contactMenuLabel(it, ctx.ui.temporary != null) }
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
        // Make private ⇄ Make visible: the same contact, kept somewhere else (asks first).
        ContactMenu.Action.MAKE_PRIVATE -> ctx.show(ContactDialog.ConfirmMakePrivate)
        ContactMenu.Action.MAKE_VISIBLE -> ctx.show(ContactDialog.ConfirmMakeVisible)
        ContactMenu.Action.DELETE_AUTOMATICALLY -> ctx.show(ContactDialog.Expiry)
        ContactMenu.Action.LOG_CHAT_OR_VISIT -> ctx.show(ContactDialog.LogInteraction)
        ContactMenu.Action.ADD_TO_HOME_SCREEN -> ctx.show(ContactDialog.AddToHomeScreen)
        ContactMenu.Action.COPY_TO_SIM -> ctx.show(ContactDialog.CopyToSim)
        ContactMenu.Action.SET_RINGTONE -> ctx.pickRingtone(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, d.customRingtone?.let(Uri::parse)),
        )
        ContactMenu.Action.ALLOW_SIMILAR_NUMBERS ->
            BlockingDialogs.show(BlockingDialog.PrefixAllow(d.composedName.ifBlank { null }, d.phones.map { it.value }))
        ContactMenu.Action.SEPARATE -> ctx.page.separate(ctx.back)
        ContactMenu.Action.DELETE -> ctx.show(ContactDialog.ConfirmDelete)
    }
}

/** The words and icon of a contact page ⋮ action ([temporary]: it already deletes itself, so its time can change). */
@Composable
@Suppress("CyclomaticComplexMethod") // One label per action.
private fun contactMenuLabel(a: ContactMenu.Action, temporary: Boolean): MenuLabel = when (a) {
    ContactMenu.Action.SHARE_FILE -> MenuLabel(stringResource(R.string.detail_share_file), Icons.Rounded.Share)
    ContactMenu.Action.SHOW_QR -> MenuLabel(stringResource(R.string.detail_show_qr), Icons.Rounded.QrCode2)
    ContactMenu.Action.SHARE_ENCRYPTED_QR -> MenuLabel(stringResource(R.string.detail_share_private), Icons.Rounded.Lock)
    ContactMenu.Action.VERSION_HISTORY -> MenuLabel(stringResource(R.string.detail_versions), Icons.Rounded.History)
    ContactMenu.Action.REMIND_TO_CALL -> MenuLabel(stringResource(R.string.to_call_remind_me_to_call), Icons.Rounded.AlarmAdd)
    ContactMenu.Action.BLOCK_NUMBERS -> MenuLabel(stringResource(R.string.detail_block_numbers), Icons.Rounded.Block)
    ContactMenu.Action.UNBLOCK_NUMBERS -> MenuLabel(stringResource(R.string.detail_unblock_numbers), Icons.Rounded.RemoveModerator)
    ContactMenu.Action.MAKE_PRIVATE -> MenuLabel(stringResource(R.string.detail_move_vault), Icons.Rounded.Lock)
    ContactMenu.Action.MAKE_VISIBLE -> MenuLabel(stringResource(R.string.contact_make_visible), Icons.Rounded.LockOpen)
    ContactMenu.Action.DELETE_AUTOMATICALLY ->
        MenuLabel(stringResource(if (temporary) R.string.detail_change_expiry else R.string.contact_make_temporary), Icons.Rounded.Timer)
    ContactMenu.Action.LOG_CHAT_OR_VISIT -> MenuLabel(stringResource(R.string.circle_log_interaction), Icons.Rounded.Handshake)
    ContactMenu.Action.ADD_TO_HOME_SCREEN -> MenuLabel(stringResource(R.string.detail_add_home), Icons.Rounded.AddToHomeScreen)
    ContactMenu.Action.COPY_TO_SIM -> MenuLabel(stringResource(R.string.detail_copy_sim), Icons.Rounded.SimCard)
    ContactMenu.Action.SET_RINGTONE -> MenuLabel(stringResource(R.string.detail_set_ringtone), Icons.Rounded.MusicNote)
    ContactMenu.Action.ALLOW_SIMILAR_NUMBERS -> MenuLabel(stringResource(R.string.blk_prefix_title), Icons.Rounded.Business)
    ContactMenu.Action.SEPARATE -> MenuLabel(stringResource(R.string.detail_separate), Icons.Rounded.LinkOff)
    ContactMenu.Action.DELETE -> MenuLabel(stringResource(R.string.main_delete), Icons.Rounded.Delete)
}
