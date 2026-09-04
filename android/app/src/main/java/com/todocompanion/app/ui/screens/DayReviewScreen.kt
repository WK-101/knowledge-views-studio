package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.CoreValueEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.AdaptivePrompts
import com.todocompanion.app.domain.DailyQuestion
import com.todocompanion.app.domain.DailyQuestions
import com.todocompanion.app.domain.DayAlignment
import com.todocompanion.app.domain.DayAlignments
import com.todocompanion.app.domain.EmotionWords
import com.todocompanion.app.domain.DayMemories
import com.todocompanion.app.domain.Goal
import com.todocompanion.app.domain.Goals
import com.todocompanion.app.domain.Prediction
import com.todocompanion.app.domain.Predictions
import com.todocompanion.app.domain.ReflectionCompanion
import com.todocompanion.app.domain.ReviewCadence
import com.todocompanion.app.domain.ReviewInsights
import com.todocompanion.app.domain.ReviewRollup
import com.todocompanion.app.domain.YearReviewed
import com.todocompanion.app.domain.WeeklyReview
import com.todocompanion.app.domain.WeeklyReviews
import com.todocompanion.app.domain.calendar.CalendarEngine
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.DoneRecord
import com.todocompanion.app.domain.habit.FourthWave
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.AppTextField
import com.todocompanion.app.ui.components.DateOnlyPickerDialog
import com.todocompanion.app.ui.components.OptionChips
import com.todocompanion.app.util.DayCard
import com.todocompanion.app.util.ProgressCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * R66/R106 — the daily review: a date-selectable page that both *recaps* a day (what you finished,
 * wins, habits, events, tracked time) and lets you *close* it — reflect (rating, mood, highlight,
 * gratitude, lesson), reckon with what's still open (carry it to tomorrow), and get ready for
 * tomorrow (preview + set the one thing that matters). Shareable as an image or text. Step with ‹ ›,
 * jump to any date, or tap Today. Entirely on-device and workspace-scoped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayReviewScreen(vm: AppViewModel, initialDay: Long, startInClose: Boolean = false, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val todayEd = LocalDate.now(zone).toEpochDay()
    var day by remember { mutableLongStateOf(initialDay) }
    var showPicker by remember { mutableStateOf(false) }
    var showReflect by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showClose by remember { mutableStateOf(false) }
    var showQuestions by remember { mutableStateOf(false) }
    // Wave 1 — the guided Weekly Review (opened from the Week roll-up), and the ISO-week it targets.
    var showWeekly by remember { mutableStateOf(false) }
    var weeklyIso by remember { mutableStateOf("") }
    // Wave 3 — sealed letter to future me (A), Drucker prediction loop (C), reflection companion (E),
    // "Year, reviewed" (B). All local; the sealed store/crypto is reused from R32 via the VM.
    var showWriteLetter by remember { mutableStateOf(false) }
    var openLetter by remember { mutableStateOf<com.todocompanion.app.data.entity.SealedNoteEntity?>(null) }
    var showAddPrediction by remember { mutableStateOf(false) }
    var resolvePrediction by remember { mutableStateOf<Prediction?>(null) }
    var showCompanion by remember { mutableStateOf(false) }
    var showYear by remember { mutableStateOf(false) }
    // Wave 1 — deliberate rollover: ids the user chose to "let go" (everything else defaults to carry).
    var rolloverLetGo by remember { mutableStateOf(setOf<String>()) }
    // Phase F — opened via the "Close your day" shortcut / evening nudge: land straight in the close flow.
    LaunchedEffect(startInClose) { if (startInClose) showClose = true }
    // Phase D — Day · Week · Month. Day keeps the full close-the-day screen; Week/Month roll the reviewed
    // period up into read-only aggregate cards.
    var mode by remember { mutableStateOf(ReviewRange.DAY) }

    val tasks by vm.tasks.collectAsState()
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val activities by vm.timeActivities.collectAsState()
    val events by vm.events.collectAsState()
    val dayLogs by vm.dayLogs.collectAsState()
    val settings by vm.settings.collectAsState()
    val coreValues by vm.coreValues.collectAsState()
    val sealedNotes by vm.sealedNotes.collectAsState()

    val date = LocalDate.ofEpochDay(day)
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()
    val isToday = day == todayEd

    val feed = remember(tasks, habits, checkins, timeEntries, day) {
        DoneRecord.build(tasks, habits, checkins, timeEntries, zone).filter { it.epochDay == day }
    }
    val tasksDone = tasks.filter { it.completed && it.completedAt != null && it.completedAt!! in dayStart until dayEnd && !it.trashed }
        .sortedByDescending { it.completedAt }
    val wins = feed.filter { it.isWin && it.isTaskLike }
    val focusMin = feed.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin }

    val expected = habits.filter { !it.archived && HabitStats.isExpectedDay(it, day) }
    val habitsKept = expected.mapNotNull { h ->
        val c = checkins.firstOrNull { it.habitId == h.id && it.epochDay == day }?.count ?: 0
        if (HabitStats.meetsGoal(h, c)) h to c else null
    }
    val habitsExpected = expected.size
    val missedHabits = expected.filter { h -> habitsKept.none { it.first.id == h.id } }

    val occ = remember(events, day) { CalendarEngine.expand(events, dayStart, dayEnd, zone).sortedBy { it.startMillis } }

    val tracked = remember(timeEntries, day) {
        timeEntries.groupBy { it.activityId }.mapValues { (_, es) ->
            es.sumOf { com.todocompanion.app.domain.TimeTracking.minutesInWindow(it.startMillis, it.endMillis, dayStart, dayEnd, now) }
        }.filterValues { it > 0 }
    }
    val trackedTotal = tracked.values.sum()
    val bookend = dayLogs.firstOrNull { it.epochDay == day }

    // Phase C — self-scored Daily Questions: the user's active questions (from settings) and this day's
    // scores (from the day's log). The 14-day trend reads each recent day's own scores map.
    val questions = remember(settings.dailyQuestionsJson) { DailyQuestions.parseQuestions(settings.dailyQuestionsJson) }
    val todayScores = remember(bookend?.dailyScoresJson) { DailyQuestions.parseScores(bookend?.dailyScoresJson ?: "") }
    val scores14 = remember(dayLogs, day) {
        (13 downTo 0).map { back ->
            val d0 = day - back
            DailyQuestions.parseScores(dayLogs.firstOrNull { it.epochDay == d0 }?.dailyScoresJson ?: "")
        }
    }

    // Phase E — align the day to what the user is working toward. Goals are the app's Unified Goals
    // (settings JSON); "top values" are the highest-ranked rows of the values card-sort (by orderIndex).
    // The day's recorded alignment resolves back to live goal / value objects so names & emoji stay real.
    val goals = remember(settings.goalsJson) { Goals.parse(settings.goalsJson) }
    val topValues = remember(coreValues) { coreValues.sortedBy { it.orderIndex }.take(TOP_VALUES) }
    val alignment = remember(bookend?.alignmentJson) { DayAlignments.parse(bookend?.alignmentJson ?: "") }
    val movedGoals = remember(goals, alignment) { goals.filter { it.id in alignment.movedGoalIds } }
    val honoredValues = remember(coreValues, alignment) { coreValues.filter { it.id in alignment.honoredValueIds }.sortedBy { it.orderIndex } }

    // At-a-glance context: vs your usual, and the review streak.
    val avg7 = remember(tasks, day) {
        val since = dayStart - 7L * 86_400_000L
        tasks.count { it.completed && !it.trashed && it.completedAt != null && it.completedAt!! in since until dayStart } / 7.0
    }
    val reviewedDays = remember(dayLogs) {
        dayLogs.filter {
            it.pmReflection.isNotBlank() || it.dayRating > 0 || it.amIntention.isNotBlank() ||
                it.highlight.isNotBlank() || it.gratitude.isNotBlank() || it.lesson.isNotBlank() || it.tomorrowFocus.isNotBlank()
        }.map { it.epochDay }.toHashSet()
    }
    // Phase F — streak recovery: repaired days (a settings-side overlay) count toward the streak, and a
    // single missed day can be repaired with one of a capped, monthly allowance of "streak repairs".
    val repairedDays = remember(settings.repairedDaysCsv) {
        settings.repairedDaysCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.toHashSet()
    }
    val repairTokens = remember(settings.streakRepairTokens, settings.streakRepairPeriod, todayEd) {
        ReviewCadence.tokensForPeriod(settings.streakRepairTokens, settings.streakRepairPeriod, ReviewCadence.periodKey(todayEd))
    }
    val streakState = remember(reviewedDays, repairedDays, repairTokens, todayEd) {
        ReviewCadence.computeStreak(reviewedDays, repairedDays, todayEd, repairTokens)
    }
    val reviewStreak = streakState.streak

    // Reckon + Ready are anchored to *today* (carrying forward / planning a past day makes no sense).
    val openTasks = remember(tasks, isToday) { if (isToday) FourthWave.shutdownCarryForward(tasks, todayEd, zone) else emptyList() }
    // Wave 1 — every still-open / overdue task as of today (not day-scoped) — the "get clear" set the
    // Weekly Review lets the user carry or let go, reusing the deliberate-rollover mechanism.
    val allOpenTasks = remember(tasks) { FourthWave.shutdownCarryForward(tasks, todayEd, zone) }
    val tmr = day + 1
    val tStart = LocalDate.ofEpochDay(tmr).atStartOfDay(zone).toInstant().toEpochMilli()
    val tEnd = LocalDate.ofEpochDay(tmr + 1).atStartOfDay(zone).toInstant().toEpochMilli()
    val tmrTasks = if (isToday) tasks.filter { !it.completed && !it.trashed && !it.abandoned && it.dueDate != null && it.dueDate!! in tStart until tEnd }.sortedBy { it.dueDate } else emptyList()
    val tmrOcc = if (isToday) remember(events, tmr) { CalendarEngine.expand(events, tStart, tEnd, zone).sortedBy { it.startMillis } } else emptyList()

    val nothing = tasksDone.isEmpty() && habitsKept.isEmpty() && occ.isEmpty() && tracked.isEmpty() && focusMin == 0

    fun fmtHm(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    fun timeLabel(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    fun mood(v: Int) = when (v) { 1 -> "😞"; 2 -> "🙁"; 3 -> "😐"; 4 -> "🙂"; 5 -> "😄"; else -> "" }
    fun outcomeLabel(v: Int) = when (v) { 1 -> "Not yet"; 2 -> "Partly"; 3 -> "Done"; else -> "" }
    val reflectionLine = bookend?.let { it.highlight.ifBlank { it.pmReflection } } ?: ""

    // Wave 2 (feature 8) — local memory resurfacing: one past moment from the same date in a prior
    // year/month, or a recent good moment worth savouring. Computed on-device from the loaded day logs.
    val memory = remember(dayLogs, day) { DayMemories.select(day, dayLogs) }

    // Wave 3 (C) — predictions due to resurface today (the Drucker loop). Parsed from settings, like the
    // Daily Questions / Weekly Reviews stores; only checked-in on today.
    val predictions = remember(settings.predictionsJson) { Predictions.parseAll(settings.predictionsJson) }
    val duePredictions = remember(predictions, todayEd, isToday) {
        if (isToday) Predictions.dueToResurface(predictions, todayEd) else emptyList()
    }
    // Wave 3 (D) — at most one gentle, non-judgmental observation for today, mined over the trailing ~90
    // days from the same engine as the Patterns card. Occasional by design (a strong, undismissed finding
    // rarely stands out), and dismissible (remembered in settings).
    val nudge = remember(dayLogs, questions, habits, checkins, timeEntries, activities, day, isToday, settings.nudgeDismissedCsv) {
        if (!isToday) null else {
            val dismissed = settings.nudgeDismissedCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            ReviewInsights.nudge(day - 89, day, dayLogs, questions, habits, checkins, timeEntries, activities, zone, System.currentTimeMillis(), dismissed)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            expandedHeight = 52.dp,
            title = { Text("Day review") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { showShare = true }) { Icon(Icons.Filled.Share, "Share day") }
                if (!isToday) TextButton(onClick = { day = todayEd }) { Text("Today") }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 2.dp)) {
            // ── Range mode: Day (this screen) · Week · Month (roll-up) ──
            OptionChips(
                options = ReviewRange.ALL, selected = mode, onSelect = { mode = it }, wrap = false,
                label = { it.label }, modifier = Modifier.padding(bottom = 12.dp),
            )
            if (mode != ReviewRange.DAY) {
                RangeRollup(
                    mode = mode, anchor = day, todayEd = todayEd, zone = zone,
                    weekStartSetting = settings.weekStart,
                    dayLogs = dayLogs, questions = questions, habits = habits, checkins = checkins,
                    timeEntries = timeEntries, activities = activities, goals = goals, tasks = tasks,
                    weeklyReviewsJson = settings.weeklyReviewsJson,
                    onStartWeeklyReview = { iso -> weeklyIso = iso; showWeekly = true },
                    onAnchorChange = { day = it },
                    onOpenDay = { d -> day = d; mode = ReviewRange.DAY },
                    onOpenYearReview = { showYear = true },
                )
                Spacer(Modifier.height(24.dp))
                return@Column
            }
            // Date navigator.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day -= 1 }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
                val rel = when (day) { todayEd -> "Today"; todayEd - 1 -> "Yesterday"; else -> "" }
                val label = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", " +
                    date.dayOfMonth + " " + date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { showPicker = true }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (rel.isNotBlank()) Text(rel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { if (day < todayEd) day += 1 }, enabled = day < todayEd) { Icon(Icons.Filled.ChevronRight, "Next day") }
            }
            Spacer(Modifier.height(12.dp))

            // ── At-a-glance: the day's metrics as evenly-spread stat tiles (rating + context live in Reflect) ──
            AppCard {
                if (nothing) {
                    Text("A quiet day — nothing tracked yet. Reflect on it, or check what's still open below.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val tiles = buildList {
                        add(Triple("✓", tasksDone.size.toString(), "done"))
                        if (habitsExpected > 0) add(Triple("🔁", "${habitsKept.size}/$habitsExpected", "habits"))
                        if (focusMin > 0) add(Triple("🎯", fmtHm(focusMin), "focus"))
                        if (trackedTotal > 0) add(Triple("⧗", fmtHm(trackedTotal), "tracked"))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        tiles.forEach { (icon, value, label) -> StatTile(icon, value, label, Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.height(if (nothing) 10.dp else 14.dp))
                // Review-streak: a "don't break the chain" strip of the last 14 days. A repaired day is
                // shown in a distinct tertiary tint (not the same as a truly reviewed day) — honest by design.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (reviewStreak > 0) "🔥 $reviewStreak-day streak" else "Reviewed days",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (reviewStreak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        (13 downTo 0).forEach { back ->
                            val d0 = todayEd - back
                            val reviewed = d0 in reviewedDays
                            val repaired = !reviewed && d0 in repairedDays
                            Box(Modifier.size(if (d0 == day) 13.dp else 11.dp).clip(RoundedCornerShape(3.dp))
                                .background(when {
                                    reviewed -> MaterialTheme.colorScheme.primary
                                    repaired -> MaterialTheme.colorScheme.tertiary.copy(alpha = .55f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }))
                        }
                    }
                }
                // Phase F — a gentle, opt-in recovery when a single missed day just broke the streak. Never
                // auto-consumed; the remaining allowance is shown plainly, and it's capped per month.
                streakState.repairableDay?.let { repairDay ->
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Missed yesterday — keep your streak going?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                            Text("${streakState.tokensAvailable} streak repair${if (streakState.tokensAvailable == 1) "" else "s"} left this month", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = { vm.keepStreak(repairDay) }) { Text("Keep my streak") }
                    }
                }
            }

            // ── Close the day: the guided ritual (Recall → Feel → Reflect → Tomorrow → done) ──
            Spacer(Modifier.height(12.dp))
            val closedToday = day in reviewedDays
            FilledTonalButton(onClick = { showClose = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (closedToday) "🌙  Review the close" else "🌙  Close the day")
            }

            // ── On this day / from your reviews: a gentle, occasional local memory (feature 8) ──
            memory?.let { mem ->
                Spacer(Modifier.height(12.dp))
                AppCard(modifier = Modifier.clickable { day = mem.epochDay }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (mem.kind == DayMemories.Kind.ON_THIS_DAY) "🕰️" else "✨", Modifier.width(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (mem.kind == DayMemories.Kind.ON_THIS_DAY) "On this day" else "Worth remembering",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(mem.whenLabel + (if (mem.rating in 1..5) "  ·  " + "★".repeat(mem.rating) else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(Icons.Filled.ChevronRight, "Open that day", tint = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("“${mem.text}”", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }

            // ── Wave 3 (D) — a single gentle, judgment-free observation. Soft, dismissible, occasional. ──
            nudge?.let { n ->
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Text("🔎", Modifier.width(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("An observation, not a verdict — computed privately on your device.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                        TextButton(onClick = { vm.dismissNudge(n.key) }) { Text("Dismiss") }
                    }
                }
            }

            // ── Wave 3 (C) — Drucker prediction loop: resurface a due prediction, and log a new one. ──
            if (isToday) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                        Text("🔮 Predictions", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showAddPrediction = true }) { Text("Log one") }
                    }
                    if (duePredictions.isEmpty()) {
                        Text("Predict how a change or a finish will make you feel, and set when to check back. When the day comes, you'll compare what you expected with what actually happened — Drucker's feedback analysis.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        duePredictions.forEach { p ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f)).padding(12.dp)) {
                                Text("${Predictions.sinceLabel(p.createdEpochDay, todayEd)} you predicted:",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                Text("“${p.expectation}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                                Text("How did it actually turn out?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                FilledTonalButton(onClick = { resolvePrediction = p }) { Text("Record the outcome") }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            // ── Recap cards ──
            if (wins.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("⭐ Wins")
                    wins.forEach { a ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenTask(a.refId) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("⭐", Modifier.width(24.dp))
                            Text(a.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (tasksDone.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("Completed · ${tasksDone.size}")
                    tasksDone.take(30).forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenTask(t.id) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            DoneTick()
                            Spacer(Modifier.width(10.dp))
                            Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            t.completedAt?.let { Text(timeLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                        }
                    }
                    if (tasksDone.size > 30) Text("+ ${tasksDone.size - 30} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (habitsKept.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("Habits · ${habitsKept.size}/$habitsExpected kept")
                    val tertiary = MaterialTheme.colorScheme.tertiary
                    MetricTileGrid(habitsKept.map { (h, c) ->
                        val target = h.targetPerDay.coerceAtLeast(1)
                        Metric(
                            emoji = h.emoji ?: "🔁",
                            name = h.name,
                            value = if (target > 1) "$c/$target${h.unit?.let { " $it" } ?: ""}" else "Done",
                            frac = (c.toFloat() / target).coerceIn(0f, 1f),
                            color = tertiary,
                        )
                    })
                }
            }
            if (occ.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("Events · ${occ.size}")
                    occ.forEach { o ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (o.event.allDay) "all-day" else timeLabel(o.startMillis), Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text(o.event.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (tracked.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("Time tracked · ${fmtHm(trackedTotal)}")
                    val maxMin = (tracked.values.maxOrNull() ?: 1).coerceAtLeast(1)
                    val fallback = MaterialTheme.colorScheme.primary
                    MetricTileGrid(tracked.entries.sortedByDescending { it.value }.map { (actId, min) ->
                        val a = activities.firstOrNull { it.id == actId }
                        val col = a?.colorArgb?.let { Color(it) } ?: fallback
                        Metric(emoji = a?.emoji, name = a?.name ?: "—", value = fmtHm(min), frac = min / maxMin.toFloat(), color = col)
                    })
                }
            }

            // ── Reckon: what's still open today — reviewed one by one (deliberate rollover, not auto-carry) ──
            if (isToday && (openTasks.isNotEmpty() || missedHabits.isNotEmpty())) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("Didn't get to")
                    if (openTasks.isNotEmpty()) {
                        Text("Decide each one — carry it to tomorrow, or let it go.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        openTasks.take(12).forEach { t ->
                            val letGo = t.id in rolloverLetGo
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(t.title, Modifier.fillMaxWidth().clickable { onOpenTask(t.id) },
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = !letGo, onClick = { rolloverLetGo = rolloverLetGo - t.id }, label = { Text("Carry to tomorrow") })
                                    FilterChip(selected = letGo, onClick = { rolloverLetGo = rolloverLetGo + t.id }, label = { Text("Let go") })
                                }
                            }
                        }
                        if (openTasks.size > 12) Text("+ ${openTasks.size - 12} more (kept)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
                    }
                    missedHabits.take(8).forEach { h ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(h.emoji ?: "🔁", Modifier.width(24.dp))
                            Text(h.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("missed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (openTasks.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val ids = openTasks.map { it.id }
                        val letGoIds = ids.filter { it in rolloverLetGo }
                        val carryIds = ids.filter { it !in rolloverLetGo }
                        FilledTonalButton(onClick = { vm.reviewRollover(carryIds, letGoIds) }) {
                            Text(
                                when {
                                    letGoIds.isEmpty() -> "Carry ${carryIds.size} to tomorrow & close"
                                    carryIds.isEmpty() -> "Let go of ${letGoIds.size} & close the day"
                                    else -> "Carry ${carryIds.size} · let go ${letGoIds.size} & close"
                                },
                            )
                        }
                    }
                }
            }

            // ── Reflect ──
            Spacer(Modifier.height(12.dp))
            AppCard {
                SectionTitle("Reflect")
                // Feature 6 — bind the reflection to that day's numbers: a slim at-a-glance caption so the
                // "why" is always read next to the "what", right inside this card.
                if (!nothing) {
                    val glance = buildString {
                        append("✓ ${tasksDone.size}")
                        if (habitsExpected > 0) append("  ·  🔁 ${habitsKept.size}/$habitsExpected")
                        if (focusMin > 0) append("  ·  🎯 ${fmtHm(focusMin)}")
                        if (trackedTotal > 0) append("  ·  ⧗ ${fmtHm(trackedTotal)}")
                    }
                    Text(glance, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 6.dp))
                }
                // Mood + rating are captured in the close-the-day flow and the "Reflect on today" editor;
                // shown here read-only (no duplicate pickers) — use Reflect / Edit below to change them.
                val moodV = bookend?.pmMood ?: 0
                val ratingV = bookend?.dayRating ?: 0
                val emoV = bookend?.emotionLabel ?: ""
                if (moodV > 0 || ratingV > 0 || emoV.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        if (moodV > 0) {
                            Text(mood(moodV), style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(10.dp))
                        }
                        if (ratingV > 0) Text("★".repeat(ratingV) + "☆".repeat(5 - ratingV), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        if (emoV.isNotBlank()) {
                            if (moodV > 0 || ratingV > 0) Spacer(Modifier.width(10.dp))
                            EmotionChip(emoV)
                        }
                    }
                }
                // Context vs your usual (moved here from the summary card).
                val vs = when {
                    avg7 < 0.5 -> null
                    tasksDone.size > avg7 * 1.15 -> "▲ above your usual ${avg7.roundToInt()}/day"
                    tasksDone.size < avg7 * 0.85 -> "▼ below your usual ${avg7.roundToInt()}/day"
                    else -> "about your usual ${avg7.roundToInt()}/day"
                }
                if (vs != null && !nothing) Text(vs, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                val hasProse = bookend != null && (bookend.pmReflection.isNotBlank() || bookend.highlight.isNotBlank() || bookend.gratitude.isNotBlank() || bookend.lesson.isNotBlank() ||
                    bookend.good1.isNotBlank() || bookend.good2.isNotBlank() || bookend.good3.isNotBlank() || bookend.promptAnswer.isNotBlank())
                if (bookend?.amIntention?.isNotBlank() == true) {
                    val oc = outcomeLabel(bookend.intentionOutcome)
                    Row(Modifier.padding(bottom = 4.dp)) {
                        Text("🌅 ${mood(bookend.amMood)}", Modifier.width(48.dp))
                        Text(bookend.amIntention + if (oc.isNotBlank()) " — $oc" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (hasProse) {
                    bookend!!.let {
                        if (it.pmReflection.isNotBlank()) Row(Modifier.padding(vertical = 2.dp)) { Text("🌙 ${mood(it.pmMood)}", Modifier.width(48.dp)); Text(it.pmReflection, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
                        if (it.highlight.isNotBlank()) ReflectLine("✨ Highlight", it.highlight)
                        if (it.gratitude.isNotBlank()) ReflectLine("🙏 Grateful for", it.gratitude)
                        if (it.lesson.isNotBlank()) ReflectLine("💡 Lesson", it.lesson)
                        val goods = listOf(it.good1, it.good2, it.good3).filter { g -> g.isNotBlank() }
                        if (goods.isNotEmpty()) {
                            Text("Three good things", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            goods.forEach { g ->
                                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MiniCheck(); Spacer(Modifier.width(8.dp))
                                    Text(g, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        if (it.promptAnswer.isNotBlank()) {
                            // The label adapts to the kind of day (savor / reframe / neutral), from the day's own rating + mood.
                            val ap = AdaptivePrompts.promptFor(day, it.dayRating, it.pmMood)
                            val g = AdaptivePrompts.glyph(ap.kind)
                            Text((if (g.isNotBlank()) "$g  " else "") + ap.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                            Text(it.promptAnswer, Modifier.padding(vertical = 1.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (it.energy > 0) Text("Energy: ${"◆".repeat(it.energy)}${"◇".repeat(5 - it.energy)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { showReflect = true }) { Text("Edit reflection") }
                } else {
                    Text("Close the day in a few words — how it went, a highlight, something you're grateful for, one lesson.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { showReflect = true }) { Text("Reflect on today") }
                }
                // Wave 3 (E) — an optional, rule-based reflection companion: a short chain of context-aware
                // follow-ups picked on-device from the day's own mood/rating. No LLM, no model, no service.
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showCompanion = true }) { Text("🫧  Go deeper") }
                    Spacer(Modifier.width(10.dp))
                    Text("A private guide — a few questions, all on your device. No AI service.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Phase E — the day's alignment, rendered back: goals advanced + values honored.
                if (movedGoals.isNotEmpty() || honoredValues.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    if (movedGoals.isNotEmpty()) {
                        Text("🎯 Moved a goal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        StaticChipRow(movedGoals.map { "${it.emoji} ${it.name}" })
                    }
                    if (honoredValues.isNotEmpty()) {
                        if (movedGoals.isNotEmpty()) Spacer(Modifier.height(6.dp))
                        Text("🧭 Values honored", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        StaticChipRow(honoredValues.map { v -> (v.emoji?.let { "$it " } ?: "") + v.name })
                    }
                }
            }

            // ── Wave 3 (A) — a sealed letter to your future self. Reuses R32's sealed store + tamper-evident
            // hash (via the VM); locked entries show only their date + a lock, never the body, until due. ──
            Spacer(Modifier.height(12.dp))
            SealedLettersReviewCard(
                notes = sealedNotes, today = date,
                onWrite = { showWriteLetter = true },
                onOpen = { openLetter = it },
            )

            // ── Daily questions: self-scored effort on what you value (Marshall Goldsmith) ──
            Spacer(Modifier.height(12.dp))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    Text("Daily questions", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (questions.isNotEmpty()) TextButton(onClick = { showQuestions = true }) { Text("Edit questions") }
                }
                if (questions.isEmpty()) {
                    Text("Score a few “Did I do my best to…” questions each night. Scoring your effort — not the outcome — keeps the win in your hands.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { showQuestions = true }) { Text("Set up") }
                } else {
                    questions.forEachIndexed { i, q ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        Text(q.text, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ScorePips(todayScores[q.id] ?: 0) { s -> vm.saveDailyScore(day, q.id, s) }
                            Spacer(Modifier.width(10.dp))
                            ScoreSparkline(scores14.map { it[q.id] }, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Last 14 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            // ── Ready: tomorrow ──
            if (isToday) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("Ready for tomorrow")
                    val focusText = bookend?.tomorrowFocus ?: ""
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("🎯", Modifier.width(28.dp))
                        Text(if (focusText.isBlank()) "Set the one thing that matters tomorrow" else focusText,
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = if (focusText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = { showReflect = true }) { Text(if (focusText.isBlank()) "Set" else "Edit") }
                    }
                    // Wave 2 — the tomorrow WOOP if-then, rendered beneath the focus (feature 7). Secondary.
                    val obstacleText = bookend?.tomorrowObstacle ?: ""
                    val planText = bookend?.tomorrowPlan ?: ""
                    if (obstacleText.isNotBlank()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text("🧱", Modifier.width(28.dp))
                            Text(obstacleText, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (planText.isNotBlank()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text("🧭", Modifier.width(28.dp))
                            Text(planText, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (tmrTasks.isNotEmpty() || tmrOcc.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("On the calendar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        tmrOcc.take(4).forEach { o ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(if (o.event.allDay) "all-day" else timeLabel(o.startMillis), Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                Text(o.event.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        tmrTasks.take(6).forEach { t ->
                            Row(Modifier.fillMaxWidth().clickable { onOpenTask(t.id) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) { OpenTick() }
                                Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text("Nothing scheduled tomorrow — a clear slate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPicker) DateOnlyPickerDialog(
        initial = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        allowFuture = false,
        onDismiss = { showPicker = false },
        onConfirm = { ms -> day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay(); showPicker = false },
    )

    if (showReflect) ReflectDialog(
        day = day, isToday = isToday, log = bookend,
        onDismiss = { showReflect = false },
        onSave = { rating, energy, reflection, mood, highlight, gratitude, lesson, tomorrow ->
            vm.saveEveningReflection(day, reflection, mood)
            vm.saveDayReflect(day, rating, energy, highlight, gratitude, lesson)
            if (isToday) vm.saveTomorrowFocus(day, tomorrow)
            showReflect = false
        },
        onSaveExtras = { good1, good2, good3, intentionOutcome, promptAnswer ->
            vm.saveDayReflectExtras(day, good1, good2, good3, intentionOutcome, promptAnswer)
        },
        onSaveEmotion = { label -> vm.saveEmotionLabel(day, label) },
        onSaveTomorrowPlan = { obstacle, plan -> vm.saveTomorrowPlan(day, obstacle, plan) },
    )

    if (showQuestions) DailyQuestionsDialog(
        initial = questions,
        onDismiss = { showQuestions = false },
        onSave = { list -> vm.saveDailyQuestions(list); showQuestions = false },
    )

    if (showClose) CloseDayFlow(
        day = day, isToday = isToday, log = bookend,
        questions = questions,
        initialScores = todayScores,
        onScore = { qId, s -> vm.saveDailyScore(day, qId, s) },
        goals = goals,
        topValues = topValues,
        initialAlignment = alignment,
        onSaveAlignment = { movedGoalIds, honoredValueIds -> vm.saveDayAlignment(day, movedGoalIds, honoredValueIds) },
        summary = if (nothing) "A quiet day — nothing tracked." else buildString {
            append("✓ ${tasksDone.size} done")
            if (habitsExpected > 0) append(" · 🔁 ${habitsKept.size}/$habitsExpected")
            if (focusMin > 0) append(" · 🎯 ${fmtHm(focusMin)}")
            if (trackedTotal > 0) append(" · ⧗ ${fmtHm(trackedTotal)}")
        },
        wins = wins.map { it.title },
        streak = reviewStreak + (if (day !in reviewedDays) 1 else 0),
        onDismiss = { showClose = false },
        onSave = { rating, energy, reflection, mood, highlight, gratitude, lesson, tomorrow ->
            vm.saveEveningReflection(day, reflection, mood)
            vm.saveDayReflect(day, rating, energy, highlight, gratitude, lesson)
            if (isToday) vm.saveTomorrowFocus(day, tomorrow)
        },
        onSaveExtras = { good1, good2, good3, intentionOutcome, promptAnswer ->
            vm.saveDayReflectExtras(day, good1, good2, good3, intentionOutcome, promptAnswer)
        },
        onSaveEmotion = { label -> vm.saveEmotionLabel(day, label) },
        onSaveTomorrowPlan = { obstacle, plan -> vm.saveTomorrowPlan(day, obstacle, plan) },
    )

    // Wave 1 — the guided Weekly Review, opened from the Week roll-up. Computes the week window from the
    // current anchor + week-start setting, rolls it up read-only, and persists the reflection by ISO week.
    if (showWeekly) {
        val ws = weekStartOf(date, settings.weekStart)
        val wStart = ws.toEpochDay()
        val wEnd = minOf(ws.plusDays(6).toEpochDay(), todayEd)
        val weekRollup = remember(wStart, wEnd, dayLogs, questions, habits, checkins, timeEntries, activities, goals, tasks) {
            ReviewRollup.compute(wStart, wEnd, dayLogs, questions, habits, checkins, timeEntries, activities, zone, System.currentTimeMillis(), goals, tasks)
        }
        val existing = remember(settings.weeklyReviewsJson, weeklyIso) { WeeklyReviews.forWeek(settings.weeklyReviewsJson, weeklyIso) }
        WeeklyReviewFlow(
            isoWeek = weeklyIso,
            weekLabel = weekLabel(ws, ws.plusDays(6)),
            rollup = weekRollup,
            openTasks = allOpenTasks,
            existing = existing,
            onSaveRollover = { carryIds, letGoIds -> vm.reviewRollover(carryIds, letGoIds) },
            onSave = { reflection, nextFocus, areas -> vm.saveWeeklyReview(weeklyIso, reflection, nextFocus, areas) },
            onDismiss = { showWeekly = false },
        )
    }

    if (showShare) ShareDialog(
        onDismiss = { showShare = false },
        onShare = { includeTitles, includeReflection, asImage ->
            val data = DayCard.Data(
                dateLabel = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", " + date.dayOfMonth + " " + date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                rating = bookend?.dayRating ?: 0,
                done = tasksDone.size, habitsKept = habitsKept.size, habitsExpected = habitsExpected,
                focusMin = focusMin, trackedMin = trackedTotal,
                wins = if (includeTitles) wins.map { it.title } else emptyList(),
                reflection = if (includeReflection) reflectionLine else "",
                moodEmoji = mood(bookend?.pmMood ?: 0),
                accentArgb = settings.accentArgb.takeIf { it != 0L },
            )
            if (asImage) {
                val bmp = DayCard.render(data)
                val res = ProgressCard.saveAndShareUri(ctx, bmp, "kairo-day-$day.png")
                res.shareUri?.let { ProgressCard.share(ctx, it) }
            } else {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, DayCard.text(data))
                }
                runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share my day").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            showShare = false
        },
    )

    // ── Wave 3 (A) — write & seal a letter to future me. Reuses R32's VM path (sealed store + tamper-
    // evident hash + SQLCipher-at-rest); nothing new is encrypted here. ──
    if (showWriteLetter) WriteSealedLetterDialog(
        today = date,
        onDismiss = { showWriteLetter = false },
        onSeal = { title, body, revealDay -> vm.sealLetter(title, body, revealDay); showWriteLetter = false },
    )
    openLetter?.let { n ->
        SealedLetterRevealDialog(
            note = n, ready = todayEd >= n.revealEpochDay, intact = vm.letterIntact(n),
            onDismiss = { openLetter = null },
            onAck = { vm.acknowledgeLetter(n); openLetter = null },
            onDelete = { vm.deleteLetter(n.id); openLetter = null },
        )
    }

    // ── Wave 3 (C) — log a new prediction, and record the outcome of one that resurfaced. ──
    if (showAddPrediction) AddPredictionDialog(
        today = date,
        onDismiss = { showAddPrediction = false },
        onAdd = { text, resurfaceDay -> vm.addPrediction(text, resurfaceDay); showAddPrediction = false },
    )
    resolvePrediction?.let { p ->
        ResolvePredictionDialog(
            prediction = p, today = todayEd,
            onDismiss = { resolvePrediction = null },
            onResolve = { note, matched -> vm.resolvePrediction(p.id, note, matched); resolvePrediction = null },
            onForget = { vm.removePrediction(p.id); resolvePrediction = null },
        )
    }

    // ── Wave 3 (E) — the rule-based reflection companion (no LLM); saves into the day's reflection field. ──
    if (showCompanion) ReflectionCompanionDialog(
        rating = bookend?.dayRating ?: 0,
        mood = bookend?.pmMood ?: 0,
        emotionLabel = bookend?.emotionLabel ?: "",
        existingReflection = bookend?.pmReflection ?: "",
        onDismiss = { showCompanion = false },
        onSave = { merged -> vm.saveEveningReflection(day, merged, bookend?.pmMood ?: 0); showCompanion = false },
    )

    // ── Wave 3 (B) — the fully-local "Year, reviewed" recap, opened from the Month roll-up. ──
    if (showYear) YearReviewedScreen(
        anchorDay = day, todayEd = todayEd, zone = zone,
        dayLogs = dayLogs, habits = habits, checkins = checkins, timeEntries = timeEntries, activities = activities,
        accentArgb = settings.accentArgb.takeIf { it != 0L },
        onBack = { showYear = false },
    )
}

@Composable
private fun ReflectLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
}

/** A stat tile for the at-a-glance summary: a tonal rounded box with icon, big value and label. */
@Composable
private fun StatTile(icon: String, value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A modern filled-circle check (completed), à la TickTick/Things. */
@Composable
private fun DoneTick(modifier: Modifier = Modifier) {
    Box(modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** An outlined circle for an open / not-done item. */
@Composable
private fun OpenTick(modifier: Modifier = Modifier) {
    Icon(Icons.Outlined.Circle, null, modifier.size(22.dp), tint = MaterialTheme.colorScheme.outline)
}

/** A "dynamic card" row — leading marker, name, trailing value and a proportional meter bar, matching
 *  the activity time-tracking breakdown. */
@Composable
private fun MeterRow(leading: @Composable () -> Unit, name: String, trailing: String, frac: Float, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) { leading() }
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0.03f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

/** A metric shown as a self-contained tile in a grid (habits, tracked activities): leading glyph/dot,
 *  name, a big value and a slim progress meter — the same tonal-box language as the at-a-glance tiles. */
private data class Metric(val emoji: String?, val name: String, val value: String, val frac: Float, val color: Color)

@Composable
private fun MetricTile(m: Metric, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.emoji != null) Text(m.emoji, style = MaterialTheme.typography.titleMedium)
            else Box(Modifier.size(12.dp).clip(CircleShape).background(m.color))
            Spacer(Modifier.width(7.dp))
            Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(m.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(m.frac.coerceIn(0.04f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(m.color))
        }
    }
}

/** Lay a list of [Metric]s out as a two-column grid of tiles, matching the at-a-glance box row. */
@Composable
private fun MetricTileGrid(metrics: List<Metric>) {
    metrics.chunked(2).forEachIndexed { i, row ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { m -> MetricTile(m, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** A modern filled check in a tinted circle — the app's single "done" glyph, used for read-back ticks
 *  (three good things, etc.) so no raw "✓" characters remain. Smaller sibling of [DoneTick]. */
@Composable
private fun MiniCheck(modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** A per-day mood strip (1–5 → 😞…😄), one dot-height bar per day, for the roll-up mood trend. */
@Composable
private fun MoodStrip(moods: List<Int?>, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.tertiary
    val off = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.height(26.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        moods.forEach { s ->
            val frac = if (s != null) (s.coerceIn(1, 5) / 5f) else 0f
            Box(Modifier.weight(1f).fillMaxHeight(frac.coerceAtLeast(0.14f)).clip(RoundedCornerShape(2.dp))
                .background(if (s != null) on else off.copy(alpha = .5f)))
        }
    }
}

/** Phase C — a tappable 1–5 effort selector for a Daily Question, filled up to the chosen score.
 *  Mirrors the energy ◆ row's diamond idiom; a score of 0 means "not scored yet". */
@Composable
private fun ScorePips(score: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { i ->
            val filled = score >= i
            Text(
                if (filled) "◆" else "◇",
                style = MaterialTheme.typography.titleLarge,
                color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.clip(CircleShape).clickable { onPick(i) }.padding(horizontal = 3.dp, vertical = 2.dp),
            )
        }
    }
}

/** Phase C — a thin trend of recent daily-question scores: one bar per day, height-scaled to score/5,
 *  muted where no score was logged. Reuses the meter-bar drawing idiom. */
@Composable
private fun ScoreSparkline(scores: List<Int?>, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.height(26.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        scores.forEach { s ->
            val frac = if (s != null) (s.coerceIn(1, 5) / 5f) else 0f
            Box(
                Modifier.weight(1f).fillMaxHeight(frac.coerceAtLeast(0.14f)).clip(RoundedCornerShape(2.dp))
                    .background(if (s != null) on else off.copy(alpha = .5f)),
            )
        }
    }
}

/** Phase E — a wrapping multi-select chip row (goals advanced / values honored), tapped in the close
 *  flow's align step. Mirrors the app's single-select OptionChips idiom with Material3 FilterChips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectableChips(items: List<Pair<String, String>>, selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (id, label) ->
            FilterChip(selected = id in selected, onClick = { onToggle(id) }, label = { Text(label, maxLines = 1) })
        }
    }
}

/** Phase E — a read-only wrapping row of tonal pills, used to render a day's chosen goals / honored
 *  values back in the reflect card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaticChipRow(labels: List<String>) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { l ->
            Text(
                l, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** Wave 1 — the precise-emotion-word picker: the ~24 curated words laid out as a compact grid grouped
 *  into the four energy×pleasantness quadrants. Single-select and optional — tapping the chosen word
 *  again clears it. Mirrors the app's FilterChip idiom (SelectableChips) so it feels native. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmotionPicker(selected: String, onSelect: (String) -> Unit) {
    EmotionWords.QUADRANTS.forEach { (q, words) ->
        Text(q.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            words.forEach { w ->
                val on = selected.equals(w, ignoreCase = true)
                FilterChip(selected = on, onClick = { onSelect(if (on) "" else w) }, label = { Text(w, maxLines = 1) })
            }
        }
    }
}

/** Wave 1 — a small tonal pill naming the day's precise emotion, rendered beside the mood face in the
 *  Reflect card's read-back. */
@Composable
private fun EmotionChip(word: String) {
    Text(
        word, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Phase E — how many of the ranked values to surface as "top values" in the close flow's align step. */
private const val TOP_VALUES = 5

/** Phase A — the guided "Close the day" ritual: Recall → Feel → Reflect → Tomorrow → closed. Express hides
 *  the extra prose fields for a ~60-second close; Full keeps them. Reuses the existing DayLog fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloseDayFlow(
    day: Long,
    isToday: Boolean,
    summary: String,
    wins: List<String>,
    streak: Int,
    log: com.todocompanion.app.data.entity.DayLogEntity?,
    questions: List<DailyQuestion>,
    initialScores: Map<String, Int>,
    onScore: (questionId: String, score: Int) -> Unit,
    goals: List<Goal>,
    topValues: List<CoreValueEntity>,
    initialAlignment: DayAlignment,
    onSaveAlignment: (movedGoalIds: List<String>, honoredValueIds: List<String>) -> Unit,
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
    // Phase B — reflection-depth state.
    var good1 by remember { mutableStateOf(log?.good1 ?: "") }
    var good2 by remember { mutableStateOf(log?.good2 ?: "") }
    var good3 by remember { mutableStateOf(log?.good3 ?: "") }
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 22.dp)) {
                // Progress bar — one segment per step (the terminal "done" step isn't counted).
                val totalSteps = (steps.size - 1).coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until totalSteps).forEach { i ->
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (stepId == "done" || i <= idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                    }
                }
                Spacer(Modifier.height(24.dp))
                // Hero header — a large glyph + title + one-line intent for the step.
                Text(when (stepId) { "recall" -> "🗓️"; "feel" -> "💗"; "questions" -> "🎯"; "reflect" -> "🌙"; "align" -> "🧭"; "tomorrow" -> "🌅"; else -> "✅" }, style = MaterialTheme.typography.displaySmall)
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
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    when (stepId) {
                    "recall" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                            FilterChip(selected = !full, onClick = { full = false }, label = { Text("Express · 60s") })
                            FilterChip(selected = full, onClick = { full = true }, label = { Text("Full") })
                        }
                        Text(summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (wins.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            wins.take(3).forEach { Text("⭐  $it", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                            (1..5).forEach { i -> Text(if (rating >= i) "★" else "☆", style = MaterialTheme.typography.headlineSmall,
                                color = if (rating >= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.clip(CircleShape).clickable { rating = if (rating == i) 0 else i }.padding(horizontal = 3.dp)) }
                        }
                        Text("Energy", style = MaterialTheme.typography.labelMedium, color = muted)
                        Row(Modifier.padding(top = 2.dp, bottom = 10.dp)) {
                            (1..5).forEach { i -> Text(if (energy >= i) "◆" else "◇", style = MaterialTheme.typography.titleLarge,
                                color = if (energy >= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.clip(CircleShape).clickable { energy = if (energy == i) 0 else i }.padding(horizontal = 3.dp)) }
                        }
                        Text("Mood", style = MaterialTheme.typography.labelMedium, color = muted)
                        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1 to "😞", 2 to "🙁", 3 to "😐", 4 to "🙂", 5 to "😄").forEach { (v, e) ->
                                Text(e, style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.clip(CircleShape).clickable { mood = if (mood == v) 0 else v }
                                        .background(if (mood == v) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(4.dp))
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
                            Spacer(Modifier.height(6.dp))
                            AppTextField(gratitude, { gratitude = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🙏 One thing you're grateful for") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            AppTextField(lesson, { lesson = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("💡 One lesson / what you'd change") }, singleLine = true)
                            Spacer(Modifier.height(12.dp))
                            Text("Three good things", style = MaterialTheme.typography.labelMedium, color = muted)
                            Spacer(Modifier.height(4.dp))
                            AppTextField(good1, { good1 = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("✓ One good thing") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            AppTextField(good2, { good2 = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("✓ Another") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            AppTextField(good3, { good3 = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("✓ One more") }, singleLine = true)
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
                            Text("🔥 Reviewed $streak day${if (streak == 1) "" else "s"} in a row. Rest well.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
                                onSaveExtras(if (full) good1 else "", if (full) good2 else "", if (full) good3 else "", intentionOutcome, if (full) promptAnswer else "")
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
}

/** Wave 1 — a distinct, guided Weekly Review, modeled on the CloseDayFlow shell. Four calm steps:
 *  Get Clear (carry / let go of what's still open, reusing the deliberate-rollover mechanism), Get
 *  Current (the week's roll-up highlights, read-only), Get Creative (what to try / change + next week's
 *  focus), and Sharpen the saw (a light check across life areas). Persists only the reflection, focus and
 *  areas via a settings JSON keyed by ISO week — no new Room table. Reachable from the Week roll-up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeeklyReviewFlow(
    isoWeek: String,
    weekLabel: String,
    rollup: ReviewRollup.Rollup,
    openTasks: List<TaskEntity>,
    existing: WeeklyReview?,
    onSaveRollover: (carryIds: List<String>, letGoIds: List<String>) -> Unit,
    onSave: (reflection: String, nextFocus: String, areas: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var reflection by remember { mutableStateOf(existing?.reflection ?: "") }
    var nextFocus by remember { mutableStateOf(existing?.nextFocus ?: "") }
    var areas by remember { mutableStateOf(existing?.areas?.toSet() ?: emptySet()) }
    var letGo by remember { mutableStateOf(setOf<String>()) }
    var rolledOver by remember { mutableStateOf(false) }

    val steps = remember(openTasks.isEmpty()) {
        buildList {
            if (openTasks.isNotEmpty()) add("clear")
            add("current"); add("creative"); add("roles"); add("done")
        }
    }
    var idx by remember { mutableIntStateOf(0) }
    val stepId = steps[idx]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 22.dp)) {
                val totalSteps = (steps.size - 1).coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until totalSteps).forEach { i ->
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (stepId == "done" || i <= idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(when (stepId) { "clear" -> "🧹"; "current" -> "📊"; "creative" -> "🌱"; "roles" -> "⚖️"; else -> "✅" }, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(when (stepId) {
                    "clear" -> "Get clear"; "current" -> "Get current"; "creative" -> "Get creative"; "roles" -> "Sharpen the saw"; else -> "Week reviewed"
                }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(weekLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                val sub = when (stepId) {
                    "clear" -> "Tidy up what's still open."; "current" -> "How your week actually went."
                    "creative" -> "What to try or change next week."; "roles" -> "A light look across your life areas."; else -> ""
                }
                if (sub.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(sub, style = MaterialTheme.typography.bodyMedium, color = muted) }
                Spacer(Modifier.height(22.dp))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    when (stepId) {
                    "clear" -> {
                        Text("Carry what still matters; let go of the rest. Your choices apply when you continue.",
                            style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                        openTasks.take(20).forEach { t ->
                            val lg = t.id in letGo
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = !lg, onClick = { letGo = letGo - t.id }, label = { Text("Carry") })
                                    FilterChip(selected = lg, onClick = { letGo = letGo + t.id }, label = { Text("Let go") })
                                }
                            }
                        }
                        if (openTasks.size > 20) Text("+ ${openTasks.size - 20} more (kept)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
                        val missed = rollup.habitConsistency.filter { it.pct < 100 }
                        if (missed.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Habits below target this week", style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(bottom = 2.dp))
                            missed.take(6).forEach { h ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(h.emoji ?: "🔁", Modifier.width(24.dp))
                                    Text(h.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${h.pct}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                    "current" -> {
                        if (rollup.ratedDays > 0) {
                            val r = rollup.avgRating.roundToInt().coerceIn(1, 5)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                Text("★".repeat(r) + "☆".repeat(5 - r), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("${oneDp(rollup.avgRating)} avg rating · ${rollup.ratedDays} rated", style = MaterialTheme.typography.labelMedium, color = muted)
                            }
                        }
                        if (rollup.moodCount > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                Text(moodFace(rollup.avgMood.roundToInt()), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Text("mood ${oneDp(rollup.avgMood)} avg · ${rollup.moodCount} day${if (rollup.moodCount == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = muted)
                            }
                        }
                        Text("${rollup.reviewedDays} of ${rollup.periodDays} days closed", style = MaterialTheme.typography.labelMedium, color = muted)
                        if (rollup.wins.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("⭐ Top wins", style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(bottom = 2.dp))
                            rollup.wins.take(5).forEach { w ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("⭐", Modifier.width(24.dp))
                                    Text(w.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (w.count > 1) Text("×${w.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        if (rollup.habitConsistency.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Habit consistency", style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(bottom = 2.dp))
                            rollup.habitConsistency.take(6).forEach { h ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(h.emoji ?: "🔁", Modifier.width(24.dp))
                                    Text(h.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${h.pct}% · ${h.kept}/${h.expected}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        if (rollup.ratedDays == 0 && rollup.moodCount == 0 && rollup.wins.isEmpty() && rollup.habitConsistency.isEmpty()) {
                            Text("A quiet week — nothing rolled up yet. Close a few days and highlights gather here.", style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                    }
                    "creative" -> {
                        Text("Looking at the week, what do you want to try or change next week?", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                        AppTextField(reflection, { reflection = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("What to try or change") }, minLines = 3)
                        Spacer(Modifier.height(12.dp))
                        Text("Next week's focus", style = MaterialTheme.typography.labelMedium, color = muted)
                        Spacer(Modifier.height(4.dp))
                        AppTextField(nextFocus, { nextFocus = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🎯 The one focus for next week") }, singleLine = true)
                    }
                    "roles" -> {
                        Text("Which areas got your attention this week? A light check — all optional.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 10.dp))
                        SelectableChips(WeeklyReviews.AREAS.map { it to it }, areas) { a -> areas = if (a in areas) areas - a else areas + a }
                    }
                    else -> {
                        Spacer(Modifier.height(24.dp))
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            }
                            Spacer(Modifier.height(18.dp))
                            Text("Week reviewed.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("$weekLabel — closed with intention.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                }
                Spacer(Modifier.height(12.dp))
                if (stepId == "done") {
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) { Text("Done") }
                } else {
                    val nextIsDone = steps[idx + 1] == "done"
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { if (idx > 0) idx-- else onDismiss() }) { Text(if (idx > 0) "Back" else "Cancel") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            if (stepId == "clear" && !rolledOver) {
                                val ids = openTasks.map { it.id }
                                onSaveRollover(ids.filter { it !in letGo }, ids.filter { it in letGo })
                                rolledOver = true
                            }
                            if (nextIsDone) onSave(reflection, nextFocus, areas.toList())
                            idx++
                        }) { Text(if (nextIsDone) "Finish review" else "Next") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReflectDialog(
    day: Long, isToday: Boolean, log: com.todocompanion.app.data.entity.DayLogEntity?,
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
                AppTextField(good1, { good1 = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("✓ One good thing") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                AppTextField(good2, { good2 = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("✓ Another") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                AppTextField(good3, { good3 = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("✓ One more") }, singleLine = true)
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
 *  yet, the editor is pre-filled with the suggested starters so they can keep, tweak, or clear them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyQuestionsDialog(
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
                }
            }
        },
    )
}

@Composable
private fun ShareDialog(onDismiss: () -> Unit, onShare: (includeTitles: Boolean, includeReflection: Boolean, asImage: Boolean) -> Unit) {
    var includeTitles by remember { mutableStateOf(true) }
    var includeReflection by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onShare(includeTitles, includeReflection, true) }) { Text("Share image") } },
        dismissButton = { TextButton(onClick = { onShare(includeTitles, includeReflection, false) }) { Text("Share text") } },
        title = { Text("Share your day") },
        text = {
            Column {
                Text("Choose what to include. Everything stays on your device until you pick where to send it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { includeTitles = !includeTitles }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeTitles, { includeTitles = it }); Text("Include win titles")
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { includeReflection = !includeReflection }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeReflection, { includeReflection = it }); Text("Include a line of reflection")
                }
            }
        },
    )
}

/** Phase D — the Day Review's range mode. Day is the full close-the-day screen; Week/Month roll up. */
private enum class ReviewRange(val label: String) {
    DAY("Day"), WEEK("Week"), MONTH("Month");
    companion object { val ALL = listOf(DAY, WEEK, MONTH) }
}

/**
 * Phase D — the weekly / monthly reflection roll-up. A period navigator (‹ label ›, honoring the
 * week-start setting) over read-only aggregate cards computed by [ReviewRollup] from the same day logs,
 * habits, check-ins and tracked time the Day view already holds. Each card renders only when it has
 * data, and reuses the day-review idioms (AppCard, SectionTitle, MeterRow, ScoreSparkline). The current
 * week/month is capped at today, matching the Recap screen's "this week / this month" semantics.
 */
@Composable
private fun RangeRollup(
    mode: ReviewRange,
    anchor: Long,
    todayEd: Long,
    zone: ZoneId,
    weekStartSetting: Int,
    dayLogs: List<com.todocompanion.app.data.entity.DayLogEntity>,
    questions: List<DailyQuestion>,
    habits: List<com.todocompanion.app.data.entity.HabitEntity>,
    checkins: List<com.todocompanion.app.data.entity.HabitCheckinEntity>,
    timeEntries: List<com.todocompanion.app.data.entity.TimeEntryEntity>,
    activities: List<com.todocompanion.app.data.entity.TimeActivityEntity>,
    goals: List<Goal>,
    tasks: List<TaskEntity>,
    weeklyReviewsJson: String,
    onStartWeeklyReview: (isoWeek: String) -> Unit,
    onAnchorChange: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenYearReview: () -> Unit,
) {
    val date = LocalDate.ofEpochDay(anchor)
    val today = LocalDate.ofEpochDay(todayEd)
    val ctx = LocalContext.current
    val accentArgb = MaterialTheme.colorScheme.primary.toArgb().toLong()

    // Resolve the window, label, relative caption and the prev/next anchors for this range.
    val start: Long
    val end: Long
    val label: String
    val relative: String
    val prevAnchor: Long
    val nextAnchor: Long
    val canNext: Boolean
    if (mode == ReviewRange.WEEK) {
        val ws = weekStartOf(date, weekStartSetting)
        val we = ws.plusDays(6)
        val curWs = weekStartOf(today, weekStartSetting)
        start = ws.toEpochDay()
        end = minOf(we.toEpochDay(), todayEd)
        label = weekLabel(ws, we)
        relative = when (ws) { curWs -> "This week"; curWs.minusWeeks(1) -> "Last week"; else -> "" }
        prevAnchor = anchor - 7
        nextAnchor = anchor + 7
        canNext = ws < curWs
    } else {
        val first = date.withDayOfMonth(1)
        val last = date.withDayOfMonth(date.lengthOfMonth())
        val curFirst = today.withDayOfMonth(1)
        start = first.toEpochDay()
        end = minOf(last.toEpochDay(), todayEd)
        label = first.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + first.year
        relative = when (first) { curFirst -> "This month"; curFirst.minusMonths(1) -> "Last month"; else -> "" }
        prevAnchor = first.minusMonths(1).toEpochDay()
        nextAnchor = first.plusMonths(1).toEpochDay()
        canNext = first < curFirst
    }

    val rollup = remember(mode, start, end, dayLogs, questions, habits, checkins, timeEntries, activities, goals, tasks) {
        ReviewRollup.compute(start, end, dayLogs, questions, habits, checkins, timeEntries, activities, zone, System.currentTimeMillis(), goals, tasks)
    }
    // Feature 5 — the on-device cross-stream patterns for this period (descriptive, never causal).
    val insights = remember(mode, start, end, dayLogs, questions, habits, checkins, timeEntries, activities) {
        ReviewInsights.compute(start, end, dayLogs, questions, habits, checkins, timeEntries, activities, zone, System.currentTimeMillis())
    }

    // ── Period navigator (mirrors the day navigator) ──
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onAnchorChange(prevAnchor) }) { Icon(Icons.Filled.ChevronLeft, "Previous ${mode.label.lowercase()}") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (relative.isNotBlank()) Text(relative, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { if (canNext) onAnchorChange(nextAnchor) }, enabled = canNext) { Icon(Icons.Filled.ChevronRight, "Next ${mode.label.lowercase()}") }
        // Phase F — share this period as an on-device PNG card (permission-free), mirroring the day share.
        if (rollup.hasData) {
            IconButton(onClick = {
                val reviewedSet = dayLogs.asSequence().filter { ReviewCadence.isReviewed(it) }.map { it.epochDay }.toSet()
                val streak = ReviewCadence.computeStreak(reviewedSet, emptySet(), end, 0).streak
                val wd = DayCard.WeekData(
                    rangeLabel = label,
                    reviewedDays = rollup.reviewedDays,
                    periodDays = rollup.periodDays,
                    avgRating = rollup.avgRating,
                    streak = streak,
                    topWin = rollup.wins.firstOrNull()?.text ?: "",
                    accentArgb = accentArgb,
                )
                runCatching {
                    val bmp = DayCard.renderWeek(wd)
                    val res = ProgressCard.saveAndShareUri(ctx, bmp, "kairo-week-$start.png")
                    res.shareUri?.let { ProgressCard.share(ctx, it) }
                }
            }) { Icon(Icons.Filled.Share, "Share ${mode.label.lowercase()}") }
        }
    }
    Spacer(Modifier.height(12.dp))

    // ── Wave 1 — enter the guided Weekly Review (Week roll-up only) ──
    if (mode == ReviewRange.WEEK) {
        val weekIso = WeeklyReviews.isoWeekKey(LocalDate.ofEpochDay(start))
        val reviewed = WeeklyReviews.isReviewed(weeklyReviewsJson, weekIso)
        FilledTonalButton(onClick = { onStartWeeklyReview(weekIso) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (reviewed) "🗓️  Weekly review ✓ — reopen" else "🗓️  Start weekly review")
        }
        Spacer(Modifier.height(12.dp))
    }

    // ── Wave 3 (B) — the fully-local "Year, reviewed" recap (Month roll-up only) ──
    if (mode == ReviewRange.MONTH) {
        FilledTonalButton(onClick = onOpenYearReview, modifier = Modifier.fillMaxWidth()) {
            Text("📖  Year, reviewed")
        }
        Spacer(Modifier.height(12.dp))
    }

    // ── 1. At-a-glance header ──
    AppCard {
        Text("${rollup.reviewedDays} of ${rollup.periodDays} days reviewed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        val closedPct = if (rollup.periodDays > 0) (rollup.reviewedDays * 100) / rollup.periodDays else 0
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((closedPct / 100f).coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
        }
    }

    // ── 1a. How your days felt: rating + evening mood, each averaged over the period with its own trend ──
    if (rollup.ratedDays > 0 || rollup.moodCount > 0) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("How your days felt")
            if (rollup.ratedDays > 0) {
                val r = rollup.avgRating.roundToInt().coerceIn(1, 5)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("★".repeat(r) + "☆".repeat(5 - r), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("${oneDp(rollup.avgRating)} avg · ${rollup.ratedDays} rated", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                ScoreSparkline(rollup.ratingTrend, Modifier.fillMaxWidth())
            }
            if (rollup.moodCount > 0) {
                if (rollup.ratedDays > 0) Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(moodFace(rollup.avgMood.roundToInt()), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text("mood ${oneDp(rollup.avgMood)} avg · ${rollup.moodCount} day${if (rollup.moodCount == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                MoodStrip(rollup.moodTrend, Modifier.fillMaxWidth())
            }
        }
    }

    if (!rollup.hasData) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            Text("Nothing to roll up in this ${mode.label.lowercase()} yet — close a few days and your wins, lessons and consistency gather here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // ── 2. Wins ──
    if (rollup.wins.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("⭐ Wins")
            rollup.wins.forEach { w ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⭐", Modifier.width(24.dp))
                    Text(w.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (w.count > 1) Text("×${w.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (rollup.moreWins > 0) Text("+ ${rollup.moreWins} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
        }
    }

    // ── 2b. Phase E — goals advanced this period ──
    if (rollup.goalsMoved.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("🎯 Goals advanced")
            rollup.goalsMoved.forEach { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${g.count} day${if (g.count == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    // ── 3. Lessons & reflections (tap a card to open that day) ──
    if (rollup.reflections.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("Lessons & reflections")
            rollup.reflections.forEach { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpenDay(r.epochDay) }.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dayChip(r.epochDay), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Feature 6 — that day's numbers, so the note is never read without its context.
                    val metrics = reflectionMetricsLine(r)
                    if (metrics.isNotBlank()) Text(metrics, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp, bottom = 2.dp))
                    Text(r.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            if (rollup.moreReflections > 0) Text("+ ${rollup.moreReflections} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
        }
    }

    // ── 4. Habit consistency ──
    if (rollup.habitConsistency.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("Habit consistency")
            val tertiary = MaterialTheme.colorScheme.tertiary
            rollup.habitConsistency.forEach { h ->
                MeterRow(
                    leading = { Text(h.emoji ?: "🔁") },
                    name = h.name,
                    trailing = "${h.pct}% · ${h.kept}/${h.expected}",
                    frac = h.pct / 100f,
                    color = h.colorArgb?.let { Color(it) } ?: tertiary,
                )
            }
        }
    }

    // ── 5. Top time activities ──
    if (rollup.topActivities.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("Time tracked · top activities")
            val maxMin = (rollup.topActivities.maxOfOrNull { it.minutes } ?: 1).coerceAtLeast(1)
            val fallback = MaterialTheme.colorScheme.primary
            rollup.topActivities.forEach { a ->
                val col = a.colorArgb?.let { Color(it) } ?: fallback
                MeterRow(
                    leading = { val e = a.emoji; if (e != null) Text(e) else Box(Modifier.size(12.dp).clip(CircleShape).background(col)) },
                    name = a.name,
                    trailing = formatHm(a.minutes),
                    frac = a.minutes / maxMin.toFloat(),
                    color = col,
                )
            }
        }
    }

    // ── 6. Daily-question averages ──
    if (rollup.questionAverages.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("Daily questions")
            rollup.questionAverages.forEachIndexed { i, q ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Text(q.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(oneDp(q.avg), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("avg", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(10.dp))
                    ScoreSparkline(q.trend, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Effort scores, averaged over the ${mode.label.lowercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    } else if (questions.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("Daily questions")
            Text("No effort scores logged this ${mode.label.lowercase()} yet — score your questions when you close a day.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // ── 7. Patterns — the on-device cross-stream correlation engine (feature 5). Replaces the old single
    // "Your best days share…" line with a richer, ranked, honestly-gated set of descriptive findings. ──
    if (insights.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("Patterns")
            insights.forEach { ins ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("•", Modifier.width(16.dp), color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(ins.text, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            StrengthDots(ins.strength)
                            Spacer(Modifier.width(6.dp))
                            Text("${ins.confidence.label} signal · ${ins.sampleSize} days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            Text("Descriptive, not a cause — patterns your reviews share, computed privately on your device.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
        }
    } else if (rollup.ratedDays in 1 until ReviewInsights.MIN_RATED_DAYS) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("Patterns")
            Text("Keep reviewing — patterns across your habits, time and mood appear after about ${ReviewInsights.MIN_RATED_DAYS} rated days.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

private fun formatHm(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

private fun moodFace(v: Int): String = when (v.coerceIn(0, 5)) { 1 -> "😞"; 2 -> "🙁"; 3 -> "😐"; 4 -> "🙂"; 5 -> "😄"; else -> "😐" }

/** A compact week label: "1–7 Sep", or "28 Aug – 3 Sep" when the week straddles two months. */
private fun weekLabel(a: LocalDate, b: LocalDate): String {
    val ma = a.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val mb = b.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return if (a.month == b.month) "${a.dayOfMonth}–${b.dayOfMonth} $mb" else "${a.dayOfMonth} $ma – ${b.dayOfMonth} $mb"
}

/** A short dated chip for a reflection entry, e.g. "Mon 1". */
private fun dayChip(epochDay: Long): String {
    val d = LocalDate.ofEpochDay(epochDay)
    return d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.dayOfMonth
}

/** Feature 5 — a compact three-dot strength meter for a pattern's confidence signal. */
@Composable
private fun StrengthDots(strength: Double) {
    val filled = when {
        strength >= 0.60 -> 3
        strength >= 0.35 -> 2
        else -> 1
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.surfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..3).forEach { i ->
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (i <= filled) on else off))
        }
    }
}

/** Feature 6 — a one-line "that day's numbers" caption for a reflection digest entry: rating stars,
 *  mood face, tasks-done count and the top tracked activity, so a note keeps its context. */
private fun reflectionMetricsLine(r: ReviewRollup.ReflectionEntry): String {
    val parts = buildList {
        if (r.rating in 1..5) add("★".repeat(r.rating) + "☆".repeat(5 - r.rating))
        if (r.mood in 1..5) add(moodFace(r.mood))
        if (r.tasksDone > 0) add("✓ ${r.tasksDone}")
        r.topActivityName?.let { add((r.topActivityEmoji?.let { e -> "$e " } ?: "") + it) }
    }
    return parts.joinToString("  ·  ")
}

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
private fun SealedLettersReviewCard(
    notes: List<com.todocompanion.app.data.entity.SealedNoteEntity>,
    today: LocalDate,
    onWrite: () -> Unit,
    onOpen: (com.todocompanion.app.data.entity.SealedNoteEntity) -> Unit,
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
private fun WriteSealedLetterDialog(today: LocalDate, onDismiss: () -> Unit, onSeal: (String, String, Long) -> Unit) {
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
private fun SealedLetterRevealDialog(
    note: com.todocompanion.app.data.entity.SealedNoteEntity, ready: Boolean, intact: Boolean,
    onDismiss: () -> Unit, onAck: () -> Unit, onDelete: () -> Unit,
) {
    val sealedOn = LocalDate.ofEpochDay(note.createdEpochDay)
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
private fun AddPredictionDialog(today: LocalDate, onDismiss: () -> Unit, onAdd: (String, Long) -> Unit) {
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
private fun ResolvePredictionDialog(
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
private fun ReflectionCompanionDialog(
    rating: Int, mood: Int, emotionLabel: String, existingReflection: String,
    onDismiss: () -> Unit, onSave: (String) -> Unit,
) {
    val chain = remember(rating, mood, emotionLabel) { ReflectionCompanion.chainFor(rating, mood, emotionLabel) }
    var answers by remember { mutableStateOf(List(chain.prompts.size) { "" }) }
    var idx by remember { mutableIntStateOf(0) }
    val last = idx >= chain.prompts.lastIndex
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (last) onSave(ReflectionCompanion.merge(existingReflection, ReflectionCompanion.compose(chain, answers)))
                else idx++
            }) { Text(if (last) "Save" else "Next") }
        },
        dismissButton = { TextButton(onClick = { if (idx > 0) idx-- else onDismiss() }) { Text(if (idx > 0) "Back" else "Cancel") } },
        title = { Text("${chain.track.glyph}  ${chain.track.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(chain.intro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("Question ${idx + 1} of ${chain.prompts.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(chain.prompts[idx], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 4.dp))
                AppTextField(answers[idx], { v -> answers = answers.toMutableList().also { it[idx] = v } }, minLines = 2, placeholder = { Text("Your answer (optional)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("A private guide, all on your device — no AI service. Your answers save into today's reflection.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        },
    )
}

/**
 * Wave 3 (B) — the fully-local "Year, reviewed": a calm, multi-panel year-in-review computed on-device by
 * [YearReviewed] over the rolling 365 days ending today, with a permission-free shareable PNG rendered
 * through the existing DayCard/ProgressCard pipeline (guarded, never blank). Reachable from the Month
 * roll-up. Nothing leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YearReviewedScreen(
    anchorDay: Long, todayEd: Long, zone: ZoneId,
    dayLogs: List<com.todocompanion.app.data.entity.DayLogEntity>,
    habits: List<com.todocompanion.app.data.entity.HabitEntity>,
    checkins: List<com.todocompanion.app.data.entity.HabitCheckinEntity>,
    timeEntries: List<com.todocompanion.app.data.entity.TimeEntryEntity>,
    activities: List<com.todocompanion.app.data.entity.TimeActivityEntity>,
    accentArgb: Long?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val end = todayEd
    val start = todayEd - (YearReviewed.WINDOW_DAYS - 1)
    val recap = remember(dayLogs, habits, checkins, timeEntries, activities, end) {
        YearReviewed.compute(start, end, dayLogs, habits, checkins, timeEntries, activities, zone, System.currentTimeMillis())
    }
    val fromLabel = LocalDate.ofEpochDay(start)
    val toLabel = LocalDate.ofEpochDay(end)
    val windowLabel = "${fromLabel.dayOfMonth} ${fromLabel.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${fromLabel.year} – " +
        "${toLabel.dayOfMonth} ${toLabel.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${toLabel.year}"

    fun shareYear() {
        runCatching {
            val yd = DayCard.YearData(
                yearLabel = "My year",
                daysReviewed = recap.daysReviewed,
                avgRating = recap.avgRating,
                trackedHours = recap.trackedHours,
                longestStreak = recap.longestStreakDays,
                winsCount = recap.winsCount,
                topActivity = recap.topActivities.firstOrNull()?.name ?: "",
                topEmotion = recap.topEmotionWord,
                highlight = recap.highlightText,
                accentArgb = accentArgb,
            )
            val bmp = DayCard.renderYear(yd)
            val res = ProgressCard.saveAndShareUri(ctx, bmp, "kairo-year-$end.png")
            res.shareUri?.let { ProgressCard.share(ctx, it) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            expandedHeight = 52.dp,
            title = { Text("Year, reviewed") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (recap.hasData) IconButton(onClick = { shareYear() }) { Icon(Icons.Filled.Share, "Share year") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            if (!recap.hasData) {
                AppCard {
                    Text("📖", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Your year starts filling in as you review your days.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            // Header panel.
            AppCard {
                Text("The last 12 months", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("${recap.daysReviewed} days reviewed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(windowLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(10.dp))
                val tiles = buildList {
                    if (recap.longestStreakDays > 0) add(Triple("🔥", "${recap.longestStreakDays}", "longest streak"))
                    if (recap.winsCount > 0) add(Triple("⭐", "${recap.winsCount}", "good things"))
                    if (recap.trackedMinutes > 0) add(Triple("⧗", "${recap.trackedHours}h", "tracked"))
                }
                if (tiles.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        tiles.forEach { (icon, value, label) -> StatTile(icon, value, label, Modifier.weight(1f)) }
                    }
                }
            }

            // How the year felt: rating + mood, each with its monthly trend.
            if (recap.ratedDays > 0 || recap.moodDays > 0) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("How your year felt")
                    if (recap.ratedDays > 0) {
                        val r = recap.avgRating.roundToInt().coerceIn(1, 5)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("★".repeat(r) + "☆".repeat(5 - r), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("${oneDp(recap.avgRating)} avg · ${recap.ratedDays} rated", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        ScoreSparkline(recap.ratingTrend, Modifier.fillMaxWidth())
                    }
                    if (recap.moodDays > 0) {
                        if (recap.ratedDays > 0) Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(moodFace(recap.avgMood.roundToInt()), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Text("mood ${oneDp(recap.avgMood)} avg · ${recap.moodDays} days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        MoodStrip(recap.moodTrend, Modifier.fillMaxWidth())
                    }
                    if (recap.topEmotionWord.isNotBlank() && recap.topEmotionCount >= 3) {
                        Spacer(Modifier.height(10.dp))
                        Text("Most often, you felt ${recap.topEmotionWord.lowercase(Locale.getDefault())} (${recap.topEmotionCount} days named it).",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Top activities.
            if (recap.topActivities.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("Where your time went")
                    val maxMin = (recap.topActivities.maxOfOrNull { it.minutes } ?: 1).coerceAtLeast(1)
                    val fallback = MaterialTheme.colorScheme.primary
                    recap.topActivities.forEach { a ->
                        val col = a.colorArgb?.let { Color(it) } ?: fallback
                        MeterRow(
                            leading = { val e = a.emoji; if (e != null) Text(e) else Box(Modifier.size(12.dp).clip(CircleShape).background(col)) },
                            name = a.name, trailing = formatHm(a.minutes), frac = a.minutes / maxMin.toFloat(), color = col,
                        )
                    }
                }
            }

            // Habit consistency.
            if (recap.habitConsistency.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("Habits, over the year")
                    val tertiary = MaterialTheme.colorScheme.tertiary
                    recap.habitConsistency.forEach { h ->
                        MeterRow(
                            leading = { Text(h.emoji ?: "🔁") },
                            name = h.name, trailing = "${h.pct}% · ${h.kept}/${h.expected}", frac = h.pct / 100f, color = tertiary,
                        )
                    }
                }
            }

            // A standout highlight.
            if (recap.highlightText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("✨ A highlight")
                    Text("“${recap.highlightText}”", style = MaterialTheme.typography.bodyLarge)
                    if (recap.highlightEpochDay > 0) {
                        val d = LocalDate.ofEpochDay(recap.highlightEpochDay)
                        Text(d.dayOfMonth.toString() + " " + d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.year +
                            (if (recap.highlightRating in 1..5) "  ·  " + "★".repeat(recap.highlightRating) else ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = { shareYear() }, modifier = Modifier.fillMaxWidth()) { Text("Share a summary") }
            Spacer(Modifier.height(8.dp))
            Text("Built entirely on your device from your private record. Nothing was sent anywhere.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}
