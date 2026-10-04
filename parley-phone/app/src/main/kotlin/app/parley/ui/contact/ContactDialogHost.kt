package app.parley.ui.contact

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.data.AccountRef
import app.parley.data.people.OriginalPhotos
import app.parley.data.circle.Interaction
import app.parley.data.primary
import app.parley.jobs.UserErrorText
import app.parley.messaging.ReachSheet
import app.parley.messaging.ReachTarget
import app.parley.security.launchVault
import app.parley.shortcuts.Shortcuts
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.Routes
import app.parley.ui.calls.RemindToCallSheet
import app.parley.ui.circle.LogInteractionDialog
import app.parley.ui.circle.PreCallPeekSheet
import app.parley.ui.cases.CaseCard
import app.parley.ui.circle.PromiseNoteField
import app.parley.ui.circle.RhythmDialog
import app.parley.ui.common.Format
import app.parley.ui.menus.CallReasonFlow
import app.parley.ui.people.CopyToSimDialog
import app.parley.ui.vault.ExpiryDialog

/**
 * Draws the one dialog or sheet a contact's page has open ([dialog]); closing it shows [ContactDialog.None]. The ⋮
 * menu and its group sheets are drawn by the top bar ([ContactBarActions]), where the menu is anchored.
 */
@Composable
@Suppress("CyclomaticComplexMethod") // One branch per dialog.
internal fun ContactDialogHost(ctx: ContactPageContext, dialog: ContactDialog, original: OriginalPhotos.Original?) {
    val d = ctx.d
    val close = { ctx.show(ContactDialog.None) }
    when (dialog) {
        ContactDialog.None, ContactDialog.Menu, is ContactDialog.MenuSheet -> Unit
        is ContactDialog.MessageOn -> ReachSheet(
            ReachTarget.Person(ctx.reach.copy(defaultNumber = dialog.number.ifEmpty { ctx.reach.defaultNumber })) { p -> ctx.page.setMessengerPrefs(p) },
            onDismiss = close,
            onCall = { num -> ctx.callPeek(num, ctx.reach.name) },
        )
        is ContactDialog.WebLink -> ConfirmWebLink(dialog.link, close)
        ContactDialog.Qr -> QrDialog(ctx.vm, d, ctx.isPrivate, close)
        // A private contact's Parley key isn't part of what it shares.
        ContactDialog.SecureQr -> SecureQrDialog(ctx.vm, if (ctx.isPrivate) d.copy(id = 0, lookupKey = "") else d, ctx.isPrivate, close)
        ContactDialog.CopyToSim -> CopyToSimDialog(ctx.vm, d, close)
        ContactDialog.EditNote -> EditNoteDialog(ctx)
        ContactDialog.Rhythm -> RhythmDialog(ctx.vm, d, ctx.contactId, ctx.ui.meta, close)
        is ContactDialog.CallReason -> CallReasonFlow(ctx.vm, dialog.target, close)
        is ContactDialog.Peek -> PreCallPeekSheet(
            ctx.vm, d.lookupKey, d.given.ifBlank { d.displayName }, ctx.ui.memory, ctx.goodTime,
            onCall = { close(); ctx.vm.requestCall(dialog.number, d.displayName) },
            onDismiss = close,
            // The case card opens the case file; Call stays right below, so nothing stands in the call's way.
            extra = { if (ctx.case.shown) CaseCard(ctx.vm, ctx.caseOwner, { r -> close(); ctx.open(r) }) },
        )
        ContactDialog.RemindToCall -> d.phones.primary()?.let { p ->
            // Several numbers: the sheet asks which one, the default chosen first.
            val resources = LocalResources.current
            val numbers = d.phones.distinctBy { it.value }.map { it.value to Format.phoneType(resources, it.type, it.label) }
            RemindToCallSheet(ctx.vm, p.value, d.displayName, numbers = numbers, onDismiss = close)
        }
        ContactDialog.LogInteraction -> LogDialog(ctx, null)
        // The entry may have gone meanwhile (deleted elsewhere): then nothing is left to edit.
        is ContactDialog.EditInteraction -> ctx.ui.interactions.firstOrNull { it.id == dialog.id }?.let { LogDialog(ctx, it) }
        ContactDialog.AddToHomeScreen -> AddToHomeScreenDialog(ctx)
        is ContactDialog.ChooseRelation -> ChooseRelationDialog(ctx, dialog.people)
        ContactDialog.Expiry -> ExpiryDialog(onDismiss = close) { days ->
            close()
            ctx.page.setExpiry(days)
        }
        ContactDialog.Photo -> d.photoUri?.let { uri ->
            val export = contactPhotoImage(ctx.vm, d.displayName, original, uri, ctx.contactId.takeUnless { ctx.isPrivate }, ctx.isPrivate)
            original?.let { OriginalPhotoViewer(ctx.vm, it, export, close) }
                ?: PhotoViewer(ctx.vm, uri, export, stringResource(R.string.detail_contact_photo), close)
        }
        ContactDialog.ConfirmDelete, ContactDialog.DeleteWithoutCopy -> DeleteDialogs(ctx, dialog)
        ContactDialog.ConfirmMakePrivate -> MakePrivateDialog(ctx)
        ContactDialog.ConfirmMakeVisible -> MakeVisibleDialog(ctx)
        ContactDialog.PrivateQrWarning -> ConfirmDialog(
            title = stringResource(R.string.contact_private_qr_title),
            text = stringResource(R.string.contact_private_qr_body),
            confirmLabel = stringResource(R.string.contact_private_qr_confirm),
            icon = Icons.Rounded.QrCode2,
            onConfirm = { ctx.show(ContactDialog.Qr) },
            onDismiss = close,
            dismissLabel = stringResource(R.string.main_cancel),
        )
        is ContactDialog.SimFor -> SimForDialog(ctx, dialog.number)
    }
}

@Composable
private fun EditNoteDialog(ctx: ContactPageContext) {
    // What was typed survives a rotation, with the dialog.
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(ctx.ui.meta?.pinnedNote.orEmpty())) }
    ConfirmDialog(
        title = stringResource(R.string.detail_note_title),
        text = null,
        confirmLabel = stringResource(R.string.main_save),
        onConfirm = { ctx.show(ContactDialog.None); ctx.page.setPinnedNote(text.text) },
        onDismiss = { ctx.show(ContactDialog.None) },
        dismissLabel = stringResource(R.string.main_cancel),
        content = { PromiseNoteField(text, { text = it }, placeholder = stringResource(R.string.detail_note_placeholder)) },
    )
}

@Composable
private fun LogDialog(ctx: ContactPageContext, initial: Interaction?) {
    val d = ctx.d
    LogInteractionDialog(d.given.ifBlank { d.displayName }, initial, onDismiss = { ctx.show(ContactDialog.None) }) { type, note, time ->
        ctx.show(ContactDialog.None)
        ctx.page.saveInteraction(initial, type, note, time)
    }
}

@Composable
private fun AddToHomeScreenDialog(ctx: ContactPageContext) {
    val d = ctx.d
    val context = LocalContext.current
    val close = { ctx.show(ContactDialog.None) }
    fun pin(kind: Shortcuts.Kind, number: String?, lookupKey: String? = null) {
        close()
        if (lookupKey != null) {
            Shortcuts.pin(context, kind, d.displayName, number, ctx.contactId, d.photoUri, lookupKey)
        } else {
            Shortcuts.pin(context, kind, d.displayName, number, ctx.contactId, d.photoUri)
        }
    }
    ParleyDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.detail_add_home)) },
        text = {
            Column {
                d.phones.forEach { p ->
                    val shown = Bidi.ltr(Format.number(p.value, ctx.vm.countryIso))
                    ParleyListItem(
                        headlineContent = { Text(stringResource(R.string.main_call_who, shown)) },
                        leadingContent = { Icon(Icons.Rounded.Call, null) },
                        modifier = Modifier.clickable { pin(Shortcuts.Kind.CALL, p.value) },
                    )
                    ParleyListItem(
                        headlineContent = { Text(stringResource(R.string.main_message_who, shown)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) },
                        modifier = Modifier.clickable { pin(Shortcuts.Kind.MESSAGE, p.value) },
                    )
                }
                ParleyListItem(
                    headlineContent = { Text(stringResource(R.string.main_open_contact)) },
                    leadingContent = { Icon(Icons.Rounded.Person, null) },
                    modifier = Modifier.clickable { pin(Shortcuts.Kind.OPEN, null, d.lookupKey) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(close) { Text(stringResource(R.string.main_cancel)) } },
    )
}

@Composable
private fun ChooseRelationDialog(ctx: ContactPageContext, people: List<ContactSummary>) {
    val close = { ctx.show(ContactDialog.None) }
    ParleyDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.detail_which_contact)) },
        text = {
            Column {
                people.forEach { ct ->
                    ParleyListItem(
                        modifier = Modifier.clickable {
                            close()
                            ctx.open(Routes.contact(ct.id))
                        },
                        headlineContent = { Text(ct.displayName) },
                        supportingContent = { ct.phones.firstOrNull()?.let { Text(Bidi.ltr(Format.number(it.number, ctx.vm.countryIso))) } },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(close) { Text(stringResource(R.string.main_cancel)) } },
    )
}

@Composable
private fun DeleteDialogs(ctx: ContactPageContext, dialog: ContactDialog) {
    val d = ctx.d
    val close = { ctx.show(ContactDialog.None) }
    if (dialog == ContactDialog.ConfirmDelete) {
        ConfirmDialog(
            title = if (ctx.isPrivate) stringResource(R.string.vault_delete_title) else stringResource(R.string.detail_delete_title, d.displayName),
            // A private contact's copy is kept sealed ("Deleted private contacts" in History & undo), never plain.
            text = stringResource(if (ctx.isPrivate) R.string.vault_delete_text else R.string.detail_delete_body),
            confirmLabel = stringResource(R.string.main_delete),
            onConfirm = {
                close()
                if (ctx.isPrivate) {
                    ctx.page.deletePrivate(noCopy = { ctx.show(ContactDialog.DeleteWithoutCopy) }, then = ctx.back)
                } else {
                    ctx.vm.deleteContacts(listOf(ctx.contactId))
                    ctx.back()
                }
            },
            onDismiss = close,
            destructive = true,
            dismissLabel = stringResource(R.string.main_cancel),
        )
    } else {
        ConfirmDialog(
            title = stringResource(R.string.vault_delete_no_copy_title),
            text = stringResource(R.string.vault_delete_no_copy_text),
            confirmLabel = stringResource(R.string.vault_delete_no_copy_confirm),
            onConfirm = {
                close()
                ctx.page.deletePrivate(keepCopy = false, then = ctx.back)
            },
            onDismiss = close,
            destructive = true,
            dismissLabel = stringResource(R.string.main_cancel),
        )
    }
}

@Composable
private fun MakePrivateDialog(ctx: ContactPageContext) {
    val d = ctx.d
    val context = LocalContext.current
    val resources = LocalResources.current
    // The page's scope, not this dialog's: the dialog closes before the move runs.
    val scope = ctx.scope
    ConfirmDialog(
        title = stringResource(R.string.contact_make_private_title, d.given.ifBlank { d.displayName }),
        text = stringResource(R.string.contact_make_private_body),
        confirmLabel = stringResource(R.string.detail_move_vault),
        icon = Icons.Rounded.Lock,
        onConfirm = {
            ctx.show(ContactDialog.None)
            scope.launchVault(
                context as? ComponentActivity,
                { e -> ctx.vm.toast(resources.getString(R.string.detail_move_failed, UserErrorText.of(context, e))) },
            ) {
                // The note for calls and the messaging choice go with them, sealed; the rest is re-keyed.
                val sealed = d.copy(pinnedNote = ctx.ui.meta?.pinnedNote.orEmpty(), messengerPrefs = ctx.prefs.encode().orEmpty())
                val id = ctx.vm.moveToVault(ctx.contactId, sealed)
                ctx.vm.toast(resources.getString(R.string.detail_moved_private))
                ctx.back()
                ctx.open(Routes.contact(-id))
            }
        },
        onDismiss = { ctx.show(ContactDialog.None) },
        dismissLabel = stringResource(R.string.main_cancel),
    )
}

@Composable
private fun MakeVisibleDialog(ctx: ContactPageContext) {
    val d = ctx.d
    val vm = ctx.vm
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = ctx.scope
    ConfirmDialog(
        title = stringResource(R.string.contact_make_visible_title, d.given.ifBlank { d.displayName }),
        text = makeVisibleBody(vm.c.contacts),
        confirmLabel = stringResource(R.string.contact_make_visible_confirm),
        icon = Icons.Rounded.LockOpen,
        onConfirm = {
            ctx.show(ContactDialog.None)
            scope.launchVault(
                context as? ComponentActivity,
                { e -> vm.toast(resources.getString(R.string.vault_move_failed, UserErrorText.of(context, e))) },
            ) {
                val s = vm.settings.value
                // Restores the original contact losslessly when the vault kept its record; else the default account.
                val requested = AccountRef(s.defaultAccountType, s.defaultAccountName)
                when (val made = ctx.page.makeVisible(requested)) {
                    is ContactConversions.MadeVisible.Done -> {
                        // Where Android 16 put it, when it refused the phone: the account actually written.
                        vm.toast(madeVisibleText(resources, made.redirectedTo))
                        ctx.back()
                        ctx.open(Routes.contact(made.contactId))
                    }
                    // Nothing changed either way; say why, so the tap isn't simply lost.
                    ContactConversions.MadeVisible.NotWritten -> vm.toast(resources.getString(R.string.contact_make_visible_failed))
                    ContactConversions.MadeVisible.CallsKept -> vm.toast(resources.getString(R.string.contact_make_visible_calls_kept))
                }
            }
        },
        onDismiss = { ctx.show(ContactDialog.None) },
        dismissLabel = stringResource(R.string.main_cancel),
    )
}

@Composable
private fun SimForDialog(ctx: ContactPageContext, number: String) {
    val sims by ctx.vm.sims.collectAsStateWithLifecycle()
    val close = { ctx.show(ContactDialog.None) }
    ParleyDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.detail_sim_for, Bidi.ltr(Format.number(number, ctx.vm.countryIso)))) },
        text = {
            Column {
                ParleyListItem(
                    headlineContent = { Text(stringResource(R.string.detail_sim_ask)) },
                    modifier = Modifier.clickable { ctx.page.setSimFor(number, null); close() },
                )
                sims.forEach { s ->
                    ParleyListItem(
                        headlineContent = { Text(stringResource(R.string.detail_always_sim, s.label)) },
                        leadingContent = { Icon(Icons.Rounded.SimCard, null) },
                        modifier = Modifier.clickable { ctx.page.setSimFor(number, s.id); close() },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(close) { Text(stringResource(R.string.main_cancel)) } },
    )
}
