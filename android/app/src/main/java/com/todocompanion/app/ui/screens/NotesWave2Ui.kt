package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.NoteReview
import com.todocompanion.app.ui.components.AppTextField

/**
 * Wave 2 · Evergreen Resurfacing — pick a spaced-review cadence for a note. The app's alarm engine (and
 * the "Due for review" surface) then brings the note back when it falls due.
 */
@Composable
fun ReviewCadenceDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Resurface this note") },
        text = {
            Column {
                Text("Bring this note back for a deliberate re-read on a spaced cadence — so it doesn't vanish into the archive.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                NoteReview.CADENCES.forEach { days ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .selectable(selected = days == current, onClick = { onPick(days) })
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = days == current, onClick = { onPick(days) })
                        Spacer(Modifier.width(8.dp))
                        Text(NoteReview.label(days), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}

/**
 * Wave 2 · Writing Sprints — set a word goal, then start a timed sprint. On stop the elapsed time is logged
 * as tracked time on the note and a goal hit is celebrated.
 */
@Composable
fun SprintStartDialog(currentGoal: Int, onStart: (goalWords: Int) -> Unit, onDismiss: () -> Unit) {
    var goal by remember { mutableStateOf((if (currentGoal > 0) currentGoal else 250).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onStart(goal.toIntOrNull()?.coerceIn(10, 10000) ?: 250) }) { Text("Start sprint") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Writing sprint") },
        text = {
            Column {
                Text("Write toward a word goal; the session logs as tracked time on this note, and hitting the goal gets a celebration.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = goal, onValueChange = { goal = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true, modifier = Modifier.width(140.dp),
                    placeholder = { Text("Words") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
    )
}

/**
 * Wave 2 · Privacy Governance Dial — per-note switches only a local, network-incapable app can actually
 * honor: exclude from the JSON backup, from the `.md` folder export, and from search / Ask / related
 * indexing. Plus, when the note lives in a notebook, an auto-vault-on-close toggle for the whole notebook.
 */
@Composable
fun NotePrivacyDialog(
    noBackup: Boolean, noExport: Boolean, noIndex: Boolean, notebookAutoVault: Boolean?,
    onChange: (Boolean, Boolean, Boolean) -> Unit,
    onNotebookAutoVault: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Privacy for this note") },
        text = {
            Column {
                Text("Controls only a local, offline app can truly keep — nothing leaves the device to enforce them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                PrivacyRow("Exclude from backup", "Kept out of the JSON backup file", noBackup) { onChange(it, noExport, noIndex) }
                PrivacyRow("Exclude from .md export", "Skipped when exporting the notes folder", noExport) { onChange(noBackup, it, noIndex) }
                PrivacyRow("Exclude from search & AI", "Dropped from search, Ask and related", noIndex) { onChange(noBackup, noExport, it) }
                if (notebookAutoVault != null) {
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    PrivacyRow("Auto-vault this notebook", "Encrypt notes here when the editor closes", notebookAutoVault) { onNotebookAutoVault(it) }
                }
            }
        },
    )
}

@Composable
private fun PrivacyRow(title: String, sub: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/**
 * Wave 2 · Threads — a prev/next bar shown atop the editor for each thread (Map of Content) the note
 * belongs to. Tap the thread title to open the thread note; the arrows walk to the neighbouring item.
 */
@Composable
fun ThreadBar(
    pos: com.todocompanion.app.domain.NoteThreads.Position,
    onOpenPrev: () -> Unit, onOpenNext: () -> Unit, onOpenThread: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(
                onClick = onOpenPrev, enabled = pos.prevTitle != null,
                modifier = Modifier.size(34.dp),
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous in thread", modifier = Modifier.size(20.dp)) }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).selectable(selected = false, onClick = onOpenThread)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("🧵 ${pos.threadTitle}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("${pos.index + 1} of ${pos.size}" + (pos.nextTitle?.let { " · next: $it" } ?: " · end"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            androidx.compose.material3.IconButton(
                onClick = onOpenNext, enabled = pos.nextTitle != null,
                modifier = Modifier.size(34.dp),
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next in thread", modifier = Modifier.size(20.dp)) }
        }
    }
}

/**
 * Wave 2 · Threads — "Add to thread": drop this note into an existing thread (Map of Content) or start a
 * new one. [threads] = (id, title). [suggestTitle] seeds the new-thread name field.
 */
@Composable
fun AddToThreadDialog(
    threads: List<Pair<String, String>>, suggestTitle: String,
    onAdd: (threadId: String) -> Unit, onCreate: (threadTitle: String) -> Unit, onDismiss: () -> Unit,
) {
    var newTitle by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = newTitle.isNotBlank(), onClick = { onCreate(newTitle.trim()) }) { Text("New thread") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add to a thread") },
        text = {
            Column {
                Text("A thread is an ordered map of content — a reading order across your notes, tasks and events.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (threads.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Existing threads", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    threads.take(8).forEach { (id, title) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .selectable(selected = false, onClick = { onAdd(id) })
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🧵", modifier = Modifier.padding(end = 8.dp))
                            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                AppTextField(
                    value = newTitle, onValueChange = { newTitle = it.take(60) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (suggestTitle.isNotBlank()) "New thread (e.g. $suggestTitle)" else "New thread name") },
                )
            }
        },
    )
}

/**
 * Wave 3 · Encrypted Note Courier — one passphrase prompt, reused for both sending and receiving a note.
 * The passphrase never leaves the device; it derives the AES-GCM key for the portable envelope.
 */
@Composable
fun CourierPassphraseDialog(
    title: String, message: String, confirmLabel: String,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = pass.isNotBlank(), onClick = { onConfirm(pass) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = pass, onValueChange = { pass = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    placeholder = { Text("Shared passphrase") },
                )
            }
        },
    )
}

/**
 * Wave 3 · Outcome Ledger — "what actually moved because of this note." A read-only rollup of the live
 * state of everything the note's `[[links]]` spawned: tasks done/open, events, habit streaks, tracked time.
 */
@Composable
fun OutcomeLedgerCard(r: com.todocompanion.app.domain.NoteOutcome.Rollup) {
    if (!r.hasAny) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("WHAT THIS NOTE MOVED", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (r.tasksTotal > 0) LedgerStat("${r.tasksDone}/${r.tasksTotal}", "tasks done")
                if (r.eventsTotal > 0) LedgerStat("${r.eventsTotal}", if (r.eventsTotal == 1) "event" else "events")
                if (r.habitsTotal > 0) LedgerStat(if (r.habitBestStreak > 0) "${r.habitBestStreak}🔥" else "${r.habitsTotal}", if (r.habitBestStreak > 0) "streak" else "habits")
                if (r.trackedMinutes > 0) LedgerStat("%.1fh".format(r.trackedMinutes / 60.0), "tracked")
            }
        }
    }
}

@Composable
private fun LedgerStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Wave 2 · the live sprint bar shown atop the editor while a sprint runs. */
@Composable
fun SprintBar(elapsedSec: Long, words: Int, goal: Int, onStop: () -> Unit) {
    val pct = if (goal > 0) (words.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val hit = goal > 0 && words >= goal
    val accent = if (hit) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (hit) "🎉 " else "✍️ ") + "%d words".format(words) + (if (goal > 0) " / $goal" else ""),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = accent,
                    modifier = Modifier.weight(1f),
                )
                Text("%d:%02d".format(elapsedSec / 60, elapsedSec % 60),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onStop, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Filled.Stop, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Stop")
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = accent, trackColor = MaterialTheme.colorScheme.surface)
        }
    }
}
