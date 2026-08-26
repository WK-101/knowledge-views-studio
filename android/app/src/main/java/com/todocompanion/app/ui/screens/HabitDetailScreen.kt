package com.todocompanion.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.habit.HabitStats
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Per-habit analytics screen (Tier I): strength score, streak/consistency tiles, best-days chart,
 * a 12-week strength trend, a tappable month calendar and a year heatmap. Fully offline; every
 * colour comes from [MaterialTheme] except the habit's own colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDetailScreen(
    vm: com.todocompanion.app.ui.AppViewModel,
    habitId: String,
    onBack: () -> Unit,
    onEdit: (com.todocompanion.app.data.entity.HabitEntity) -> Unit,
) {
    androidx.activity.compose.BackHandler { onBack() }
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val h = habits.firstOrNull { it.id == habitId }

    if (h == null) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Habit") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Habit not found", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }
        }
        return
    }

    val today = LocalDate.now().toEpochDay()
    val startDay = h.startEpochDay()
    val hc = checkins.filter { it.habitId == h.id }
    val countsByDay = hc.associate { it.epochDay to it.count }
    val notesByDay = hc.filter { it.reason.isNotBlank() }.associate { it.epochDay to it.reason }
    val doneDays = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
    val skipDays = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
    val relapseDays = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
    val photoByDay = hc.filter { it.photoUri != null }.associate { it.epochDay to it.photoUri!! }
    var editorDay by remember { mutableStateOf<Long?>(null) }
    // K5: pick a photo for the day currently open in the editor.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val day = editorDay
        if (uri != null && day != null) vm.setHabitPhoto(h, day, uri)
    }

    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    // Z8 correction: the headline strength honours the graded-strength opt-in, matching Momentum & goals.
    val strength = vm.strengthOf(h)
    val forgivingStreaks = vm.settings.collectAsState().value.forgivingStreaks
    val current = HabitStats.displayStreak(h, doneDays, skipDays, relapseDays, today, forgivingStreaks)
    val best = HabitStats.bestStreak(h, doneDays, skipDays, relapseDays, today)
    val rate = HabitStats.rate(h, doneDays, skipDays, today, 30)
    val weekday = HabitStats.weekdayRates(doneDays, skipDays, today, 180)
    val trend = remember(doneDays, skipDays, relapseDays, today, h) {
        (11 downTo 0).map { w -> HabitStats.strength(h, doneDays, skipDays, relapseDays, today - w * 7L) }
    }

    val isBreak = h.habitType == "break"
    val streakLabel = if (isBreak) "Days clean" else "Current streak"
    val tiles = buildList {
        add("Consistency (30d)" to "${(rate * 100).toInt()}%")
        add(streakLabel to "$current")
        add("Best streak" to "$best")
        add("Days tracked" to "${hc.size}")
        // V1: time-since — for a build habit, how long since the last done day + the average gap.
        if (!isBreak) {
            val since = HabitStats.daysSinceLastDone(doneDays, today)
            add("Last done" to when { since < 0 -> "never"; since == 0 -> "today"; since == 1 -> "yesterday"; else -> "$since days ago" })
            HabitStats.averageGapDays(doneDays, today, 90)?.let { add("Avg gap" to (String.format(Locale.US, "%.1f", it) + "d")) }
        }
        // V2: today's grade against target + stretch goal.
        if (!isBreak && h.extraTarget != null) {
            val tc = hc.firstOrNull { it.epochDay == today }?.count ?: 0
            add("Today" to when (HabitStats.grade(h, tc)) {
                HabitStats.DayGrade.EXTRA -> "🌟 goodjob"; HabitStats.DayGrade.MET -> "✓ on target"
                HabitStats.DayGrade.PARTIAL -> "◑ partial"; HabitStats.DayGrade.NONE -> "—"
            })
        }
        // V4: streak-freeze insurance you've banked.
        if (h.freezeTokens > 0) add("Streak freezes" to "${h.freezeTokens} ❄️")
        if (isBreak && h.moneyPerUnit != null) {
            add("Money saved" to ("$" + String.format(Locale.US, "%.2f", h.moneyPerUnit!! * current)))
        }
    }

    val shareCtx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                // M4: share an on-device progress image (strength ring + heatmap). Offline by construction.
                IconButton(onClick = {
                    vm.shareHabitProgress(h) { loc ->
                        if (loc != null) android.widget.Toast.makeText(shareCtx, "Saved a copy to $loc", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Filled.Share, "Share progress") }
                // W8: mute/unmute this habit's reminders.
                val muted = h.id in vm.settings.collectAsState().value.mutedHabits
                IconButton(onClick = { vm.toggleMutedHabit(h.id) }) {
                    Icon(if (muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications, if (muted) "Unmute reminders" else "Mute reminders")
                }
                IconButton(onClick = { onEdit(h) }) { Icon(Icons.Filled.Edit, "Edit") }
            },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Header
            Header(h, color)

            // O2: the real time-of-day this habit gets done, from stamped completions.
            val typicalMinute = remember(hc) { HabitStats.typicalDoneMinute(hc) }
            if (typicalMinute != null) {
                Text("⏰ You usually do this around ${HabitStats.minuteLabel(typicalMinute)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Y2 — keystone badge: the app names (and quietly guards) your highest-leverage habit.
            val isKeystone = remember(habits, checkins) { vm.keystoneHabitId() == h.id }
            if (isKeystone) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("🗝️ Keystone habit — days you keep this, you get more done",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            // X6 — rhythm-matched schedule: this "daily" habit clearly clusters on a few weekdays; offer
            //      to reshape the plan to reality so it stops marking honest rest days as misses.
            val rhythm = remember(h, hc) { vm.rhythmSuggestion(h.id) }
            if (rhythm != null) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .7f)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Match the schedule to your rhythm?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text("You keep this mostly on ${rhythm.weekdayLabel()}${rhythm.minute?.let { ", around ${HabitStats.minuteLabel(it)}" } ?: ""}. Set those days so honest rest days don't count as misses.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        TextButton(onClick = { vm.applyRhythmSuggestion(rhythm) }, modifier = Modifier.padding(top = 6.dp)) { Text("Use ${rhythm.weekdayLabel()}") }
                    }
                }
            }

            // 1a. L4 — recovery mode: when strength has crashed but there's real history, replace the
            //     broken-streak sting with a kind restart. Resetting the start date gives a clean slate.
            if (!isBreak && strength < 25 && (best >= 7 || hc.size >= 14)) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .7f)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Rough patch — that's OK.", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text("A streak doesn't define you. You've done this ${hc.count { it.epochDay in doneDays }} times before — you can start again today.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                            TextButton(onClick = {
                                val nh = if (h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH) h.copy(freqParam = (h.freqParam - 1).coerceAtLeast(1)) else h.copy(targetPerDay = (h.targetPerDay - 1).coerceAtLeast(1))
                                vm.saveHabit(nh)
                            }) { Text("Make it easier") }
                            TextButton(onClick = {
                                val todayMs = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                                vm.saveHabit(h.copy(startDate = todayMs))
                            }) { Text("Restart fresh today") }
                        }
                    }
                }
            }

            // N6: break-habit craving/slip log with a trigger breakdown.
            if (isBreak) {
                var showSlip by remember { mutableStateOf(false) }
                val triggers = remember(hc) {
                    hc.flatMap { it.reason.split(";").map { s -> s.trim() } }
                        .filter { it.isNotBlank() && !it.equals("slip", true) }
                        .groupingBy { it.lowercase() }.eachCount().entries.sortedByDescending { it.value }.take(4)
                }
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Cravings & slips", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("You're on a $current-day clean streak. Log a slip if it happens — noting the trigger builds awareness.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        if (triggers.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Your top triggers", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            triggers.forEach { (t, n) ->
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(t.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text("×$n", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        TextButton(onClick = { showSlip = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Text("Log a slip…")
                        }
                    }
                }
                if (showSlip) {
                    var trig by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showSlip = false },
                        confirmButton = { TextButton(onClick = { vm.logSlip(h, trig); showSlip = false }) { Text("Log slip") } },
                        dismissButton = { TextButton(onClick = { showSlip = false }) { Text("Cancel") } },
                        title = { Text("Log a slip") },
                        text = {
                            Column {
                                Text("It's okay — awareness is progress. What triggered it? (optional)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(trig, { trig = it.take(30) }, singleLine = true, label = { Text("Trigger (e.g. stress, boredom)") }, modifier = Modifier.fillMaxWidth())
                            }
                        },
                    )
                }
            }

            // 1b. Identity, momentum, freezes & reward (Tier K)
            run {
                val identityCount = doneDays.count { today - it in 0 until 30 }
                val adaptiveUp = !isBreak && strength >= 85 && current >= 14 &&
                    (h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH || h.targetPerDay > 1)
                val adaptiveDown = !isBreak && strength <= 35 && best >= 5 &&
                    (h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH || h.targetPerDay > 1)
                val showReward = h.rewardText.isNotBlank() && h.rewardAtStreak > 0
                if (h.identity.isNotBlank() || h.freezeTokens > 0 || adaptiveUp || adaptiveDown || showReward) {
                    SectionCard {
                        if (h.identity.isNotBlank()) {
                            Text("You've been ${h.identity} on ${identityCount} of the last 30 days.",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
                            if (h.freezeTokens > 0 || adaptiveUp || adaptiveDown || showReward) Spacer(Modifier.height(10.dp))
                        }
                        if (h.freezeTokens > 0) {
                            Text("❄️  ${h.freezeTokens} streak ${if (h.freezeTokens == 1) "freeze" else "freezes"} banked — spend one to protect a missed day (long-press it on the calendar).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (showReward) {
                            Spacer(Modifier.height(if (h.freezeTokens > 0) 8.dp else 0.dp))
                            val reached = best >= h.rewardAtStreak
                            Text((if (reached) "🎉 " else "🎁 ") + "Reward: ${h.rewardText} — ${current.coerceAtMost(h.rewardAtStreak)}/${h.rewardAtStreak}" + if (reached) " · earned!" else "",
                                style = MaterialTheme.typography.bodySmall, fontWeight = if (reached) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (reached) color else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (adaptiveUp || adaptiveDown) {
                            Spacer(Modifier.height(10.dp))
                            if (adaptiveUp) {
                                Text("You're crushing it. Ready to level up?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    val nh = if (h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH) h.copy(freqParam = h.freqParam + 1) else h.copy(targetPerDay = h.targetPerDay + 1)
                                    vm.saveHabit(nh)
                                }) { Text("Raise the goal") }
                            } else {
                                Text("Struggling lately? Make it easier to rebuild momentum.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    val nh = if (h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH) h.copy(freqParam = (h.freqParam - 1).coerceAtLeast(1)) else h.copy(targetPerDay = (h.targetPerDay - 1).coerceAtLeast(1))
                                    vm.saveHabit(nh)
                                }) { Text("Ease the goal") }
                            }
                        }
                    }
                }
            }

            // 2+3. E3: strength ring and stat tiles share one row — the ring on the left, the key numbers
            //      as a compact grid on the right, so the "at a glance" block fits one screen instead of two cards.
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StrengthRing(strength, color)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // 4. Weekday bars
            SectionCard(title = "Best days") {
                WeekdayBars(weekday, color)
            }

            // 5. Strength trend
            SectionCard(title = "Strength over the last 12 weeks") {
                StrengthTrend(trend, color)
            }

            // 6. Milestones
            SectionCard(title = "Milestones") {
                Milestones(current = current, best = best, isBreak = isBreak, color = color)
            }

            // 7. Month calendar (tap to log, long-press to edit the day)
            SectionCard {
                MonthCalendar(today, startDay, color, doneDays, skipDays, countsByDay, notesByDay,
                    onCycle = { day -> vm.cycleHabit(h, day, countsByDay[day] ?: 0) },
                    onEdit = { day -> editorDay = day })
                Spacer(Modifier.height(6.dp))
                Text("Tap a day to log · long-press to edit value, rest day or note",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 8. Year heatmap
            SectionCard(title = "Last 26 weeks") {
                YearHeatmap(today, color, doneDays, skipDays, countsByDay)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    editorDay?.let { day ->
        val missedPast = day < today && day >= startDay && day !in doneDays && day !in skipDays && HabitStats.isExpectedDay(h, day)
        DayEditorDialog(
            habit = h,
            epochDay = day,
            initialCount = countsByDay[day] ?: 0,
            initialSkip = day in skipDays,
            initialNote = notesByDay[day] ?: "",
            color = color,
            photoPath = photoByDay[day],
            canFreeze = h.freezeTokens > 0 && missedPast,
            onFreeze = { vm.spendHabitFreeze(h, day); editorDay = null },
            onPickPhoto = { runCatching { photoPicker.launch("image/*") } },
            onRemovePhoto = { vm.setHabitPhoto(h, day, null) },
            onDismiss = { editorDay = null },
            onSave = { count, skip, note ->
                vm.setHabitDay(h, day, count, if (skip) "skip" else "done", note)
                editorDay = null
            },
        )
    }
}

/**
 * Streak milestones. Reached ones (by best streak) glow; the next one shows how many days remain
 * from the current streak. 66 days is the median for a habit to become automatic (Lally et al.).
 */
@Composable
private fun Milestones(current: Int, best: Int, isBreak: Boolean, color: Color) {
    val marks = listOf(3, 7, 14, 30, 66, 100, 180, 365)
    val next = marks.firstOrNull { it > current }
    val unit = if (isBreak) "days clean" else "day streak"
    Column {
        FlowRowMilestones(marks, best, color)
        if (next != null) {
            Spacer(Modifier.height(12.dp))
            val remaining = next - current
            Text(
                "Next: $next-$unit — $remaining ${if (remaining == 1) "day" else "days"} to go",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val prev = marks.lastOrNull { it <= current } ?: 0
            val frac = if (next > prev) ((current - prev).toFloat() / (next - prev)).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            }
        } else if (best >= marks.last()) {
            Spacer(Modifier.height(12.dp))
            Text("Every milestone reached. Legendary.", style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowMilestones(marks: List<Int>, best: Int, color: Color) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        marks.forEach { m ->
            val reached = best >= m
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (reached) color.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (reached) Icons.Filled.EmojiEvents else Icons.Filled.Lock,
                        contentDescription = null, modifier = Modifier.size(15.dp),
                        tint = if (reached) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("$m", style = MaterialTheme.typography.labelMedium, fontWeight = if (reached) FontWeight.Bold else FontWeight.Normal,
                        color = if (reached) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Per-day editor: exact value, rest-day toggle, and a free-text note — opened by long-pressing a calendar day. */
@Composable
private fun DayEditorDialog(
    habit: com.todocompanion.app.data.entity.HabitEntity,
    epochDay: Long,
    initialCount: Int,
    initialSkip: Boolean,
    initialNote: String,
    color: Color,
    photoPath: String?,
    canFreeze: Boolean,
    onFreeze: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (count: Int, skip: Boolean, note: String) -> Unit,
) {
    var count by remember { mutableStateOf(initialCount) }
    var skip by remember { mutableStateOf(initialSkip) }
    var note by remember { mutableStateOf(initialNote) }
    val date = LocalDate.ofEpochDay(epochDay)
    val step = habit.clickIncrement.coerceAtLeast(1)
    val unit = habit.unit?.takeIf { it.isNotBlank() }
    val photoBitmap = remember(photoPath) { photoPath?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { count = (count - step).coerceAtLeast(0); if (count > 0) skip = false }, enabled = !skip) {
                        Icon(Icons.Filled.Remove, "Less")
                    }
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (skip) "—" else "$count", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = if (skip) MaterialTheme.colorScheme.onSurfaceVariant else color)
                        Text(unit ?: (if (count == 1) "time" else "times"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { count += step; skip = false }, enabled = !skip) { Icon(Icons.Filled.Add, "More") }
                    Spacer(Modifier.width(2.dp))
                    Text("target ${habit.targetPerDay}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Direct entry — typing beats tapping +/- 10000 times for large numeric goals (steps, etc.).
                if ((habit.targetPerDay > 1 || unit != null || step > 1) && !skip) {
                    OutlinedTextField(
                        value = if (count == 0) "" else count.toString(),
                        onValueChange = { v -> count = v.filter { it.isDigit() }.take(7).toIntOrNull() ?: 0 },
                        label = { Text("Type an exact value" + (unit?.let { " ($it)" } ?: "")) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FilterChip(
                    selected = skip,
                    onClick = { skip = !skip },
                    label = { Text("Rest day (skip — protects the streak)") },
                    leadingIcon = if (skip) { { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                )
                if (canFreeze) {
                    TextButton(onClick = onFreeze) { Text("❄️ Protect with a streak freeze") }
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note for this day (optional)") },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                // K5: per-day photo journal.
                if (photoBitmap != null) {
                    Image(photoBitmap, contentDescription = "Day photo",
                        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onPickPhoto) { Text("Change photo") }
                        TextButton(onClick = onRemovePhoto) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    TextButton(onClick = onPickPhoto) { Text("📷 Add a photo") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(count, skip, note) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(0, false, "") }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Header(h: com.todocompanion.app.data.entity.HabitEntity, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(color.copy(alpha = .16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (h.emoji != null) Text(h.emoji, style = MaterialTheme.typography.headlineSmall)
            else Box(Modifier.size(22.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HabitStats.frequencyLabel(h), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (h.description.isNotBlank()) {
                Text(h.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (h.paused) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("Paused", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
private fun StrengthRing(strength: Int, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, -90f, strength / 100f * 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$strength", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = color)
            Text("STRENGTH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatGrid(tiles: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val WEEKDAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

@Composable
private fun WeekdayBars(rates: FloatArray, color: Color) {
    val maxRate = rates.maxOrNull() ?: 0f
    val bestIdx = if (maxRate > 0f) rates.indices.maxByOrNull { rates[it] } ?: -1 else -1
    val maxBar = 96f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        rates.forEachIndexed { i, r ->
            val frac = if (maxRate <= 0f) 0f else r / maxRate
            val hl = i == bestIdx
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(r * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (hl) color else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height((maxBar * frac).dp.coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (hl) color else color.copy(alpha = .35f)),
                )
                Spacer(Modifier.height(4.dp))
                Text(WEEKDAY_LETTERS[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StrengthTrend(points: List<Int>, color: Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        if (points.size < 2) return@Canvas
        val w = size.width
        val hgt = size.height
        val stepX = w / (points.size - 1)
        fun px(i: Int) = i * stepX
        fun py(v: Int) = hgt - (v / 100f) * hgt
        // baseline
        drawLine(grid, Offset(0f, hgt), Offset(w, hgt), strokeWidth = 1.dp.toPx())
        val line = Path()
        val area = Path()
        points.forEachIndexed { i, v ->
            val x = px(i)
            val y = py(v)
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, hgt); area.lineTo(x, y) }
            else { line.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(px(points.size - 1), hgt)
        area.close()
        drawPath(area, color.copy(alpha = .15f))
        drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 4.5.dp.toPx(), Offset(px(points.size - 1), py(points.last())))
    }
}

@Composable
private fun MonthCalendar(
    today: Long,
    startDay: Long,
    color: Color,
    doneDays: Set<Long>,
    skipDays: Set<Long>,
    countsByDay: Map<Long, Int>,
    notesByDay: Map<Long, String>,
    onCycle: (Long) -> Unit,
    onEdit: (Long) -> Unit,
) {
    var monthOffset by remember { mutableStateOf(0) }
    val month = YearMonth.now().plusMonths(monthOffset.toLong())
    val first = month.atDay(1)
    val daysInMonth = month.lengthOfMonth()
    val leading = first.dayOfWeek.value - 1
    val weeks = (leading + daysInMonth + 6) / 7

    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthOffset-- }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = { if (monthOffset < 0) monthOffset++ }, enabled = monthOffset < 0) {
                Text("›", style = MaterialTheme.typography.titleLarge,
                    color = if (monthOffset < 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_LETTERS.forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (wk in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (dow in 0..6) {
                    val dayNum = wk * 7 + dow - leading + 1
                    if (dayNum in 1..daysInMonth) {
                        val epochDay = month.atDay(dayNum).toEpochDay()
                        DayCell(Modifier.weight(1f), dayNum, epochDay, today, startDay, color, doneDays, skipDays, countsByDay,
                            hasNote = notesByDay.containsKey(epochDay),
                            onCycle = { onCycle(epochDay) }, onEdit = { onEdit(epochDay) })
                    } else {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    modifier: Modifier,
    dayNum: Int,
    epochDay: Long,
    today: Long,
    startDay: Long,
    color: Color,
    doneDays: Set<Long>,
    skipDays: Set<Long>,
    countsByDay: Map<Long, Int>,
    hasNote: Boolean,
    onCycle: () -> Unit,
    onEdit: () -> Unit,
) {
    val done = epochDay in doneDays
    val skip = epochDay in skipDays
    val partial = !done && !skip && (countsByDay[epochDay] ?: 0) > 0
    val future = epochDay > today
    val beforeStart = epochDay < startDay
    // Editable-days lock: future days and days before the habit began can't be checked in.
    val locked = future || beforeStart
    val bg = when {
        done -> color
        partial -> color.copy(alpha = .4f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (locked) .18f else .5f)
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.aspectRatio(1f).padding(2.dp).clip(shape)
            .then(if (skip) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, shape) else Modifier.background(bg))
            .combinedClickable(enabled = !locked, onClick = onCycle, onLongClick = onEdit),
        contentAlignment = Alignment.Center,
    ) {
        val textColor = when {
            done -> Color.White
            skip -> MaterialTheme.colorScheme.outline
            locked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(if (skip) "s" else "$dayNum", style = MaterialTheme.typography.labelSmall, color = textColor)
        if (hasNote) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(4.dp).clip(CircleShape)
                    .background(if (done) Color.White else color),
            )
        }
    }
}

@Composable
private fun YearHeatmap(
    today: Long,
    color: Color,
    doneDays: Set<Long>,
    skipDays: Set<Long>,
    countsByDay: Map<Long, Int>,
) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val todayDate = LocalDate.now()
    val currentMonday = todayDate.minusDays((todayDate.dayOfWeek.value - 1).toLong())
    val weeks = 26
    val startMonday = currentMonday.minusWeeks((weeks - 1).toLong())
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (c in 0 until weeks) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (r in 0..6) {
                    val day = startMonday.plusWeeks(c.toLong()).plusDays(r.toLong()).toEpochDay()
                    Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp))
                        .background(heatColor(day, today, color, empty, doneDays, skipDays, countsByDay)))
                }
            }
        }
    }
}

private fun heatColor(
    day: Long,
    today: Long,
    color: Color,
    empty: Color,
    doneDays: Set<Long>,
    skipDays: Set<Long>,
    countsByDay: Map<Long, Int>,
): Color = when {
    day > today -> empty.copy(alpha = .15f)
    day in doneDays -> color
    day in skipDays -> empty.copy(alpha = .6f)
    (countsByDay[day] ?: 0) > 0 -> color.copy(alpha = .4f)
    else -> empty.copy(alpha = .5f)
}
