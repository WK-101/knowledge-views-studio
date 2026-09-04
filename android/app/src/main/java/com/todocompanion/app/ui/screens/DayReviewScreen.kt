package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.todocompanion.app.domain.calendar.CalendarEngine
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.DoneRecord
import com.todocompanion.app.domain.habit.FourthWave
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.AppTextField
import com.todocompanion.app.ui.components.DateOnlyPickerDialog
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

    val tasks by vm.tasks.collectAsState()
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val activities by vm.timeActivities.collectAsState()
    val events by vm.events.collectAsState()
    val dayLogs by vm.dayLogs.collectAsState()
    val settings by vm.settings.collectAsState()

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

    // At-a-glance context: vs your usual, and the review streak.
    val avg7 = remember(tasks, day) {
        val since = dayStart - 7L * 86_400_000L
        tasks.count { it.completed && !it.trashed && it.completedAt != null && it.completedAt!! in since until dayStart } / 7.0
    }
    val reviewStreak = remember(dayLogs) {
        val reviewed = dayLogs.filter { it.pmReflection.isNotBlank() || it.dayRating > 0 || it.amIntention.isNotBlank() }
            .map { it.epochDay }.toHashSet()
        var s = 0; var d0 = if (todayEd in reviewed) todayEd else todayEd - 1
        while (d0 in reviewed) { s++; d0-- }
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
                if (reviewStreak > 0) {
                    Spacer(Modifier.height(if (nothing) 8.dp else 12.dp))
                    Text("🔥 Reviewed $reviewStreak day${if (reviewStreak == 1) "" else "s"} in a row",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
                val hasProse = bookend != null && (bookend.pmReflection.isNotBlank() || bookend.highlight.isNotBlank() || bookend.gratitude.isNotBlank() || bookend.lesson.isNotBlank())
                if (bookend?.amIntention?.isNotBlank() == true) {
                    Row(Modifier.padding(bottom = 4.dp)) { Text("🌅 ${mood(bookend.amMood)}", Modifier.width(48.dp)); Text(bookend.amIntention, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
                }
                if (hasProse) {
                    bookend!!.let {
                        if (it.pmReflection.isNotBlank()) Row(Modifier.padding(vertical = 2.dp)) { Text("🌙 ${mood(it.pmMood)}", Modifier.width(48.dp)); Text(it.pmReflection, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
                        if (it.highlight.isNotBlank()) ReflectLine("✨ Highlight", it.highlight)
                        if (it.gratitude.isNotBlank()) ReflectLine("🙏 Grateful for", it.gratitude)
                        if (it.lesson.isNotBlank()) ReflectLine("💡 Lesson", it.lesson)
                        if (it.energy > 0) Text("Energy: ${"◆".repeat(it.energy)}${"◇".repeat(5 - it.energy)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { showReflect = true }) { Text("Edit reflection") }
                } else {
                    Text("Close the day in a few words — how it went, a highlight, something you're grateful for, one lesson.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { showReflect = true }) { Text("Reflect on today") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReflectDialog(
    day: Long, isToday: Boolean, log: com.todocompanion.app.data.entity.DayLogEntity?,
    onDismiss: () -> Unit,
    onSave: (rating: Int, energy: Int, reflection: String, mood: Int, highlight: String, gratitude: String, lesson: String, tomorrow: String) -> Unit,
) {
    var rating by remember { mutableIntStateOf(log?.dayRating ?: 0) }
    var energy by remember { mutableIntStateOf(log?.energy ?: 0) }
    var pmMood by remember { mutableIntStateOf(log?.pmMood ?: 0) }
    var reflection by remember { mutableStateOf(log?.pmReflection ?: "") }
    var highlight by remember { mutableStateOf(log?.highlight ?: "") }
    var gratitude by remember { mutableStateOf(log?.gratitude ?: "") }
    var lesson by remember { mutableStateOf(log?.lesson ?: "") }
    var tomorrow by remember { mutableStateOf(log?.tomorrowFocus ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(rating, energy, reflection, pmMood, highlight, gratitude, lesson, tomorrow) }) { Text("Save") } },
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
                if (isToday) {
                    Spacer(Modifier.height(6.dp))
                    AppTextField(tomorrow, { tomorrow = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("🎯 The one thing that matters tomorrow") }, singleLine = true)
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
