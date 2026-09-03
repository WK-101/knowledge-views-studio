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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.calendar.CalendarEngine
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.DoneRecord
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.DateOnlyPickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * R66 — the end-of-day review: one date-selectable page that gathers everything about a single day —
 * what you finished, your wins, the habits you kept, the day's events, the time you tracked, and the
 * morning-intention / evening-reflection bookends — so a day closes on a record, at a glance. Step with
 * ‹ ›, jump to any date, or tap Today. Entirely on-device and workspace-scoped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayReviewScreen(vm: AppViewModel, initialDay: Long, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val zone = ZoneId.systemDefault()
    val todayEd = LocalDate.now(zone).toEpochDay()
    var day by remember { mutableLongStateOf(initialDay) }
    var showPicker by remember { mutableStateOf(false) }

    val tasks by vm.tasks.collectAsState()
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val activities by vm.timeActivities.collectAsState()
    val events by vm.events.collectAsState()
    val dayLogs by vm.dayLogs.collectAsState()

    val date = LocalDate.ofEpochDay(day)
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()

    // What got done — the accomplishment feed, filtered to this day.
    val feed = remember(tasks, habits, checkins, timeEntries, day) {
        DoneRecord.build(tasks, habits, checkins, timeEntries, zone).filter { it.epochDay == day }
    }
    val tasksDone = tasks.filter { it.completed && it.completedAt != null && it.completedAt!! in dayStart until dayEnd && !it.trashed }
        .sortedByDescending { it.completedAt }
    val wins = feed.filter { it.isWin && it.isTaskLike }
    val focusMin = feed.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin }

    // Habits kept — expected that day and meeting their goal.
    val habitsKept = habits.filter { !it.archived && HabitStats.isExpectedDay(it, day) }
        .mapNotNull { h ->
            val c = checkins.firstOrNull { it.habitId == h.id && it.epochDay == day }?.count ?: 0
            if (HabitStats.meetsGoal(h, c)) h to c else null
        }
    val habitsExpected = habits.count { !it.archived && HabitStats.isExpectedDay(it, day) }

    // Events on this day (recurrence expanded).
    val occ = remember(events, day) {
        CalendarEngine.expand(events, dayStart, dayEnd, zone).sortedBy { it.startMillis }
    }

    // Time tracked per activity on this day (minutes overlapping the day window).
    val tracked = remember(timeEntries, day) {
        timeEntries.groupBy { it.activityId }.mapValues { (_, es) ->
            es.sumOf { com.todocompanion.app.domain.TimeTracking.minutesInWindow(it.startMillis, it.endMillis, dayStart, dayEnd, now) }
        }.filterValues { it > 0 }
    }
    val trackedTotal = tracked.values.sum()

    val bookend = dayLogs.firstOrNull { it.epochDay == day }

    val nothing = tasksDone.isEmpty() && habitsKept.isEmpty() && occ.isEmpty() && tracked.isEmpty() &&
        focusMin == 0 && (bookend == null || (bookend.amIntention.isBlank() && bookend.pmReflection.isBlank() && bookend.amMood == 0 && bookend.pmMood == 0))

    fun fmtHm(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    fun timeLabel(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    fun mood(v: Int) = when (v) { 1 -> "😞"; 2 -> "🙁"; 3 -> "😐"; 4 -> "🙂"; 5 -> "😄"; else -> "" }

    Scaffold(topBar = {
        TopAppBar(
            expandedHeight = 52.dp,
            title = { Text("Day review") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (day != todayEd) TextButton(onClick = { day = todayEd }) { Text("Today") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Date navigator.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day -= 1 }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
                val rel = when (day) { todayEd -> "Today"; todayEd - 1 -> "Yesterday"; else -> "" }
                val label = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", " +
                    date.dayOfMonth + " " + date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                Column(
                    Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable { showPicker = true }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (rel.isNotBlank()) Text(rel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { if (day < todayEd) day += 1 }, enabled = day < todayEd) { Icon(Icons.Filled.ChevronRight, "Next day") }
            }
            Spacer(Modifier.height(12.dp))

            if (nothing) {
                AppCard {
                    Text("A quiet day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Nothing was recorded for this day. Completed tasks, habits, events and tracked time will gather here.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            // Summary strip.
            AppCard {
                Text(buildString {
                    append("✓ ${tasksDone.size} done")
                    if (habitsExpected > 0) append("   ·   🔁 ${habitsKept.size}/$habitsExpected habits")
                    if (focusMin > 0) append("   ·   🎯 ${fmtHm(focusMin)} focus")
                    if (trackedTotal > 0) append("   ·   ⧗ ${fmtHm(trackedTotal)} tracked")
                }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }

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
                        Row(Modifier.fillMaxWidth().clickable { onOpenTask(t.id) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("✓", Modifier.width(24.dp), color = MaterialTheme.colorScheme.primary)
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
                    SectionTitle("Habits kept · ${habitsKept.size}")
                    habitsKept.forEach { (h, c) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(h.emoji ?: "🔁", Modifier.width(24.dp))
                            Text(h.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h.targetPerDay > 1) Text("$c/${h.targetPerDay}${h.unit?.let { " $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
                    tracked.entries.sortedByDescending { it.value }.forEach { (actId, min) ->
                        val a = activities.firstOrNull { it.id == actId }
                        val c = a?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (a?.emoji != null) Text(a.emoji!!, Modifier.width(24.dp))
                            else Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterStart) { Box(Modifier.size(12.dp).clip(CircleShape).background(c)) }
                            Text(a?.name ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fmtHm(min), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (bookend != null && (bookend.amIntention.isNotBlank() || bookend.pmReflection.isNotBlank() || bookend.amMood > 0 || bookend.pmMood > 0)) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("The day, in your words")
                    if (bookend.amIntention.isNotBlank() || bookend.amMood > 0) {
                        Row(Modifier.padding(top = 2.dp)) {
                            Text("🌅 ${mood(bookend.amMood)}", Modifier.width(48.dp))
                            Text(bookend.amIntention.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (bookend.pmReflection.isNotBlank() || bookend.pmMood > 0) {
                        Row(Modifier.padding(top = 6.dp)) {
                            Text("🌙 ${mood(bookend.pmMood)}", Modifier.width(48.dp))
                            Text(bookend.pmReflection.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
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
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
}
