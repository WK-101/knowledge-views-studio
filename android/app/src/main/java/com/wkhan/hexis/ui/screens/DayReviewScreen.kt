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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.window.Dialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wkhan.hexis.data.entity.HabitEntity
import com.wkhan.hexis.data.entity.TaskEntity
import com.wkhan.hexis.ui.theme.LocalHexisColors
import com.wkhan.hexis.domain.AdaptivePrompts
import com.wkhan.hexis.domain.DailyQuestion
import com.wkhan.hexis.domain.DailyQuestions
import com.wkhan.hexis.domain.DayAlignments
import com.wkhan.hexis.domain.DayShareConfigs
import com.wkhan.hexis.domain.PeriodShareConfigs
import com.wkhan.hexis.domain.EmotionWords
import com.wkhan.hexis.domain.DayMemories
import com.wkhan.hexis.domain.ExecutionScore
import com.wkhan.hexis.domain.RetroLens
import com.wkhan.hexis.domain.Goal
import com.wkhan.hexis.domain.Goals
import com.wkhan.hexis.domain.Prediction
import com.wkhan.hexis.domain.Predictions
import com.wkhan.hexis.domain.PeriodRange
import com.wkhan.hexis.domain.ReflectionCompanion
import com.wkhan.hexis.domain.ReviewCadence
import com.wkhan.hexis.domain.ReviewInsights
import com.wkhan.hexis.domain.ReviewRollup
import com.wkhan.hexis.domain.weekStartOf
import com.wkhan.hexis.domain.TextInsights
import com.wkhan.hexis.domain.YearReviewed
import com.wkhan.hexis.domain.WeeklyReview
import com.wkhan.hexis.domain.WeeklyReviews
import com.wkhan.hexis.domain.calendar.CalendarEngine
import com.wkhan.hexis.domain.done.DoneKind
import com.wkhan.hexis.domain.done.DoneRecord
import com.wkhan.hexis.domain.habit.FourthWave
import com.wkhan.hexis.domain.habit.HabitStats
import com.wkhan.hexis.ui.AppViewModel
import com.wkhan.hexis.ui.components.AppCard
import com.wkhan.hexis.ui.components.AppTextField
import com.wkhan.hexis.ui.components.DateOnlyPickerDialog
import com.wkhan.hexis.ui.components.DoneTick
import com.wkhan.hexis.ui.components.EmotionChip
import com.wkhan.hexis.ui.components.Metric
import com.wkhan.hexis.ui.components.MetricTileGrid
import com.wkhan.hexis.ui.components.metricColumnsFor
import com.wkhan.hexis.ui.components.MeterRow
import com.wkhan.hexis.ui.components.MiniCheck
import com.wkhan.hexis.ui.components.OpenTick
import com.wkhan.hexis.ui.components.OptionChips
import com.wkhan.hexis.ui.components.PeriodSwitcher
import com.wkhan.hexis.ui.components.SectionTitle
import com.wkhan.hexis.ui.components.StatTile
import com.wkhan.hexis.util.DayCard
import com.wkhan.hexis.util.ProgressCard
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
/** One quit/bad habit's standing for the reviewed day: clean (no relapse) and the current days-free streak. */
private data class QuitDay(val habit: HabitEntity, val clean: Boolean, val streak: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayReviewScreen(vm: AppViewModel, initialDay: Long, startInClose: Boolean = false, startInWeekly: Boolean = false, onOpenTask: (String) -> Unit, onOpenNote: (String) -> Unit = {}, onBack: () -> Unit) {
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
    var openLetter by remember { mutableStateOf<com.wkhan.hexis.data.entity.SealedNoteEntity?>(null) }
    var showAddPrediction by remember { mutableStateOf(false) }
    var resolvePrediction by remember { mutableStateOf<Prediction?>(null) }
    var showCompanion by remember { mutableStateOf(false) }
    var showYear by remember { mutableStateOf(false) }
    // Which period the unified share dialog opens on (This day by default; the Week/Month roll-up share
    // buttons preselect their own period so the modular flow spans day → week → month → year in one place).
    var sharePreselect by remember { mutableStateOf(SharePeriod.DAY) }
    // Wave 1 — deliberate rollover: ids the user chose to "let go" (everything else defaults to carry).
    var rolloverLetGo by remember { mutableStateOf(setOf<String>()) }
    // Phase F — opened via the "Close your day" shortcut / evening nudge: land straight in the close flow.
    LaunchedEffect(startInClose) { if (startInClose) showClose = true }
    // Coherence Move 7 — the shared period switcher: Day · Week · Month · Year · All. Day keeps the full
    // close-the-day screen; every other period rolls the reviewed span up into read-only aggregate cards.
    var mode by remember { mutableStateOf(PeriodRange.DAY) }

    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val habits by vm.habits.collectAsStateWithLifecycle()
    val checkins by vm.habitCheckins.collectAsStateWithLifecycle()
    val timeEntries by vm.timeVm.timeEntries.collectAsStateWithLifecycle()
    val activities by vm.timeVm.timeActivities.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val dayLogs by vm.dayLogs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val coreValues by vm.coreValues.collectAsStateWithLifecycle()
    val sealedNotes by vm.sealedNotes.collectAsStateWithLifecycle()

    val date = LocalDate.ofEpochDay(day)
    // Surfaced from the drawer's "Weekly review": land straight in the guided weekly ritual for the shown
    // day's week (mirrors startInClose). The Week roll-up still opens the same flow the same way.
    LaunchedEffect(startInWeekly) {
        if (startInWeekly) {
            mode = PeriodRange.WEEK
            weeklyIso = WeeklyReviews.isoWeekKey(weekStartOf(date, settings.weekStart))
            showWeekly = true
        }
    }
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()
    val isToday = day == todayEd

    val feed = remember(tasks, habits, checkins, timeEntries, day) {
        DoneRecord.build(tasks, habits, checkins, timeEntries, zone).filter { it.epochDay == day }
    }
    // P6a: memoize the heavy per-day aggregates (were recomputed on every dialog toggle / recomposition).
    // Keys mirror the neighboring already-remembered values and cover every input each block reads.
    val tasksDone = remember(tasks, day) {
        tasks.filter { it.completed && it.completedAt != null && it.completedAt!! in dayStart until dayEnd && !it.trashed }
            .sortedByDescending { it.completedAt }
    }
    val wins = remember(feed) { feed.filter { it.isWin && it.isTaskLike } }
    val focusMin = remember(feed) { feed.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin } }

    // Break/quit habits have no positive daily action (success is passively staying under the limit), so
    // they're never "expected" or "missed" here; paused habits are on vacation. isSuccessDay excludes slips.
    // P6a: memoize expected/kept/missed (missed is O(n²)); keyed on the derived values + raw inputs read.
    val expected = remember(habits, day) { habits.filter { !it.archived && !it.paused && it.habitType != "break" && HabitStats.isExpectedDay(it, day) } }
    val habitsKept = remember(expected, checkins, day) {
        expected.mapNotNull { h ->
            val c = checkins.firstOrNull { it.habitId == h.id && it.epochDay == day }
            if (c != null && HabitStats.isSuccessDay(h, c)) h to c.count else null
        }
    }
    val habitsExpected = expected.size
    val missedHabits = remember(expected, habitsKept) { expected.filter { h -> habitsKept.none { it.first.id == h.id } } }

    // Quit/bad habits ARE habits and belong in the review — their win is passive (a clean day, no relapse),
    // so they can't ride the "kept vs expected" tally above (which is for positive daily actions). Surface
    // them on their own so "stayed clean today" is celebrated and a slip is visible, with the current
    // clean-streak for encouragement. (R108 — they were previously dropped entirely and never shown.)
    // P6a: memoize the break/quit-habit roll-up (scans checkins per habit) and its clean count.
    val quitToday = remember(habits, checkins, day) {
        habits.filter { !it.archived && !it.paused && it.habitType == "break" && day >= it.startEpochDay() }
            .map { h ->
                val mine = checkins.filter { it.habitId == h.id }
                val c = mine.firstOrNull { it.epochDay == day }
                val clean = HabitStats.isWinDay(h, day, c)
                val relapseDays = mine.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
                val streak = HabitStats.currentStreak(h, emptySet(), emptySet(), relapseDays, day)
                QuitDay(h, clean, streak)
            }
    }
    val quitClean = remember(quitToday) { quitToday.count { it.clean } }

    val occ = remember(events, day) { CalendarEngine.expand(events, dayStart, dayEnd, zone).sortedBy { it.startMillis } }

    val tracked = remember(timeEntries, day) {
        timeEntries.groupBy { it.activityId }.mapValues { (_, es) ->
            es.sumOf { com.wkhan.hexis.domain.TimeTracking.minutesInWindow(it.startMillis, it.endMillis, dayStart, dayEnd, now) }
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
    // Per-workspace: the align picker and every rollup below see only the active workspace's goals
    // (goals are workspace-scoped, like the day log itself). Keyed on the active workspace too so a
    // switch re-resolves. Ids are unique, so resolving a recorded alignment stays correct.
    val goals = vm.goalsState.collectAsStateWithLifecycle().value   // W3 — Room-backed, already workspace-scoped in the VM
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
    // P6a: memoize tomorrow's due-task list, mirroring the neighboring tmrOcc remember(events, tmr).
    val tmrTasks = if (isToday) remember(tasks, tmr) { tasks.filter { !it.completed && !it.trashed && !it.abandoned && it.dueDate != null && it.dueDate!! in tStart until tEnd }.sortedBy { it.dueDate } } else emptyList()
    val tmrOcc = if (isToday) remember(events, tmr) { CalendarEngine.expand(events, tStart, tEnd, zone).sortedBy { it.startMillis } } else emptyList()

    val nothing = tasksDone.isEmpty() && habitsKept.isEmpty() && occ.isEmpty() && tracked.isEmpty() && focusMin == 0

    fun fmtHm(m: Int) = com.wkhan.hexis.util.formatMinutes(m)
    fun timeLabel(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    fun mood(v: Int) = when (v) { 1 -> "😞"; 2 -> "🙁"; 3 -> "😐"; 4 -> "🙂"; 5 -> "😄"; else -> "" }
    fun outcomeLabel(v: Int) = when (v) { 1 -> "Not yet"; 2 -> "Partly"; 3 -> "Done"; else -> "" }

    // Wave 2 (feature 8) — local memory resurfacing: one past moment from the same date in a prior
    // year/month, or a recent good moment worth savouring. Computed on-device from the loaded day logs.
    val memory = remember(dayLogs, day) { DayMemories.select(day, dayLogs) }

    // Track 3.2 — the ranked "moments to reflect on": anniversaries, a just-finished project/goal, a
    // returning obstacle, a recent hard day and a bright high-mood moment — all from the user's own logs.
    val moments = remember(dayLogs, tasks, habits, checkins, timeEntries, day) {
        val fullFeed = DoneRecord.build(tasks, habits, checkins, timeEntries, zone)
        DayMemories.moments(day, dayLogs, fullFeed)
    }

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

    // Wrap the screen and its full-screen flows in a Box so the inline Close-the-day / Weekly-review
    // OVERLAYs (and the Year-reviewed screen) — declared after the Scaffold — stack ON TOP of the day
    // content and fully cover it, top bar included.
    Box(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            TopAppBar(
                expandedHeight = 52.dp,
                title = { Text(if (mode == PeriodRange.ALL) "All-time review" else "${mode.label} review") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.openDailyNote(day) { id -> onOpenNote(id) } }) { Icon(Icons.AutoMirrored.Filled.Article, "Daily note") }
                    IconButton(onClick = { sharePreselect = SharePeriod.DAY; showShare = true }) { Icon(Icons.Filled.Share, "Share day") }
                    if (!isToday) TextButton(onClick = { day = todayEd }) { Text("Today") }
                },
            )
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 2.dp)) {
                // ── The one shared period switcher: Day (this screen) · Week · Month · Year · All (roll-up) ──
                PeriodSwitcher(selected = mode, onSelect = { mode = it }, modifier = Modifier.padding(bottom = 12.dp))
                if (mode != PeriodRange.DAY) {
                    RangeRollup(
                        mode = mode, anchor = day, todayEd = todayEd, zone = zone,
                        weekStartSetting = settings.weekStart,
                        dayLogs = dayLogs, questions = questions, habits = habits, checkins = checkins,
                        timeEntries = timeEntries, activities = activities, goals = goals, tasks = tasks,
                        weeklyReviewsJson = settings.weeklyReviewsJson,
                        onStartWeeklyReview = { iso -> weeklyIso = iso; showWeekly = true },
                        onAnchorChange = { day = it },
                        onOpenDay = { d -> day = d; mode = PeriodRange.DAY },
                        onOpenYearReview = { showYear = true },
                        onSharePeriod = { p -> sharePreselect = p; showShare = true },
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
                            tiles.forEach { (icon, value, label) -> StatTile(value = value, label = label, modifier = Modifier.weight(1f), icon = icon) }
                        }
                    }
                    Spacer(Modifier.height(if (nothing) 10.dp else 14.dp))
                    // Review-streak: a "don't break the chain" strip of the last 14 days. A repaired day is
                    // shown in a distinct tertiary tint (not the same as a truly reviewed day) — honest by design.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Track 2.7 — with streaks hidden, lead with consistency over the density strip, not a count.
                        val reviewed14 = (13 downTo 0).count { val d0 = todayEd - it; d0 in reviewedDays || d0 in repairedDays }
                        Text(
                            when {
                                settings.hideStreaks -> "$reviewed14 of last 14 days reviewed"
                                reviewStreak > 0 -> "🔥 $reviewStreak-day review streak"
                                else -> "Reviewed days"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (!settings.hideStreaks && reviewStreak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                // Track 2.7 — "never miss twice": lead with recovery, not the loss of a streak.
                                Text("Never miss twice — one tap gets you right back on track.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                Text("${streakState.tokensAvailable} recovery${if (streakState.tokensAvailable == 1) "" else " tokens"} left this month", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(onClick = { vm.keepStreak(repairDay) }) { Text("Get back on track") }
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

                // ── Track 3.2 — Moments to reflect on: a small, calm, optional list of second-looks. Tap to open. ──
                if (moments.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    AppCard {
                        Text("Moments to reflect on", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("A few second-looks, drawn from your own days — no pressure to open any.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                        moments.forEach { m ->
                            val tappable = m.epochDay != null
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .then(if (tappable) Modifier.clickable { day = m.epochDay!! } else Modifier)
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(m.kind.icon, Modifier.width(30.dp))
                                Text(m.line, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (tappable) Icon(Icons.Filled.ChevronRight, "Open that day", tint = MaterialTheme.colorScheme.outline)
                            }
                        }
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
                        val habitMetrics = habitsKept.map { (h, c) ->
                            val target = h.targetPerDay.coerceAtLeast(1)
                            Metric(
                                emoji = h.emoji ?: "🔁",
                                name = h.name,
                                value = if (target > 1) "$c/$target${h.unit?.let { " $it" } ?: ""}" else "Done",
                                frac = (c.toFloat() / target).coerceIn(0f, 1f),
                                color = tertiary,
                            )
                        }
                        // Long habit names get the full row width (1 column) so they wrap cleanly instead of
                        // truncating in a cramped 2-up grid — the same adaptive rule as the Time-tracked card.
                        MetricTileGrid(habitMetrics, columns = metricColumnsFor(habitMetrics))
                    }
                }
                // Quit/bad habits — their own card so a clean day is a visible win and a slip is honest.
                if (quitToday.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    AppCard {
                        SectionTitle("Quit habits · $quitClean/${quitToday.size} clean")
                        val good = LocalHexisColors.current.good
                        val bad = LocalHexisColors.current.bad
                        quitToday.forEach { q ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (q.clean) "🛡" else "⚠️", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text((q.habit.emoji?.plus(" ") ?: "") + q.habit.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (q.clean) (if (q.streak > 0) "Stayed clean · ${q.streak} day${if (q.streak == 1) "" else "s"} free" else "Stayed clean")
                                        else "Relapse logged today",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (q.clean) good else bad,
                                    )
                                }
                            }
                        }
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
                        val trackedMetrics = tracked.entries.sortedByDescending { it.value }.map { (actId, min) ->
                            val a = activities.firstOrNull { it.id == actId }
                            val col = a?.colorArgb?.let { Color(it) } ?: fallback
                            Metric(emoji = a?.emoji, name = a?.name ?: "—", value = fmtHm(min), frac = min / maxMin.toFloat(), color = col)
                        }
                        // Long activity names (or a lone activity) read poorly in a cramped 2-up grid, so give
                        // them the full width; short-named multi-activity days keep the compact 2-column grid.
                        MetricTileGrid(trackedMetrics, columns = metricColumnsFor(trackedMetrics))
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
                    // The day's numbers already lead the screen in the at-a-glance StatTile card, so no glance
                    // caption is repeated here — this card is only the mood / rating / emotion read-back and the
                    // "vs your usual" context, which the top card doesn't show.
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
                        // Personalizing the set stays discoverable right in the main review (not only in the close
                        // flow): "Personalize" once questions exist, "Set up your questions" when there are none.
                        if (questions.isNotEmpty()) TextButton(onClick = { showQuestions = true }) { Text("Personalize") }
                    }
                    if (questions.isEmpty()) {
                        Text("Score a few “Did I do my best to…” questions each night. Scoring your effort — not the outcome — keeps the win in your hands.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { showQuestions = true }) { Text("Set up your questions") }
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
                append("• ${tasksDone.size} done")
                if (habitsExpected > 0) append(" · 🔁 ${habitsKept.size}/$habitsExpected")
                if (focusMin > 0) append(" · 🎯 ${fmtHm(focusMin)}")
                if (trackedTotal > 0) append(" · ⧗ ${fmtHm(trackedTotal)}")
            },
            recallTiles = if (nothing) emptyList() else buildList {
                add(Triple("✓", tasksDone.size.toString(), "done"))
                if (habitsExpected > 0) add(Triple("🔁", "${habitsKept.size}/$habitsExpected", "habits"))
                if (focusMin > 0) add(Triple("🎯", fmtHm(focusMin), "focus"))
                if (trackedTotal > 0) add(Triple("⧗", fmtHm(trackedTotal), "tracked"))
            },
            wins = wins.map { it.title },
            streak = reviewStreak + (if (day !in reviewedDays) 1 else 0),
            // Track 2.7 — cadence corrections. Gratitude is a weekly beat (only the week-start day) when
            // gratitudeWeekly is on; the three-good-things prompt asks for a short "…and why" when on; and
            // the streak line is hidden in favour of the consistency framing when hideStreaks is on.
            showGratitude = !settings.gratitudeWeekly || weekStartOf(date, settings.weekStart) == date,
            requireGoodWhy = settings.requireGoodThingWhy,
            hideStreaks = settings.hideStreaks,
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
            // Track 2.5 — close the loop: last week's focus, resurfaced at the start of this week's review.
            val lastWeekFocus = remember(settings.weeklyReviewsJson, weeklyIso, ws) {
                val prevIso = WeeklyReviews.isoWeekKey(ws.minusDays(7))
                WeeklyReviews.forWeek(settings.weeklyReviewsJson, prevIso)?.nextFocus?.takeIf { it.isNotBlank() } ?: ""
            }
            // Track 2.1 — the execution score for this week (planned commitments vs done).
            val execScore = remember(weekRollup, tasks) { ExecutionScore.fromRollup(weekRollup, tasks, zone) }
            WeeklyReviewFlow(
                isoWeek = weeklyIso,
                weekLabel = weekLabel(ws, ws.plusDays(6)),
                rollup = weekRollup,
                execScore = execScore,
                lastWeekFocus = lastWeekFocus,
                openTasks = allOpenTasks,
                existing = existing,
                onSaveRollover = { carryIds, letGoIds -> vm.reviewRollover(carryIds, letGoIds) },
                onSave = { reflection, nextFocus, areas, lens, lensAnswers, focusRating ->
                    vm.saveWeeklyReview(weeklyIso, reflection, nextFocus, areas, lens, lensAnswers, focusRating)
                },
                onDismiss = { showWeekly = false },
            )
        }

        // ── The redesigned modular day share ──
        // Themes / pattern are computed on the fly from the day's own data (no schema change). Themes are the
        // salient content words of the day's reflection texts; the pattern is one soft, non-causal observation
        // mined from the trailing window (ReviewInsights.nudge), computed for the shared day (not just today).
        val shareThemes = remember(bookend) {
            val docs = listOfNotNull(
                bookend?.pmReflection, bookend?.highlight, bookend?.lesson, bookend?.gratitude,
                bookend?.good1, bookend?.good2, bookend?.good3, bookend?.promptAnswer,
            ).filter { it.isNotBlank() }
            TextInsights.themes(docs, topN = 3, minDocuments = 1).map { it.display }
        }
        val sharePattern = remember(day, dayLogs, questions, habits, checkins, timeEntries, activities) {
            ReviewInsights.nudge(day - 89, day, dayLogs, questions, habits, checkins, timeEntries, activities, zone, System.currentTimeMillis())?.text ?: ""
        }
        val shareData = remember(
            date, bookend, wins, tasksDone, expected, checkins, habitsKept, habitsExpected, tracked, activities,
            trackedTotal, questions, todayScores, movedGoals, honoredValues, shareThemes, sharePattern, settings.accentArgb, day,
        ) {
            DayCard.DayShareData(
                dateLabel = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", " + date.dayOfMonth + " " + date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                rating = bookend?.dayRating ?: 0,
                moodEmoji = mood(bookend?.pmMood ?: 0),
                energy = bookend?.energy ?: 0,
                emotion = bookend?.emotionLabel ?: "",
                wins = wins.map { it.title },
                highlight = bookend?.highlight ?: "",
                gratitude = listOfNotNull(bookend?.good1, bookend?.good2, bookend?.good3).filter { it.isNotBlank() }
                    .ifEmpty { listOfNotNull(bookend?.gratitude).filter { it.isNotBlank() } },
                lesson = bookend?.lesson ?: "",
                reflection = bookend?.pmReflection ?: "",
                themes = shareThemes,
                taskTitles = tasksDone.map { it.title },
                taskCount = tasksDone.size,
                habits = expected.map { h ->
                    val c = checkins.firstOrNull { it.habitId == h.id && it.epochDay == day }
                    val cnt = c?.count ?: 0
                    val target = h.targetPerDay.coerceAtLeast(1)
                    DayCard.HabitLine(
                        name = h.name,
                        kept = c != null && HabitStats.isSuccessDay(h, c),
                        detail = if (target > 1) "$cnt/$target${h.unit?.let { " $it" } ?: ""}" else "",
                    )
                },
                habitsKept = habitsKept.size,
                habitsExpected = habitsExpected,
                activities = tracked.entries.sortedByDescending { it.value }.map { (actId, min) ->
                    DayCard.ActivityLine(activities.firstOrNull { it.id == actId }?.name ?: "—", min)
                },
                trackedMin = trackedTotal,
                questions = questions.mapNotNull { q ->
                    val s = todayScores[q.id] ?: 0
                    if (s in 1..5) DayCard.QuestionLine(q.text, s) else null
                },
                goalsAdvanced = movedGoals.map { "${it.emoji} ${it.name}" },
                valuesHonored = honoredValues.map { v -> (v.emoji?.let { "$it " } ?: "") + v.name },
                tomorrowFocus = bookend?.tomorrowFocus ?: "",
                woopObstacle = bookend?.tomorrowObstacle ?: "",
                woopPlan = bookend?.tomorrowPlan ?: "",
                pattern = sharePattern,
                accentArgb = settings.accentArgb.takeIf { it != 0L },
            )
        }
        val shareCfg = remember(settings.dayShareConfigJson) { DayShareConfigs.parse(settings.dayShareConfigJson) }
        val periodShareCfg = remember(settings.periodShareConfigJson) { PeriodShareConfigs.parse(settings.periodShareConfigJson) }
        // Compute each reviewed period's share data on the fly from the in-scope VM state, so the day share's
        // period selector spans This day · This week · This month · This year through one FileProvider path.
        val periodShareData = remember(
            day, todayEd, dayLogs, questions, habits, checkins, timeEntries, activities, goals, tasks,
            settings.weekStart, settings.accentArgb,
        ) {
            val accent = settings.accentArgb.takeIf { it != 0L }
            val nowMs = System.currentTimeMillis()
            // Week window (anchored to the shown day, capped at today).
            val ws = weekStartOf(date, settings.weekStart)
            val wStart = ws.toEpochDay(); val wEnd = minOf(ws.plusDays(6).toEpochDay(), todayEd)
            val weekRollup = ReviewRollup.compute(wStart, wEnd, dayLogs, questions, habits, checkins, timeEntries, activities, zone, nowMs, goals, tasks)
            val weekExec = ExecutionScore.fromRollup(weekRollup, tasks, zone)
            // Month window (capped at today).
            val mFirst = date.withDayOfMonth(1); val mLast = date.withDayOfMonth(date.lengthOfMonth())
            val mStart = mFirst.toEpochDay(); val mEnd = minOf(mLast.toEpochDay(), todayEd)
            val monthRollup = ReviewRollup.compute(mStart, mEnd, dayLogs, questions, habits, checkins, timeEntries, activities, zone, nowMs, goals, tasks)
            val monthExec = ExecutionScore.fromRollup(monthRollup, tasks, zone)
            // Year window — anchored to the shown day's year (not "today"), so a navigated past year shares
            // the same window as its on-screen roll-up. calendarYearWindow caps the end at today for the
            // current year and returns the full Jan–Dec span for a past year.
            val (yStart, yEnd) = YearReviewed.calendarYearWindow(date.year, todayEd)
            val yearRecap = YearReviewed.compute(yStart, yEnd, dayLogs, habits, checkins, timeEntries, activities, zone, nowMs, tasks)
            mapOf(
                DayCard.PeriodKind.WEEK to periodDataFromRollup(weekLabel(ws, ws.plusDays(6)), weekRollup, weekExec, shareThemesFor(wStart, wEnd, dayLogs), accent),
                DayCard.PeriodKind.MONTH to periodDataFromRollup(mFirst.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + mFirst.year, monthRollup, monthExec, shareThemesFor(mStart, mEnd, dayLogs), accent),
                DayCard.PeriodKind.YEAR to periodDataFromYear(yearRecap, shareThemesFor(yStart, yEnd, dayLogs), accent, date.year.toString()),
            )
        }
        if (showShare) ShareDialog(
            initialPeriod = sharePreselect,
            dayInitial = shareCfg,
            dayData = shareData,
            periodInitial = periodShareCfg,
            periodData = periodShareData,
            onPersistDay = { vm.saveDayShareConfig(it) },
            onPersistPeriod = { vm.savePeriodShareConfig(it) },
            onShareDayImage = { cfg ->
                val bmp = DayCard.renderShare(shareData, cfg)
                val res = ProgressCard.saveAndShareUri(ctx, bmp, "hexis-day-$day.png")
                res.shareUri?.let { ProgressCard.share(ctx, it) }
                showShare = false
            },
            onShareDayText = { cfg ->
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, DayCard.shareText(shareData, cfg))
                }
                runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share my day").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                showShare = false
            },
            onSharePeriodImage = { cfg, kind ->
                periodShareData[kind]?.let { pd ->
                    runCatching {
                        val bmp = DayCard.renderPeriodShare(pd, cfg, kind)
                        val res = ProgressCard.saveAndShareUri(ctx, bmp, "hexis-${kind.name.lowercase(Locale.getDefault())}-$day.png")
                        res.shareUri?.let { ProgressCard.share(ctx, it) }
                    }
                }
                showShare = false
            },
            onSharePeriodText = { cfg, kind ->
                periodShareData[kind]?.let { pd ->
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, DayCard.periodShareText(pd, cfg, kind))
                    }
                    runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                showShare = false
            },
            onDismiss = { showShare = false },
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
                currentCount = remember(n.id, feed) { vm.accomplishmentCount() }, todayEd = todayEd,
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

        // ── Wave 3 (E) + Track 3.6 — the richer, adaptive rule-based reflection companion (no LLM); saves into
        // the day's reflection field. Signals include energy, a marked win, and whether an obstacle recurred. ──
        if (showCompanion) {
            val obst = (bookend?.tomorrowObstacle ?: "").trim()
            val obstacleRecurred = obst.length >= 4 &&
                dayLogs.count { it.epochDay != day && it.tomorrowObstacle.trim().equals(obst, ignoreCase = true) } >= 1
            ReflectionCompanionDialog(
                signals = ReflectionCompanion.Signals(
                    rating = bookend?.dayRating ?: 0, mood = bookend?.pmMood ?: 0, energy = bookend?.energy ?: 0,
                    emotionLabel = bookend?.emotionLabel ?: "", obstacleRecurred = obstacleRecurred, wasWin = wins.isNotEmpty(),
                ),
                existingReflection = bookend?.pmReflection ?: "",
                onDismiss = { showCompanion = false },
                onSave = { merged -> vm.saveEveningReflection(day, merged, bookend?.pmMood ?: 0); showCompanion = false },
            )
        }

        // ── Wave 3 (B) — the fully-local "Year, reviewed" recap, opened from the Month roll-up. ──
        if (showYear) YearReviewedScreen(
            anchorDay = day, todayEd = todayEd, zone = zone,
            dayLogs = dayLogs, habits = habits, checkins = checkins, timeEntries = timeEntries, activities = activities,
            accentArgb = settings.accentArgb.takeIf { it != 0L },
            periodShareCfg = periodShareCfg,
            onBack = { showYear = false },
        )
    }
}

@Composable
private fun ReflectLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

// SectionTitle, StatTile, DoneTick, OpenTick, MeterRow, Metric, MetricTile, MetricTileGrid and MiniCheck
// now live in the shared ui/components/ReviewComponents.kt so every review surface uses one set.

/** Phase C — a tappable 1–5 effort selector for a Daily Question, filled up to the chosen score.
 *  Mirrors the energy ◆ row's diamond idiom; a score of 0 means "not scored yet". */
@Composable
internal fun ScorePips(score: Int, onPick: (Int) -> Unit) {
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

/** Phase E — a wrapping multi-select chip row (goals advanced / values honored), tapped in the close
 *  flow's align step. Mirrors the app's single-select OptionChips idiom with Material3 FilterChips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectableChips(items: List<Pair<String, String>>, selected: Set<String>, onToggle: (String) -> Unit) {
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
internal fun EmotionPicker(selected: String, onSelect: (String) -> Unit) {
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

// EmotionChip now lives in the shared ui/components/ReviewComponents.kt.

/** Phase E — how many of the ranked values to surface as "top values" in the close flow's align step. */
private const val TOP_VALUES = 5

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
    execScore: ExecutionScore.Score,
    lastWeekFocus: String,
    openTasks: List<TaskEntity>,
    existing: WeeklyReview?,
    onSaveRollover: (carryIds: List<String>, letGoIds: List<String>) -> Unit,
    onSave: (reflection: String, nextFocus: String, areas: List<String>, lens: String, lensAnswers: Map<String, String>, focusRating: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var reflection by remember { mutableStateOf(existing?.reflection ?: "") }
    var nextFocus by remember { mutableStateOf(existing?.nextFocus ?: "") }
    var areas by remember { mutableStateOf(existing?.areas?.toSet() ?: emptySet()) }
    var letGo by remember { mutableStateOf(setOf<String>()) }
    var rolledOver by remember { mutableStateOf(false) }
    // Track 2.4 — the chosen retrospective lens ("" = free-text reflection) and its per-field answers.
    var lens by remember { mutableStateOf(existing?.lens ?: "") }
    var lensAnswers by remember { mutableStateOf(existing?.lensAnswers ?: emptyMap()) }
    // Track 2.5 — how last week's focus went, self-rated this week (0 none · 1 missed · 2 partly · 3 nailed).
    var focusRating by remember { mutableIntStateOf(existing?.focusRating ?: 0) }

    val steps = remember(openTasks.isEmpty()) {
        buildList {
            if (openTasks.isNotEmpty()) add("clear")
            add("current"); add("creative"); add("roles"); add("done")
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
            val totalSteps = (steps.size - 1).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until totalSteps).forEach { i ->
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (stepId == "done" || i <= idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                }
            }
            // Hero header + step body share one scroll region between the pinned progress bar and action
            // bar, so the flow can never overflow the screen (small screens / keyboard up).
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(24.dp))
                Text(when (stepId) { "clear" -> "🧹"; "current" -> "📊"; "creative" -> "🌱"; "roles" -> "⚖️"; else -> "🎉" }, style = MaterialTheme.typography.displaySmall)
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
                    // Track 2.5 — close last week's loop before taking stock of this one.
                    if (lastWeekFocus.isNotBlank()) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)).padding(12.dp)) {
                            Text("Last week you wanted to focus on:", style = MaterialTheme.typography.labelMedium, color = muted)
                            Text("“$lastWeekFocus”", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                            Text("How did that go?", style = MaterialTheme.typography.labelMedium, color = muted)
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1 to "Missed it", 2 to "Partly", 3 to "Nailed it").forEach { (v, lbl) ->
                                    FilterChip(selected = focusRating == v, onClick = { focusRating = if (focusRating == v) 0 else v }, label = { Text(lbl) })
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    // Track 2.1 — the execution score, framed as a lead measure vs the 85% target.
                    if (execScore.hasData) {
                        ExecutionScoreCard(execScore)
                        Spacer(Modifier.height(14.dp))
                    }
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
                    // Track 2.4 — pick a retrospective lens, or free-write (the default). A light chip row.
                    Text("Reflect your way — free-write, or pick a lens.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                    val lensChips = remember { listOf("" to "✍️ Free write") + RetroLens.ALL.map { it.id to "${it.emoji} ${it.title}" } }
                    SelectableChips(lensChips, setOf(lens)) { id -> lens = id }
                    Spacer(Modifier.height(12.dp))
                    val chosen = RetroLens.byId(lens)
                    if (chosen == null) {
                        AppTextField(reflection, { reflection = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("What to try or change") }, minLines = 3)
                    } else {
                        chosen.fields.forEachIndexed { i, f ->
                            if (i > 0) Spacer(Modifier.height(8.dp))
                            Text(f.label, style = MaterialTheme.typography.labelMedium, color = muted)
                            Spacer(Modifier.height(2.dp))
                            AppTextField(
                                lensAnswers[f.id] ?: "", { v -> lensAnswers = lensAnswers.toMutableMap().apply { this[f.id] = v } },
                                modifier = Modifier.fillMaxWidth(), placeholder = { Text(f.hint) }, minLines = 2,
                            )
                        }
                    }
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
                        if (nextIsDone) onSave(reflection, nextFocus, areas.toList(), lens, lensAnswers, focusRating)
                        idx++
                    }) { Text(if (nextIsDone) "Finish review" else "Next") }
                }
            }
        }
    }
}

/**
 * Coherence Move 7 — the reflection roll-up for every non-DAY period of the shared switcher (Week ·
 * Month · Year · All). A period navigator (‹ label ›, honoring the week-start setting) over read-only
 * aggregate cards computed by [ReviewRollup] from the same day logs, habits, check-ins and tracked time
 * the Day view already holds — the window comes from [PeriodRange.window], so it folds identically for a
 * week, a month, a calendar year or all-time. Each card renders only when it has data, and reuses the
 * day-review idioms (AppCard, SectionTitle, MeterRow, ScoreSparkline). Every current period is capped at
 * today, matching the Recap screen's "this week / this month" semantics.
 */
@Composable
private fun RangeRollup(
    mode: PeriodRange,
    anchor: Long,
    todayEd: Long,
    zone: ZoneId,
    weekStartSetting: Int,
    dayLogs: List<com.wkhan.hexis.data.entity.DayLogEntity>,
    questions: List<DailyQuestion>,
    habits: List<com.wkhan.hexis.data.entity.HabitEntity>,
    checkins: List<com.wkhan.hexis.data.entity.HabitCheckinEntity>,
    timeEntries: List<com.wkhan.hexis.data.entity.TimeEntryEntity>,
    activities: List<com.wkhan.hexis.data.entity.TimeActivityEntity>,
    goals: List<Goal>,
    tasks: List<TaskEntity>,
    weeklyReviewsJson: String,
    onStartWeeklyReview: (isoWeek: String) -> Unit,
    onAnchorChange: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenYearReview: () -> Unit,
    onSharePeriod: (SharePeriod) -> Unit,
) {
    val date = LocalDate.ofEpochDay(anchor)
    val today = LocalDate.ofEpochDay(todayEd)

    // Resolve the window (via the shared [PeriodRange.window]) plus the label, relative caption, the
    // prev/next anchors and whether stepping is possible for this period. A period noun feeds the copy
    // ("Themes this week / month / year / …") so it reads naturally for every span, all-time included.
    val win = mode.window(anchor, weekStartSetting, todayEd)
    val start = win.startDay
    val end = win.endDay
    val label: String
    val relative: String
    val prevAnchor: Long
    val nextAnchor: Long
    val canPrev: Boolean
    val canNext: Boolean
    val periodNoun: String
    when (mode) {
        PeriodRange.WEEK -> {
            val ws = weekStartOf(date, weekStartSetting)
            val we = ws.plusDays(6)
            val curWs = weekStartOf(today, weekStartSetting)
            label = weekLabel(ws, we)
            relative = when (ws) { curWs -> "This week"; curWs.minusWeeks(1) -> "Last week"; else -> "" }
            prevAnchor = anchor - 7
            nextAnchor = anchor + 7
            canPrev = true
            canNext = ws < curWs
            periodNoun = "week"
        }
        PeriodRange.MONTH -> {
            val first = date.withDayOfMonth(1)
            val curFirst = today.withDayOfMonth(1)
            label = first.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + first.year
            relative = when (first) { curFirst -> "This month"; curFirst.minusMonths(1) -> "Last month"; else -> "" }
            prevAnchor = first.minusMonths(1).toEpochDay()
            nextAnchor = first.plusMonths(1).toEpochDay()
            canPrev = true
            canNext = first < curFirst
            periodNoun = "month"
        }
        PeriodRange.YEAR -> {
            val first = date.withDayOfYear(1)
            label = date.year.toString()
            relative = when (date.year) { today.year -> "This year"; today.year - 1 -> "Last year"; else -> "" }
            prevAnchor = first.minusYears(1).toEpochDay()
            nextAnchor = first.plusYears(1).toEpochDay()
            canPrev = true
            canNext = date.year < today.year
            periodNoun = "year"
        }
        else -> { // ALL — an all-time window; there is no earlier/later period to step to.
            label = "All time"
            relative = "All-time"
            prevAnchor = anchor
            nextAnchor = anchor
            canPrev = false
            canNext = false
            periodNoun = "period"
        }
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
        IconButton(onClick = { if (canPrev) onAnchorChange(prevAnchor) }, enabled = canPrev) { Icon(Icons.Filled.ChevronLeft, "Previous $periodNoun") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (relative.isNotBlank()) Text(relative, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { if (canNext) onAnchorChange(nextAnchor) }, enabled = canNext) { Icon(Icons.Filled.ChevronRight, "Next $periodNoun") }
        // Share this period through the unified, modular share flow (preselected to this period), so it
        // gets the modern marks + boxed tiles and the Personal / Professional option — routed through
        // DayCard.renderPeriodShare via the shared FileProvider path. Week / Month / Year have a card
        // renderer; the all-time span has no dedicated card, so it simply omits the share affordance.
        val sharePeriod = when (mode) {
            PeriodRange.WEEK -> SharePeriod.WEEK
            PeriodRange.MONTH -> SharePeriod.MONTH
            PeriodRange.YEAR -> SharePeriod.YEAR
            else -> null
        }
        if (rollup.hasData && sharePeriod != null) {
            IconButton(onClick = { onSharePeriod(sharePeriod) }) {
                Icon(Icons.Filled.Share, "Share $periodNoun")
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    // ── Wave 1 — enter the guided Weekly Review (Week roll-up only) ──
    if (mode == PeriodRange.WEEK) {
        val weekIso = WeeklyReviews.isoWeekKey(LocalDate.ofEpochDay(start))
        val reviewed = WeeklyReviews.isReviewed(weeklyReviewsJson, weekIso)
        FilledTonalButton(onClick = { onStartWeeklyReview(weekIso) }, modifier = Modifier.fillMaxWidth()) {
            if (reviewed) {
                // "Reviewed" reads with the app's modern completion mark, not a raw "✓".
                Text("🗓️  Weekly review")
                Spacer(Modifier.width(8.dp))
                MiniCheck()
                Spacer(Modifier.width(8.dp))
                Text("reopen")
            } else Text("🗓️  Start weekly review")
        }
        Spacer(Modifier.height(12.dp))
    }

    // ── Wave 3 (B) — the fully-local "Year, reviewed" recap (Month / Year roll-ups) ──
    if (mode == PeriodRange.MONTH || mode == PeriodRange.YEAR) {
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
        // Track 3.3 — the period in three recurring words, extracted on-device from your own reflections.
        val themeWords = remember(start, end, dayLogs) {
            val docs = dayLogs.asSequence().filter { it.epochDay in start..end }.map { l ->
                listOf(l.pmReflection, l.highlight, l.gratitude, l.lesson, l.good1, l.good2, l.good3, l.promptAnswer, l.amIntention)
                    .filter { it.isNotBlank() }.joinToString(" ")
            }.filter { it.isNotBlank() }.toList()
            TextInsights.threeWords(docs)
        }
        if (themeWords.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Themes this $periodNoun", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(themeWords.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }

    // ── 1·2.1 — Execution score (Week only): a lead measure of planned commitments vs done, vs the 85% target ──
    if (mode == PeriodRange.WEEK) {
        val execScore = remember(rollup, tasks) { ExecutionScore.fromRollup(rollup, tasks, zone) }
        if (execScore.hasData) {
            Spacer(Modifier.height(12.dp))
            ExecutionScoreCard(execScore)
        }
    }

    // ── 1a. How your days felt: rating + evening mood, rendered through the shared FeltReadout (Track 1), which
    //    reads the roll-up's own [FeltState] summary so the Day Review, the recap and the digest agree. ──
    if (rollup.ratedDays > 0 || rollup.moodCount > 0) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("How your days felt")
            FeltReadout(rollup.felt)
        }
    }

    if (!rollup.hasData) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            Text("Nothing to roll up in this $periodNoun yet — close a few days and your wins, lessons and consistency gather here.",
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
            Text("Effort scores, averaged over the $periodNoun", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    } else if (questions.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("Daily questions")
            Text("No effort scores logged this $periodNoun yet — score your questions when you close a day.",
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

/** Track 2.1 — the weekly execution score card: how much of what you planned you did, against the 85%
 *  lead-measure target, with a progress bar and a target marker. Theme-correct; no cited source. */
@Composable
private fun ExecutionScoreCard(score: ExecutionScore.Score) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val verdict = when (score.verdict) {
        ExecutionScore.Verdict.BELOW -> "Below your ${score.benchmark}% target — a lead measure, not a verdict."
        ExecutionScore.Verdict.ON_TRACK -> "Right around your ${score.benchmark}% target — on track."
        ExecutionScore.Verdict.ABOVE -> "Above your ${score.benchmark}% target. Strong week."
    }
    AppCard {
        SectionTitle("Execution score")
        Text("${score.pct}% of what you planned · target ${score.benchmark}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((score.pct / 100f).coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
            // The 85% target marker.
            Box(Modifier.fillMaxWidth((score.benchmark / 100f).coerceIn(0f, 1f)).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("${score.completed} of ${score.planned} planned — ${score.doneTasks}/${score.plannedTasks} tasks, ${score.keptHabits}/${score.expectedHabits} habits.",
            style = MaterialTheme.typography.labelSmall, color = muted)
        Text(verdict, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 2.dp))
    }
}

// Track 2.7 — a "three good things" entry stores its optional "…and why" inline, joined by GOOD_WHY_SEP,
// so no schema change is needed. These split a stored value back into its parts and rejoin them on save.
private const val GOOD_WHY_SEP = " — "
internal fun goodThingOf(stored: String): String = stored.substringBefore(GOOD_WHY_SEP, stored)
internal fun goodWhyOf(stored: String): String {
    val i = stored.indexOf(GOOD_WHY_SEP)
    return if (i >= 0) stored.substring(i + GOOD_WHY_SEP.length) else ""
}
internal fun joinGoodWhy(thing: String, why: String): String {
    val t = thing.trim()
    val w = why.trim()
    return if (t.isBlank()) "" else if (w.isBlank()) t else "$t$GOOD_WHY_SEP$w"
}

private fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

internal fun formatHm(m: Int): String = com.wkhan.hexis.util.formatMinutes(m)

internal fun moodFace(v: Int): String = when (v.coerceIn(0, 5)) { 1 -> "😞"; 2 -> "🙁"; 3 -> "😐"; 4 -> "🙂"; 5 -> "😄"; else -> "😐" }

/** A compact week label: "1–7 Sep", or "28 Aug – 3 Sep" when the week straddles two months. */
internal fun weekLabel(a: LocalDate, b: LocalDate): String {
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
        if (r.tasksDone > 0) add("• ${r.tasksDone}")
        r.topActivityName?.let { add((r.topActivityEmoji?.let { e -> "$e " } ?: "") + it) }
    }
    return parts.joinToString("  ·  ")
}
