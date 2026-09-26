// Split out of DayReviewScreen.kt (re-audit fix #17) — pure code movement, no behavior change.
// Wave 3 offline-moat dialogs: sealed letters, predictions and the reflection companion.
package com.wkhan.hexis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wkhan.hexis.domain.Prediction
import com.wkhan.hexis.domain.Predictions
import com.wkhan.hexis.domain.ReflectionCompanion
import com.wkhan.hexis.ui.components.AppCard
import com.wkhan.hexis.ui.components.AppTextField
import com.wkhan.hexis.ui.components.DateOnlyPickerDialog
import com.wkhan.hexis.ui.components.OptionChips
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

// ══════════════════════════════════════════════════════════════════════════════════════════════════
// Wave 3 — offline moats: sealed letters (A), Year-reviewed (B), prediction loop (C), companion (E).
// ══════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Wave 3 (A) — the day-review entry into R32's sealed "letter to your future self". A calm card that
 * lists your sealed letters and opens the "write" flow. Reuses the existing sealed store + tamper-evident
 * hash + SQLCipher-at-rest entirely (via the VM); NOTHING new is encrypted here. Locked letters show only
 * their unlock date and a lock — never the title or body — until the date has passed.
 */
@Composable
internal fun SealedLettersReviewCard(
    notes: List<com.wkhan.hexis.data.entity.SealedNoteEntity>,
    today: LocalDate,
    onWrite: () -> Unit,
    onOpen: (com.wkhan.hexis.data.entity.SealedNoteEntity) -> Unit,
) {
    val todayDay = today.toEpochDay()
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
            Text("✉️ Letter to your future self", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = onWrite) { Text("Write") }
        }
        if (notes.isEmpty()) {
            Text("Write a letter today; it stays locked until a date you choose. Sealed on this device — no one, not even you, can read it before then.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            notes.sortedBy { it.revealEpochDay }.forEach { n ->
                val ready = todayDay >= n.revealEpochDay
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
                        .then(if (ready) Modifier.clickable { onOpen(n) } else Modifier)
                        .background(if (ready) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (ready) "📬" else "🔒", Modifier.width(30.dp), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f)) {
                        // Never reveal the title or body while locked — only the date + lock (privacy promise).
                        Text(if (ready) n.title else "A sealed letter", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val revealDate = LocalDate.ofEpochDay(n.revealEpochDay)
                        Text(
                            if (ready) "Ready to open" else "Opens $revealDate · ${n.revealEpochDay - todayDay} days",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ready) Icon(Icons.Filled.ChevronRight, "Open letter", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Wave 3 (A) — write & seal a letter, with an explicit "open on…" date picker. Sealed on save. */
@Composable
internal fun WriteSealedLetterDialog(today: LocalDate, onDismiss: () -> Unit, onSeal: (String, String, Long) -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var revealDay by remember { mutableLongStateOf(today.plusMonths(6).toEpochDay()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val revealDate = LocalDate.ofEpochDay(revealDay)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = body.isNotBlank(), onClick = { onSeal(title, body, revealDay) }) { Text("Seal it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("A letter to future you") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AppTextField(title, { title = it }, singleLine = true, placeholder = { Text("Title (optional)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                AppTextField(body, { body = it }, minLines = 4, placeholder = { Text("What do you want to tell yourself?") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Open on…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text("📅  " + revealDate.dayOfMonth + " " + revealDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + revealDate.year)
                }
                Spacer(Modifier.height(10.dp))
                Text("Sealed on this device and locked until that date. No one — not even you — can read it before then, and it can't be edited once sealed.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
    if (showDatePicker) DateOnlyPickerDialog(
        initial = revealDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        allowFuture = true,
        onDismiss = { showDatePicker = false },
        onConfirm = { ms -> revealDay = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(); showDatePicker = false },
    )
}

/** Wave 3 (A) — read a due letter (only reachable once its unlock date has passed), with its seal state. */
@Composable
internal fun SealedLetterRevealDialog(
    note: com.wkhan.hexis.data.entity.SealedNoteEntity, ready: Boolean, intact: Boolean,
    currentCount: Int, todayEd: Long,
    onDismiss: () -> Unit, onAck: () -> Unit, onDelete: () -> Unit,
) {
    val sealedOn = LocalDate.ofEpochDay(note.createdEpochDay)
    // Track 3.4 — the "what's changed since you sealed this" diff, from the pure domain helper.
    val diff = com.wkhan.hexis.domain.SealedLetters.diff(note.sealedCount, currentCount, note.createdEpochDay, todayEd)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onAck) { Text("Keep") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        title = { Text(if (ready) note.title else "A sealed letter") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!ready) {
                    Text("Still sealed — it opens on ${LocalDate.ofEpochDay(note.revealEpochDay)}.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Sealed on $sealedOn", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(note.body, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("What's changed since you sealed this", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    Text(diff.phrase, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    diff.paceLine?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (intact) "✓ Untouched since sealing (hash verified)." else "⚠ This letter's text no longer matches its seal.",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (intact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

/** Wave 3 (C) — log a prediction with a resurface horizon. */
@Composable
internal fun AddPredictionDialog(today: LocalDate, onDismiss: () -> Unit, onAdd: (String, Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    var daysOut by remember { mutableLongStateOf(30L) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onAdd(text, Predictions.resurfaceFor(today.toEpochDay(), daysOut)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Log a prediction") },
        text = {
            Column {
                Text("Write what you expect — a result, or how a change will make you feel — then pick when to check back. Comparing it to what actually happens is Drucker's feedback analysis.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                AppTextField(text, { text = it }, minLines = 2, placeholder = { Text("I expect that … will make me feel …") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Check back in…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                OptionChips(Predictions.HORIZONS, Predictions.HORIZONS.firstOrNull { it.second == daysOut }, { daysOut = it.second }, wrap = false, spacing = 6) { it.first }
            }
        },
    )
}

/** Wave 3 (C) — record the outcome of a resurfaced prediction: a note + a matched / not-matched marker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResolvePredictionDialog(
    prediction: Prediction, today: Long,
    onDismiss: () -> Unit, onResolve: (String, Int) -> Unit, onForget: () -> Unit,
) {
    var note by remember { mutableStateOf("") }
    var matched by remember { mutableIntStateOf(Predictions.MATCH_UNSET) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onResolve(note, matched) }) { Text("Save outcome") } },
        dismissButton = { TextButton(onClick = onForget) { Text("Forget it", color = MaterialTheme.colorScheme.error) } },
        title = { Text("How did it turn out?") },
        text = {
            Column {
                Text("${Predictions.sinceLabel(prediction.createdEpochDay, today)} you predicted:",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("“${prediction.expectation}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
                Text("Did it match what you expected?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Predictions.MATCH_YES to "Matched", Predictions.MATCH_NO to "Didn't").forEach { (v, lbl) ->
                        FilterChip(selected = matched == v, onClick = { matched = if (matched == v) Predictions.MATCH_UNSET else v }, label = { Text(lbl) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                AppTextField(note, { note = it }, minLines = 2, placeholder = { Text("What actually happened?") }, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

/** Wave 3 (E) — the rule-based reflection companion: walk a short chain of context-aware follow-ups
 *  chosen on-device from the day's mood/rating, saving the answers into the day's reflection field. */
@Composable
internal fun ReflectionCompanionDialog(
    signals: ReflectionCompanion.Signals, existingReflection: String,
    onDismiss: () -> Unit, onSave: (String) -> Unit,
) {
    // The chain is recomputed each turn from the signals AND the answers so far, so a substantial answer
    // grows a deepening follow-up. Extra steps are appended after the core prompts, so the answered prefix
    // never shifts — walking forward/back stays stable.
    var answers by remember { mutableStateOf(listOf<String>()) }
    var idx by remember { mutableIntStateOf(0) }
    val chain = ReflectionCompanion.adaptiveChain(signals, answers)
    val prompts = chain.prompts
    val padded = if (answers.size < prompts.size) answers + List(prompts.size - answers.size) { "" } else answers
    val cur = idx.coerceIn(0, prompts.lastIndex)
    val last = cur >= prompts.lastIndex
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (last) onSave(ReflectionCompanion.merge(existingReflection, ReflectionCompanion.compose(chain, padded)))
                else idx = cur + 1
            }) { Text(if (last) "Save" else "Next") }
        },
        dismissButton = { TextButton(onClick = { if (cur > 0) idx = cur - 1 else onDismiss() }) { Text(if (cur > 0) "Back" else "Cancel") } },
        title = { Text("${chain.track.glyph}  ${chain.track.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(chain.intro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                // A small progress strip so a longer chain reads as a gentle walk, not an interrogation.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    prompts.indices.forEach { i ->
                        Box(Modifier.height(4.dp).weight(1f).clip(RoundedCornerShape(2.dp))
                            .background(if (i <= cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                    }
                }
                Text("Question ${cur + 1} of ${prompts.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(prompts[cur], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 4.dp))
                AppTextField(padded[cur], { v -> answers = padded.toMutableList().also { it[cur] = v } }, minLines = 2, placeholder = { Text("Your answer (optional)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("A private guide, all on your device — no AI service. Your answers save into today's reflection.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        },
    )
}
