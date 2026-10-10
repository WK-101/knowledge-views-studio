package app.parley.ui.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.people.ExpiryChange
import app.parley.common.people.MeCards
import app.parley.common.people.TemporaryChoice
import app.parley.data.AccountRef
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyShapes
import app.parley.ui.temporary.timeLeft

// Where a contact is saved, as one line of quiet chips at the top of the editor: "Save to" (an account, Private or
// Temporary) for a new contact, where an existing one lives, and how long a temporary one stays.

/** An existing contact's current expiry ([expiresAt], null: it's kept) and the editor's change to it ([pick]). */
internal data class ExpiryState(val expiresAt: Long?, val pick: ExpiryChange?)

/** Everything the "Save to" line shows and changes. */
internal class EditorSaveTo(
    /** Private vault mode from the route: 0 = new private contact, > 0 = editing one. */
    val vaultId: Long?,
    val isExisting: Boolean,
    val privateNew: Boolean,
    val temporaryNew: Boolean,
    val temporary: TemporaryChoice,
    val account: AccountRef?,
    val accounts: List<AccountRef>,
    /** Null when an existing contact can't be made temporary here (or the contact is new). */
    val expiry: ExpiryState?,
    /** Android 16's cloud default while it takes new contacts instead of the phone. */
    val systemDefault: AccountRef? = null,
) {
    val editingPrivate: Boolean get() = (vaultId ?: 0L) > 0L

    /** A new contact whose destination can be chosen (not the private-contact screen's own "New"). */
    val choosable: Boolean get() = vaultId == null && !isExisting

    /** The small print under the line: where a new private or temporary contact goes. */
    val note: Int?
        get() = when {
            !choosable -> if (vaultId != null) R.string.edit_private_note else null
            temporaryNew -> if (temporary.private) R.string.editor_temp_note_private else R.string.editor_temp_note_visible
            privateNew -> R.string.edit_private_note
            else -> null
        }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SaveToLine(
    s: EditorSaveTo,
    label: (AccountRef) -> String,
    onAccount: (AccountRef?) -> Unit,
    onTemporary: () -> Unit,
    onTemporaryChange: (TemporaryChoice) -> Unit,
    onExpiry: (ExpiryChange?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            when {
                s.vaultId != null -> InfoLine(Icons.Rounded.Lock, stringResource(R.string.editor_private_here))
                s.isExisting -> InfoLine(
                    if (s.account?.isLocal != false) Icons.Rounded.PhoneAndroid else Icons.Rounded.AccountCircle,
                    stringResource(R.string.edit_saved_in, s.account?.displayLabel ?: stringResource(R.string.detail_phone)),
                )
                else -> DestinationChip(s, label, onAccount, onTemporary)
            }
            val expiry = s.expiry
            when {
                s.choosable && s.temporaryNew -> TemporaryChip(s.temporary, onTemporaryChange)
                expiry != null && (s.isExisting || s.editingPrivate) -> ExpiryChip(expiry.expiresAt, expiry.pick, onExpiry)
            }
        }
        s.note?.let { note ->
            Text(
                stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}.padding(horizontal = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A quiet tonal chip with a menu under it (the editor's header controls). */
@Composable
private fun MenuChip(
    icon: ImageVector,
    text: String,
    actionLabel: String,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
            shape = ParleyShapes.pill,
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = null,
            modifier = Modifier.semantics { onClick(label = actionLabel) { open = true; true } },
        )
        DropdownMenu(open, { open = false }, shape = ParleyShapes.tile) { menu { open = false } }
    }
}

/**
 * An account as the Save-to line names it: short and without counts ("Device", "Google · ana@example.com").
 * How many contacts an account holds is noise while saving one; Settings › Contacts still shows it.
 */
internal fun accountName(a: AccountRef, device: String): String = when {
    a.type == null || a.isLocal -> device
    else -> a.displayLabel
}

/** The Save-to chip's current destination: "Device", "Google · ana@…", "Private" or "Temporary". */
@Composable
private fun destinationLabel(s: EditorSaveTo, label: (AccountRef) -> String): String = when {
    s.temporaryNew -> stringResource(R.string.editor_temporary)
    s.privateNew -> stringResource(R.string.editor_save_private)
    else -> s.account?.let(label) ?: stringResource(R.string.editor_account_device)
}

private fun destinationIcon(s: EditorSaveTo): ImageVector = when {
    s.temporaryNew -> Icons.Rounded.AutoDelete
    s.privateNew -> Icons.Rounded.Lock
    s.account == null || s.account.isLocal -> Icons.Rounded.PhoneAndroid
    else -> Icons.Rounded.AccountCircle
}

/** "Save to: …" for a new contact: the accounts, Private, and Temporary. */
@Composable
private fun DestinationChip(s: EditorSaveTo, label: (AccountRef) -> String, onAccount: (AccountRef?) -> Unit, onTemporary: () -> Unit) {
    val privateLabel = stringResource(R.string.edit_private_only)
    val current = destinationLabel(s, label)
    val icon = destinationIcon(s)
    MenuChip(icon, stringResource(R.string.editor_saving_to, current), stringResource(R.string.editor_change_account)) { close ->
        val tick: @Composable () -> Unit = { Icon(Icons.Rounded.Check, null) }
        s.accounts.forEach { a ->
            DropdownMenuItem(
                text = { Text(label(a)) },
                leadingIcon = { Icon(if (a.isLocal) Icons.Rounded.PhoneAndroid else Icons.Rounded.AccountCircle, null) },
                trailingIcon = if (!s.privateNew && !s.temporaryNew && a == s.account) tick else null,
                onClick = { close(); onAccount(a) },
            )
        }
        // Why "Device" is missing: Android 16 refuses new phone-only contacts while the default is a cloud account.
        s.systemDefault?.let { d ->
            Text(
                stringResource(R.string.editor_account_system_default, label(d)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        DropdownMenuItem(
            text = { Text(privateLabel) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
            trailingIcon = if (s.privateNew && !s.temporaryNew) tick else null,
            onClick = { close(); onAccount(null) },
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.editor_temporary))
                    Text(
                        stringResource(R.string.editor_temporary_sub), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            leadingIcon = { Icon(Icons.Rounded.AutoDelete, null) },
            trailingIcon = if (s.temporaryNew) tick else null,
            onClick = { close(); onTemporary() },
        )
    }
}

/** A new temporary contact's time ("7 days ▾"), with its privacy and call-history choices in the same menu. */
@Composable
private fun TemporaryChip(t: TemporaryChoice, onChange: (TemporaryChoice) -> Unit) {
    var custom by rememberSaveable { mutableStateOf(false) }
    val text = pluralStringResource(R.plurals.temp_n_days, t.days, t.days)
    MenuChip(Icons.Rounded.AutoDelete, text, stringResource(R.string.editor_change_expiry)) { close ->
        DurationItems(t.days, onPick = { d -> close(); onChange(t.copy(days = d)) }, onCustom = { close(); custom = true })
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.temp_visible)) },
            trailingIcon = { Checkbox(!t.private, onCheckedChange = null) },
            onClick = { onChange(t.copy(private = !t.private)) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.temp_also_history)) },
            trailingIcon = { Checkbox(t.purgeHistory, onCheckedChange = null) },
            onClick = { onChange(t.copy(purgeHistory = !t.purgeHistory)) },
        )
    }
    if (custom) CustomDaysDialog(t.days, onDismiss = { custom = false }) { d -> custom = false; onChange(t.copy(days = d)) }
}

/**
 * An existing contact's expiry: "Make temporary", its time left, or the new choice, with a menu of times and
 * "Keep permanently". Applied when the edit is saved, like every other change here.
 */
@Composable
private fun ExpiryChip(expiresAt: Long?, pick: ExpiryChange?, onPick: (ExpiryChange?) -> Unit) {
    val res = LocalResources.current
    var custom by rememberSaveable { mutableStateOf(false) }
    val text = when {
        pick is ExpiryChange.After -> pluralStringResource(R.plurals.editor_temp_after, pick.days, pick.days)
        pick == ExpiryChange.Keep && expiresAt != null -> stringResource(R.string.temp_keep_permanently)
        expiresAt != null -> timeLeft(res, expiresAt)
        else -> stringResource(R.string.editor_make_temporary)
    }
    val icon = if (pick == ExpiryChange.Keep && expiresAt != null) Icons.Rounded.PushPin else Icons.Rounded.AutoDelete
    MenuChip(icon, text, stringResource(R.string.editor_change_expiry)) { close ->
        DurationItems((pick as? ExpiryChange.After)?.days, onPick = { d -> close(); onPick(ExpiryChange.After(d)) }, onCustom = { close(); custom = true })
        if (expiresAt != null || pick is ExpiryChange.After) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.temp_keep_permanently)) },
                leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                // Undoing a pick on a contact that isn't temporary simply leaves it as it was.
                onClick = { close(); onPick(if (expiresAt != null) ExpiryChange.Keep else null) },
            )
        }
    }
    if (custom) {
        CustomDaysDialog((pick as? ExpiryChange.After)?.days ?: TemporaryChoice.DEFAULT_DAYS, onDismiss = { custom = false }) { d ->
            custom = false
            onPick(ExpiryChange.After(d))
        }
    }
}

/** 1 day, 7 days, 30 days (the current one ticked), then "Custom…". */
@Composable
private fun DurationItems(current: Int?, onPick: (Int) -> Unit, onCustom: () -> Unit) {
    TemporaryChoice.presets.forEach { d ->
        DropdownMenuItem(
            text = { Text(pluralStringResource(R.plurals.temp_n_days, d, d)) },
            trailingIcon = if (current == d) { { Icon(Icons.Rounded.Check, null) } } else null,
            onClick = { onPick(d) },
        )
    }
    val customNow = current != null && current !in TemporaryChoice.presets
    DropdownMenuItem(
        text = { Text(if (customNow) pluralStringResource(R.plurals.temp_n_days, current, current) else stringResource(R.string.edit_custom_more)) },
        trailingIcon = if (customNow) { { Icon(Icons.Rounded.Check, null) } } else null,
        onClick = onCustom,
    )
}

/** A custom number of days (1 to 3650), as the keypad's "Save temporary contact" allows. */
@Composable
private fun CustomDaysDialog(initial: Int, onDismiss: () -> Unit, onDone: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.toString()) }
    val days = TemporaryChoice.parseDays(text)
    ConfirmDialog(
        title = stringResource(R.string.temp_delete_after),
        text = null,
        confirmLabel = stringResource(R.string.main_ok),
        onConfirm = { days?.let(onDone) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.main_cancel),
        confirmEnabled = days != null,
        content = {
            OutlinedTextField(
                text, { v -> text = v.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.temp_days)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/**
 * My card's own option where a contact's Save-to line is: what its QR code and vCard include, each part ticked on its
 * own (name, numbers and e-mail until you choose; the note and relations only when ticked). A share with more than a
 * signed card carries goes out unsigned, and says so here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeShareLine(parts: Set<MeCards.Part>, onToggle: (MeCards.Part) -> Unit) {
    Column {
        Text(
            stringResource(R.string.me_share_includes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeCards.Part.entries.forEach { p ->
                val on = p in parts
                FilterChip(
                    on, { onToggle(p) }, label = { Text(mePartLabel(p)) },
                    leadingIcon = if (on) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                    shape = ParleyShapes.pill,
                )
            }
        }
        if (!MeCards.isSignable(parts)) {
            Text(
                stringResource(R.string.me_share_unsigned), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

/** The name of one part of My card, as the QR dialog and the editor list them. */
@Suppress("CyclomaticComplexMethod") // One label per part.
@Composable
internal fun mePartLabel(p: MeCards.Part): String = stringResource(
    when (p) {
        MeCards.Part.NAME -> R.string.me_name
        MeCards.Part.PHONES -> R.string.me_numbers
        MeCards.Part.EMAILS -> R.string.me_email
        MeCards.Part.WORK -> R.string.me_part_work
        MeCards.Part.WEBSITES -> R.string.me_websites
        MeCards.Part.ADDRESS -> R.string.me_address
        MeCards.Part.PROFILES -> R.string.me_profiles
        MeCards.Part.NAME_DETAILS -> R.string.me_part_name_details
        MeCards.Part.DATES -> R.string.me_part_dates
        MeCards.Part.HANDLES -> R.string.me_part_handles
        MeCards.Part.RELATIONS -> R.string.me_part_relations
        MeCards.Part.LANGUAGES -> R.string.me_part_languages
        MeCards.Part.OTHER -> R.string.me_part_other
        MeCards.Part.NOTE -> R.string.me_part_note
        MeCards.Part.PHOTO -> R.string.me_part_photo
    },
)
