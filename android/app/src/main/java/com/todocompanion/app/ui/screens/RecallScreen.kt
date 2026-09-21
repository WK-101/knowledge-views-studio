package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.NoteCards
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.EmptyState
import com.todocompanion.app.ui.components.KairoScreenScaffold

/**
 * Wave 3 · Active Recall — the daily review. Quizzes the flashcards authored inline in your own notes
 * (`Q:: A`, `term :: def`, `==cloze==`) that fall due today, scheduling each with SM-2 through the same
 * on-device engine that resurfaces whole notes. Tap to reveal, then grade; the interval grows with recall.
 * Fully offline, no account — the recall engine RemNote and Anki ship as separate apps, wired straight
 * into the notes you already keep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecallScreen(vm: AppViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val due by vm.recallDue.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshRecall() }

    var revealed by remember { mutableStateOf(false) }
    val card = due.firstOrNull()
    // Collapse the answer whenever the front card changes.
    LaunchedEffect(card?.id) { revealed = false }

    KairoScreenScaffold(
        title = "Recall" + if (due.isEmpty()) "" else "  ·  ${due.size} due",
        onBack = onClose,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (card == null) {
                EmptyState(emoji = "🎉", title = "Nothing due", body = "You've reviewed every card that's due. Add cards to any note with `Q:: A`, `term :: definition`, or ==highlight== a word, and they'll come back here on a spaced schedule.")
                return@Column
            }

            // The card.
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (card.cardKind == "cloze") "FILL THE BLANK" else "PROMPT",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(14.dp))
                Text(card.front, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium)
                if (revealed) {
                    Spacer(Modifier.height(22.dp))
                    androidx.compose.material3.HorizontalDivider(Modifier.width(120.dp))
                    Spacer(Modifier.height(22.dp))
                    Text("ANSWER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(10.dp))
                    Text(card.back, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }

            // Actions.
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (!revealed) {
                    Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Show answer")
                    }
                } else {
                    Text("How well did you recall it?", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradeButton("Again", MaterialTheme.colorScheme.error, Modifier.weight(1f)) { vm.gradeCard(card.id, NoteCards.Grade.AGAIN) }
                        GradeButton("Hard", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f)) { vm.gradeCard(card.id, NoteCards.Grade.HARD) }
                        GradeButton("Good", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { vm.gradeCard(card.id, NoteCards.Grade.GOOD) }
                        GradeButton("Easy", MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { vm.gradeCard(card.id, NoteCards.Grade.EASY) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeButton(label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.16f), contentColor = color),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
}
