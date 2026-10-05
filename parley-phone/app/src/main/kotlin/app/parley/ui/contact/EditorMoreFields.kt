package app.parley.ui.contact

import android.content.res.Resources
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.parley.common.people.Citizenship
import app.parley.common.people.NativeName
import app.parley.common.people.Scripts
import app.parley.ui.ParleyShapes
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
import app.parley.data.ContactDetails
import app.parley.data.CustomFieldItem
import app.parley.data.messaging.Romanizer
import app.parley.ui.FormRow
import app.parley.ui.FormTokens
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.formFieldShape

// The editor's rarer fields (custom fields, the languages, the name in their language, citizenship, Android's other phone types, the calendar a date follows,
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

/**
 * The languages they speak, in one field ("Russian, English"): typed as names or tags, the first the one to use with
 * them. What will be stored is said under it. The text is kept as typed while it is being edited (a trailing comma
 * stays), and the list follows it.
 */
@Composable
internal fun LanguagesRow(values: List<String>, locked: Boolean, focus: FocusRequester, icon: ImageVector, modifier: Modifier, onChange: (List<String>) -> Unit) {
    var text by rememberSaveable { mutableStateOf(Languages.join(values)) }
    // Changed elsewhere (a merge, a paste): show the new list.
    if (Languages.split(text) != values) text = Languages.join(values)
    // Matching a name scans every ISO language: only again when the text changes, not on every recomposition.
    val shown = remember(text) { Languages.displayList(Languages.toStoredList(Languages.split(text))) }
    val support = if (text.isNotBlank() && shown != Languages.join(Languages.split(text))) stringResource(R.string.edit_language_saved_as, shown)
    else stringResource(R.string.edit_languages_hint)
    FormRow(icon, stringResource(R.string.edit_languages), modifier.padding(bottom = FormTokens.groupGap)) {
        EditorField(
            stringResource(R.string.edit_languages), text, shape = formFieldShape(0, 1), cap = KeyboardCapitalization.Words,
            locked = locked, focus = focus, support = support,
        ) {
            text = it
            onChange(Languages.split(it))
        }
    }
}

/**
 * The name in their own language, under the name: the name as they write it, its language (with the one its script
 * suggests offered as a chip), and on request its first and last name. "⊖" removes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeNameRow(name: NativeName, locked: Boolean, focus: FocusRequester, onChange: (NativeName) -> Unit, onRemove: () -> Unit) {
    var parts by rememberSaveable { mutableStateOf(false) }
    val showParts = parts || name.given.isNotBlank() || name.family.isNotBlank()
    val lines = if (showParts) 4 else 2
    val stored = remember(name.language) { Languages.toStored(name.language) }
    val languageShown = remember(stored) { Languages.display(stored) }
    val suggested = remember(name.full, name.given, name.family) { Scripts.suggestLanguage(name.shown) }
    FormRow(
        Icons.Rounded.Translate, stringResource(R.string.edit_native_name), Modifier.padding(top = FormTokens.groupGap),
        end = if (!locked) { { RemoveButton(stringResource(R.string.edit_remove_native_name), onRemove) } } else null,
    ) {
        EditorField(
            stringResource(R.string.edit_native_name_full), name.full, shape = formFieldShape(0, lines), cap = KeyboardCapitalization.Words,
            locked = locked, focus = focus, support = stringResource(R.string.edit_native_name_hint),
        ) { onChange(name.copy(full = it)) }
        if (showParts) {
            Spacer(Modifier.height(FormTokens.segmentGap))
            EditorField(
                stringResource(R.string.edit_native_name_given), name.given, shape = formFieldShape(1, lines), cap = KeyboardCapitalization.Words, locked = locked,
            ) { onChange(name.copy(given = it)) }
            Spacer(Modifier.height(FormTokens.segmentGap))
            EditorField(
                stringResource(R.string.edit_native_name_family), name.family, shape = formFieldShape(2, lines), cap = KeyboardCapitalization.Words, locked = locked,
            ) { onChange(name.copy(family = it)) }
        }
        Spacer(Modifier.height(FormTokens.segmentGap))
        EditorField(
            stringResource(R.string.edit_native_language), name.language, shape = formFieldShape(lines - 1, lines), cap = KeyboardCapitalization.Words,
            locked = locked,
            support = if (name.language.isNotBlank() && languageShown != name.language.trim()) stringResource(R.string.edit_language_saved_as, languageShown) else null,
        ) { onChange(name.copy(language = it)) }
        val offer = suggested?.takeIf { !locked && name.language.isBlank() }
        if (offer != null || (!showParts && !locked)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                offer?.let { tag ->
                    AssistChip(
                        onClick = { onChange(name.copy(language = tag)) },
                        label = { Text(stringResource(R.string.edit_native_language_use, Languages.display(tag))) },
                        shape = ParleyShapes.pill,
                    )
                }
                if (!showParts && !locked) {
                    AssistChip(onClick = { parts = true }, label = { Text(stringResource(R.string.edit_native_name_parts)) }, shape = ParleyShapes.pill)
                }
            }
        }
    }
}

/**
 * Under the name, when it helps: "Add an English spelling" for a name typed in another script (the typed name becomes
 * the name in their language, and the main name its Latin spelling, ready to edit), or "Add name in their language"
 * when they have a language. Nothing otherwise: the "Add" chips still offer the field.
 */
@Composable
internal fun NativeNameOffer(composedName: String, hasLanguages: Boolean, onSpell: () -> Unit, onAdd: () -> Unit) {
    val nonLatin = remember(composedName) { Scripts.isNonLatin(composedName) }
    if (!nonLatin && !hasLanguages) return
    Box(Modifier.padding(start = FormTokens.gutter - 12.dp, top = 4.dp)) {
        if (nonLatin) {
            TextButton(onSpell, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.edit_english_spelling)) }
        } else {
            TextButton(onAdd, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.edit_native_name_offer)) }
        }
    }
}

/**
 * The countries they are a citizen of, as chips (each removes itself), and "Add a country" with the country picker.
 * Stored as ISO codes, shown by name. Never shown on a call screen, which the line under it says.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CitizenshipRow(codes: List<String>, locked: Boolean, modifier: Modifier, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    FormRow(Icons.Rounded.Flag, stringResource(R.string.edit_citizenship), modifier.padding(bottom = FormTokens.groupGap)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = FormTokens.fieldHeight)) {
            codes.forEach { code ->
                val country = Citizenship.display(code)
                val remove = stringResource(R.string.edit_remove_citizenship, country)
                InputChip(
                    selected = false, enabled = !locked, onClick = { onRemove(code) }, label = { Text(country) },
                    trailingIcon = { Icon(Icons.Rounded.Close, remove, Modifier.size(InputChipDefaults.IconSize)) },
                    shape = ParleyShapes.pill,
                )
            }
            if (!locked) {
                AssistChip(
                    onClick = onAdd, label = { Text(stringResource(R.string.edit_citizenship_add)) },
                    leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(AssistChipDefaults.IconSize)) }, shape = ParleyShapes.pill,
                )
            }
        }
        Text(
            stringResource(R.string.edit_citizenship_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
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

/**
 * "Add an English spelling": the name typed in another script becomes the name in their language (with the language
 * its script suggests), and each part of the main name its Latin spelling ("Иван" → "Ivan"), ready to edit.
 */
internal fun withEnglishSpelling(d: ContactDetails): ContactDetails {
    fun spell(s: String) = if (Scripts.hasNonLatin(s)) Romanizer.spelling(s.trim()) ?: s else s
    val language = Scripts.suggestLanguage(d.composedName).orEmpty()
    val parts = NativeName(given = listOf(d.given, d.middle).filter { it.isNotBlank() }.joinToString(" ").trim(), family = d.family.trim(), language = language)
    val native = parts.copy(full = parts.shown.ifEmpty { d.composedName })
    return d.copy(
        nativeName = native, prefix = spell(d.prefix), given = spell(d.given), middle = spell(d.middle), family = spell(d.family), suffix = spell(d.suffix),
    )
}
