package com.todocompanion.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppTextField
import kotlinx.coroutines.delay

/**
 * L9 — "Ask your notes". A permission-free answer box: type a question and Kairo surfaces the most
 * relevant passages from your own notes (extractive retrieval, no model, no network). Tap an answer to
 * open its note. Honest by design — it retrieves what you wrote, it never invents an answer.
 */
@Composable
fun AskNotesDialog(vm: AppViewModel, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val answers by vm.noteAnswers.collectAsState()
    // Debounced query so we don't re-scan on every keystroke.
    LaunchedEffect(q) { delay(180); vm.askNotes(q) }

    AlertDialog(
        onDismissRequest = { vm.askNotes(""); onDismiss() },
        confirmButton = { TextButton(onClick = { vm.askNotes(""); onDismiss() }) { Text("Done") } },
        title = { Text("Ask your notes") },
        text = {
            Column(Modifier.heightIn(max = 480.dp)) {
                AppTextField(
                    value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask a question — answered from your notes, on-device") },
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Retrieves the most relevant passages you wrote — no cloud, no AI model.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (q.isNotBlank() && answers.isEmpty()) {
                        Text("No matching passage found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    answers.forEach { a ->
                        Surface(
                            onClick = { vm.askNotes(""); onOpen(a.id); onDismiss() },
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.size(2.dp))
                                Text(a.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * L10 — Right Note, Right Now. The notes tied to a @context that is scheduled and open at this moment,
 * surfaced permission-free from the app's existing context open-hours engine. Empty when nothing applies.
 */
@Composable
fun RightNowDialog(vm: AppViewModel, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val notes by vm.notesNow.collectAsState()
    LaunchedEffect(Unit) { vm.refreshNotesForNow() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Relevant right now") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (notes.isEmpty()) {
                    Text(
                        "Nothing tied to a context that's open right now. Give a note an @context with open-hours, and it'll surface here during that window.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else notes.forEach { n ->
                    Surface(
                        onClick = { onOpen(n.id); onDismiss() },
                        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(n.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            if (n.preview.isNotBlank()) {
                                Spacer(Modifier.size(2.dp))
                                Text(n.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        }
                    }
                }
            }
        },
    )
}
