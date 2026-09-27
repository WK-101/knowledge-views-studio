package app.parley.ui.contact

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.TextSearch
import app.parley.common.people.RelationType
import app.parley.ui.Avatar
import app.parley.ui.people.RelationText

// Building blocks of the redesigned contact editor.

/** Where a piece sits in its group card: the pieces of one group stack into one rounded card. */
internal enum class SegPos { Top, Middle, Bottom, Single }

private val CardRadius = 24.dp

/**
 * One piece of a group card. Groups are split into pieces (head, one per row, the "Add" row) so each row can be
 * its own lazy item and animate in and out on its own, while the pieces still read as one card.
 */
@Composable
internal fun Segment(pos: SegPos, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = when (pos) {
        SegPos.Top -> RoundedCornerShape(topStart = CardRadius, topEnd = CardRadius)
        SegPos.Middle -> RoundedCornerShape(0.dp)
        SegPos.Bottom -> RoundedCornerShape(bottomStart = CardRadius, bottomEnd = CardRadius)
        SegPos.Single -> RoundedCornerShape(CardRadius)
    }
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        val top = if (pos == SegPos.Top || pos == SegPos.Single) 12.dp else 4.dp
        val bottom = if (pos == SegPos.Bottom || pos == SegPos.Single) 8.dp else 4.dp
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = top, bottom = bottom), content = content)
    }
}

/** A group's title line: its icon in a tinted circle, then the title (a TalkBack heading). */
@Composable
internal fun GroupHead(icon: ImageVector, title: String) {
    Row(
        Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** "+ Add phone": a full-width 48dp row with the + in a tinted circle. */
@Composable
internal fun AddRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The red "−" that removes one row (48dp target). */
@Composable
internal fun RemoveButton(description: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(Icons.Rounded.RemoveCircle, description, tint = MaterialTheme.colorScheme.error) }
}

/** Fields whose Data row the provider marks read-only: shown, but locked. */
internal val LocalLocked = staticCompositionLocalOf<Set<Long>> { emptySet() }

@Composable
internal fun LockIcon() = Icon(Icons.Rounded.Lock, stringResource(R.string.edit_locked))

internal val FieldShape = RoundedCornerShape(14.dp)

/**
 * A single-line editor field: IME "Next" moves on to the following field; [hint] is a gentle note shown once the
 * field was left (never while typing); [locked] rows are read-only with a lock.
 */
@Composable
internal fun EditorField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    cap: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboard: KeyboardType = KeyboardType.Text,
    locked: Boolean = false,
    focus: FocusRequester? = null,
    prefix: String? = null,
    placeholder: String? = null,
    hint: String? = null,
    support: String? = null,
    error: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    var left by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val showHint = hint != null && left && !focused
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, shape = FieldShape,
        modifier = modifier.fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { s -> if (focused && !s.isFocused) left = true; focused = s.isFocused },
        readOnly = locked,
        prefix = prefix?.let { p -> { Text(p) } },
        placeholder = placeholder?.let { p -> { Text(p) } },
        trailingIcon = if (locked) { { LockIcon() } } else trailing,
        supportingText = when {
            showHint -> { { Text(hint.orEmpty(), color = MaterialTheme.colorScheme.tertiary) } }
            support != null -> { { Text(support) } }
            else -> null
        },
        isError = error,
        keyboardOptions = KeyboardOptions(capitalization = cap, keyboardType = keyboard, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions.Default,
    )
}

/** The inline type chip under a row ("Mobile ▾"): a quick menu of the usual types plus "Custom…". */
@Composable
internal fun TypeChip(current: String, options: List<String>, enabled: Boolean = true, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val desc = stringResource(R.string.editor_type, current)
    val change = stringResource(R.string.editor_change_type)
    Box {
        AssistChip(
            onClick = { open = true }, enabled = enabled,
            label = { Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = if (enabled) { { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(AssistChipDefaults.IconSize)) } } else null,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.semantics {
                contentDescription = desc
                onClick(label = change) { open = true; true }
            },
        )
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(o) }, onClick = { open = false; onPick(i) },
                    trailingIcon = if (o == current) { { Icon(Icons.Rounded.Check, null) } } else null,
                )
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
            Avatar(name.ifBlank { "?" }, photo, 128.dp)
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                border = BorderStroke(3.dp, MaterialTheme.colorScheme.surface),
                modifier = Modifier.align(Alignment.BottomEnd).size(40.dp),
            ) { Box(contentAlignment = Alignment.Center) { Icon(if (has) Icons.Rounded.Edit else Icons.Rounded.AddAPhoto, null, Modifier.size(20.dp)) } }
        }
        Row(horizontalArrangement = Arrangement.Center) {
            TextButton(onPick) { Text(stringResource(if (has) R.string.editor_edit_photo else R.string.editor_add_photo)) }
            if (has) TextButton(onRemove) { Text(stringResource(R.string.edit_remove_photo), color = MaterialTheme.colorScheme.error) }
        }
        if (name.isNotBlank()) {
            Text(
                name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp).animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
            )
        }
    }
}

/** One entry of the "Add more info" sheet. */
internal class MoreEntry(val icon: ImageVector, val title: String, val subtitle: String, val onPick: () -> Unit)

/** "Add more info": a sheet listing only the kinds of fields this contact doesn't show yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreInfoSheet(entries: List<MoreEntry>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(false) }
    val res = LocalResources.current
    val shown = remember(query, res) { RelationText.search(res, query) }
    AlertDialog(
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
    var query by remember { mutableStateOf("") }
    val shown = remember(all, query) { all.orEmpty().filter { TextSearch.matches(query, it.displayName, it.phones.map { p -> p.number }) }.take(200) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_choose_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.main_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { c ->
                        ListItem(
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
    var text by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_custom_label)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(stringResource(R.string.edit_custom_placeholder)) }) },
        confirmButton = { TextButton({ onDismiss(); onDone(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.main_ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}
