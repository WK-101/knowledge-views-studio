package app.parley.ui.contact

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.TextSearch
import app.parley.common.people.RelationType
import app.parley.ui.Avatar
import app.parley.ui.people.RelationText
import app.parley.ui.ParleyDialog
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyShapes
import androidx.compose.ui.graphics.Shape
import app.parley.ui.ParleyListItem
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import app.parley.common.people.PhoneTyping
import app.parley.ui.ParleyFormField
import app.parley.ui.formFieldShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.unit.Dp
import app.parley.common.people.EditorForm
import app.parley.ui.FormTokens

// Building blocks of the contact editor: compact 48 dp tonal fields stacked per group, one icon per group in the
// gutter, the type as a quiet selector inside each value's field, one line of "Add" chips. See docs/EDITOR_DESIGN.md.

/** The quiet "⊖" that removes one row (48 dp target, in the form's end column). */
@Composable
internal fun RemoveButton(description: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(Icons.Rounded.RemoveCircleOutline, description, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Fields whose Data row the provider marks read-only: shown, but locked. */
internal val LocalLocked = staticCompositionLocalOf<Set<Long>> { emptySet() }

/** The country phone numbers are formatted for while typing. */
val LocalCountryIso = staticCompositionLocalOf { "US" }

@Composable
internal fun LockIcon() = Icon(Icons.Rounded.Lock, stringResource(R.string.edit_locked))

/**
 * Shows a phone number grouped the local way while it's typed ([PhoneTyping]); what's saved stays as typed.
 * Numbers with their own spaces, dashes or pauses show unchanged.
 */
private class PhoneVisual(private val iso: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val p = PhoneTyping.of(text.text, iso)
        if (p.shown == text.text) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(
            AnnotatedString(p.shown),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = p.toShown(offset)
                override fun transformedToOriginal(offset: Int) = p.toTyped(offset)
            },
        )
    }

    override fun equals(other: Any?) = other is PhoneVisual && other.iso == iso
    override fun hashCode() = iso.hashCode()
}

/**
 * A single-line editor field: IME "Next" moves on to the following field ("Done" closes the keyboard where asked);
 * [hint] is a gentle note shown once the field was left (never while typing); [locked] rows are read-only with a
 * lock. [phone] formats the number as it's typed.
 */
@Composable
internal fun EditorField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    shape: Shape = formFieldShape(0, 1),
    cap: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboard: KeyboardType = KeyboardType.Text,
    locked: Boolean = false,
    focus: FocusRequester? = null,
    prefix: String? = null,
    placeholder: String? = null,
    hint: String? = null,
    support: String? = null,
    error: Boolean = false,
    phone: Boolean = false,
    ime: ImeAction = ImeAction.Next,
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    var left by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val showHint = hint != null && left && !focused
    val focusManager = LocalFocusManager.current
    val iso = LocalCountryIso.current
    val ltr = keyboard == KeyboardType.Phone || keyboard == KeyboardType.Email || keyboard == KeyboardType.Uri
    ParleyFormField(
        value, onChange, label, shape = shape,
        modifier = modifier.fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { s ->
                if (focused && !s.hasFocus) left = true
                focused = s.hasFocus
            },
        readOnly = locked,
        prefix = prefix,
        placeholder = placeholder,
        trailing = if (locked) { { LockIcon() } } else trailing,
        supporting = if (showHint) hint else support,
        supportingColor = if (showHint) MaterialTheme.colorScheme.tertiary else null,
        isError = error,
        forceLtr = ltr,
        visualTransformation = if (phone) remember(iso) { PhoneVisual(iso) } else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = cap, keyboardType = keyboard, imeAction = ime,
            autoCorrectEnabled = if (ltr || cap == KeyboardCapitalization.Words) false else null,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}

/**
 * The type of a value ("Mobile ▾") as a quiet text button at the end of its field: a quick menu of the usual types,
 * the current one ticked, plus "Custom…". Under the field only at large font sizes or on a narrow line ([TypedLine]).
 */
@Composable
internal fun TypePill(current: String, options: List<String>, enabled: Boolean = true, onOpen: (() -> Unit)? = null, onPick: (Int) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    val desc = stringResource(R.string.editor_type, current)
    val change = stringResource(R.string.editor_change_type)
    Box {
        Surface(
            onClick = { if (onOpen != null) onOpen() else open = true }, enabled = enabled,
            shape = ParleyShapes.pill, color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = desc
                onClick(label = change) { if (onOpen != null) onOpen() else open = true; true }
            },
        ) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(start = 8.dp, end = if (enabled) 2.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = FormTokens.typeMaxWidth),
                )
                if (enabled) Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(20.dp))
            }
        }
        DropdownMenu(open, { open = false }, shape = ParleyShapes.tile) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(o) }, onClick = { open = false; onPick(i) },
                    trailingIcon = if (o == current) { { Icon(Icons.Rounded.Check, null) } } else null,
                )
            }
        }
    }
}

/**
 * A value's field with its type selector ([pill]) inside the field at its end. Only when the field would get too
 * narrow for the value, or the font is large (≥ 1.3), does the selector move to its own line under the field
 * ([EditorForm.typeBelow]), so the value always has room without every row paying for a second line.
 */
@Composable
internal fun TypedLine(pill: (@Composable () -> Unit)?, field: @Composable (trailing: (@Composable () -> Unit)?) -> Unit) {
    if (pill == null) { field(null); return }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (!EditorForm.typeBelow(maxWidth.value, fontScale)) {
            field(pill)
        } else {
            Column {
                field(null)
                Box(Modifier.padding(start = 8.dp)) { pill() }
            }
        }
    }
}

/**
 * The contact's photo, small enough to sit beside the name fields, with an edit badge. With no photo a tap opens the
 * picker; with one, a menu offers "Change photo" and a red "Remove photo".
 */
@Composable
internal fun CompactPhoto(name: String, photo: String?, onPick: () -> Unit, onRemove: () -> Unit, size: Dp = FormTokens.headerPhoto) {
    val has = photo != null
    var menu by remember { mutableStateOf(false) }
    val pickLabel = stringResource(if (has) R.string.editor_edit_photo else R.string.editor_add_photo)
    val photoDesc = stringResource(R.string.editor_photo_desc)
    Box {
        Box(
            Modifier.semantics(mergeDescendants = true) { contentDescription = photoDesc }
                .clip(CircleShape).clickable(onClickLabel = pickLabel) { if (has) menu = true else onPick() },
        ) {
            Avatar(name.ifBlank { "?" }, photo, size)
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                modifier = Modifier.align(Alignment.BottomEnd).size(28.dp),
            ) { Box(contentAlignment = Alignment.Center) { Icon(if (has) Icons.Rounded.Edit else Icons.Rounded.AddAPhoto, null, Modifier.size(16.dp)) } }
        }
        DropdownMenu(menu, { menu = false }, shape = ParleyShapes.tile) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_edit_photo)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                onClick = { menu = false; onPick() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_remove_photo), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}

/** One "Add" chip: a kind of field this contact can take, with its icon. */
internal class AddChoice(val icon: ImageVector, val label: String, val onPick: () -> Unit)

/**
 * The editor's one add control: a line of small chips ("+ Email", "Work", "Date"…) for the kinds this contact can
 * still take, commonest first. It scrolls sideways on one line; at large font sizes it wraps instead, so no chip
 * is ever cut off where scrolling is harder to notice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddChips(choices: List<AddChoice>, modifier: Modifier = Modifier) {
    val wrap = LocalDensity.current.fontScale >= 1.3f
    val chip: @Composable (AddChoice) -> Unit = { c ->
        val desc = stringResource(R.string.editor_add_field, c.label)
        AssistChip(
            onClick = c.onPick,
            label = { Text(c.label, maxLines = 1) },
            leadingIcon = { Icon(c.icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
            shape = ParleyShapes.pill,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.primary,
            ),
            border = null,
            modifier = Modifier.semantics { contentDescription = desc },
        )
    }
    if (wrap) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices.forEach { chip(it) } }
    } else {
        Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { chip(it) }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
internal fun RelationTypeDialog(onDismiss: () -> Unit, onPick: (RelationType?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var custom by rememberSaveable { mutableStateOf(false) }
    val res = LocalResources.current
    val shown = remember(query, res) { RelationText.search(res, query) }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_relation)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.main_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.key }) { t ->
                        ListItem(
                            headlineContent = { Text(RelationText.label(res, t)) },
                            supportingContent = { Text(RelationText.group(res, t.group)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(t) },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.edit_custom_more)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { custom = true },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
    if (custom) CustomLabelDialog(query.ifBlank { null }, { custom = false }) { l -> custom = false; onPick(RelationType(key = "custom", label = l)) }
}

/** Pick the related person from your contacts (their lookup key is remembered, so renames don't break it). */
@Composable
fun ContactChooserDialog(vm: AppViewModel, onDismiss: () -> Unit, onPick: (id: Long, name: String, lookupKey: String) -> Unit) {
    val all by vm.contacts.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(all, query) { all.orEmpty().filter { TextSearch.matches(query, it.displayName, it.phones.map { p -> p.number }) }.take(200) }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_choose_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.main_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { c ->
                        ParleyListItem(
                            leadingContent = { Avatar(c.displayName, c.photoUri, 36.dp) },
                            headlineContent = { Text(c.displayName) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(c.id, c.displayName, c.lookupKey) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

/** Free-text label for a phone, e-mail, date… (stored as TYPE_CUSTOM with this label; survives export). */
@Composable
internal fun CustomLabelDialog(initial: String?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    ConfirmDialog(
        title = stringResource(R.string.edit_custom_label),
        text = null,
        confirmLabel = stringResource(R.string.main_ok),
        onConfirm = { onDismiss(); onDone(text.trim()) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.main_cancel),
        confirmEnabled = text.isNotBlank(),
        content = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(stringResource(R.string.edit_custom_placeholder)) }) },
    )
}
