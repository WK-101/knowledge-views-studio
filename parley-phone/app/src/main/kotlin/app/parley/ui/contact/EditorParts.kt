package app.parley.ui.contact

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.heading
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
import app.parley.ui.ParleySheet
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

// Building blocks of the contact editor (4.2): tonal fields stacked per group, one icon per group in the gutter,
// the type as a quiet pill at the end of each value. See docs/EDITOR_DESIGN.md.

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
 * The type of a value ("Mobile ▾") as a quiet pill: a quick menu of the usual types, the current one ticked, plus
 * "Custom…". It sits at the end of the value's field, or under it when the line is too narrow ([TypedLine]).
 */
@Composable
internal fun TypePill(current: String, options: List<String>, enabled: Boolean = true, onOpen: (() -> Unit)? = null, onPick: (Int) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    val desc = stringResource(R.string.editor_type, current)
    val change = stringResource(R.string.editor_change_type)
    Box(Modifier.padding(end = 8.dp)) {
        Surface(
            onClick = { if (onOpen != null) onOpen() else open = true }, enabled = enabled,
            shape = ParleyShapes.pill, color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = desc
                onClick(label = change) { if (onOpen != null) onOpen() else open = true; true }
            },
        ) {
            Row(Modifier.heightIn(min = 32.dp).padding(start = 12.dp, end = if (enabled) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 128.dp),
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
 * A value's field with its type [pill] at the field's end, or on its own line under the field when the field would
 * get too narrow (small screens, large fonts), so the value itself always has room.
 */
@Composable
internal fun TypedLine(pill: (@Composable () -> Unit)?, field: @Composable (trailing: (@Composable () -> Unit)?) -> Unit) {
    if (pill == null) { field(null); return }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val below = maxWidth < 232.dp || fontScale >= 1.5f
        if (!below) {
            field(pill)
        } else {
            Column {
                field(null)
                Box(Modifier.padding(top = 4.dp, bottom = 2.dp)) { pill() }
            }
        }
    }
}

/** Large round photo with an edit badge; "Add photo" / "Change photo" and "Remove photo" under it. */
@Composable
internal fun PhotoHeader(name: String, photo: String?, onPick: () -> Unit, onRemove: () -> Unit) {
    val has = photo != null
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val pickLabel = stringResource(R.string.edit_choose_photo)
        val photoDesc = stringResource(R.string.editor_photo_desc)
        Box(
            Modifier.semantics(mergeDescendants = true) { contentDescription = photoDesc }
                .clip(CircleShape).clickable(onClickLabel = pickLabel, onClick = onPick),
        ) {
            Avatar(name.ifBlank { "?" }, photo, 120.dp)
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                border = BorderStroke(3.dp, MaterialTheme.colorScheme.surface),
                modifier = Modifier.align(Alignment.BottomEnd).size(40.dp),
            ) { Box(contentAlignment = Alignment.Center) { Icon(if (has) Icons.Rounded.Edit else Icons.Rounded.AddAPhoto, null, Modifier.size(20.dp)) } }
        }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onPick) { Text(stringResource(if (has) R.string.editor_edit_photo else R.string.editor_add_photo)) }
            if (has) TextButton(onRemove) { Text(stringResource(R.string.edit_remove_photo), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** One entry of the "Add more info" sheet. */
internal class MoreEntry(val icon: ImageVector, val title: String, val subtitle: String, val onPick: () -> Unit)

/** "Add more info": a sheet listing only the kinds of fields this contact doesn't show yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreInfoSheet(entries: List<MoreEntry>, onDismiss: () -> Unit) {
    ParleySheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.editor_more_info_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
            )
            entries.forEach { e ->
                ListItem(
                    leadingContent = {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Icon(e.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                    },
                    headlineContent = { Text(e.title) },
                    supportingContent = { Text(e.subtitle) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = e.onPick).padding(horizontal = 8.dp),
                )
            }
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
