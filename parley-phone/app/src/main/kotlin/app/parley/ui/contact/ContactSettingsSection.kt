package app.parley.ui.contact

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.parley.R
import app.parley.common.people.ContactCapability
import app.parley.common.people.ContactSection
import app.parley.common.ux.DefaultAppFeature
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroupScope
import app.parley.ui.Spacing
import app.parley.ui.blended
import app.parley.ui.calls.DefaultAppNote
import app.parley.ui.calltime.ContactCallTimeRows
import app.parley.ui.common.Format
import app.parley.ui.people.AccountChips
import app.parley.ui.people.CallBackgroundInfoRow
import app.parley.ui.people.CallPhotoRow
import app.parley.ui.people.ProvenanceRow

/** Everything that changes how Parley and the phone treat this person rather than describing them, folded at the bottom. */
@Composable
internal fun ContactSettingsSection(sections: PageSections, ctx: ContactPageContext) {
    val resources = LocalResources.current
    sections.addRows(
        ContactSection.SETTINGS, sectionTitle(resources, ContactSection.SETTINGS), resources.getString(R.string.contact_page_settings_summary),
        // A private contact's ringtone and "Send to voicemail" need Parley's own ringer: said here when it isn't.
        after = if (ctx.isPrivate) ({ DefaultAppNote(ctx.vm, DefaultAppFeature.PRIVATE_CALLER) }) else null,
    ) {
        callingRows(ctx)
        savedInRows(ctx)
        variantRows(ctx)
    }
}

/** The Circle, voicemail, call time, ringtone, haptics and the call screen's picture. */
private fun SegmentedGroupScope.callingRows(ctx: ContactPageContext) {
    val d = ctx.d
    if (d.lookupKey.isNotEmpty() && !ctx.inCircle) {
        item {
            InfoRow(
                modifier = Modifier.clickable { ctx.show(ContactDialog.Rhythm) },
                leading = { Icon(Icons.Rounded.Handshake, null) },
                headline = { Text(stringResource(R.string.circle_add_to_circle)) },
                supporting = { Text(stringResource(R.string.circle_add_to_circle_body)) },
            )
        }
    }
    if (ctx.can(ContactCapability.SEND_TO_VOICEMAIL)) {
        item {
            InfoRow(
                modifier = Modifier.toggleable(d.sendToVoicemail, role = Role.Switch, onValueChange = { v -> ctx.page.setSendToVoicemail(v) }),
                leading = { Icon(Icons.Rounded.Voicemail, null) },
                headline = { Text(stringResource(R.string.detail_send_to_voicemail)) },
                trailing = {
                    // Only the look of a switch: the whole group row above toggles (a SwitchRow is a row of its own).
                    @Suppress("DesignSystemComponent")
                    Switch(d.sendToVoicemail, onCheckedChange = null, modifier = Modifier.padding(end = Spacing.m))
                },
            )
        }
    }
    if (ctx.can(ContactCapability.CALL_TIME)) blended { ContactCallTimeRows(ctx.vm, d.lookupKey, d.displayName, d.starred) }
    if (ctx.can(ContactCapability.RINGTONE)) {
        item {
            val context = LocalContext.current
            val resources = LocalResources.current
            val tone = d.customRingtone?.let {
                // A tune made from the name has a hash for a file name; say whose it is instead.
                if (CallerTunes.isOurs(context, it)) resources.getString(R.string.caller_tune_made_for, d.displayName)
                else runCatching { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }.getOrNull()
            }
            GroupDataRow(
                Icons.Rounded.MusicNote, true, tone ?: resources.getString(R.string.detail_default_ringtone), resources.getString(R.string.detail_ringtone),
                onClick = {
                    val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    ctx.pickRingtone(picker.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE))
                },
            )
        }
    }
    // Haptic caller ID and auto-answer for this person (Parley applies both, private contacts included).
    if (d.lookupKey.isNotEmpty()) {
        blended {
            val resources = LocalResources.current
            val onTune: ((Uri) -> Unit)? = if (ctx.can(ContactCapability.RINGTONE)) {
                { uri ->
                    ctx.page.setRingtone(uri)
                    ctx.vm.toast(resources.getString(R.string.caller_tune_set, d.displayName))
                }
            } else {
                null
            }
            CallerChoiceRows(ctx.vm, d.lookupKey, d.displayName, onTune)
        }
    }
    blended { CallBackgroundInfoRow(ctx.vm, d) }
    blended { CallPhotoRow(ctx.vm, d) }
}

/** Where it's saved, as chips with their own actions (edit this copy, move, unlink); a private contact is kept only in Parley. */
private fun SegmentedGroupScope.savedInRows(ctx: ContactPageContext) {
    val d = ctx.d
    if (!ctx.can(ContactCapability.ACCOUNTS)) {
        item {
            InfoRow(
                leading = { Icon(Icons.Rounded.Lock, null) },
                headline = { Text(stringResource(R.string.detail_saved_in)) },
                supporting = { Text(stringResource(R.string.contact_saved_private)) },
            )
        }
        return
    }
    item {
        val resources = LocalResources.current
        InfoRow(
            leading = { Icon(Icons.Rounded.Sync, null) },
            headline = {
                val n = d.rawContacts.size
                Text(if (n > 1) resources.getQuantityString(R.plurals.detail_linked_from, n, n) else resources.getString(R.string.detail_saved_in))
            },
            supporting = {
                AccountChips(ctx.vm, d, ctx.open) { newId ->
                    if (newId != null && newId != ctx.contactId) {
                        ctx.back()
                        ctx.open(Routes.contact(newId))
                    } else {
                        ctx.page.reload()
                    }
                }
            },
        )
    }
    blended { ProvenanceRow(ctx.vm, ctx.contactId, d, ctx.open) }
}

/** The variants, converted both ways from here (and from ⋮): private ⇄ visible, temporary ⇄ permanent; then the page's own settings. */
private fun SegmentedGroupScope.variantRows(ctx: ContactPageContext) {
    val isPrivate = ctx.isPrivate
    item {
        val resources = LocalResources.current
        GroupDataRow(
            if (isPrivate) Icons.Rounded.LockOpen else Icons.Rounded.Lock, true,
            resources.getString(if (isPrivate) R.string.contact_make_visible else R.string.detail_move_vault),
            resources.getString(if (isPrivate) R.string.contact_make_visible_summary else R.string.contact_make_private_summary),
            onClick = { ctx.show(if (isPrivate) ContactDialog.ConfirmMakeVisible else ContactDialog.ConfirmMakePrivate) },
        )
    }
    val temp = ctx.ui.temporary
    if (temp == null) {
        item {
            val resources = LocalResources.current
            GroupDataRow(
                Icons.Rounded.Timer, true, resources.getString(R.string.contact_make_temporary),
                resources.getString(R.string.contact_make_temporary_summary), onClick = { ctx.show(ContactDialog.Expiry) },
            )
        }
    } else {
        item {
            val context = LocalContext.current
            val resources = LocalResources.current
            GroupDataRow(
                Icons.Rounded.Timer, true, resources.getString(R.string.detail_deletes_on, Format.fullDate(context, temp.expiresAt)),
                resources.getString(R.string.detail_change_expiry), onClick = { ctx.show(ContactDialog.Expiry) },
            )
        }
        item {
            val resources = LocalResources.current
            GroupDataRow(
                Icons.Rounded.Timer, true, resources.getString(R.string.contact_keep_permanently),
                resources.getString(R.string.contact_keep_permanently_summary), onClick = { ctx.page.setExpiry(null) },
            )
        }
    }
    item {
        val resources = LocalResources.current
        GroupDataRow(
            Icons.Rounded.ViewAgenda, true, resources.getString(R.string.contact_page_settings_title),
            resources.getString(R.string.set_contact_page_summary), onClick = { ctx.open(ContactPageRoutes.Sections) },
        )
    }
}
