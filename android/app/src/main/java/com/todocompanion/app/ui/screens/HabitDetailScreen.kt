package com.todocompanion.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    val hc = checkins.filter { it.habitId == h.id }
    val countsByDay = hc.associate { it.epochDay to it.count }
    val doneDays = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
    val skipDays = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
    val relapseDays = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()

    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val strength = HabitStats.strength(h, doneDays, skipDays, relapseDays, today)
    val current = HabitStats.currentStreak(h, doneDays, skipDays, relapseDays, today)
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
        if (isBreak && h.moneyPerUnit != null) {
            add("Money saved" to ("$" + String.format(Locale.US, "%.2f", h.moneyPerUnit!! * current)))
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { onEdit(h) }) { Icon(Icons.Filled.Edit, "Edit") } },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Header
            Header(h, color)

            // 2. Strength ring
            SectionCard {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    StrengthRing(strength, color)
                }
            }

            // 3. Stat tiles
            StatGrid(tiles)

            // 4. Weekday bars
            SectionCard(title = "Best days") {
                WeekdayBars(weekday, color)
            }

            // 5. Strength trend
            SectionCard(title = "Strength over the last 12 weeks") {
                StrengthTrend(trend, color)
            }

            // 6. Month calendar
            SectionCard {
                MonthCalendar(today, color, doneDays, skipDays, countsByDay,
                    onCycle = { day -> vm.cycleHabit(h, day, countsByDay[day] ?: 0) },
                    onSkip = { day -> vm.skipHabitDay(h, day) })
            }

            // 7. Year heatmap
            SectionCard(title = "Last 26 weeks") {
                YearHeatmap(today, color, doneDays, skipDays, countsByDay)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
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
    Box(Modifier.size(158.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
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
    color: Color,
    doneDays: Set<Long>,
    skipDays: Set<Long>,
    countsByDay: Map<Long, Int>,
    onCycle: (Long) -> Unit,
    onSkip: (Long) -> Unit,
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
                        DayCell(Modifier.weight(1f), dayNum, epochDay, today, color, doneDays, skipDays, countsByDay,
                            onCycle = { onCycle(epochDay) }, onSkip = { onSkip(epochDay) })
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
    color: Color,
    doneDays: Set<Long>,
    skipDays: Set<Long>,
    countsByDay: Map<Long, Int>,
    onCycle: () -> Unit,
    onSkip: () -> Unit,
) {
    val done = epochDay in doneDays
    val skip = epochDay in skipDays
    val partial = !done && !skip && (countsByDay[epochDay] ?: 0) > 0
    val future = epochDay > today
    val bg = when {
        done -> color
        partial -> color.copy(alpha = .4f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (future) .25f else .5f)
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.aspectRatio(1f).padding(2.dp).clip(shape)
            .then(if (skip) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, shape) else Modifier.background(bg))
            .combinedClickable(enabled = !future, onClick = onCycle, onLongClick = onSkip),
        contentAlignment = Alignment.Center,
    ) {
        val textColor = when {
            done -> Color.White
            skip -> MaterialTheme.colorScheme.outline
            future -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(if (skip) "s" else "$dayNum", style = MaterialTheme.typography.labelSmall, color = textColor)
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
