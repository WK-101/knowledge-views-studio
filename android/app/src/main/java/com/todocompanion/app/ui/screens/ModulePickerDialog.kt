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

/**
 * T0 — the first-run "what's your main use?" picker. Sets the primary module (the launch home and the
 * always-shown one); every module stays on, and any can be switched off later in Settings. Non-coercive
 * by design: this only chooses emphasis, never removes a capability.
 */
@Composable
fun ModulePickerDialog(onPick: (String) -> Unit, onSkip: () -> Unit) {
    var choice by remember { mutableStateOf(Modules.TASKS) }
    val options = listOf(
        Triple(Modules.TASKS, "✔  Tasks", "Plan and finish to-dos, projects, deadlines"),
        Triple(Modules.HABITS, "↻  Habits", "Build daily routines and streaks"),
        Triple(Modules.TIME, "⧗  Time tracking", "See where your time actually goes"),
    )
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("What do you mainly want?") },
        text = {
            Column {
                Text("Pick what this app is for you. Everything else stays available — switch any part off later in Settings.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                options.forEach { (key, title, sub) ->
                    val sel = key == choice
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))
                            .border(if (sel) 1.5.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { choice = key }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (sel) Text("●", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(choice) }) { Text("Set up") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}
