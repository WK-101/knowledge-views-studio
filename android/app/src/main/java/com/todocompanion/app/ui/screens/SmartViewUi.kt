package com.todocompanion.app.ui.screens

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
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.NotePredicate
import com.todocompanion.app.ui.components.AppTextField

private val SV_FIELDS = listOf(
    "pinned" to "Pinned", "favorite" to "Favorite", "archived" to "Archived", "untagged" to "Untagged",
    "titleContains" to "Title contains", "bodyContains" to "Body contains", "olderThanDays" to "Older than (days)",
    // Wave J (M4) — cross-module conditions no notes-only app can express.
    "hasReminder" to "Has a reminder", "hasOpenItems" to "Has open action items",
    "linkedTask" to "Linked to a task", "linkedEvent" to "Linked to an event",
    "linkedTaskOpen" to "Linked task still open", "linkedTaskOverdue" to "Linked task overdue",
)
private fun svNeedsValue(field: String) = field in setOf("titleContains", "bodyContains", "olderThanDays")
private fun svLabel(field: String) = SV_FIELDS.firstOrNull { it.first == field }?.second ?: field

/**
 * Wave D — the Smart View builder: a name, a match-All/Any toggle, and a list of condition rows. Produces
 * a [NotePredicate] the caller persists. Deliberately covers the picker-free fields (no tag picker needed).
 */
@Composable
fun SmartViewBuilderDialog(onSave: (String, NotePredicate) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var any by remember { mutableStateOf(false) }
    val conds = remember { mutableStateListOf("pinned" to "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && conds.isNotEmpty(),
                onClick = { onSave(title.trim(), NotePredicate.Group(any, conds.map { NotePredicate.Cond(it.first, it.second.trim()) })) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New Smart View") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                AppTextField(value = title, onValueChange = { title = it }, placeholder = { Text("View name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Match", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    FilterChip(selected = !any, onClick = { any = false }, label = { Text("All") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = any, onClick = { any = true }, label = { Text("Any") })
                }
                Spacer(Modifier.size(8.dp))
                conds.forEachIndexed { i, (f, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            Surface(onClick = { open = true }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(svLabel(f), Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                            }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                SV_FIELDS.forEach { (fid, lbl) ->
                                    DropdownMenuItem(text = { Text(lbl) }, onClick = { conds[i] = fid to (if (svNeedsValue(fid)) v else ""); open = false })
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (svNeedsValue(f)) {
                            AppTextField(
                                value = v, onValueChange = { conds[i] = f to it }, singleLine = true, modifier = Modifier.weight(1f),
                                placeholder = { Text(if (f == "olderThanDays") "days" else "text") },
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        IconButton(onClick = { if (conds.size > 1) conds.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove", modifier = Modifier.size(18.dp)) }
                    }
                }
                Spacer(Modifier.size(2.dp))
                TextButton(onClick = { conds.add("pinned" to "") }) { Text("+ Add condition") }
            }
        },
    )
}
