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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.CoreValueEntity
import com.todocompanion.app.domain.DailyQuestion
import com.todocompanion.app.domain.DailyQuestions
import com.todocompanion.app.domain.DayAlignment
import com.todocompanion.app.domain.DayAlignments
import com.todocompanion.app.domain.DayPrompts
import com.todocompanion.app.domain.Goal
import com.todocompanion.app.domain.Goals
import com.todocompanion.app.domain.ReviewRollup
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
fun DayReviewScreen(vm: AppViewModel, initialDay: Long, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
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
    val reviewStreak = remember(reviewedDays) {
        var s = 0; var d0 = if (todayEd in reviewedDays) todayEd else todayEd - 1
        while (d0 in reviewedDays) { s++; d0-- }
        s
    }

    // Reckon + Ready are anchored to *today* (carrying forward / planning a past day makes no sense).
    val openTasks = remember(tasks, isToday) { if (isToday) FourthWave.shutdownCarryForward(tasks, todayEd, zone) else emptyList() }
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
                    timeEntries = timeEntries, activities = activities, goals = goals,
                    onAnchorChange = { day = it },
                    onOpenDay = { d -> day = d; mode = ReviewRange.DAY },
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
                // Review-streak: a "don't break the chain" strip of the last 14 days.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (reviewStreak > 0) "🔥 $reviewStreak-day streak" else "Reviewed days",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (reviewStreak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        (13 downTo 0).forEach { back ->
                            val d0 = todayEd - back
                            val on = d0 in reviewedDays
                            Box(Modifier.size(if (d0 == day) 13.dp else 11.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                        }
                    }
                }
            }

            // ── Close the day: the guided ritual (Recall → Feel → Reflect → Tomorrow → done) ──
            Spacer(Modifier.height(12.dp))
            val closedToday = day in reviewedDays
            FilledTonalButton(onClick = { showClose = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (closedToday) "🌙  Review the close" else "🌙  Close the day")
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
                    habitsKept.forEach { (h, c) ->
                        val target = h.targetPerDay.coerceAtLeast(1)
                        MeterRow(
                            leading = { Text(h.emoji ?: "🔁") },
                            name = h.name,
                            trailing = if (target > 1) "$c/$target${h.unit?.let { " $it" } ?: ""}" else "done",
                            frac = (c.toFloat() / target).coerceIn(0f, 1f),
                            color = tertiary,
                        )
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
                    tracked.entries.sortedByDescending { it.value }.forEach { (actId, min) ->
                        val a = activities.firstOrNull { it.id == actId }
                        val col = a?.colorArgb?.let { Color(it) } ?: fallback
                        MeterRow(
                            leading = { if (a?.emoji != null) Text(a.emoji!!) else Box(Modifier.size(12.dp).clip(CircleShape).background(col)) },
                            name = a?.name ?: "—",
                            trailing = fmtHm(min),
                            frac = min / maxMin.toFloat(),
                            color = col,
                        )
                    }
                }
            }

            // ── Reckon: what's still open today ──
            if (isToday && (openTasks.isNotEmpty() || missedHabits.isNotEmpty())) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("Didn't get to")
                    openTasks.take(12).forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenTask(t.id) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) { OpenTick() }
                            Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
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
                        FilledTonalButton(onClick = { vm.carryForwardTasks(openTasks.map { it.id }) }) {
                            Text("Carry ${openTasks.size} to tomorrow & close the day")
                        }
                    }
                }
            }

            // ── Reflect ──
            Spacer(Modifier.height(12.dp))
            AppCard {
                SectionTitle("Reflect")
                // One-tap mood — sets today's evening mood immediately, mirroring the tappable rating stars below.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(1 to "😞", 2 to "🙁", 3 to "😐", 4 to "🙂", 5 to "😄").forEach { (v, e) ->
                        val sel = (bookend?.pmMood ?: 0) == v
                        Text(
                            e, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.clip(CircleShape).clickable {
                                val m = if ((bookend?.pmMood ?: 0) == v) 0 else v
                                vm.saveEveningReflection(day, bookend?.pmReflection ?: "", m)
                            }.background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                // Rating (tappable) — how the day felt overall.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    (1..5).forEach { i ->
                        val filled = (bookend?.dayRating ?: 0) >= i
                        Text(
                            if (filled) "★" else "☆",
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clip(CircleShape).clickable {
                                val r = if ((bookend?.dayRating ?: 0) == i) 0 else i
                                vm.saveDayReflect(day, r, bookend?.energy ?: 0, bookend?.highlight ?: "", bookend?.gratitude ?: "", bookend?.lesson ?: "")
                            }.padding(horizontal = 3.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("How was today?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            goods.forEach { g -> Text("✓ $g", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 1.dp)) }
                        }
                        if (it.promptAnswer.isNotBlank()) {
                            Text(DayPrompts.promptFor(day), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
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
    )

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
    // Phase B — reflection-depth state.
    var good1 by remember { mutableStateOf(log?.good1 ?: "") }
    var good2 by remember { mutableStateOf(log?.good2 ?: "") }
    var good3 by remember { mutableStateOf(log?.good3 ?: "") }
    var promptAnswer by remember { mutableStateOf(log?.promptAnswer ?: "") }
    var intentionOutcome by remember { mutableIntStateOf(log?.intentionOutcome ?: 0) }
    // Phase C — the day's Daily-Question scores, edited in-flow and persisted immediately on each tap.
    var scores by remember { mutableStateOf(initialScores) }
    // Phase E — the goals today advanced and the top values it honored, chosen in the "align" step.
    var movedGoalIds by remember { mutableStateOf(initialAlignment.movedGoalIds.toSet()) }
    var honoredValueIds by remember { mutableStateOf(initialAlignment.honoredValueIds.toSet()) }
    val amIntention = log?.amIntention?.trim().orEmpty()
    val prompt = remember(day) { DayPrompts.promptFor(day) }

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

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (stepId == "done") TextButton(onClick = onDismiss) { Text("Done") }
            else {
                val nextIsDone = steps[idx + 1] == "done"
                TextButton(onClick = {
                    if (nextIsDone) {
                        onSave(rating, energy, reflection, mood, highlight, if (full) gratitude else "", if (full) lesson else "", tomorrow)
                        onSaveExtras(if (full) good1 else "", if (full) good2 else "", if (full) good3 else "", intentionOutcome, if (full) promptAnswer else "")
                        // Express never shows the align step, so leave any recorded alignment untouched there.
                        if (full) onSaveAlignment(movedGoalIds.toList(), honoredValueIds.toList())
                    }
                    idx++
                }) { Text(if (nextIsDone) "Close the day" else "Next") }
            }
        },
        dismissButton = {
            if (stepId != "done") TextButton(onClick = { if (idx > 0) idx-- else onDismiss() }) { Text(if (idx > 0) "Back" else "Cancel") }
        },
        title = {
            Text(when (stepId) {
                "recall" -> "Recall your day"; "feel" -> "How did it feel?"; "questions" -> "Daily questions"; "reflect" -> "Reflect"
                "align" -> "Align"; "tomorrow" -> "Ready for tomorrow"; else -> "Day closed"
            })
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (stepId != "done") Text("Step ${idx + 1} of ${steps.size - 1}", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(bottom = 8.dp))
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
                            Text(prompt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DoneTick(); Spacer(Modifier.width(10.dp))
                            Text("The day is closed.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("🔥 Reviewed $streak day${if (streak == 1) "" else "s"} in a row. Rest well.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReflectDialog(
    day: Long, isToday: Boolean, log: com.todocompanion.app.data.entity.DayLogEntity?,
    onDismiss: () -> Unit,
    onSave: (rating: Int, energy: Int, reflection: String, mood: Int, highlight: String, gratitude: String, lesson: String, tomorrow: String) -> Unit,
    onSaveExtras: (good1: String, good2: String, good3: String, intentionOutcome: Int, promptAnswer: String) -> Unit,
) {
    var rating by remember { mutableIntStateOf(log?.dayRating ?: 0) }
    var energy by remember { mutableIntStateOf(log?.energy ?: 0) }
    var pmMood by remember { mutableIntStateOf(log?.pmMood ?: 0) }
    var reflection by remember { mutableStateOf(log?.pmReflection ?: "") }
    var highlight by remember { mutableStateOf(log?.highlight ?: "") }
    var gratitude by remember { mutableStateOf(log?.gratitude ?: "") }
    var lesson by remember { mutableStateOf(log?.lesson ?: "") }
    var tomorrow by remember { mutableStateOf(log?.tomorrowFocus ?: "") }
    // Phase B — keep parity with the guided close: three good things + the day's rotating prompt.
    var good1 by remember { mutableStateOf(log?.good1 ?: "") }
    var good2 by remember { mutableStateOf(log?.good2 ?: "") }
    var good3 by remember { mutableStateOf(log?.good3 ?: "") }
    var promptAnswer by remember { mutableStateOf(log?.promptAnswer ?: "") }
    val intentionOutcome = log?.intentionOutcome ?: 0 // preserved as-is (set from the guided close's after-action step)
    val prompt = remember(day) { DayPrompts.promptFor(day) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(rating, energy, reflection, pmMood, highlight, gratitude, lesson, tomorrow)
                onSaveExtras(good1, good2, good3, intentionOutcome, promptAnswer)
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
                Text(prompt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                AppTextField(promptAnswer, { promptAnswer = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Your answer") }, singleLine = true)
                if (isToday) {
                    Spacer(Modifier.height(6.dp))
                    AppTextField(tomorrow, { tomorrow = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🎯 The one thing that matters tomorrow") }, singleLine = true)
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
    onAnchorChange: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
) {
    val date = LocalDate.ofEpochDay(anchor)
    val today = LocalDate.ofEpochDay(todayEd)

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

    val rollup = remember(mode, start, end, dayLogs, questions, habits, checkins, timeEntries, activities, goals) {
        ReviewRollup.compute(start, end, dayLogs, questions, habits, checkins, timeEntries, activities, zone, System.currentTimeMillis(), goals)
    }

    // ── Period navigator (mirrors the day navigator) ──
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onAnchorChange(prevAnchor) }) { Icon(Icons.Filled.ChevronLeft, "Previous ${mode.label.lowercase()}") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (relative.isNotBlank()) Text(relative, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { if (canNext) onAnchorChange(nextAnchor) }, enabled = canNext) { Icon(Icons.Filled.ChevronRight, "Next ${mode.label.lowercase()}") }
    }
    Spacer(Modifier.height(12.dp))

    // ── 1. At-a-glance header ──
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${rollup.reviewedDays} of ${rollup.periodDays} days reviewed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (rollup.ratedDays > 0) Text("Average rating ${oneDp(rollup.avgRating)} · ${rollup.ratedDays} rated", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rollup.avgRating > 0) {
                val r = rollup.avgRating.roundToInt()
                Row {
                    (1..5).forEach { i ->
                        Text(if (i <= r) "★" else "☆", style = MaterialTheme.typography.titleMedium,
                            color = if (i <= r) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        if (rollup.ratingTrend.any { it != null }) {
            Spacer(Modifier.height(10.dp))
            Text("Rating trend", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            ScoreSparkline(rollup.ratingTrend, Modifier.fillMaxWidth())
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
    }

    // ── 7. "Your best days share…" — descriptive, computed on-device ──
    if (rollup.correlations.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard {
            SectionTitle("Your best days share…")
            rollup.correlations.forEach { c ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•", Modifier.width(16.dp), color = MaterialTheme.colorScheme.primary)
                    Text(c, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("A pattern in your reviews, not a cause — just what your higher-rated days had in common.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

private fun formatHm(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

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
