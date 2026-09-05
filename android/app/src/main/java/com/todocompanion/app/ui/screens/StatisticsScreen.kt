package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.ReviewRollup
import com.todocompanion.app.domain.Trend
import com.todocompanion.app.domain.WeekChanges
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.OptionChips
import com.todocompanion.app.ui.components.StatTile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val tasks by vm.tasks.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val legacyFocus by vm.focusSessions.collectAsState()
    // Focus stats derive from the one timeline (kind="focus" intervals), matching the Time reports.
    val focus = remember(timeEntries, legacyFocus) { vm.focusViews() }
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val dayLogs by vm.dayLogs.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val todayEpoch = today.toEpochDay()

    // Track 2.2 — a real range picker driving the cards, mirroring The Record's OptionChips + STAT_RANGES idiom.
    var range by remember { mutableIntStateOf(30) }
    val rangeDays = if (range == YEAR) today.dayOfYear else range
    val curStart = todayEpoch - (rangeDays - 1)
    val baseStart = todayEpoch - (2 * rangeDays - 1)
    val baseEnd = todayEpoch - rangeDays

    fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    val completed = tasks.filter { it.completed && it.completedAt != null && !it.trashed }
    val habitById = remember(habits) { habits.associateBy { it.id } }

    fun doneIn(s: Long, e: Long) = completed.count { dayOf(it.completedAt!!) in s..e }
    fun focusMinIn(s: Long, e: Long) = focus.filter { it.epochDay in s..e }.sumOf { it.minutes }
    fun habitDaysIn(s: Long, e: Long) = checkins.count { c ->
        c.epochDay in s..e && c.status == "done" && habitById[c.habitId]?.let { HabitStats.meetsGoal(it, c.count) } == true
    }
    fun reviewedIn(s: Long, e: Long) = dayLogs.count { it.epochDay in s..e && ReviewRollup.isReviewed(it) }

    // Track 2.2 — each headline metric drifts against its own baseline (the equal window just before).
    data class Head(val label: String, val value: String, val metric: Trend.Metric)
    val heads = remember(range, tasks, focus, habits, checkins, dayLogs, todayEpoch) {
        fun head(label: String, cur: Double, base: Double, value: String) =
            Head(label, value, Trend.Metric(label, Trend.analyze(cur, base, hasBaseline = true)))
        listOf(
            head("Tasks done", doneIn(curStart, todayEpoch).toDouble(), doneIn(baseStart, baseEnd).toDouble(), doneIn(curStart, todayEpoch).toString()),
            head("Focus time", focusMinIn(curStart, todayEpoch).toDouble(), focusMinIn(baseStart, baseEnd).toDouble(), fmtMin(focusMinIn(curStart, todayEpoch))),
            head("Habit days", habitDaysIn(curStart, todayEpoch).toDouble(), habitDaysIn(baseStart, baseEnd).toDouble(), habitDaysIn(curStart, todayEpoch).toString()),
            head("Days reviewed", reviewedIn(curStart, todayEpoch).toDouble(), reviewedIn(baseStart, baseEnd).toDouble(), reviewedIn(curStart, todayEpoch).toString()),
        )
    }
    val slipping = heads.filter { Trend.isSlipping(it.metric) }
    val steady = heads.filter { !Trend.isSlipping(it.metric) }

    // Track 2.3 — "what changed this week": movers over a 7-day window vs the prior week, plus factor-effects.
    val changes = remember(tasks, focus, habits, checkins, dayLogs, todayEpoch) {
        val cs = todayEpoch - 6; val bs = todayEpoch - 13; val be = todayEpoch - 7
        val wk = listOf(
            Trend.Metric("tasks done", Trend.analyze(doneIn(cs, todayEpoch).toDouble(), doneIn(bs, be).toDouble(), true)),
            Trend.Metric("focus time", Trend.analyze(focusMinIn(cs, todayEpoch).toDouble(), focusMinIn(bs, be).toDouble(), true)),
            Trend.Metric("habit days", Trend.analyze(habitDaysIn(cs, todayEpoch).toDouble(), habitDaysIn(bs, be).toDouble(), true)),
            Trend.Metric("days reviewed", Trend.analyze(reviewedIn(cs, todayEpoch).toDouble(), reviewedIn(bs, be).toDouble(), true)),
        )
        WeekChanges.compute(wk, vm.reviewInsightsFor(todayEpoch - 89, todayEpoch))
    }

    // Track 1.1 — the felt lane follows the selected range now (rating + mood + emotion).
    val felt = remember(dayLogs, curStart, todayEpoch) { vm.feltSummary(curStart, todayEpoch) }

    // Per-day completed bar over the last 7 days (kept as a fixed weekly shape).
    val perDay = (0..6).map { i -> val d = today.minusDays((6 - i).toLong()); d to completed.count { dayOf(it.completedAt!!) == d.toEpochDay() } }

    val focusMin = focusMinIn(curStart, todayEpoch)
    val focusSessions = focus.filter { it.epochDay in curStart..todayEpoch }.size
    val avgHabit = if (habits.isEmpty()) 0f else habits.map { h ->
        val done = checkins.filter { it.habitId == h.id && it.count >= h.targetPerDay }.map { it.epochDay }.toSet()
        HabitStats.rate(done, todayEpoch)
    }.average().toFloat()

    // ---- Gamification (all on-device) ----
    val totalDone = completed.size
    val totalFocusMin = focus.sumOf { it.minutes }
    val doneDays = completed.map { dayOf(it.completedAt!!) }.toSet()
    val streak = HabitStats.streak(doneDays, todayEpoch)
    val score = totalDone * 10 + totalFocusMin / 6 + streak * 15
    val level = 1 + score / 500
    val intoLevel = (score % 500) / 500f
    val levelTitle = when (level) {
        1 -> "Getting started"; 2 -> "Finding rhythm"; 3 -> "In the flow"; 4 -> "Consistent"
        5 -> "Focused"; 6 -> "Productive"; 7 -> "Relentless"; else -> "Master"
    }

    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp, title = { Text("Statistics") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AchievementsCard(score, level, levelTitle, intoLevel, streak, totalDone, totalFocusMin)
            // Track 2.3 — the story first: what changed this week, so the user reads it instead of scanning tiles.
            if (changes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    Text("What changed this week", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    changes.forEach { c ->
                        Text("• ${c.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Track 2.2 — the range picker.
            OptionChips(STAT_RANGES, STAT_RANGES.firstOrNull { it.first == range }, { range = it.first }, wrap = false, spacing = 6) { it.second }
            // Track 2.2 — headline metrics as rate + drift-vs-baseline arrows, grouped Slipping / Holding steady.
            if (slipping.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Slipping vs your baseline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
                MetricRows(slipping.map { Triple(it.label, it.value, it.metric.result) })
            }
            if (steady.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Holding steady", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                MetricRows(steady.map { Triple(it.label, it.value, it.metric.result) })
            }
            Spacer(Modifier.height(12.dp))
            AppCard {
                Text("Completed per day · 7 days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val max = (perDay.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    perDay.forEach { (d, n) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Text(if (n > 0) n.toString() else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.fillMaxWidth().height((6 + 90 * n / max).dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (n > 0) 1f else .25f)))
                            Spacer(Modifier.height(4.dp))
                            Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // Track 1.1 — "How it felt" lane over the selected range.
            if (felt.hasData) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    Text("How it felt · ${rangeLabelStat(range)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    FeltReadout(felt)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(value = "${focusMin}m", label = "Focus · ${rangeLabelStat(range)}", modifier = Modifier.weight(1f), sub = "$focusSessions sessions")
                StatTile(value = "${(avgHabit * 100).toInt()}%", label = "Habit rate", modifier = Modifier.weight(1f), sub = "${habits.size} habits")
            }
            // Focus time by list, over the selected range — where your deep work actually went.
            val lists by vm.lists.collectAsState()
            val listById = lists.associateBy { it.id }
            val taskListOf = tasks.associate { it.id to it.listId }
            val focusRange = focus.filter { it.epochDay in curStart..todayEpoch }
            val byList = focusRange.filter { it.taskId != null }
                .groupBy { taskListOf[it.taskId] }
                .mapNotNull { (listId, sess) -> listId?.let { (listById[it]?.name ?: "List") to sess.sumOf { s -> s.minutes } } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            if (byList.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    Text("Focus by list · ${rangeLabelStat(range)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    val maxMin = byList.maxOf { it.second }.coerceAtLeast(1)
                    byList.take(6).forEach { (name, min) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(name, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Box(Modifier.weight(1f).height(14.dp)) {
                                Box(Modifier.fillMaxWidth(min.toFloat() / maxMin).height(14.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .85f)))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("${min}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // ---------- Estimation calibration (B7): logged focus time vs your estimate ----------
            val focusByTask = focus.filter { it.taskId != null }.groupBy { it.taskId }.mapValues { e -> e.value.sumOf { it.minutes } }
            data class Calib(val title: String, val est: Int, val actual: Int)
            val calibs = tasks.mapNotNull { t ->
                val est = t.estimateMin ?: return@mapNotNull null
                val act = focusByTask[t.id] ?: return@mapNotNull null
                if (est <= 0 || act <= 0) null else Calib(t.title.ifBlank { "Untitled" }, est, act)
            }.sortedByDescending { it.actual }
            if (calibs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    // Track 1.4 — ratio + verdict come from the one shared Calibration engine (15% tolerance here).
                    val ratio = com.todocompanion.app.domain.Calibration.overallRatio(
                        calibs.map { com.todocompanion.app.domain.Calibration.Pair(it.est, it.actual) }) ?: 1.0
                    val off = com.todocompanion.app.domain.Calibration.percentOff(ratio)
                    val verdict = when (com.todocompanion.app.domain.Calibration.classify(ratio, 0.15)) {
                        com.todocompanion.app.domain.Calibration.Verdict.OVER -> "You take about ${off}% longer than you estimate — pad your estimates."
                        com.todocompanion.app.domain.Calibration.Verdict.UNDER -> "You finish about ${-off}% faster than you estimate — you can commit to more."
                        com.todocompanion.app.domain.Calibration.Verdict.ON_POINT -> "Your estimates are on point (within 15%). Nice calibration."
                    }
                    Text("Estimation calibration", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(verdict, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    val maxV = calibs.take(6).maxOf { maxOf(it.est, it.actual) }.coerceAtLeast(1)
                    calibs.take(6).forEach { c ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(c.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Est", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.weight(1f).height(10.dp)) {
                                    Box(Modifier.fillMaxWidth(c.est.toFloat() / maxV).height(10.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.secondary.copy(alpha = .8f)))
                                }
                                Spacer(Modifier.width(6.dp)); Text("${c.est}m", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Actual", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.weight(1f).height(10.dp)) {
                                    Box(Modifier.fillMaxWidth(c.actual.toFloat() / maxV).height(10.dp).clip(RoundedCornerShape(3.dp)).background(
                                        if (c.actual > c.est) MaterialTheme.colorScheme.error.copy(alpha = .8f) else MaterialTheme.colorScheme.primary.copy(alpha = .85f)))
                                }
                                Spacer(Modifier.width(6.dp)); Text("${c.actual}m", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text("Actual = focus time logged against the task.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("All stats are computed on-device from your data. Arrows compare each window to the one before it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Track 2.2 — the Statistics range picker windows. -1 = "This year" (Jan 1 → today). */
private const val YEAR = -1
private val STAT_RANGES = listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", YEAR to "This year")
private fun rangeLabelStat(range: Int): String = STAT_RANGES.firstOrNull { it.first == range }?.second ?: "$range days"
private fun fmtMin(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

/** Track 2.2 — render a set of headline metrics as stat tiles, two per row. Each carries its rate and a
 *  drift-vs-baseline arrow (▲ rising · ▼ easing · • level) folded into the shared [StatTile]'s sub slot. */
@Composable
private fun MetricRows(items: List<Triple<String, String, Trend.Result>>) {
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    items.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (label, value, r) ->
                val (arrow, tint) = when (r.direction) {
                    Trend.Direction.RISING -> "▲" to primary
                    Trend.Direction.EASING -> "▼" to error
                    Trend.Direction.LEVEL -> "•" to muted
                }
                val drift = r.hasBaseline && r.direction != Trend.Direction.LEVEL
                StatTile(
                    value = value,
                    label = label,
                    modifier = Modifier.weight(1f),
                    sub = if (drift) "$arrow ${abs(r.deltaPct).roundToInt()}% vs baseline" else "$arrow holding",
                    subColor = if (drift) tint else muted,
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AchievementsCard(score: Int, level: Int, levelTitle: String, intoLevel: Float, streak: Int, totalDone: Int, totalFocusMin: Int) {
    data class Badge(val emoji: String, val label: String, val unlocked: Boolean)
    val badges = listOf(
        Badge("🌱", "First step", totalDone >= 1),
        Badge("✅", "10 done", totalDone >= 10),
        Badge("🏅", "50 done", totalDone >= 50),
        Badge("🏆", "100 done", totalDone >= 100),
        Badge("🔥", "7-day streak", streak >= 7),
        Badge("⏳", "10h focus", totalFocusMin >= 600),
    )
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(level.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Level $level · $levelTitle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("$score pts" + if (streak > 0) " · 🔥 $streak-day streak" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { intoLevel },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Text("${(intoLevel * 500).toInt()} / 500 to level ${level + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            badges.forEach { b ->
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (b.unlocked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(b.emoji, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(b.label, style = MaterialTheme.typography.labelMedium,
                        color = if (b.unlocked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

// StatTile / TrendTile now come from the shared ui/components/ReviewComponents.kt.
