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
