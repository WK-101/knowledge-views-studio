package com.todocompanion.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.data.entity.NoteEntity
import com.todocompanion.app.data.entity.NoteRevisionEntity
import com.todocompanion.app.domain.NoteEditing
import com.todocompanion.app.ui.components.borderlessFieldColors

/**
 * Wave A — the Markdown body editor with real editing "feel": a formatting toolbar that wraps the
 * selection (or drops an empty pair with the caret inside), smart list-continuation on Enter, and a
 * coalesced undo/redo. Owns a [TextFieldValue] so it controls selection/caret; syncs back to the
 * String [value] via [onValueChange]. All logic lives in [NoteEditing] (pure + unit-tested); this is
 * the thin Compose shell over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteBodyEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    noteTitles: List<String> = emptyList(),
    tagNames: List<String> = emptyList(),
    liveStyle: Boolean = false,
    type: com.todocompanion.app.domain.NoteAppearance.NoteType = com.todocompanion.app.domain.NoteAppearance.NoteType(),
    onFontScaleChange: (Int) -> Unit = {},
) {
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Resync only when the body changes from OUTSIDE (a toolbar-inserted link elsewhere, a fresh note);
    // our own edits set tfv first so value==tfv.text here and this is a no-op.
    LaunchedEffect(value) { if (value != tfv.text) tfv = TextFieldValue(value, TextRange(value.length)) }

    val undo = remember { mutableStateListOf<TextFieldValue>() }
    val redo = remember { mutableStateListOf<TextFieldValue>() }

    fun emit(next: TextFieldValue, snapshotPrev: Boolean) {
        if (snapshotPrev) { undo.add(tfv); if (undo.size > 120) undo.removeAt(0); redo.clear() }
        val changed = next.text != tfv.text
        tfv = next
        if (changed) onValueChange(next.text)
    }

    fun onFieldChange(new: TextFieldValue) {
        val old = tfv
        if (new.text == old.text) { tfv = new; return }   // caret/selection move only — don't churn undo/callback
        // Smart list continuation when a single '\n' was just inserted.
        val continued = if (new.text.length == old.text.length + 1 && new.selection.collapsed &&
            new.selection.start > 0 && new.text.getOrNull(new.selection.start - 1) == '\n'
        ) NoteEditing.continueList(new.text, new.selection.start) else null
        val next = if (continued != null) TextFieldValue(continued.text, TextRange(continued.selStart, continued.selEnd)) else new
        // Coalesce typing into word/line-level undo entries (WriteOn snapshots every keystroke; we don't).
        val lenDiff = next.text.length - old.text.length
        val lastChar = next.text.getOrNull(next.selection.start - 1)
        val boundary = continued != null || lenDiff !in 0..1 || undo.isEmpty() ||
            (lastChar != null && (lastChar.isWhitespace() || lastChar in ".,;:!?)]}\"'"))
        emit(next, snapshotPrev = boundary)
    }

    fun apply(edit: NoteEditing.Edit) = emit(TextFieldValue(edit.text, TextRange(edit.selStart, edit.selEnd)), true)

    // Wave R — insert a block (e.g. a visually-built table) at the caret, on its own blank-line-separated lines.
    fun insertBlock(block: String) {
        val pos = tfv.selection.start.coerceIn(0, tfv.text.length)
        val pre = tfv.text.substring(0, pos); val post = tfv.text.substring(pos)
        val lead = if (pre.isNotEmpty() && !pre.endsWith("\n")) "\n\n" else ""
        val trail = if (post.isNotEmpty() && !post.startsWith("\n")) "\n" else ""
        val insert = lead + block + "\n" + trail
        val caret = (pre + insert).length
        emit(TextFieldValue(pre + insert + post, TextRange(caret)), true)
    }
    var showTable by remember { mutableStateOf(false) }
    var showBlocks by remember { mutableStateOf(false) }   // "+" → block-insert sheet
    var moreFormat by remember { mutableStateOf(false) }    // "⋮" → secondary formatting row

    val onWrap: (String) -> Unit = { m -> apply(NoteEditing.wrapInline(tfv.text, tfv.selection.start, tfv.selection.end, m)) }
    val onLinePrefix: (String) -> Unit = { p -> apply(NoteEditing.insertLinePrefix(tfv.text, tfv.selection.start, p)) }
    val doUndo = { if (undo.isNotEmpty()) { val prev = undo.removeAt(undo.lastIndex); redo.add(tfv); tfv = prev; onValueChange(prev.text) } }
    val doRedo = { if (redo.isNotEmpty()) { val nx = redo.removeAt(redo.lastIndex); undo.add(tfv); tfv = nx; onValueChange(nx.text) } }

    Column(modifier.fillMaxSize()) {
        val cs = MaterialTheme.colorScheme
        val family = when (type.effectiveFont("system")) {
            "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
            "mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
            "sans" -> androidx.compose.ui.text.font.FontFamily.SansSerif
            else -> androidx.compose.ui.text.font.FontFamily.Default
        }
        val baseSize = 16.sp * type.scale()
        val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = family, fontSize = baseSize, lineHeight = baseSize * type.lineFactor(), color = cs.onSurface,
        )
        // Wave Q — inline Markdown live-styling: style syntax in place (identity offsets), so editing feel
        // matches the read view. Dimmed markers, styled bold/italic/code/links — Bear's Panda, on Android.
        val transform: androidx.compose.ui.text.input.VisualTransformation = remember(liveStyle, cs) {
            if (liveStyle) com.todocompanion.app.ui.components.MarkdownVisualTransformation(
                base = cs.onSurface, muted = cs.onSurfaceVariant, accent = cs.primary, code = cs.tertiary, quote = cs.outline,
            ) else androidx.compose.ui.text.input.VisualTransformation.None
        }
        // The body fills the surface as a tight, borderless field (BasicTextField — no Material padding),
        // so title and body read as one continuous page. The formatting bar is anchored at the bottom.
        BasicTextField(
            value = tfv,
            onValueChange = ::onFieldChange,
            readOnly = readOnly,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp, start = 16.dp, end = 16.dp),
            textStyle = bodyStyle,
            cursorBrush = SolidColor(cs.primary),
            visualTransformation = transform,
            decorationBox = { inner ->
                if (tfv.text.isEmpty()) Text("Start writing your note…", style = bodyStyle, color = cs.onSurfaceVariant)
                inner()
            },
        )
        if (!readOnly) {
            Column(Modifier.imePadding()) {
                // Wave M — [[wiki]] / #tag autocomplete and the "/" quick-insert palette, shown just above
                // the toolbar as you type.
                val slashQuery = if (tfv.selection.collapsed) NoteEditing.quickQuery(tfv.text, tfv.selection.start) else null
                if (slashQuery != null) {
                    val cmds = NoteEditing.filterQuick(slashQuery)
                    if (cmds.isNotEmpty()) QuickInsertBar(cmds) { cmd ->
                        val snippet = if (cmd.dynamic) dynamicSnippet(cmd.id) else cmd.snippet
                        apply(NoteEditing.applyQuick(tfv.text, tfv.selection.start, snippet, if (cmd.dynamic) -1 else cmd.caretOffset))
                    }
                }
                val linkQ = if (tfv.selection.collapsed) NoteEditing.linkAutocompleteQuery(tfv.text, tfv.selection.start) else null
                if (linkQ != null && noteTitles.isNotEmpty()) {
                    val q = linkQ.trim().lowercase()
                    val matches = noteTitles.asSequence().filter { it.isNotBlank() }.distinct()
                        .filter { q.isEmpty() || it.lowercase().contains(q) }
                        .sortedByDescending { it.lowercase().startsWith(q) }.take(8).toList()
                    if (matches.isNotEmpty()) TokenBar(matches.map { "[[$it]]" }, matches) { t -> apply(NoteEditing.applyLink(tfv.text, tfv.selection.start, t)) }
                }
                val tagQ = if (tfv.selection.collapsed) NoteEditing.tagAutocompleteQuery(tfv.text, tfv.selection.start) else null
                if (tagQ != null && tagNames.isNotEmpty()) {
                    val q = tagQ.trim().lowercase()
                    val matches = tagNames.asSequence().filter { it.isNotBlank() }.distinct()
                        .filter { it.lowercase().contains(q) }
                        .sortedByDescending { it.lowercase().startsWith(q) }.take(8).toList()
                    if (matches.isNotEmpty()) TokenBar(matches.map { "#$it" }, matches) { t -> apply(NoteEditing.applyTag(tfv.text, tfv.selection.start, t)) }
                }
                // A proper anchored bottom bar (full width, solid surface, top divider) — not floating chips.
                Surface(color = cs.surface, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        HorizontalDivider(color = cs.outlineVariant.copy(alpha = .6f))
                        if (moreFormat) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // NotesNook-style paragraph/heading selector + font-size stepper.
                                HeadingMenu(
                                    level = NoteEditing.headingLevelOf(tfv.text, tfv.selection.start),
                                    onPick = { lvl -> apply(NoteEditing.setLineHeading(tfv.text, tfv.selection.start, lvl)) },
                                )
                                SizeStepper(
                                    px = Math.round(16f * (type.scalePct / 100f)),
                                    onStep = { deltaPx ->
                                        val cur = Math.round(16f * (type.scalePct / 100f))
                                        val newPx = (cur + deltaPx).coerceIn(11, 32)
                                        onFontScaleChange(Math.round(newPx / 16f * 100f))
                                    },
                                )
                                Spacer(Modifier.width(2.dp))
                                BarIcon(Icons.Filled.FormatQuote, "Quote") { onLinePrefix("> ") }
                                BarIcon(Icons.Filled.FormatListBulleted, "Bulleted list") { onLinePrefix("- ") }
                                BarIcon(Icons.Filled.FormatListNumbered, "Numbered list") { onLinePrefix("1. ") }
                                BarIcon(Icons.Filled.CheckBox, "Checklist") { onLinePrefix("- [ ] ") }
                                BarIcon(Icons.Filled.TableChart, "Table") { showTable = true }
                                BarIcon(Icons.Filled.Functions, "Math") { insertBlock("$$\n\n$$") }
                                BarIcon(Icons.Filled.HorizontalRule, "Divider") { insertBlock("---") }
                            }
                            HorizontalDivider(color = cs.outlineVariant.copy(alpha = .35f))
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            BarIcon(Icons.Filled.Add, "Insert block") { showBlocks = true }
                            BarIcon(Icons.Filled.FormatBold, "Bold") { onWrap("**") }
                            BarIcon(Icons.Filled.FormatItalic, "Italic") { onWrap("*") }
                            BarIcon(Icons.Filled.FormatStrikethrough, "Strikethrough") { onWrap("~~") }
                            BarIcon(Icons.Filled.Code, "Inline code") { onWrap("`") }
                            BarIcon(Icons.Filled.MoreVert, if (moreFormat) "Fewer options" else "More options", active = moreFormat) { moreFormat = !moreFormat }
                            BarIcon(Icons.Filled.Undo, "Undo", enabled = undo.isNotEmpty()) { doUndo() }
                            BarIcon(Icons.Filled.Redo, "Redo", enabled = redo.isNotEmpty()) { doRedo() }
                        }
                    }
                }
            }
        }
    }
    if (showTable) TableEditorDialog(onInsert = { insertBlock(it); showTable = false }, onDismiss = { showTable = false })
    if (showBlocks) {
        ModalBottomSheet(onDismissRequest = { showBlocks = false }, sheetState = rememberModalBottomSheetState(), dragHandle = null) {
            Spacer(Modifier.height(8.dp))
            Text("Choose a block to insert", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp))
            val blocks = listOf<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>>(
                Triple(Icons.Filled.CheckBox, "Task list") { onLinePrefix("- [ ] ") },
                Triple(Icons.Filled.FormatListBulleted, "Bulleted list") { onLinePrefix("- ") },
                Triple(Icons.Filled.FormatListNumbered, "Numbered list") { onLinePrefix("1. ") },
                Triple(Icons.Filled.FormatQuote, "Quote") { onLinePrefix("> ") },
                Triple(Icons.Filled.Code, "Code block") { insertBlock("```\n\n```") },
                Triple(Icons.Filled.Functions, "Math & formulas") { insertBlock("$$\n\n$$") },
                Triple(Icons.Filled.TableChart, "Table") { showTable = true },
                Triple(Icons.Filled.HorizontalRule, "Horizontal rule") { insertBlock("---") },
            )
            blocks.forEach { (icon, label, action) ->
                Row(
                    Modifier.fillMaxWidth().clickable { action(); showBlocks = false }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A flat, borderless icon button for the bottom formatting bar (NotesNook-style continuous bar).
 *  Sized to fill the row generously — bigger touch target and glyph than a default IconButton. */
@Composable
private fun BarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            icon, contentDescription, modifier = Modifier.size(25.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** NotesNook-style paragraph / H1–H6 selector: a compact pill that shows the caret line's current
 *  block level and opens a dropdown to change it. [level] 0 = Paragraph, 1..6 = that heading. */
@Composable
private fun HeadingMenu(level: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val label = if (level in 1..6) "H$level" else "¶"
    Box {
        Surface(
            onClick = { open = true },
            shape = NotesTokens.Pill,
            color = if (level in 1..6) cs.secondaryContainer else cs.surfaceVariant.copy(alpha = .5f),
            modifier = Modifier.height(38.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (level in 1..6) "Heading $level" else "Paragraph",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (level in 1..6) cs.onSecondaryContainer else cs.onSurface,
                    maxLines = 1,
                )
                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items = listOf(0 to "Paragraph", 1 to "Heading 1", 2 to "Heading 2", 3 to "Heading 3", 4 to "Heading 4", 5 to "Heading 5", 6 to "Heading 6")
            items.forEach { (lvl, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            style = if (lvl in 1..3) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (lvl in 1..6) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (lvl == level) cs.primary else cs.onSurface,
                        )
                    },
                    onClick = { onPick(lvl); open = false },
                )
            }
        }
    }
}

/** A "− 16px +" font-size stepper (NotesNook parity). [px] is the shown size; [onStep] gets ±1. */
@Composable
private fun SizeStepper(px: Int, onStep: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = NotesTokens.Pill, color = cs.surfaceVariant.copy(alpha = .5f), modifier = Modifier.height(38.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(-1) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Remove, "Smaller text", Modifier.size(19.dp), tint = cs.onSurface)
            }
            Text("${px}px", style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1)
            IconButton(onClick = { onStep(1) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Add, "Larger text", Modifier.size(19.dp), tint = cs.onSurface)
            }
        }
    }
}

/** Wave G — the slash-command chip row shown while typing a `/query` at line start. */
@Composable
private fun QuickInsertBar(commands: List<NoteEditing.QuickCommand>, onPick: (NoteEditing.QuickCommand) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(commands, key = { it.id }) { cmd ->
            Surface(
                onClick = { onPick(cmd) },
                shape = NotesTokens.Pill,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.height(34.dp),
            ) {
                Row(Modifier.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cmd.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("  ${cmd.hint}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .6f), maxLines = 1)
                }
            }
        }
    }
}

/** Wave M — a chip row of autocomplete candidates ([[wiki]] titles or #tags); [labels] are shown,
 *  the parallel [values] are handed to [onPick]. */
@Composable
private fun TokenBar(labels: List<String>, values: List<String>, onPick: (String) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(labels.indices.toList(), key = { it }) { i ->
            Surface(
                onClick = { onPick(values[i]) },
                shape = NotesTokens.Pill,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.height(34.dp),
            ) {
                Row(Modifier.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(labels[i], style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                }
            }
        }
    }
}

/** Resolve a dynamic quick-insert snippet (date/time) at insert time — fully local, no locale surprises. */
private fun dynamicSnippet(id: String): String = when (id) {
    "date" -> java.time.LocalDate.now().toString()
    "time" -> java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }
    else -> ""
}


/**
 * Wave R — a visual GFM table editor. Build/resize a grid of cells and insert it as Markdown; the pure
 * [com.todocompanion.app.domain.MarkdownTable] serializes it. No more hand-aligning pipes.
 */
@Composable
fun TableEditorDialog(initial: String? = null, onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    val start = remember { initial?.let { com.todocompanion.app.domain.MarkdownTable.parse(it) } ?: com.todocompanion.app.domain.MarkdownTable.empty(2, 2) }
    val headers = remember { mutableStateListOf<String>().apply { addAll(start.headers) } }
    val rows = remember { mutableStateListOf<SnapshotStateListWrapper>().apply { start.rows.forEach { add(SnapshotStateListWrapper(it)) } } }
    fun cols() = headers.size
    fun addCol() { headers.add(""); rows.forEach { it.cells.add("") } }
    fun removeCol() { if (cols() > 1) { headers.removeAt(headers.lastIndex); rows.forEach { if (it.cells.isNotEmpty()) it.cells.removeAt(it.cells.lastIndex) } } }
    fun addRow() { rows.add(SnapshotStateListWrapper(List(cols()) { "" })) }
    fun removeRow() { if (rows.size > 1) rows.removeAt(rows.lastIndex) }
    fun build(): String {
        val t = com.todocompanion.app.domain.MarkdownTable.Table(headers.toList(), rows.map { it.cells.toList() })
        return com.todocompanion.app.domain.MarkdownTable.serialize(t)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onInsert(build()) }) { Text("Insert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Table") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            headers.indices.forEach { c ->
                                TableCell(headers[c], header = true) { headers[c] = it }
                            }
                        }
                        rows.forEach { r ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (0 until cols()).forEach { c ->
                                    TableCell(r.cells.getOrElse(c) { "" }) { v -> while (r.cells.size <= c) r.cells.add(""); r.cells[c] = v }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniBtn("+ Col") { addCol() }; MiniBtn("− Col") { removeCol() }
                    MiniBtn("+ Row") { addRow() }; MiniBtn("− Row") { removeRow() }
                }
            }
        },
    )
}

/** A mutable row of cells, wrapped so a [mutableStateListOf] of rows is stable. */
class SnapshotStateListWrapper(initial: List<String>) {
    val cells = androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(initial) }
}

@Composable
private fun TableCell(value: String, header: Boolean = false, onChange: (String) -> Unit) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        modifier = Modifier.defaultMinSize(minWidth = 92.dp).heightIn(min = 48.dp),
        textStyle = if (header) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyMedium,
        colors = borderlessFieldColors(),
        placeholder = { Text(if (header) "Header" else "", style = MaterialTheme.typography.labelSmall) },
    )
}

@Composable
private fun MiniBtn(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.height(36.dp)) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/**
 * Wave R — reorder a note's heading-delimited sections (move whole sections up/down) without cut-and-paste.
 * Pure logic in [com.todocompanion.app.domain.MarkdownSections].
 */
@Composable
fun SectionReorderDialog(body: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    var working by remember { mutableStateOf(body) }
    val sections = remember(working) { com.todocompanion.app.domain.MarkdownSections.sections(working) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(working) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Reorder sections") },
        text = {
            if (sections.count { it.heading.isNotEmpty() } < 2) {
                Text("Add at least two headings (#, ##, …) to reorder a note by section.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(sections.size) { i ->
                        val s = sections[i]
                        val label = s.heading.ifEmpty { "(intro)" }.trimStart('#', ' ').ifBlank { "(intro)" }
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("·".repeat((s.level - 1).coerceAtLeast(0)) + " " + label, Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            Text("↑", Modifier.clickable { if (i > 0) working = com.todocompanion.app.domain.MarkdownSections.move(working, i, i - 1) }.padding(8.dp),
                                color = if (i > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
                            Text("↓", Modifier.clickable { if (i < sections.lastIndex) working = com.todocompanion.app.domain.MarkdownSections.move(working, i, i + 1) }.padding(8.dp),
                                color = if (i < sections.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
                        }
                    }
                }
            }
        },
    )
}

/** Wave V — Notes Wrapped: a locally-generated yearly recap. Pure stats in NoteWrapped. */
@Composable
fun NoteWrappedDialog(stats: com.todocompanion.app.domain.NoteWrapped.Stats, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Notes Wrapped ${stats.year}") },
        text = {
            if (stats.isEmpty) Text(
                "No notes from ${stats.year} yet. Come back once you've written a few — your recap is generated right here on your device.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else Column {
                AboutRow("Notes written", "${stats.created}")
                AboutRow("Words", "${stats.words}")
                AboutRow("Busiest month", "${stats.busiestMonth} (${stats.busiestMonthCount})")
                AboutRow("Longest note", "${stats.longestTitle} · ${stats.longestWords} words")
                if (stats.journalDays > 0) AboutRow("Journal days", "${stats.journalDays}")
                if (stats.distinctTags > 0) AboutRow("Tags used", "${stats.distinctTags}")
                if (stats.topTags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Top tags", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    stats.topTags.forEach { (t, c) ->
                        Text("#$t · $c", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    }
                }
            }
        },
    )
}

/** Wave U — pick a cross-module template (a note scaffold that pulls tasks/events/habits in live), plus
 *  the user's own saved templates. "Save current note as template" captures the open note's body so any
 *  scaffold you build by hand becomes reusable; custom ones can be deleted here. */
@Composable
fun NoteTemplateDialog(
    custom: List<com.todocompanion.app.domain.NoteTemplates.Template>,
    onPick: (com.todocompanion.app.domain.NoteTemplates.Template) -> Unit,
    onSaveCurrent: (name: String, emoji: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("📄") }
    val label = { text: String -> @Composable {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (creating) TextButton(enabled = name.isNotBlank(), onClick = { onSaveCurrent(name, emoji); onDismiss() }) { Text("Save") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        dismissButton = { if (creating) TextButton(onClick = { creating = false }) { Text("Back") } },
        title = { Text(if (creating) "Save note as template" else "Templates") },
        text = {
            if (creating) {
                Column {
                    Text("This saves the current note's content as a reusable template.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(2) }, singleLine = true,
                            label = { Text("Icon") }, modifier = Modifier.width(88.dp))
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                            label = { Text("Template name") }, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (custom.isNotEmpty()) {
                        item { label("YOUR TEMPLATES")() }
                        items(custom, key = { it.id }) { t ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable { onPick(t) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(t.emoji + "  ", style = MaterialTheme.typography.titleMedium)
                                    Text(t.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                }
                                IconButton(onClick = { onDelete(t.id) }) {
                                    androidx.compose.material3.Icon(Icons.Filled.Delete, "Delete template ${t.name}",
                                        modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                    }
                    item { label("STARTER TEMPLATES")() }
                    items(com.todocompanion.app.domain.NoteTemplates.ALL, key = { it.id }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.emoji + "  ", style = MaterialTheme.typography.titleMedium)
                            Text(t.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                    item {
                        Row(Modifier.fillMaxWidth().clickable { name = ""; emoji = "📄"; creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Save current note as template…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
    )
}

/** Wave T — on-device "related notes" (shared tags / [[links]] / vocabulary, no cloud). Tap to open. */
@Composable
fun RelatedNotesDialog(hits: List<com.todocompanion.app.domain.NoteRelated.Hit>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Related notes") },
        text = {
            if (hits.isEmpty()) Text(
                "No related notes yet. Shared #tags, [[links]] and vocabulary surface connections here — all computed on your device, no cloud.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(hits, key = { it.id }) { h ->
                    Text(
                        h.title.ifBlank { "(untitled)" },
                        Modifier.fillMaxWidth().clickable { onOpen(h.id) }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    )
                }
            }
        },
    )
}

/** Wave S — render a note's frontmatter properties as compact key:value chips (shown above the read view). */
@Composable
fun NotePropertyChips(props: Map<String, String>) {
    LazyRow(Modifier.fillMaxWidth().padding(bottom = 6.dp, end = 36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(props.entries.toList(), key = { it.key }) { (k, v) ->
            Surface(shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp)) {
                    Text("$k ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(v.ifBlank { "—" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Wave S — edit a note's frontmatter properties (Obsidian Properties / Tana fields). Add/rename/remove
 * key:value pairs; on apply the pure [com.todocompanion.app.domain.NoteProperties] rewrites the leading
 * `---` block (an empty set removes it). Powers group-by board views and property predicates, on-device.
 */
@Composable
fun NotePropertiesDialog(body: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    val keys = remember { mutableStateListOf<String>().apply { addAll(com.todocompanion.app.domain.NoteProperties.parse(body).keys) } }
    val vals = remember { mutableStateListOf<String>().apply { addAll(com.todocompanion.app.domain.NoteProperties.parse(body).values) } }
    fun build(): String {
        val m = LinkedHashMap<String, String>()
        for (i in keys.indices) { val k = keys[i].trim(); if (k.isNotEmpty()) m[k] = vals.getOrElse(i) { "" } }
        return com.todocompanion.app.domain.NoteProperties.withProperties(body, m)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(build()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Properties") },
        text = {
            Column {
                if (keys.isEmpty()) Text("Add typed fields — status, rating, author… — to power board views and filters.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(keys.indices.toList(), key = { it }) { i ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextField(keys.getOrElse(i) { "" }, { keys[i] = it }, singleLine = true, placeholder = { Text("key") },
                                modifier = Modifier.weight(1f), colors = borderlessFieldColors(), textStyle = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(0.dp))
                            TextField(vals.getOrElse(i) { "" }, { while (vals.size <= i) vals.add(""); vals[i] = it }, singleLine = true, placeholder = { Text("value") },
                                modifier = Modifier.weight(1.2f), colors = borderlessFieldColors(), textStyle = MaterialTheme.typography.bodyMedium)
                            Text("✕", Modifier.clickable { if (i < keys.size) keys.removeAt(i); if (i < vals.size) vals.removeAt(i) }.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("＋ Add property", Modifier.fillMaxWidth().clickable { keys.add(""); vals.add("") }.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

/** Wave A — a note's "About" sheet: word/character counts, an estimated read time, and timestamps. */
@Composable
fun NoteAboutDialog(note: NoteEntity, onDismiss: () -> Unit) {
    val words = note.body.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    val chars = note.body.length
    val readMin = (words / 200.0).let { if (it < 1) "< 1 min" else "${Math.round(it)} min" }
    val outLinks = com.todocompanion.app.domain.NoteGrammar.WIKI_LINK.findAll(note.body).count()
    val tagCount = com.todocompanion.app.domain.NoteGrammar.TAG.findAll(note.body).count()
    fun fmt(ts: Long): String = if (ts <= 0L) "—" else runCatching {
        java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
    }.getOrDefault("—")
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("About this note") },
        text = {
            Column {
                AboutRow("Words", "$words")
                AboutRow("Characters", "$chars")
                AboutRow("Read time", readMin)
                if (outLinks > 0) AboutRow("Links out", "$outLinks")
                if (tagCount > 0) AboutRow("Tags", "$tagCount")
                AboutRow("Created", fmt(note.createdAt))
                AboutRow("Edited", fmt(note.updatedAt))
            }
        },
    )
}

/** Wave B — the local version-history timeline: each snapshot with its time, a ±char delta, and Restore. */
@Composable
fun NoteVersionHistoryDialog(
    revisions: List<NoteRevisionEntity>,
    onRestore: (NoteRevisionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    fun fmt(ts: Long): String = runCatching {
        java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }.getOrDefault("—")
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Version history") },
        text = {
            if (revisions.isEmpty()) {
                Text("No versions yet. Snapshots are captured automatically as you edit and close the note.")
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(revisions, key = { it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onRestore(r) }.padding(vertical = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(fmt(r.createdAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                val preview = r.title.ifBlank { r.body.take(60).ifBlank { "(empty)" } }
                                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text(
                                if (r.charDelta >= 0) "+${r.charDelta}" else "${r.charDelta}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("Restore", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
    )
}

/** Wave C/M — a table-of-contents / outline (fenced-code-aware), plus word/reading stats and a few
 *  Markdown lint nits. Pure logic lives in [com.todocompanion.app.domain.NoteOutline] / NoteLint. */
@Composable
fun NoteOutlineDialog(body: String, onDismiss: () -> Unit) {
    val headings = remember(body) { com.todocompanion.app.domain.NoteOutline.outline(body) }
    val stats = remember(body) { com.todocompanion.app.domain.NoteOutline.stats(body) }
    val issues = remember(body) { com.todocompanion.app.domain.NoteLint.lint(body) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Outline & stats") },
        text = {
            Column {
                Text(
                    "${stats.words} words · ${stats.readMinutes} min read · ${stats.lines} lines",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (headings.isEmpty()) {
                    Text("No headings yet. Use #, ## or ### to build an outline.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(headings.size) { i ->
                            val h = headings[i]
                            Text(
                                h.title,
                                Modifier.fillMaxWidth().padding(start = ((h.level - 1) * 14).dp, top = 6.dp, bottom = 6.dp),
                                style = if (h.level <= 1) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                            )
                        }
                    }
                }
                if (issues.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("${issues.size} style nit${if (issues.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    issues.take(4).forEach { iss ->
                        Text("· line ${iss.line + 1}: ${iss.message}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        },
    )
}

/**
 * Wave F/H — a note's reminder editor. Presets + a themed date/time picker choose a time; a "Repeat" row
 * (reusing the app's RRULE Recurrence presets) makes it recurring; "Keep reminding" re-nudges until opened;
 * and picking more times stacks multiple reminders. Fully offline — it just composes epoch-millis + an
 * RRULE the existing AlarmScheduler/Notifications engine fires (no new permission). [onApply] gets the
 * primary time (null = none), the RRULE, the extra one-shot times, and the keep flag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteReminderDialog(
    current: Long?,
    currentRrule: String?,
    currentExtra: List<Long>,
    currentKeep: Boolean,
    onApply: (primary: Long?, rrule: String?, extra: List<Long>, keep: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.LocalDateTime.now(zone)
    fun ms(dt: java.time.LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    fun fmt(at: Long) = runCatching {
        java.time.Instant.ofEpochMilli(at).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a"))
    }.getOrDefault("")

    var primary by remember { mutableStateOf(current) }
    var rrule by remember { mutableStateOf(currentRrule) }
    val extra = remember { mutableStateListOf<Long>().apply { addAll(currentExtra) } }
    var keep by remember { mutableStateOf(currentKeep) }
    var step by remember { mutableStateOf("main") }           // main | date | time
    var pickedDate by remember { mutableStateOf(now.toLocalDate()) }

    // A newly-chosen time becomes the primary if none is set, else stacks as an extra.
    fun addTime(at: Long) {
        if (at <= System.currentTimeMillis()) return
        if (primary == null) primary = at else if (at != primary && at !in extra) extra.add(at)
        step = "main"
    }

    when (step) {
        "date" -> {
            val dateState = rememberDatePickerState(
                initialSelectedDateMillis = pickedDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { step = "main" },
                confirmButton = {
                    TextButton(onClick = {
                        dateState.selectedDateMillis?.let { pickedDate = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
                        step = "time"
                    }) { Text("Next") }
                },
                dismissButton = { TextButton(onClick = { step = "main" }) { Text("Back") } },
            ) { DatePicker(state = dateState, showModeToggle = false) }
        }
        "time" -> {
            val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = false)
            AlertDialog(
                onDismissRequest = { step = "main" },
                confirmButton = { TextButton(onClick = { addTime(ms(pickedDate.atTime(timeState.hour, timeState.minute))) }) { Text("Add") } },
                dismissButton = { TextButton(onClick = { step = "date" }) { Text("Back") } },
                title = { Text("Reminder time") },
                text = { Column { TimePicker(state = timeState) } },
            )
        }
        else -> {
            val evening = now.toLocalDate().atTime(18, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
            val presets = listOf(
                "In 1 hour" to ms(now.plusHours(1)),
                "This evening" to ms(evening),
                "Tomorrow 9 AM" to ms(now.toLocalDate().plusDays(1).atTime(9, 0)),
                "In 3 days" to ms(now.toLocalDate().plusDays(3).atTime(9, 0)),
                "Next week" to ms(now.toLocalDate().plusWeeks(1).atTime(9, 0)),
            )
            val hasAny = primary != null || extra.isNotEmpty()
            AlertDialog(
                onDismissRequest = onDismiss,
                confirmButton = { TextButton(onClick = { onApply(primary, rrule, extra.toList(), keep) }) { Text("Done") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                title = { Text("Remind me") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        // Currently-set times (primary first), each removable.
                        if (hasAny) {
                            primary?.let { p ->
                                ReminderRow("⏰  ${fmt(p)}", onRemove = { primary = extra.removeFirstOrNull() })
                            }
                            extra.toList().forEach { e ->
                                ReminderRow("＋  ${fmt(e)}", onRemove = { extra.remove(e) })
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Text("Add a time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            items(presets, key = { it.first }) { (label, at) ->
                                Surface(onClick = { addTime(at) }, shape = NotesTokens.Pill,
                                    color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.height(34.dp)) {
                                    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                }
                            }
                        }
                        Text("Pick date & time…", Modifier.fillMaxWidth().clickable { pickedDate = now.toLocalDate(); step = "date" }.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)

                        // Repeat (RRULE) — reuse the shared Recurrence presets.
                        Text("Repeat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            items(com.todocompanion.app.domain.recurrence.Recurrence.PRESETS, key = { it.second }) { (rule, label) ->
                                FilterChip(selected = rrule == rule, onClick = { rrule = rule }, label = { Text(label) })
                            }
                        }
                        // Keep reminding until opened.
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep reminding until I open it", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Switch(checked = keep, onCheckedChange = { keep = it })
                        }
                        if (hasAny) {
                            Text("Clear reminder", Modifier.fillMaxWidth().clickable { onApply(null, null, emptyList(), false) }.padding(vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ReminderRow(label: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("✕", Modifier.clickable { onRemove() }.padding(6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}
