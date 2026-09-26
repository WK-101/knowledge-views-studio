package com.wkhan.hexis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wkhan.hexis.domain.NotePredicate
import com.wkhan.hexis.ui.components.AppTextField

// L1 — the full field set. Ordered so the everyday toggles come first, then text/date, then the
// cross-module conditions no notes-only app can express, then structure (container/tag/context/colour).
private val SV_FIELDS = listOf(
    "pinned" to "Pinned", "favorite" to "Favorite", "archived" to "Archived",
    "untagged" to "Untagged", "noContext" to "No context",
    "titleContains" to "Title contains", "bodyContains" to "Body contains", "textContains" to "Title or body contains",
    "kind" to "Kind is…",
    "updatedWithinDays" to "Edited within (days)", "olderThanDays" to "Not edited in (days)",
    "createdWithinDays" to "Created within (days)", "createdOlderThanDays" to "Created before (days ago)",
    // Cross-module conditions no notes-only app can express.
    "hasReminder" to "Has a reminder", "hasOpenItems" to "Has open action items",
    "linkedTask" to "Linked to a task", "linkedEvent" to "Linked to an event",
    "linkedTaskOpen" to "Linked task still open", "linkedTaskOverdue" to "Linked task overdue",
    // Structure — need a picker for their value.
    "hasTag" to "Has tag…", "hasContext" to "Has context…", "container" to "In…", "color" to "Colour…",
)
private enum class VKind { NONE, TEXT, DAYS, KIND, TAG, CONTEXT, CONTAINER, COLOR }
private fun svKind(field: String): VKind = when (field) {
    "titleContains", "bodyContains", "textContains" -> VKind.TEXT
    "updatedWithinDays", "olderThanDays", "createdWithinDays", "createdOlderThanDays" -> VKind.DAYS
    "kind" -> VKind.KIND
    "hasTag" -> VKind.TAG
    "hasContext" -> VKind.CONTEXT
    "container" -> VKind.CONTAINER
    "color" -> VKind.COLOR
    else -> VKind.NONE
}
private fun svLabel(field: String) = SV_FIELDS.firstOrNull { it.first == field }?.second ?: field

/** One editable row: an optional NOT, the field, and its value (id for pickers, text for TEXT/DAYS). */
private data class SvRow(val not: Boolean = false, val field: String = "pinned", val value: String = "")

/** Turn a builder row into a predicate, resolving the "container" pseudo-field to notebook/folder and
 *  wrapping in [NotePredicate.Not] when negated. */
private fun SvRow.toPredicate(useNotebooks: Boolean): NotePredicate {
    val realField = if (field == "container") (if (useNotebooks) "notebook" else "folder") else field
    val base: NotePredicate = NotePredicate.Cond(realField, value.trim())
    return if (not) NotePredicate.Not(base) else base
}

/**
 * L1 — the Smart View builder. A name, a top-level match-All/Any, a list of condition rows (each with a
 * NOT toggle and a per-field value picker), plus optional nested "sub-groups" so mixed logic like
 * "pinned AND (tag=work OR tag=urgent)" is expressible. Produces a [NotePredicate] the caller persists.
 */
@Composable
fun SmartViewBuilderDialog(
    onSave: (String, NotePredicate) -> Unit,
    onDismiss: () -> Unit,
    useNotebooks: Boolean = true,
    containers: List<Pair<String, String>> = emptyList(),
    tags: List<Pair<String, String>> = emptyList(),
    contexts: List<Pair<String, String>> = emptyList(),
) {
    var title by remember { mutableStateOf("") }
    var any by remember { mutableStateOf(false) }
    val rows = remember { mutableStateListOf(SvRow()) }
    // Each nested group: its own All/Any plus its own rows.
    val groups = remember { mutableStateListOf<Pair<Boolean, MutableList<SvRow>>>() }

    fun build(): NotePredicate {
        val topKids = rows.map { it.toPredicate(useNotebooks) } +
            groups.map { (gAny, gRows) -> NotePredicate.Group(gAny, gRows.map { it.toPredicate(useNotebooks) }) }
        return NotePredicate.Group(any, topKids)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (rows.isNotEmpty() || groups.isNotEmpty()),
                onClick = { onSave(title.trim(), build()) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New Smart View") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                AppTextField(value = title, onValueChange = { title = it }, placeholder = { Text("View name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(10.dp))
                MatchToggle(any) { any = it }
                Spacer(Modifier.size(8.dp))
                rows.forEachIndexed { i, r ->
                    ConditionRow(
                        row = r, useNotebooks = useNotebooks, containers = containers, tags = tags, contexts = contexts,
                        onChange = { rows[i] = it }, onRemove = { if (rows.size > 1 || groups.isNotEmpty()) rows.removeAt(i) },
                    )
                }
                TextButton(onClick = { rows.add(SvRow()) }) { Text("+ Add condition") }

                groups.forEachIndexed { gi, (gAny, gRows) ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sub-group", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { groups.removeAt(gi) }) { Icon(Icons.Filled.Close, "Remove group", modifier = Modifier.size(18.dp)) }
                    }
                    MatchToggle(gAny) { groups[gi] = it to gRows }
                    gRows.forEachIndexed { ri, r ->
                        ConditionRow(
                            row = r, useNotebooks = useNotebooks, containers = containers, tags = tags, contexts = contexts,
                            onChange = { val nl = gRows.toMutableList(); nl[ri] = it; groups[gi] = gAny to nl },
                            onRemove = { val nl = gRows.toMutableList(); if (nl.size > 1) { nl.removeAt(ri); groups[gi] = gAny to nl } },
                        )
                    }
                    TextButton(onClick = { val nl = gRows.toMutableList(); nl.add(SvRow()); groups[gi] = gAny to nl }) { Text("+ condition in sub-group") }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TextButton(onClick = { groups.add(true to mutableStateListOf(SvRow())) }) { Text("+ Add sub-group (any/all of…)") }
            }
        },
    )
}

@Composable
private fun MatchToggle(any: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Match", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        FilterChip(selected = !any, onClick = { onChange(false) }, label = { Text("All") })
        Spacer(Modifier.width(6.dp))
        FilterChip(selected = any, onClick = { onChange(true) }, label = { Text("Any") })
    }
}

@Composable
private fun ConditionRow(
    row: SvRow,
    useNotebooks: Boolean,
    containers: List<Pair<String, String>>,
    tags: List<Pair<String, String>>,
    contexts: List<Pair<String, String>>,
    onChange: (SvRow) -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // NOT toggle — a single tappable chip that inverts the condition.
        FilterChip(selected = row.not, onClick = { onChange(row.copy(not = !row.not)) }, label = { Text("not") })
        Spacer(Modifier.width(6.dp))
        var open by remember { mutableStateOf(false) }
        Box {
            Surface(onClick = { open = true }, shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.surfaceVariant) {
                val lbl = if (row.field == "container") (if (useNotebooks) "In notebook…" else "In folder…") else svLabel(row.field)
                Text(lbl, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SV_FIELDS.forEach { (fid, lbl) ->
                    val shown = if (fid == "container") (if (useNotebooks) "In notebook…" else "In folder…") else lbl
                    DropdownMenuItem(text = { Text(shown) }, onClick = { onChange(row.copy(field = fid, value = "")); open = false })
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when (svKind(row.field)) {
            VKind.TEXT -> AppTextField(value = row.value, onValueChange = { onChange(row.copy(value = it)) }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("text") })
            VKind.DAYS -> AppTextField(value = row.value, onValueChange = { onChange(row.copy(value = it.filter { c -> c.isDigit() })) }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("days") })
            VKind.KIND -> ValueDropdown(Modifier.weight(1f), current = row.value, options = listOf("note" to "Note", "journal" to "Journal", "meeting" to "Meeting"), onPick = { onChange(row.copy(value = it)) })
            VKind.TAG -> ValueDropdown(Modifier.weight(1f), current = row.value, options = tags, onPick = { onChange(row.copy(value = it)) }, emptyHint = "no tags")
            VKind.CONTEXT -> ValueDropdown(Modifier.weight(1f), current = row.value, options = contexts, onPick = { onChange(row.copy(value = it)) }, emptyHint = "no contexts")
            VKind.CONTAINER -> ValueDropdown(Modifier.weight(1f), current = row.value, options = listOf("none" to "(none)") + containers, onPick = { onChange(row.copy(value = it)) })
            VKind.COLOR -> ValueDropdown(Modifier.weight(1f), current = row.value.ifBlank { "any" }, options = listOf("any" to "Has any colour", "none" to "No colour"), onPick = { onChange(row.copy(value = it)) })
            VKind.NONE -> Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove", modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun ValueDropdown(
    modifier: Modifier = Modifier,
    current: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    emptyHint: String = "pick",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(onClick = { open = true }, shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.surfaceVariant) {
            val label = options.firstOrNull { it.first == current }?.second ?: (if (options.isEmpty()) emptyHint else "pick…")
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, lbl) -> DropdownMenuItem(text = { Text(lbl) }, onClick = { onPick(id); open = false }) }
        }
    }
}
