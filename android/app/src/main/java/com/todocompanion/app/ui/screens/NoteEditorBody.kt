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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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

    Column(modifier.fillMaxSize()) {
        if (!readOnly) {
            FormattingToolbar(
                onWrap = { m -> apply(NoteEditing.wrapInline(tfv.text, tfv.selection.start, tfv.selection.end, m)) },
                onLinePrefix = { p -> apply(NoteEditing.insertLinePrefix(tfv.text, tfv.selection.start, p)) },
                canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(),
                onUndo = { if (undo.isNotEmpty()) { val prev = undo.removeAt(undo.lastIndex); redo.add(tfv); tfv = prev; onValueChange(prev.text) } },
                onRedo = { if (redo.isNotEmpty()) { val nx = redo.removeAt(redo.lastIndex); undo.add(tfv); tfv = nx; onValueChange(nx.text) } },
            )
            // Wave G — the "/" quick-insert palette: type "/" at the start of a line for a filterable menu
            // of block snippets (headings, lists, callout, code, table, wiki-link, date/time). Pure logic
            // lives in NoteEditing; this is the thin chip row over it.
            val slashQuery = if (tfv.selection.collapsed) NoteEditing.quickQuery(tfv.text, tfv.selection.start) else null
            if (slashQuery != null) {
                val cmds = NoteEditing.filterQuick(slashQuery)
                if (cmds.isNotEmpty()) QuickInsertBar(cmds) { cmd ->
                    val snippet = if (cmd.dynamic) dynamicSnippet(cmd.id) else cmd.snippet
                    apply(NoteEditing.applyQuick(tfv.text, tfv.selection.start, snippet, if (cmd.dynamic) -1 else cmd.caretOffset))
                }
            }
        }
        TextField(
            value = tfv,
            onValueChange = ::onFieldChange,
            readOnly = readOnly,
            placeholder = { Text("Write in Markdown…") },
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = borderlessFieldColors(),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(12.dp),
        )
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
                shape = RoundedCornerShape(9.dp),
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

/** Resolve a dynamic quick-insert snippet (date/time) at insert time — fully local, no locale surprises. */
private fun dynamicSnippet(id: String): String = when (id) {
    "date" -> java.time.LocalDate.now().toString()
    "time" -> java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }
    else -> ""
}

@Composable
private fun FormattingToolbar(
    onWrap: (String) -> Unit,
    onLinePrefix: (String) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tb("B", bold = true) { onWrap("**") }
        Tb("I", italic = true) { onWrap("*") }
        Tb("S̶") { onWrap("~~") }
        Tb("</>") { onWrap("`") }
        Tb("H") { onLinePrefix("# ") }
        Tb("❝") { onLinePrefix("> ") }
        Tb("•") { onLinePrefix("- ") }
        Tb("☑") { onLinePrefix("- [ ] ") }
        Tb("1.") { onLinePrefix("1. ") }
        Tb("↶", enabled = canUndo, onClick = onUndo)
        Tb("↷", enabled = canRedo, onClick = onRedo)
    }
}

@Composable
private fun Tb(label: String, bold: Boolean = false, italic: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.5f else 0.2f),
        modifier = Modifier.height(34.dp),
    ) {
        Box(Modifier.defaultMinSize(minWidth = 40.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Wave A — a note's "About" sheet: word/character counts, an estimated read time, and timestamps. */
@Composable
fun NoteAboutDialog(note: NoteEntity, onDismiss: () -> Unit) {
    val words = note.body.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    val chars = note.body.length
    val readMin = (words / 200.0).let { if (it < 1) "< 1 min" else "${Math.round(it)} min" }
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

/** Wave C — a table-of-contents / outline built from the note's Markdown headings (indented by level). */
@Composable
fun NoteOutlineDialog(body: String, onDismiss: () -> Unit) {
    val headings = remember(body) {
        body.lineSequence().mapNotNull { ln ->
            val m = Regex("""^(#{1,6})\s+(.*\S)\s*$""").find(ln.trim()) ?: return@mapNotNull null
            m.groupValues[1].length to m.groupValues[2]
        }.toList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Outline") },
        text = {
            if (headings.isEmpty()) {
                Text("No headings yet. Use #, ## or ### in the note to build an outline.")
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(headings.size) { i ->
                        val (lvl, txt) = headings[i]
                        Text(
                            txt,
                            Modifier.fillMaxWidth().padding(start = ((lvl - 1) * 14).dp, top = 6.dp, bottom = 6.dp),
                            style = if (lvl <= 1) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                        )
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
                                Surface(onClick = { addTime(at) }, shape = RoundedCornerShape(9.dp),
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
