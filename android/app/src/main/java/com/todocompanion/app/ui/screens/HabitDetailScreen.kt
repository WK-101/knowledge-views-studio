package com.todocompanion.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.todocompanion.app.ui.components.MiniCheck
import com.todocompanion.app.ui.components.StatTile
import kotlin.math.roundToInt
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
    val cravings by vm.cravings.collectAsState()
    val h = habits.firstOrNull { it.id == habitId }

    if (h == null) {
        Scaffold(topBar = {
            TopAppBar(expandedHeight = 52.dp, 
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

    val today = vm.today()
    val startDay = h.startEpochDay()
    val hc = checkins.filter { it.habitId == h.id }
    val countsByDay = hc.associate { it.epochDay to it.count }
    val notesByDay = hc.filter { it.reason.isNotBlank() }.associate { it.epochDay to it.reason }
    val doneDays = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
    val skipDays = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
    val relapseDays = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
    val photoByDay = hc.filter { it.photoUri != null }.associate { it.epochDay to it.photoUri!! }
    var editorDay by remember { mutableStateOf<Long?>(null) }
    // K5: pick a photo for the day currently open in the editor. R45 — via SystemPicker (classic
    // Activity startActivityForResult, gallery ACTION_PICK).

    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    // Z8 correction: the headline strength honours the graded-strength opt-in, matching Momentum & goals.
    val strength = vm.strengthOf(h)
    val forgivingStreaks = vm.settings.collectAsState().value.forgivingStreaks
    // Wrapped in remember: the forgiving branch re-scans every done-day, so an unmemoized recompute of a
    // multi-year habit runs on each recomposition.
    val current = remember(h, doneDays, skipDays, relapseDays, today, forgivingStreaks) { HabitStats.displayStreak(h, doneDays, skipDays, relapseDays, today, forgivingStreaks) }
    val best = remember(h, doneDays, skipDays, relapseDays, today, forgivingStreaks) { HabitStats.displayBestStreak(h, doneDays, skipDays, relapseDays, today, forgivingStreaks) }
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
        // (Money/clean-time for a break habit live in the single quit dashboard below — the header no
        //  longer repeats them with a weaker formula, so there is one authoritative figure per stat.)
    }

    val shareCtx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp, 
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

            // R33 — the BUILDER coaching block: automaticity, identity votes, never-miss-twice, coach tips,
            // and (for quit habits) the quit dashboard + urge button.
            BuilderSection(vm, h, hc, doneDays, skipDays, current, today, color, cravings.filter { it.habitId == h.id })

            // R56 — skip-with-reason log: why this habit gets skipped, most common first, so the pattern
            // ("always travel") is visible. Only skips carry a reason; a skip never breaks the streak.
            val skipReasons = remember(hc) {
                hc.filter { it.status == "skip" && it.reason.isNotBlank() }
                    .groupingBy { it.reason.trim() }.eachCount()
                    .entries.sortedByDescending { it.value }.take(6)
            }
            if (skipReasons.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("⏭️ Why you skip", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Spacer(Modifier.size(6.dp))
                        skipReasons.forEach { (reason, count) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(reason, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("×$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // Y2 — keystone badge: the app names (and quietly guards) your highest-leverage habit.
            val isKeystone = remember(habits, checkins) { vm.keystoneHabitId() == h.id }
            if (isKeystone) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("🗝️ Keystone habit — days you keep this, you get more done",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            // B1 — habits × time (the cross-module "unclaimed intersection"): how much more, or less, you
            // focus on days you keep this habit. Now that Focus is unified into the one timeline, this joins
            // check-ins with kind="focus" minutes directly. Shown only with enough of both kinds of days.
            val settingsSnap by vm.settings.collectAsState()
            val timeEntries by vm.timeEntries.collectAsState()
            if (!isBreak && com.todocompanion.app.domain.Modules.isEnabled(settingsSnap, com.todocompanion.app.domain.Modules.TIME)) {
                val lift = remember(timeEntries, doneDays, today) {
                    HabitStats.focusLift(doneDays, vm.focusMinutesByDay(), today, h.startEpochDay())
                }
                if (lift.onDays >= 5 && lift.offDays >= 5 && (lift.onAvgMin > 0 || lift.offAvgMin > 0) && kotlin.math.abs(lift.liftPct) >= 10) {
                    val up = lift.liftPct >= 0
                    Surface(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        color = (if (up) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer).copy(alpha = .7f),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                (if (up) "⚡ " else "💤 ") + "On days you keep this, you focus ${kotlin.math.abs(lift.liftPct)}% ${if (up) "more" else "less"}",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${lift.onAvgMin}m focused on the days you did it vs ${lift.offAvgMin}m otherwise (last 90 days).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // X6 — rhythm-matched schedule: this "daily" habit clearly clusters on a few weekdays; offer
            //      to reshape the plan to reality so it stops marking honest rest days as misses.
            val rhythm = remember(h, hc) { vm.rhythmSuggestion(h.id) }
            if (rhythm != null) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .7f)) {
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
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .7f)) {
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
                                val todayMs = LocalDate.ofEpochDay(today).atStartOfDay(vm.zoneId).toInstant().toEpochMilli()
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
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
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
                                com.todocompanion.app.ui.components.AppTextField(trig, { trig = it.take(30) }, singleLine = true, label = { Text("Trigger (e.g. stress, boredom)") }, modifier = Modifier.fillMaxWidth())
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
                            // Base "earned" on the same streak the progress line shows (and the celebration
                            // fires on), so the badge can't read "earned" while no celebration ever fired.
                            val reached = current >= h.rewardAtStreak
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
                                pair.forEach { (label, value) -> StatTile(value = value, label = label, modifier = Modifier.weight(1f)) }
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
            onPickPhoto = { com.todocompanion.app.util.SystemPicker.galleryOne(onError = { vm.toastMsg(it) }) { uri -> vm.setHabitPhoto(h, day, uri) } },
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
    var count by remember { mutableIntStateOf(initialCount) }
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
                    com.todocompanion.app.ui.components.AppTextField(
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
                com.todocompanion.app.ui.components.AppTextField(
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
            // R55 — the general notes field (like a task note), shown under the "why".
            if (h.notes.isNotBlank()) {
                Text("📝 ${h.notes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
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

// StatTile now comes from the shared ui/components/ReviewComponents.kt.

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
    var monthOffset by remember { mutableIntStateOf(0) }
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
    val todayDate = LocalDate.ofEpochDay(today)   // the zone-aware today the cells are keyed on, not the system clock
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

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  R33 · Habit BUILDER coaching — automaticity, identity votes, never-miss-twice, coach, quit + urge.
// ═══════════════════════════════════════════════════════════════════════════════════════════════
private typealias HB = com.todocompanion.app.domain.habit.HabitBuilder

@Composable
private fun BuilderSection(
    vm: com.todocompanion.app.ui.AppViewModel, h: com.todocompanion.app.data.entity.HabitEntity,
    hc: List<com.todocompanion.app.data.entity.HabitCheckinEntity>, doneDays: Set<Long>, skipDays: Set<Long>,
    current: Int, today: Long, color: Color, myCravings: List<com.todocompanion.app.data.entity.CravingEventEntity>,
) {
    val isBreak = h.habitType == "break"
    val tips = remember(hc, today) { HB.coachTips(h, hc, today) }

    if (!isBreak) {
        // F15 — automaticity meter (recency-aware: a lapsed habit decays rather than reading "Automatic").
        val auto = remember(doneDays, today) { HB.automaticity(doneDays, today) }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Becoming automatic", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${auto.pct}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(auto.pct / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                }
                Spacer(Modifier.height(6.dp))
                Text("${auto.stage} · ${auto.reps} reps. Habits settle in around 66 days (Lally), not on a perfect streak.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // F5 — identity votes.
        if (h.identity.isNotBlank()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("“${h.identity}”", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("Every check-in is a vote for this identity.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .8f))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${doneDays.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("votes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .8f))
                    }
                }
            }
        }

        // F9 — never miss twice.
        val miss = remember(doneDays, skipDays, today) { HB.missStatus(h, doneDays, skipDays, today, today in doneDays) }
        if (miss.atRiskToday) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .8f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Don't miss twice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("You missed the last one — missing once is an accident, twice starts a new habit. Do the two-minute version today.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(top = 2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        FilledTonalButton(onClick = { vm.setHabitValue(h, today, h.targetPerDay.coerceAtLeast(1)) }) { Text("Do it now") }
                        if (h.freezeTokens > 0) miss.lastMissedDay?.let { md ->
                            TextButton(onClick = { vm.useFreeze(h, md) }) { Text("❄ Use a freeze (${h.freezeTokens})") }
                        }
                    }
                }
            }
        }
    } else {
        // F12 — quit dashboard.
        val q = remember(hc, today) { HB.quitStats(h, hc, today) }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)) {
            Column(Modifier.padding(16.dp)) {
                Text("Clean time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("${q.cleanDays} day${if (q.cleanDays == 1) "" else "s"}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (q.moneySaved > 0) Column { Text("Money saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f)); Text(runCatching { java.text.NumberFormat.getCurrencyInstance().apply { maximumFractionDigits = 0 }.format(q.moneySaved) }.getOrDefault("%,.0f".format(q.moneySaved)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    if (q.minutesSaved > 0) Column { Text("Time reclaimed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f)); Text("${q.minutesSaved / 60}h", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pledgedToday = h.lastPledgeDay == today
                    FilledTonalButton(onClick = { vm.pledgeToday(h) }, enabled = !pledgedToday) {
                        if (pledgedToday) {
                            // "Pledged" reads with the modern completion mark, not a raw "✓".
                            MiniCheck()
                            Spacer(Modifier.width(6.dp))
                            Text("Pledged")
                        } else Text("Pledge today")
                    }
                    if (h.quitSinceMillis == null) TextButton(onClick = { vm.startQuitClock(h) }) { Text("Start clean-time") }
                }
                if (q.moneySaved <= 0 && q.minutesSaved <= 0)
                    Text("Add a per-use cost or time in the editor to see money & time reclaimed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f), modifier = Modifier.padding(top = 6.dp))
            }
        }

        // F13 — urge button + urge trigger heatmap.
        var urge by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("When an urge hits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Ride it out — urges crest and pass. Log it either way to learn your triggers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Button(onClick = { urge = true }, modifier = Modifier.padding(top = 10.dp)) { Text("🌊  I have an urge") }
                if (myCravings.isNotEmpty()) {
                    val buckets = remember(myCravings) { HB.urgeByTimeBucket(myCravings) }
                    val maxB = (buckets.maxOrNull() ?: 1).coerceAtLeast(1)
                    Spacer(Modifier.height(12.dp))
                    Text("When urges strike", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val labels = listOf("12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p")
                        buckets.forEachIndexed { i, b ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.BottomCenter) {
                                    Box(Modifier.fillMaxWidth().fillMaxHeight(if (b == 0) 0.02f else (b.toFloat() / maxB)).clip(RoundedCornerShape(3.dp)).background(if (b == 0) MaterialTheme.colorScheme.surfaceVariant else color))
                                }
                                Text(labels[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                            }
                        }
                    }
                    val surfed = myCravings.count { it.surfed }
                    Text("$surfed of ${myCravings.size} urges ridden out. Each one you surf makes the next easier.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (urge) UrgeDialog(competingResponse = h.competingResponse, onDismiss = { urge = false }, onLog = { intensity, trigger, surfed, halt, dur -> vm.logCraving(h, intensity, trigger, surfed, halt, dur); urge = false })
    }

    // R34 — the life-systems cards for this habit (WOOP, values, commitment, forfeit, urge analytics,
    // lapse-recovery, what-if, chronotype, context capture).
    LifeSystemsHabitCards(vm, h, hc, doneDays, skipDays, today, color, myCravings)

    // R35 — the third-wave cards (friction, cue-disruption, context stability, self-tuning reminder,
    // forecast, graduation, make-up, lapse early-warning, future-self scene).
    ThirdWaveHabitCards(vm, h, hc, doneDays, skipDays, today, color)

    // R36 — the fourth-wave cards (adaptive horizon, red-chain, micro-lesson, extinction ladder, escrow).
    FourthWaveHabitCards(vm, h, hc, today, color, myCravings)

    // F17 — the insights coach (both kinds).
    if (tips.isNotEmpty()) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Coach", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
                tips.forEach { t ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("💡", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(t, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** F13 / LS10 — the urge-surfing timer: a 90-second breathing countdown that offers the pre-chosen
 *  competing response, then logs intensity + trigger + HALT state + how long it lasted + outcome. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UrgeDialog(competingResponse: String, onDismiss: () -> Unit, onLog: (Int, String, Boolean, String, Int) -> Unit) {
    var secs by remember { mutableIntStateOf(90) }
    var intensity by remember { mutableIntStateOf(3) }
    var trigger by remember { mutableStateOf("") }
    val halt = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { while (secs > 0) { kotlinx.coroutines.delay(1000); secs-- } }
    val elapsed = 90 - secs
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onLog(intensity, trigger, true, halt.joinToString(","), elapsed) }) { Text("I rode it out 🌊") } },
        dismissButton = { TextButton(onClick = { onLog(intensity, trigger, false, halt.joinToString(","), elapsed) }) { Text("I gave in", color = MaterialTheme.colorScheme.error) } },
        title = { Text(if (secs > 0) "Breathe — this will pass" else "You made it") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (secs > 0) "Notice the urge without acting. Watch it rise and fall. ${secs}s" else "The wave passed. Most urges fade within a couple of minutes.",
                    style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(1f - secs / 90f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                }
                // LS10 competing response: redirect, don't just white-knuckle.
                if (competingResponse.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f)) {
                        Text("Instead, do: $competingResponse", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Text("How strong is it?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { n ->
                        androidx.compose.material3.FilterChip(selected = intensity == n, onClick = { intensity = n }, label = { Text("$n") })
                    }
                }
                // HALT check — hungry / angry / lonely / tired often underlie an urge.
                Text("HALT — feeling any of these?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("hungry", "angry", "lonely", "tired").forEach { f ->
                        androidx.compose.material3.FilterChip(selected = f in halt, onClick = { if (f in halt) halt.remove(f) else halt.add(f) }, label = { Text(f.replaceFirstChar { it.uppercase() }) })
                    }
                }
                com.todocompanion.app.ui.components.AppTextField(trigger, { trigger = it }, singleLine = true, label = { Text("Trigger? (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

private typealias LS = com.todocompanion.app.domain.habit.LifeSystems

/** R34 — the life-systems cards for one habit: WOOP plan, value link, context capture, commitment +
 *  witness, self-forfeit + akrasia horizon, lapse-recovery, urge analytics, what-if, chronotype. */
@Composable
private fun LifeSystemsHabitCards(
    vm: com.todocompanion.app.ui.AppViewModel, h: com.todocompanion.app.data.entity.HabitEntity,
    hc: List<com.todocompanion.app.data.entity.HabitCheckinEntity>, doneDays: Set<Long>, skipDays: Set<Long>,
    today: Long, color: Color, myCravings: List<com.todocompanion.app.data.entity.CravingEventEntity>,
) {
    val isBreak = h.habitType == "break"
    val settings by vm.settings.collectAsState()
    val values by vm.coreValues.collectAsState()
    val witnesses by vm.witnessEvents.collectAsState()
    // Apply any queued "make it easier" change whose one-week horizon has now passed.
    LaunchedEffect(h.id) { vm.applyPendingEaseIfDue(h) }

    // LS1 · WOOP plan — the back half of the intention.
    if (h.woopOutcome.isNotBlank() || h.woopObstacle.isNotBlank() || h.woopCoping.isNotBlank()) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your plan (WOOP)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
                if (h.woopOutcome.isNotBlank()) Text("🎯 Outcome — ${h.woopOutcome}", style = MaterialTheme.typography.bodyMedium)
                if (h.woopObstacle.isNotBlank()) Text("🧱 Obstacle — ${h.woopObstacle}", style = MaterialTheme.typography.bodyMedium)
                if (h.woopCoping.isNotBlank()) Text("↪ If it hits: ${h.woopCoping}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }

    // LS5 · value link.
    h.valueId?.let { vid -> values.firstOrNull { it.id == vid } }?.let { v ->
        Surface(Modifier.fillMaxWidth().clickable { vm.lifeSystemsRoute.value = "values" }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .5f)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(v.emoji ?: "🧭", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Text("In service of your value: ${v.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }

    // LS · chronotype nudge.
    val typical = remember(hc) { HabitStats.typicalDoneMinute(hc.filter { it.habitId == h.id }) }
    LS.chronotypeNudge(h, typical, settings.chronotype)?.let { nudge ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
            Row(Modifier.padding(14.dp)) { Text("⏰", Modifier.padding(end = 10.dp)); Text(nudge, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    // LS2 · context capture — a light "how did it go?" once today's habit is done.
    if (!isBreak && today in doneDays) {
        val todayCheckin = hc.firstOrNull { it.habitId == h.id && it.epochDay == today }
        var energy by remember(todayCheckin?.ctxEnergy) { mutableIntStateOf(todayCheckin?.ctxEnergy ?: 0) }
        var mood by remember(todayCheckin?.ctxMood) { mutableIntStateOf(todayCheckin?.ctxMood ?: 0) }
        var place by remember(todayCheckin?.ctxPlace) { mutableStateOf(todayCheckin?.ctxPlace ?: "") }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("How did it go? (optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("A tag or two now becomes the correlation engine's evidence later.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Energy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { n -> androidx.compose.material3.FilterChip(selected = energy == n, onClick = { energy = n; vm.setCheckinContext(h, today, energy, mood, place) }, label = { Text("$n") }) }
                }
                Text("Mood", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { n -> androidx.compose.material3.FilterChip(selected = mood == n, onClick = { mood = n; vm.setCheckinContext(h, today, energy, mood, place) }, label = { Text("$n") }) }
                }
                Text("Where", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Home", "Work", "Gym", "Outside", "Out & about").forEach { p ->
                        androidx.compose.material3.FilterChip(
                            selected = place.equals(p, ignoreCase = true),
                            onClick = { place = if (place.equals(p, ignoreCase = true)) "" else p; vm.setCheckinContext(h, today, energy, mood, place) },
                            label = { Text(p) })
                    }
                }
            }
        }
    }

    // LS9 · what-if forward simulator (build habits).
    if (!isBreak && doneDays.size >= 4) {
        val proj = remember(doneDays, today) { LS.whatIf(h, doneDays, today) }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("What if…", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Where this habit's automaticity heads over the next 6 months, at three paces.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                proj.forEach { p ->
                    val end = p.weeks.last().second
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.adherenceLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Box(Modifier.width(90.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(end / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("$end%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
                    }
                }
            }
        }
    }

    // LS7 · commitment contract + local referee.
    if (h.contractText.isNotBlank() || h.refereeName.isNotBlank()) {
        val myWitnesses = witnesses.filter { it.habitId == h.id }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Commitment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (h.contractText.isNotBlank()) Text("“${h.contractText}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                if (h.refereeName.isNotBlank()) {
                    Text("Referee: ${h.refereeName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    FilledTonalButton(onClick = { vm.addWitness(h, "Day ${doneDays.size}", "") }, modifier = Modifier.padding(top = 8.dp)) { Text("✍️ ${h.refereeName} witnessed it") }
                }
                myWitnesses.take(3).forEach { w ->
                    Text("· ${w.refereeName} confirmed ${w.milestoneLabel} — ${java.time.Instant.ofEpochMilli(w.atMillis).atZone(vm.zoneId).toLocalDate()}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    // LS7 · self-forfeit + akrasia horizon.
    if (h.forfeitText.isNotBlank()) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .4f)) {
            Column(Modifier.padding(16.dp)) {
                Text("Stake", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("If you derail: ${h.forfeitText}" + if (h.forfeitLevel > 0) " · owed ${h.forfeitLevel}×" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                if (h.pendingEaseMillis > System.currentTimeMillis()) {
                    val days = ((h.pendingEaseMillis - System.currentTimeMillis()) / (24L * 3600 * 1000)).toInt() + 1
                    Text("An easing you queued applies in $days day${if (days == 1) "" else "s"} (akrasia horizon).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
                    TextButton(onClick = { vm.cancelEase(h) }) { Text("Cancel the easing") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    TextButton(onClick = { vm.escalateForfeit(h) }) { Text("I derailed — owe the forfeit") }
                    if (!isBreak && h.pendingEaseMillis <= System.currentTimeMillis()) TextButton(onClick = { vm.queueEase(h, (h.targetPerDay - 1).coerceAtLeast(1)) }) { Text("Make it easier (in 7d)") }
                }
            }
        }
    }

    if (isBreak) {
        // LS10 · competing response card (also offered live in the urge dialog).
        if (h.competingResponse.isNotBlank()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Do this instead", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(h.competingResponse, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Redirect the same cue to an incompatible action — the clinical way to break a habit.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .8f), modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        // LS10 · urge analytics — triggers, HALT, duration curve.
        if (myCravings.size >= 3) {
            val stats = remember(myCravings) { LS.urgeStats(myCravings) }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Urge analytics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (stats.medianDurationSec > 0) Text("Your urges pass in about ${stats.medianDurationSec}s — proof they crest and fall.", style = MaterialTheme.typography.bodyMedium)
                    if (stats.topTriggers.isNotEmpty()) Text("Top triggers: " + stats.topTriggers.joinToString(", ") { "${it.first} (${it.second})" }, style = MaterialTheme.typography.bodyMedium)
                    if (stats.haltCounts.isNotEmpty()) Text("Often when: " + stats.haltCounts.entries.joinToString(", ") { "${it.key} (${it.value})" }, style = MaterialTheme.typography.bodyMedium)
                    Text("${(stats.surfedRate * 100).roundToInt()}% ridden out across ${stats.total} urges.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // LS · lapse-recovery — after a recent relapse, replace shame with a fresh start.
        val relapses = hc.filter { it.habitId == h.id && HabitStats.isRelapse(h, it.count) }.map { it.epochDay }
        val lastSlip = relapses.maxOrNull()
        if (lastSlip != null && (today - lastSlip) in 0..2) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("A lapse is data, not identity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("One slip isn't a relapse — the spiral is what does the damage. You've already come far. Set the next tiny action and start clean from today.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        FilledTonalButton(onClick = { vm.startQuitClock(h) }) { Text("Fresh start today") }
                        TextButton(onClick = { vm.pledgeToday(h) }) { Text("Re-pledge") }
                    }
                }
            }
        }
    }
}

private typealias TW = com.todocompanion.app.domain.habit.ThirdWave

/** R35 — the third-wave cards: friction/prep, cue-disruption, context stability, self-tuning reminder,
 *  data-grounded forecast, graduation, make-up ledger, lapse early-warning, future-self scene. */
@Composable
private fun ThirdWaveHabitCards(
    vm: com.todocompanion.app.ui.AppViewModel, h: com.todocompanion.app.data.entity.HabitEntity,
    hc: List<com.todocompanion.app.data.entity.HabitCheckinEntity>, doneDays: Set<Long>, skipDays: Set<Long>,
    today: Long, color: Color,
) {
    val isBreak = h.habitType == "break"
    val mine = remember(hc, h.id) { hc.filter { it.habitId == h.id } }

    // TW-A · friction / prep steps (build = ready kit; break = obstacle course).
    if (h.frictionSteps.isNotBlank()) {
        val steps = h.frictionSteps.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text(if (isBreak) "Make it harder" else "Make it easy — your ready kit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(if (isBreak) "Add steps between you and the bad habit — friction beats willpower." else "Prep the night before so the good habit takes the fewest possible steps.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                steps.forEach { s -> Row(Modifier.padding(vertical = 2.dp)) { Text("•  ", color = color); Text(s, style = MaterialTheme.typography.bodyMedium) } }
            }
        }
    }

    // TW-A · cue-disruption (break).
    if (isBreak && (h.cueToDisrupt.isNotBlank() || h.cueDisruptionPlan.isNotBlank())) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Disrupt the cue", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (h.cueToDisrupt.isNotBlank()) Text("🔔 The trigger: ${h.cueToDisrupt}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                if (h.cueDisruptionPlan.isNotBlank()) Text("✂ Your plan: ${h.cueDisruptionPlan}", style = MaterialTheme.typography.bodyMedium)
                Text("Removing the antecedent is easier than resisting the routine — kill the cue, not the willpower.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    // TW-A · context-stability score (build).
    if (!isBreak) TW.contextStability(h, hc)?.let { score ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Context stability", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("$score%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(score / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                }
                Text(if (score >= 70) "Rock-steady time & place — the fast lane to automatic." else "Scattered timing slows habit formation. Try pinning it to one anchor.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    // TW-B · self-tuning reminder drift.
    TW.reminderDrift(h, hc)?.let { (minute, msg) ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .5f)) {
            Column(Modifier.padding(16.dp)) {
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                FilledTonalButton(onClick = { vm.applyReminderDrift(h, minute) }, modifier = Modifier.padding(top = 8.dp)) { Text("Move the reminder") }
            }
        }
    }

    // TW-B · lapse early-warning (break).
    TW.lapseWarning(h, hc, today)?.let { warn ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .45f)) {
            Row(Modifier.padding(16.dp)) { Text("⚠", Modifier.padding(end = 10.dp)); Text(warn, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }

    // TW-C · data-grounded forecast (build).
    if (!isBreak) TW.forecast(h, hc, today)?.let { fc ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Forecast (from your own pace)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Projected automaticity, with a band from how steady you've actually been.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                fc.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.weeks} wk", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(f.mid / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${f.low}–${f.high}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
                    }
                }
            }
        }
    }

    // TW-F · make-up ledger — repay a recent missed expected day (build).
    if (!isBreak) {
        val missed = remember(doneDays, skipDays, today) {
            ((today - 10) until today).filter { HabitStats.isExpectedDay(h, it) && it >= h.startEpochDay() && it !in doneDays && it !in skipDays }.sortedDescending()
        }
        if (missed.isNotEmpty()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text("Make-up ledger", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Missed a day? Repay it — a make-up clears the debt. Not a failure, just a balance restored.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                    missed.take(3).forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(java.time.LocalDate.ofEpochDay(d).toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { vm.logMakeUp(h, d) }) { Text("Make up") }
                        }
                    }
                }
            }
        }
    }

    // TW-D · reward taper / graduation (build, near-automatic).
    if (!isBreak) {
        val auto = remember(doneDays, today) { com.todocompanion.app.domain.habit.HabitBuilder.automaticity(doneDays, today) }
        if (h.graduated || auto.pct >= 90) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (h.graduated) "🎓 Graduated" else "Ready to graduate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(if (h.graduated) "This one's part of you now — celebrations and prompts have eased off. The app's job here is done." else "This habit is nearly automatic. Graduate it to quiet the prompts and let it run on its own.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 2.dp))
                    TextButton(onClick = { vm.setGraduated(h, !h.graduated) }) { Text(if (h.graduated) "Bring back coaching" else "Graduate this habit") }
                }
            }
        }
    }

    // TW-D · future-self scene (shown for reference; also surfaced in the urge dialog).
    if (h.futureScene.isNotBlank()) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Your future self", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
                Text("“${h.futureScene}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                Text("Picturing a specific future you — the one who kept this — makes the payoff feel real now.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}


private typealias FW = com.todocompanion.app.domain.habit.FourthWave

/** R36 — the fourth-wave cards: adaptive automaticity horizon, red-chain counter, just-in-time
 *  micro-lesson, cue-exposure extinction ladder, and any escrow riding on this habit. */
@Composable
private fun FourthWaveHabitCards(
    vm: com.todocompanion.app.ui.AppViewModel, h: com.todocompanion.app.data.entity.HabitEntity,
    hc: List<com.todocompanion.app.data.entity.HabitCheckinEntity>, today: Long, color: Color,
    cravings: List<com.todocompanion.app.data.entity.CravingEventEntity>,
) {
    val isBreak = h.habitType == "break"
    val escrows by vm.escrows.collectAsState()
    val checkins = hc

    // FW-2 · just-in-time micro-lesson (the teachable moment for where this habit is right now).
    FW.microLesson(h, hc, cravings, today)?.let { lesson ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .45f)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lesson.emoji, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp))
                    Text(lesson.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Text(lesson.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    // FW-3 · adaptive automaticity ETA (build). The "Becoming automatic" card above owns the single
    // automaticity % + bar; this one carries only the time-to-automatic forecast, so there aren't two
    // competing percentage meters for the same concept on one screen.
    if (!isBreak) FW.adaptiveHorizon(h, hc, today)?.let { hz ->
        if (hz.repsToTarget > 0) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Time to automatic", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("~${hz.etaDays} days at your current pace (${hz.adherence}% adherence, ${hz.repsToTarget} more reps). Not a fixed 66 days — it's tuned to you.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    // FW-4 · red-chain counter (break).
    if (isBreak) FW.redChain(h, hc, today)?.let { rc ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            color = if (rc.redDays > 0) MaterialTheme.colorScheme.errorContainer.copy(alpha = .45f) else MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Clean streak", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("🟢 ${rc.cleanDays}d", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                }
                Text("Longest clean run: ${rc.longestClean} days · ${rc.relapses} slips logged.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (rc.redDays > 0) Text("🔴 Red chain: ${rc.redDays} day${if (rc.redDays == 1) "" else "s"} in a row. Two is a pattern forming — break it today, the smallest way you can.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    // FW-8 · cue-exposure extinction ladder (break).
    if (isBreak) FW.extinctionLadder(h, cravings)?.let { ex ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Extinction ladder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Rung ${ex.rung}/4 — ${ex.rungLabel}", style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(top = 2.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(ex.rung / 4f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                }
                Text(
                    (if (ex.falling) "Your urge intensity is falling — from ${"%.1f".format(ex.earlyAvg)} to ${"%.1f".format(ex.recentAvg)} out of 5. The cue is losing its grip. "
                    else "Average urge intensity: ${"%.1f".format(ex.recentAvg)}/5 across ${ex.exposures} logged urges. ") +
                    "Keep facing the trigger without acting — that's what extinguishes it.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    // FW-9 · escrows riding on this habit.
    val mine = escrows.filter { it.habitId == h.id }
    if (mine.isNotEmpty()) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("On the line", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                mine.forEach { e ->
                    val st = FW.escrowStatus(e, listOf(h), checkins, today)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (e.kind == "stake") "🎯 " else "🎁 ", style = MaterialTheme.typography.bodyMedium)
                        Column(Modifier.weight(1f)) {
                            Text(e.description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Box(Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(st.pct / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (e.released) MaterialTheme.colorScheme.outline else color))
                            }
                        }
                        Text(if (e.released) "done" else "${st.current}/${st.target}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                TextButton(onClick = { vm.lifeSystemsRoute.value = "escrow" }) { Text("Manage escrows") }
            }
        }
    }
}
