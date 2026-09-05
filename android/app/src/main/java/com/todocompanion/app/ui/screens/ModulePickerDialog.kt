package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.Modules
import com.todocompanion.app.ui.components.DoneTick
import com.todocompanion.app.ui.components.OpenTick

/**
 * T0 / CU2 — the first-run picker, now a calmer, progressive multi-select. Choose which of the three
 * modules to start with; the rest stay off until you want them, so a newcomer sees only what they came
 * for. The first selected (Tasks → Habits → Time order) becomes the primary home. Nothing is deleted —
 * every module can be switched on later in Settings.
 */
@Composable
fun ModulePickerDialog(onPick: (String, Set<String>) -> Unit, onSkip: () -> Unit) {
    val order = listOf(Modules.TASKS, Modules.HABITS, Modules.TIME)
    // Default: start with just Tasks — the calmest first run. The user can add the others with a tap.
    var chosen by remember { mutableStateOf(setOf(Modules.TASKS)) }
    val options = listOf(
        Triple(Modules.TASKS, "✔  Tasks", "Plan and finish to-dos, projects, deadlines"),
        Triple(Modules.HABITS, "↻  Habits", "Build daily routines and streaks"),
        Triple(Modules.TIME, "⧗  Time tracking", "See where your time actually goes"),
    )
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("What do you want to start with?") },
        text = {
            Column {
                Text("Pick one or more. You'll see only what you choose — add the rest any time in Settings. The first pick becomes your home screen.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                options.forEach { (key, title, sub) ->
                    val sel = key in chosen
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))
                            .border(if (sel) 1.5.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { chosen = if (sel) (chosen - key).ifEmpty { setOf(key) } else chosen + key }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // The modern selected/unselected marks (filled disc vs open ring), not raw ☑/☐.
                        if (sel) DoneTick() else OpenTick()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(order.first { it in chosen }, chosen) }) { Text("Set up") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}
