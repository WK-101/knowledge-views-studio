package app.parley.ui.contact

import android.content.res.Resources
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.AltCalendar
import app.parley.common.people.AddressParts
import app.parley.common.people.Languages
import app.parley.common.people.PhoneTypes
import app.parley.data.CustomFieldItem
import app.parley.ui.FormRow
import app.parley.ui.FormTokens
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.formFieldShape

// The editor's rarer fields (custom fields, the language, Android's other phone types, the calendar a date follows,
// RFC 9554's address parts), kept apart from ContactEditScreen. See docs/EDITOR_DESIGN.md, "More fields".

/** One custom field: its label and its value as two lines of one block, and "⊖". */
@Composable
internal fun CustomFieldRow(
    item: CustomFieldItem,
    locked: Boolean,
    focus: FocusRequester,
    icon: ImageVector?,
    title: String?,
    shapeIndex: Int,
    count: Int,
    onChange: (CustomFieldItem) -> Unit,
    onRemove: () -> Unit,
) {
    // Each field takes two lines inside the group's block: the label, then the value.
    val lines = count * 2
    FormRow(icon, title, end = if (!locked) { { RemoveButton(stringResource(R.string.edit_remove_custom_field), onRemove) } } else null) {
        EditorField(
            stringResource(R.string.edit_custom_field_label), item.label, shape = formFieldShape(shapeIndex * 2, lines),
            cap = KeyboardCapitalization.Sentences, locked = locked, focus = focus,
            placeholder = stringResource(R.string.edit_custom_field_placeholder),
        ) { onChange(item.copy(label = it)) }
        Spacer(Modifier.height(FormTokens.segmentGap))
        EditorField(
            stringResource(R.string.edit_custom_field_value), item.value, shape = formFieldShape(shapeIndex * 2 + 1, lines),
            cap = KeyboardCapitalization.Sentences, locked = locked,
        ) { onChange(item.copy(value = it)) }
    }
}

/** The language to use with them: typed as a name or a tag; what will be stored is said under it. */
@Composable
internal fun LanguageRow(value: String, locked: Boolean, focus: FocusRequester, icon: ImageVector, modifier: Modifier, onChange: (String) -> Unit) {
    // Matching a name scans every ISO language: only again when the text changes, not on every recomposition.
    val stored = remember(value) { Languages.toStored(value) }
    val shown = remember(stored) { Languages.display(stored) }
    val support = if (value.isNotBlank() && shown != value.trim()) stringResource(R.string.edit_language_saved_as, shown)
    else stringResource(R.string.edit_language_hint)
    FormRow(icon, stringResource(R.string.edit_language), modifier.padding(bottom = FormTokens.groupGap)) {
        EditorField(
            stringResource(R.string.edit_language), value, shape = formFieldShape(0, 1), cap = KeyboardCapitalization.Words,
            locked = locked, focus = focus, support = support,
        ) { onChange(it) }
    }
}

/** Android's other fourteen phone types ([PhoneTypes.more]), the current one ticked. */
@Composable
internal fun PhoneMoreTypesDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val res = LocalResources.current
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_phone_types_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(PhoneTypes.more, key = { it }) { t ->
                    val on = t == current
                    ParleyListItem(
                        headlineContent = { Text(Phone.getTypeLabel(res, t, null).toString()) },
                        trailingContent = if (on) { { Icon(Icons.Rounded.Check, null) } } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.semantics { selected = on }.clickable(role = Role.RadioButton) { onPick(t) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

/** RFC 9554's address parts another app wrote ("Floor: 3 · Building: B"), shown under the address; not edited. */
@Composable
internal fun AddressPartsLine(parts: String) {
    val text = addressPartsText(LocalResources.current, parts) ?: return
    Text(
        stringResource(R.string.edit_address_parts, text), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp),
    )
}

/** "Floor: 3 · Building: B", or null when [parts] holds none. */
internal fun addressPartsText(res: Resources, parts: String): String? =
    AddressParts.decode(parts).map { (p, v) -> res.getString(addressPartLabel(p)) + ": " + v }
        .joinToString(res.getString(R.string.main_separator)).ifEmpty { null }

private fun addressPartLabel(p: AddressParts.Part): Int = when (p) {
    AddressParts.Part.ROOM -> R.string.addr_part_room
    AddressParts.Part.APARTMENT -> R.string.addr_part_apartment
    AddressParts.Part.FLOOR -> R.string.addr_part_floor
    AddressParts.Part.STREET_NUMBER -> R.string.addr_part_street_number
    AddressParts.Part.STREET_NAME -> R.string.addr_part_street_name
    AddressParts.Part.BUILDING -> R.string.addr_part_building
    AddressParts.Part.BLOCK -> R.string.addr_part_block
    AddressParts.Part.SUBDISTRICT -> R.string.addr_part_subdistrict
    AddressParts.Part.DISTRICT -> R.string.addr_part_district
    AddressParts.Part.LANDMARK -> R.string.addr_part_landmark
    AddressParts.Part.DIRECTION -> R.string.addr_part_direction
}

/** A calendar's name ("Chinese lunar"); null is Gregorian. */
internal fun calendarName(res: Resources, c: AltCalendar?): String = res.getString(
    when (c) {
        null -> R.string.edit_calendar_gregorian
        AltCalendar.CHINESE -> R.string.edit_calendar_chinese
        AltCalendar.HEBREW -> R.string.edit_calendar_hebrew
        AltCalendar.HIJRI -> R.string.edit_calendar_hijri
    },
)

/** "Chinese lunar calendar" for a date kept by another calendar; null for a Gregorian one. */
internal fun calendarLine(res: Resources, key: String?): String? =
    AltCalendar.byKey(key)?.let { res.getString(R.string.edit_calendar_named, calendarName(res, it)) }
