// Split out of DayReviewScreen.kt (re-audit fix #17) — pure code movement, no behavior change.
// The guided close-the-day, weekly-review, reflect and daily-questions flows.
package com.wkhan.hexis.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wkhan.hexis.data.entity.CoreValueEntity
import com.wkhan.hexis.domain.AdaptivePrompts
import com.wkhan.hexis.domain.DailyQuestion
import com.wkhan.hexis.domain.DailyQuestions
import com.wkhan.hexis.domain.DayAlignment
import com.wkhan.hexis.domain.Goal
import com.wkhan.hexis.ui.components.AppTextField
import com.wkhan.hexis.ui.components.StatTile

/** Phase A — the guided "Close the day" ritual: Recall → Feel → Reflect → Tomorrow → closed. Express hides
 *  the extra prose fields for a ~60-second close; Full keeps them. Reuses the existing DayLog fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloseDayFlow(
    day: Long,
    isToday: Boolean,
    summary: String,
    recallTiles: List<Triple<String, String, String>>,
    wins: List<String>,
    streak: Int,
    log: com.wkhan.hexis.data.entity.DayLogEntity?,
    questions: List<DailyQuestion>,
    initialScores: Map<String, Int>,
    onScore: (questionId: String, score: Int) -> Unit,
    goals: List<Goal>,
    topValues: List<CoreValueEntity>,
    initialAlignment: DayAlignment,
    onSaveAlignment: (movedGoalIds: List<String>, honoredValueIds: List<String>) -> Unit,
    // Track 2.7 — show the gratitude prompt (weekly beat vs daily), prompt each good thing for a "why",
    // and whether the closing-step streak line is hidden.
    showGratitude: Boolean = true,
    requireGoodWhy: Boolean = false,
    hideStreaks: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (rating: Int, energy: Int, reflection: String, mood: Int, highlight: String, gratitude: String, lesson: String, tomorrow: String) -> Unit,
    onSaveExtras: (good1: String, good2: String, good3: String, intentionOutcome: Int, promptAnswer: String) -> Unit,
    onSaveEmotion: (label: String) -> Unit,
    onSaveTomorrowPlan: (obstacle: String, plan: String) -> Unit,
) {
    var full by remember { mutableStateOf(true) }
    var rating by remember { mutableIntStateOf(log?.dayRating ?: 0) }
    var energy by remember { mutableIntStateOf(log?.energy ?: 0) }
    var mood by remember { mutableIntStateOf(log?.pmMood ?: 0) }
    var reflection by remember { mutableStateOf(log?.pmReflection ?: "") }
    var highlight by remember { mutableStateOf(log?.highlight ?: "") }
    var gratitude by remember { mutableStateOf(log?.gratitude ?: "") }
    var lesson by remember { mutableStateOf(log?.lesson ?: "") }
    var tomorrow by remember { mutableStateOf(log?.tomorrowFocus ?: "") }
    // Wave 2 — tomorrow's WOOP if-then (feature 7): the obstacle you expect + the implementation intention.
    var obstacle by remember { mutableStateOf(log?.tomorrowObstacle ?: "") }
    var plan by remember { mutableStateOf(log?.tomorrowPlan ?: "") }
    // Phase B — reflection-depth state. Track 2.7 — the optional "…and why" is split out for editing and
    // rejoined on save, so the day-log field is unchanged (no schema).
    var good1 by remember { mutableStateOf(goodThingOf(log?.good1 ?: "")) }
    var good2 by remember { mutableStateOf(goodThingOf(log?.good2 ?: "")) }
    var good3 by remember { mutableStateOf(goodThingOf(log?.good3 ?: "")) }
    var why1 by remember { mutableStateOf(goodWhyOf(log?.good1 ?: "")) }
    var why2 by remember { mutableStateOf(goodWhyOf(log?.good2 ?: "")) }
    var why3 by remember { mutableStateOf(goodWhyOf(log?.good3 ?: "")) }
    var promptAnswer by remember { mutableStateOf(log?.promptAnswer ?: "") }
    var intentionOutcome by remember { mutableIntStateOf(log?.intentionOutcome ?: 0) }
    // Wave 1 — an optional precise emotion word chosen in the "feel" step, alongside the mood face.
    var emotionLabel by remember { mutableStateOf(log?.emotionLabel ?: "") }
    // Phase C — the day's Daily-Question scores, edited in-flow and persisted immediately on each tap.
    var scores by remember { mutableStateOf(initialScores) }
    // Phase E — the goals today advanced and the top values it honored, chosen in the "align" step.
    var movedGoalIds by remember { mutableStateOf(initialAlignment.movedGoalIds.toSet()) }
    var honoredValueIds by remember { mutableStateOf(initialAlignment.honoredValueIds.toSet()) }
    val amIntention = log?.amIntention?.trim().orEmpty()
    // Wave 1 — the reflect prompt adapts to the kind of day, from the rating + mood chosen a step earlier.
    val adaptive = AdaptivePrompts.promptFor(day, rating, mood)

    // Phase E — the align step is a Full-flow-only, additive reflective prompt; skip it entirely when the
    // user has neither goals nor ranked values (nothing to align to), so Express stays a ~60s close.
    val hasAlignTargets = goals.isNotEmpty() || topValues.isNotEmpty()
    val steps = remember(isToday, questions.isEmpty(), full, hasAlignTargets) {
        buildList {
            add("recall"); add("feel")
            if (questions.isNotEmpty()) add("questions")
            add("reflect")
            if (full && hasAlignTargets) add("align")
            if (isToday) add("tomorrow")
            add("done")
        }
    }
    var idx by remember { mutableIntStateOf(0) }
    val stepId = steps[idx]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Rendered inline as a full-screen OVERLAY (not a Dialog): the app's content is edge-to-edge and
    // does receive window insets, so systemBarsPadding()/imePadding() below resolve correctly and the
    // pinned action bar clears the navigation bar — a Compose Dialog window did not reliably dispatch
    // those insets. BackHandler restores the dismiss-on-back the Dialog gave for free.
    BackHandler { onDismiss() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 22.dp)) {
            // Progress bar — one segment per step (the terminal "done" step isn't counted). Pinned at top.
            val totalSteps = (steps.size - 1).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until totalSteps).forEach { i ->
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (stepId == "done" || i <= idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                }
            }
            // Everything between the pinned progress bar and the pinned action bar scrolls (hero header
            // included), so the flow can never overflow the screen — on small screens or with the keyboard up.
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(24.dp))
                Text(when (stepId) { "recall" -> "🗓️"; "feel" -> "💗"; "questions" -> "🎯"; "reflect" -> "🌙"; "align" -> "🧭"; "tomorrow" -> "🌅"; else -> "🎉" }, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(when (stepId) {
                    "recall" -> "Recall your day"; "feel" -> "How did it feel?"; "questions" -> "Daily questions"; "reflect" -> "Reflect"
                    "align" -> "Align"; "tomorrow" -> "Ready for tomorrow"; else -> "Day closed"
                }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val sub = when (stepId) {
                    "recall" -> "A quiet look back at your day."; "feel" -> "Reckon with how it went."; "questions" -> "Did you do your best?"
                    "reflect" -> "Put a few words to it."; "align" -> "Tie today to what you're working toward."; "tomorrow" -> "Pre-decide the one thing."; else -> ""
                }
                if (sub.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(sub, style = MaterialTheme.typography.bodyMedium, color = muted) }
                Spacer(Modifier.height(22.dp))
                when (stepId) {
                "recall" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        FilterChip(selected = !full, onClick = { full = false }, label = { Text("Express · 60s") })
                        FilterChip(selected = full, onClick = { full = true }, label = { Text("Full") })
                    }
                    // The day's metrics as tonal box tiles, matching the day-review at-a-glance card.
                    if (recallTiles.isEmpty()) {
                        Text(summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            recallTiles.forEach { (icon, value, label) -> StatTile(value = value, label = label, modifier = Modifier.weight(1f), icon = icon) }
                        }
                    }
                    if (wins.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("Wins", style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(bottom = 2.dp))
                        wins.take(3).forEach {
                            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("⭐", Modifier.width(28.dp))
                                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                "feel" -> {
                    // After-action compare: reckon with the morning's intention before rating the day.
                    if (amIntention.isNotBlank()) {
                        Text("This morning you meant to:", style = MaterialTheme.typography.labelMedium, color = muted)
                        Text(amIntention, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                            listOf(1 to "No", 2 to "Partly", 3 to "Yes").forEach { (v, lbl) ->
                                FilterChip(selected = intentionOutcome == v, onClick = { intentionOutcome = if (intentionOutcome == v) 0 else v }, label = { Text(lbl) })
                            }
                        }
                    }
                    Text("Rating", style = MaterialTheme.typography.labelMedium, color = muted)
                    Row(Modifier.padding(top = 2.dp, bottom = 10.dp)) {
                        (1..5).forEach { i -> Text(if (rating >= i) "★" else "☆", style = MaterialTheme.typography.headlineMedium,
                            color = if (rating >= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clip(CircleShape).clickable { rating = if (rating == i) 0 else i }.padding(horizontal = 4.dp, vertical = 2.dp)) }
                    }
                    Text("Energy", style = MaterialTheme.typography.labelMedium, color = muted)
                    Row(Modifier.padding(top = 2.dp, bottom = 10.dp)) {
                        (1..5).forEach { i -> Text(if (energy >= i) "◆" else "◇", style = MaterialTheme.typography.headlineMedium,
                            color = if (energy >= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clip(CircleShape).clickable { energy = if (energy == i) 0 else i }.padding(horizontal = 4.dp, vertical = 2.dp)) }
                    }
                    Text("Mood", style = MaterialTheme.typography.labelMedium, color = muted)
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1 to "😞", 2 to "🙁", 3 to "😐", 4 to "🙂", 5 to "😄").forEach { (v, e) ->
                            Text(e, style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.clip(CircleShape).clickable { mood = if (mood == v) 0 else v }
                                    .background(if (mood == v) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                    }
                    // Wave 1 — name it: an optional, single-select precise emotion word (affect-labeling).
                    Spacer(Modifier.height(14.dp))
                    Text("Name it", style = MaterialTheme.typography.labelMedium, color = muted)
                    Text("Optional — the more precise word for how you feel.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 2.dp))
                    EmotionPicker(emotionLabel) { emotionLabel = it }
                }
                "questions" -> {
                    Text("Did you do your best today? Score your effort, 1–5 — not whether it worked out.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 10.dp))
                    questions.forEachIndexed { i, q ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Text(q.text, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(2.dp))
                        ScorePips(scores[q.id] ?: 0) { s ->
                            scores = scores.toMutableMap().apply { this[q.id] = s }
                            onScore(q.id, s)
                        }
                    }
                }
                "reflect" -> {
                    AppTextField(reflection, { reflection = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("How did the day go?") }, minLines = 2)
                    if (full) {
                        Spacer(Modifier.height(6.dp))
                        AppTextField(highlight, { highlight = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("✨ Highlight of the day") }, singleLine = true)
                        // Track 2.7 — gratitude as a weekly beat: only shown on the week-start day when the
                        // weekly-gratitude setting is on, so the daily close doesn't nag for it every night.
                        if (showGratitude) {
                            Spacer(Modifier.height(6.dp))
                            AppTextField(gratitude, { gratitude = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🙏 One thing you're grateful for") }, singleLine = true)
                        }
                        Spacer(Modifier.height(6.dp))
                        AppTextField(lesson, { lesson = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("💡 One lesson / what you'd change") }, singleLine = true)
                        Spacer(Modifier.height(12.dp))
                        Text("Three good things", style = MaterialTheme.typography.labelMedium, color = muted)
                        if (requireGoodWhy) Text("Add a short “…and why” — it makes the good things stick.", style = MaterialTheme.typography.bodySmall, color = muted)
                        Spacer(Modifier.height(4.dp))
                        listOf(
                            Triple("One good thing", good1, why1),
                            Triple("Another", good2, why2),
                            Triple("One more", good3, why3),
                        ).forEachIndexed { i, (ph, thing, why) ->
                            if (i > 0) Spacer(Modifier.height(6.dp))
                            AppTextField(thing, { v -> when (i) { 0 -> good1 = v; 1 -> good2 = v; else -> good3 = v } },
                                modifier = Modifier.fillMaxWidth(), placeholder = { Text(ph) }, singleLine = true)
                            if (requireGoodWhy) {
                                Spacer(Modifier.height(3.dp))
                                AppTextField(why, { v -> when (i) { 0 -> why1 = v; 1 -> why2 = v; else -> why3 = v } },
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp), placeholder = { Text("…and why") }, singleLine = true)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val pg = AdaptivePrompts.glyph(adaptive.kind)
                        Text((if (pg.isNotBlank()) "$pg  " else "") + adaptive.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        AppTextField(promptAnswer, { promptAnswer = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Your answer") }, singleLine = true)
                    }
                }
                "align" -> {
                    Text("Tie today to what you're working toward — both optional.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 12.dp))
                    Text("🎯 Did today move a goal?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    if (goals.isEmpty()) {
                        Text("No goals yet — set up a goal to link your tasks, a habit and tracked time, then mark the days it advances.", style = MaterialTheme.typography.bodySmall, color = muted)
                    } else {
                        SelectableChips(goals.map { it.id to "${it.emoji} ${it.name}" }, movedGoalIds) { id ->
                            movedGoalIds = if (id in movedGoalIds) movedGoalIds - id else movedGoalIds + id
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("🧭 Which of your values did today honor?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    if (topValues.isEmpty()) {
                        Text("No ranked values yet — rank your core values in Life Systems and your top ones will show here to check off.", style = MaterialTheme.typography.bodySmall, color = muted)
                    } else {
                        SelectableChips(topValues.map { v -> v.id to ((v.emoji?.let { "$it " } ?: "") + v.name) }, honoredValueIds) { id ->
                            honoredValueIds = if (id in honoredValueIds) honoredValueIds - id else honoredValueIds + id
                        }
                    }
                }
                "tomorrow" -> {
                    Text("Pre-decide the one thing that matters, so tomorrow starts with the decision already made.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                    AppTextField(tomorrow, { tomorrow = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🎯 Tomorrow's one thing") }, singleLine = true)
                    // Wave 2 — a light WOOP if-then (feature 7): name the obstacle you expect, then pre-decide
                    // your response. Both optional and secondary so the step stays fast.
                    Spacer(Modifier.height(12.dp))
                    Text("Anticipate what could get in the way — optional, but it helps.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 4.dp))
                    AppTextField(obstacle, { obstacle = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🧱 The obstacle you expect") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    AppTextField(plan, { plan = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🧭 If that happens, then I will…") }, singleLine = true)
                }
                else -> {
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("The day is closed.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        // Track 2.7 — with streaks hidden, celebrate the act itself rather than the count.
                        Text(
                            if (hideStreaks) "Closed with intention — that's the whole point. Rest well."
                            else "🔥 Reviewed $streak day${if (streak == 1) "" else "s"} in a row. Rest well.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            }
            // Bottom action bar — Back / Next, or a full-width finish button on the closing step.
            Spacer(Modifier.height(12.dp))
            if (stepId == "done") {
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) { Text("Done") }
            } else {
                val nextIsDone = steps[idx + 1] == "done"
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (idx > 0) idx-- else onDismiss() }) { Text(if (idx > 0) "Back" else "Cancel") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (nextIsDone) {
                            onSave(rating, energy, reflection, mood, highlight, if (full) gratitude else "", if (full) lesson else "", tomorrow)
                            onSaveExtras(
                                if (full) joinGoodWhy(good1, why1) else "",
                                if (full) joinGoodWhy(good2, why2) else "",
                                if (full) joinGoodWhy(good3, why3) else "",
                                intentionOutcome, if (full) promptAnswer else "",
                            )
                            // The precise emotion word is captured in the always-shown "feel" step, so save it in both flows.
                            onSaveEmotion(emotionLabel)
                            // Wave 2 — the tomorrow WOOP if-then; only today has a "tomorrow" step to plan for.
                            if (isToday) onSaveTomorrowPlan(obstacle, plan)
                            // Express never shows the align step, so leave any recorded alignment untouched there.
                            if (full) onSaveAlignment(movedGoalIds.toList(), honoredValueIds.toList())
                        }
                        idx++
                    }) { Text(if (nextIsDone) "Close the day" else "Next") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReflectDialog(
    day: Long, isToday: Boolean, log: com.wkhan.hexis.data.entity.DayLogEntity?,
    onDismiss: () -> Unit,
    onSave: (rating: Int, energy: Int, reflection: String, mood: Int, highlight: String, gratitude: String, lesson: String, tomorrow: String) -> Unit,
    onSaveExtras: (good1: String, good2: String, good3: String, intentionOutcome: Int, promptAnswer: String) -> Unit,
    onSaveEmotion: (label: String) -> Unit,
    onSaveTomorrowPlan: (obstacle: String, plan: String) -> Unit,
) {
    var rating by remember { mutableIntStateOf(log?.dayRating ?: 0) }
    var energy by remember { mutableIntStateOf(log?.energy ?: 0) }
    var pmMood by remember { mutableIntStateOf(log?.pmMood ?: 0) }
    var emotionLabel by remember { mutableStateOf(log?.emotionLabel ?: "") }
    var reflection by remember { mutableStateOf(log?.pmReflection ?: "") }
    var highlight by remember { mutableStateOf(log?.highlight ?: "") }
    var gratitude by remember { mutableStateOf(log?.gratitude ?: "") }
    var lesson by remember { mutableStateOf(log?.lesson ?: "") }
    var tomorrow by remember { mutableStateOf(log?.tomorrowFocus ?: "") }
    // Wave 2 — tomorrow's WOOP if-then (feature 7): optional obstacle + implementation intention.
    var obstacle by remember { mutableStateOf(log?.tomorrowObstacle ?: "") }
    var plan by remember { mutableStateOf(log?.tomorrowPlan ?: "") }
    // Phase B — keep parity with the guided close: three good things + the day's rotating prompt.
    var good1 by remember { mutableStateOf(log?.good1 ?: "") }
    var good2 by remember { mutableStateOf(log?.good2 ?: "") }
    var good3 by remember { mutableStateOf(log?.good3 ?: "") }
    var promptAnswer by remember { mutableStateOf(log?.promptAnswer ?: "") }
    val intentionOutcome = log?.intentionOutcome ?: 0 // preserved as-is (set from the guided close's after-action step)
    // Wave 1 — the prompt adapts to the kind of day (from the rating + mood chosen just above).
    val adaptive = AdaptivePrompts.promptFor(day, rating, pmMood)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(rating, energy, reflection, pmMood, highlight, gratitude, lesson, tomorrow)
                onSaveExtras(good1, good2, good3, intentionOutcome, promptAnswer)
                onSaveEmotion(emotionLabel)
                if (isToday) onSaveTomorrowPlan(obstacle, plan)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Reflect on the day") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("How was today?", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.padding(top = 2.dp, bottom = 6.dp)) {
                    (1..5).forEach { i ->
                        Text(if (rating >= i) "★" else "☆", style = MaterialTheme.typography.headlineSmall,
                            color = if (rating >= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clip(CircleShape).clickable { rating = if (rating == i) 0 else i }.padding(horizontal = 3.dp))
                    }
                }
                Text("Energy", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.padding(top = 2.dp, bottom = 6.dp)) {
                    (1..5).forEach { i ->
                        Text(if (energy >= i) "◆" else "◇", style = MaterialTheme.typography.titleLarge,
                            color = if (energy >= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clip(CircleShape).clickable { energy = if (energy == i) 0 else i }.padding(horizontal = 3.dp))
                    }
                }
                Text("Mood", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.padding(top = 2.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1 to "😞", 2 to "🙁", 3 to "😐", 4 to "🙂", 5 to "😄").forEach { (v, e) ->
                        Text(e, style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.clip(CircleShape).clickable { pmMood = if (pmMood == v) 0 else v }
                                .background(if (pmMood == v) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(4.dp))
                    }
                }
                // Wave 1 — name it: an optional, single-select precise emotion word alongside the mood face.
                Text("Name it (optional)", style = MaterialTheme.typography.labelMedium)
                EmotionPicker(emotionLabel) { emotionLabel = it }
                Spacer(Modifier.height(6.dp))
                AppTextField(reflection, { reflection = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("How did the day go?") }, minLines = 2)
                Spacer(Modifier.height(6.dp))
                AppTextField(highlight, { highlight = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("✨ Highlight of the day") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                AppTextField(gratitude, { gratitude = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🙏 One thing you're grateful for") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                AppTextField(lesson, { lesson = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("💡 One lesson / what you'd change") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Text("Three good things", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                AppTextField(good1, { good1 = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("One good thing") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                AppTextField(good2, { good2 = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Another") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                AppTextField(good3, { good3 = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("One more") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                val rg = AdaptivePrompts.glyph(adaptive.kind)
                Text((if (rg.isNotBlank()) "$rg  " else "") + adaptive.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                AppTextField(promptAnswer, { promptAnswer = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Your answer") }, singleLine = true)
                if (isToday) {
                    Spacer(Modifier.height(6.dp))
                    AppTextField(tomorrow, { tomorrow = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🎯 The one thing that matters tomorrow") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    AppTextField(obstacle, { obstacle = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🧱 The obstacle you expect (optional)") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    AppTextField(plan, { plan = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🧭 If that happens, then I will… (optional)") }, singleLine = true)
                }
            }
        },
    )
}

/** Phase C — add / rename / remove up to [DailyQuestions.MAX] Daily Questions. When the user has none
 *  yet, the editor is pre-filled with the suggested starters so they can keep, tweak, or clear them. A
 *  "Suggestions" chip row (starters not already added) makes building a personalized set one-tap fast. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DailyQuestionsDialog(
    initial: List<DailyQuestion>,
    onDismiss: () -> Unit,
    onSave: (List<DailyQuestion>) -> Unit,
) {
    val seed = remember(initial) {
        if (initial.isNotEmpty()) initial
        else DailyQuestions.SUGGESTED.map { DailyQuestion(java.util.UUID.randomUUID().toString(), it) }
    }
    var items by remember { mutableStateOf(seed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(items.filter { it.text.isNotBlank() }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Daily questions") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Up to ${DailyQuestions.MAX} “Did I do my best to…” questions, tied to what you value. Score your effort each night — the doing, not the result.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                items.forEachIndexed { i, q ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppTextField(
                            q.text,
                            { t -> items = items.toMutableList().also { it[i] = q.copy(text = t) } },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Did I do my best to…") },
                        )
                        TextButton(onClick = { items = items.toMutableList().also { it.removeAt(i) } }) { Text("Remove") }
                    }
                }
                if (items.size < DailyQuestions.MAX) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { items = items + DailyQuestion(java.util.UUID.randomUUID().toString(), "") }) { Text("Add question") }
                    // One-tap personalization — the suggested starters not already added. Tapping a chip
                    // appends the full "Did I do my best to…" question (chip shows the compact tail),
                    // respecting the MAX cap. Recomputes as items change, so a tapped chip drops out.
                    val available = DailyQuestions.SUGGESTED.filter { s -> items.none { it.text.trim().equals(s.trim(), ignoreCase = true) } }
                    if (available.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Suggestions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            available.forEach { s ->
                                val tail = s.removePrefix("Did I do my best to ").removeSuffix("?").trim().ifBlank { s }
                                val chipLabel = "＋ " + tail.replaceFirstChar { it.uppercase() }
                                FilterChip(
                                    selected = false,
                                    onClick = { if (items.size < DailyQuestions.MAX) items = items + DailyQuestion(java.util.UUID.randomUUID().toString(), s) },
                                    label = { Text(chipLabel, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
