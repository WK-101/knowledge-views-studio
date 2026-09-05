package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.TimeStats
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.OptionChips
import com.todocompanion.app.ui.components.StatTile
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

// An intentional categorical DATA-VIZ palette for the distribution donut/legend only — one fixed, high-contrast
// hue per activity slice (used solely by `colorOf` below as the fallback when an activity has no custom colour).
// These are deliberately theme-independent so adjacent slices stay distinguishable in either light or dark; they
// are not app chrome and are not derived from MaterialTheme on purpose.
private val STAT_PALETTE = listOf(0xFF3E7BFAL, 0xFFE5484DL, 0xFFF59E0BL, 0xFF16A34AL, 0xFF8B5CF6L, 0xFF0EA5E9L, 0xFFEC4899L, 0xFF64748BL, 0xFF12A594L, 0xFF6366F1L)
private fun sfmt(min: Int): String = when {
    min <= 0 -> "0m"; min < 60 -> "${min}m"; min % 60 == 0 -> "${min / 60}h"; else -> "${min / 60}h ${min % 60}m"
}

/**
 * The Time tab's Statistics screen — a ranged distribution (donut + ranked list) over any
 * Day/Week/Month/Year, and a per-activity drill-down (averages, session lengths, a time-of-day
 * histogram and this-vs-previous). This is what turns the tracker from a bare list into rich views.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeStatsScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)   // system-back returns to the Time view, not out of the app
    val entries by vm.timeEntries.collectAsState()
    val activities by vm.timeActivities.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    var range by rememberSaveable { mutableStateOf(TimeStats.Range.WEEK) }
    var anchor by remember { mutableStateOf(LocalDate.now(zone)) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var showTrends by rememberSaveable { mutableStateOf(false) }   // Breakdown ↔ Trends & correlations

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyRunning = entries.any { it.running }
    LaunchedEffect(anyRunning) { while (anyRunning) { now = System.currentTimeMillis(); delay(1000) } }

    val overview = remember(entries, activities, range, anchor, now) { TimeStats.overview(entries, activities, range, anchor, zone, now) }
    val colorOf: (Int, Long?) -> Color = { i, argb -> argb?.let { Color(it) } ?: Color(STAT_PALETTE[i % STAT_PALETTE.size]) }
    val today = LocalDate.now(zone)
    val canNext = TimeStats.window(range, anchor).second.isBefore(today)

    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp, title = { Text("Time stats") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Range selector — the app-wide single-choice chip row, single line that scrolls if it doesn't fit.
            OptionChips(
                options = TimeStats.Range.entries,
                selected = range,
                onSelect = { range = it; anchor = today; detailId = null },
                wrap = false,
            ) { it.label }
            // Period pager.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { anchor = TimeStats.shift(range, anchor, -1) }) { Icon(Icons.Filled.ChevronLeft, "Previous") }
                Text(overview.label, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { if (canNext) anchor = TimeStats.shift(range, anchor, 1) }, enabled = canNext) { Icon(Icons.Filled.ChevronRight, "Next") }
            }

            // Breakdown ↔ Trends. Breakdown is the distribution (donut + ranked); Trends is the longer-arc
            // view — weekday rhythm, day-by-day trajectory, peak hours and cross-activity correlations.
            if (detailId == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showTrends, onClick = { showTrends = false }, label = { Text("Breakdown") })
                    FilterChip(selected = showTrends, onClick = { showTrends = true }, label = { Text("Trends") })
                }
            }

            if (detailId == null && showTrends) {
                TrendsSection(vm, range, anchor, zone, now)
            } else if (detailId == null) {
                if (overview.totalMin == 0) {
                    com.todocompanion.app.ui.components.EmptyState(emoji = "◔", title = "No time tracked",
                        body = "Track some activities in this ${range.label.lowercase()} and the breakdown shows up here.")
                } else {
                    // Donut + total in the middle.
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(196.dp)) {
                            val stroke = 34.dp.toPx()
                            val d = size.minDimension - stroke
                            val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                            val arc = Size(d, d)
                            var startA = -90f
                            overview.slices.forEachIndexed { i, s ->
                                val sweep = s.minutes / overview.totalMin.toFloat() * 360f
                                drawArc(color = colorOf(i, s.colorArgb), startAngle = startA, sweepAngle = (sweep - 1.5f).coerceAtLeast(0.4f),
                                    useCenter = false, topLeft = tl, size = arc, style = Stroke(width = stroke, cap = StrokeCap.Round))
                                startA += sweep
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(sfmt(overview.totalMin), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("tracked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val avgPerActive = if (overview.activeDays > 0) overview.totalMin / overview.activeDays else 0
                    Text("${overview.activeDays} active day${if (overview.activeDays == 1) "" else "s"}" + (if (overview.activeDays > 0) " · avg ${sfmt(avgPerActive)}/day" else ""),
                        Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Ranked list — tap a row for the drill-down.
                    AppCard {
                        overview.slices.forEachIndexed { i, s ->
                            val c = colorOf(i, s.colorArgb)
                            val pct = s.minutes * 100 / overview.totalMin
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { detailId = s.activityId }.padding(vertical = 7.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text((s.emoji?.plus(" ") ?: "") + s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                        Box(Modifier.fillMaxWidth(pct / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c))
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(sfmt(s.minutes), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Text("$pct%", Modifier.width(38.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                        }
                    }
                    Text("Tap an activity for its detailed statistics.", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                val act = activities.firstOrNull { it.id == detailId }
                val d = remember(entries, detailId, range, anchor, now) { TimeStats.detail(entries, detailId!!, range, anchor, zone, now) }
                TextButton(onClick = { detailId = null }) { Text("‹ All activities") }
                Text((act?.emoji?.plus(" ") ?: "") + (act?.name ?: "Activity"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                // Stat tiles.
                val tiles = listOf(
                    "Total" to sfmt(d.totalMin),
                    "Sessions" to d.sessions.toString(),
                    "Active days" to d.activeDays.toString(),
                    "Avg / active day" to sfmt(d.avgActiveDayMin),
                    "Longest" to sfmt(d.longestMin),
                    "Typical" to sfmt(d.typicalMin),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { rowTiles ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowTiles.forEach { (k, v) ->
                                StatTile(value = v, label = k, modifier = Modifier.weight(1f))
                            }
                            if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                // vs previous period.
                AppCard {
                    val delta = d.totalMin - d.prevTotalMin
                    Text("vs previous ${range.label.lowercase()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sfmt(d.totalMin), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(when { delta > 0 -> "▲ ${sfmt(delta)} more"; delta < 0 -> "▼ ${sfmt(-delta)} less"; else -> "— no change" },
                            style = MaterialTheme.typography.labelMedium,
                            color = when { delta > 0 -> MaterialTheme.colorScheme.primary; delta < 0 -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    }
                    if (d.bestDayEpoch != null && d.bestDayMin > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("Best day: ${LocalDate.ofEpochDay(d.bestDayEpoch).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))} · ${sfmt(d.bestDayMin)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Time-of-day histogram.
                if (d.byHour.any { it > 0 }) AppCard {
                    Text("When you do it", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val hourMax = (d.byHour.maxOrNull() ?: 1).coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                        for (h in 0..23) {
                            val frac = d.byHour[h] / hourMax.toFloat()
                            Box(Modifier.weight(1f).fillMaxHeight(frac.coerceAtLeast(0.02f)).clip(RoundedCornerShape(2.dp))
                                .background(if (d.byHour[h] > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("0", "6", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * The Trends tab of the Statistics screen — weekday rhythm, a day-by-day trajectory, peak hours and
 * the cross-activity correlations that a unified store makes possible (the tracker's answer to the
 * habits screen's rich trends). Correlations use a fixed recent window, independent of the range pager.
 */
@Composable
private fun TrendsSection(vm: AppViewModel, range: TimeStats.Range, anchor: LocalDate, zone: ZoneId, now: Long) {
    val entries by vm.timeEntries.collectAsState()
    val activities by vm.timeActivities.collectAsState()
    val t = remember(entries, range, anchor, now) { TimeStats.trends(entries, range, anchor, zone, now) }
    val corr = remember(entries, activities, now) { TimeStats.correlations(entries, activities, zone, now) }

    if (t.totalMin == 0) {
        com.todocompanion.app.ui.components.EmptyState(emoji = "◔", title = "No trends yet",
            body = "Track across a few days and your weekday rhythm, trajectory and correlations appear here.")
        return
    }

    // Pace vs previous period. (Track 1.4 — was "Momentum"; renamed so it doesn't collide with the
    // Momentum screen / achievement score / Record comeback card, which all mean different things.)
    AppCard {
        val delta = t.totalMin - t.prevTotalMin
        Text("Pace", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sfmt(t.totalMin), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(when { delta > 0 -> "▲ ${sfmt(delta)} vs previous ${range.label.lowercase()}"; delta < 0 -> "▼ ${sfmt(-delta)} vs previous ${range.label.lowercase()}"; else -> "— same as previous ${range.label.lowercase()}" },
                style = MaterialTheme.typography.labelMedium,
                color = when { delta > 0 -> MaterialTheme.colorScheme.primary; delta < 0 -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant })
        }
        val avg = if (t.activeDays > 0) t.totalMin / t.activeDays else 0
        Spacer(Modifier.height(4.dp))
        Text("Tracked on ${t.activeDays} of ${t.windowDays} day${if (t.windowDays == 1) "" else "s"}" + (if (t.activeDays > 0) " · avg ${sfmt(avg)}/active day" else ""),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Regularity: coefficient of variation of active-day totals (Track-&-Graph's variance idea) plus a
        // first-half vs second-half trend — is your time steady or erratic, rising or easing?
        val active = t.dailyTotals.map { it.second }.filter { it > 0 }
        if (active.size >= 3) {
            val mean = active.average()
            val sd = kotlin.math.sqrt(active.sumOf { (it - mean) * (it - mean) } / active.size)
            val cv = if (mean > 0) sd / mean else 0.0
            val rhythm = when { cv < 0.35 -> "steady"; cv < 0.7 -> "variable"; else -> "erratic" }
            val half = t.dailyTotals.size / 2
            val firstAvg = t.dailyTotals.take(half).map { it.second }.average()
            val secondAvg = t.dailyTotals.drop(t.dailyTotals.size - half).map { it.second }.average()
            val trend = when { secondAvg > firstAvg * 1.15 -> "rising"; secondAvg < firstAvg * 0.85 -> "easing off"; else -> "level" }
            Spacer(Modifier.height(2.dp))
            Text("Rhythm: $rhythm · trend $trend", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // Day-by-day trajectory (only when the window is a handful of days to a couple of months).
    if (t.windowDays in 2..62) AppCard {
        Text("Day by day", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val dmax = (t.dailyTotals.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(72.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            t.dailyTotals.forEach { (_, m) ->
                val frac = m / dmax.toFloat()
                Box(Modifier.weight(1f).fillMaxHeight(frac.coerceAtLeast(0.02f)).clip(RoundedCornerShape(2.dp))
                    .background(if (m > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        val first = t.dailyTotals.firstOrNull()?.first?.let { LocalDate.ofEpochDay(it) }
        val last = t.dailyTotals.lastOrNull()?.first?.let { LocalDate.ofEpochDay(it) }
        if (first != null && last != null) Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(first.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(last.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // Weekday rhythm — average minutes per day of week.
    AppCard {
        Text("Weekday rhythm", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val avgByWd = (0..6).map { i -> if (t.byWeekdayDays[i] > 0) t.byWeekdayMin[i] / t.byWeekdayDays[i] else 0 }
        val wmax = (avgByWd.maxOrNull() ?: 1).coerceAtLeast(1)
        val busiest = avgByWd.indexOf(avgByWd.maxOrNull() ?: 0)
        Row(Modifier.fillMaxWidth().height(88.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            avgByWd.forEachIndexed { i, m ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight((m / wmax.toFloat()).coerceAtLeast(0.03f)).clip(RoundedCornerShape(4.dp))
                        .background(if (i == busiest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = .35f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { d -> Text(d, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if ((avgByWd.maxOrNull() ?: 0) > 0) {
            val names = listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")
            Spacer(Modifier.height(6.dp))
            Text("You track most on ${names[busiest]} (avg ${sfmt(avgByWd[busiest])}).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // Peak hours across the window.
    if (t.peakByHour.any { it > 0 }) AppCard {
        Text("Peak hours", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val hmax = (t.peakByHour.maxOrNull() ?: 1).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
            for (h in 0..23) {
                val frac = t.peakByHour[h] / hmax.toFloat()
                Box(Modifier.weight(1f).fillMaxHeight(frac.coerceAtLeast(0.02f)).clip(RoundedCornerShape(2.dp))
                    .background(if (t.peakByHour[h] > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val peakH = t.peakByHour.indexOf(t.peakByHour.maxOrNull() ?: 0)
        Spacer(Modifier.height(4.dp))
        Text("Most active around ${"%02d:00".format(peakH)}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Correlations — "on days you track A, you also track B".
    AppCard {
        Text("Correlations", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("What travels together, over the last 60 days.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        if (corr.isEmpty()) {
            Text("Not enough overlapping days yet — keep tracking a few activities and pairings show up here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else corr.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${c.aEmoji?.plus(" ") ?: ""}${c.aName} → ${c.bEmoji?.plus(" ") ?: ""}${c.bName}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("On ${c.pct}% of the ${c.aDays} days you tracked ${c.aName}, you also tracked ${c.bName}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Text("${c.pct}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
