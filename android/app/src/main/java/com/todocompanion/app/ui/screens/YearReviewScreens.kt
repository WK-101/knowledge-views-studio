// Split out of DayReviewScreen.kt (re-audit fix #17) — pure code movement, no behavior change.
// The fully-local Year-reviewed recap and past-year review screens.
package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.PeriodShareConfig
import com.todocompanion.app.domain.PastYearReview
import com.todocompanion.app.domain.YearReviewed
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.MeterRow
import com.todocompanion.app.ui.components.SectionTitle
import com.todocompanion.app.ui.components.StatTile
import com.todocompanion.app.util.DayCard
import com.todocompanion.app.util.ProgressCard
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Wave 3 (B) — the fully-local "Year, reviewed": a calm, multi-panel year-in-review computed on-device by
 * [YearReviewed] over the canonical [YearReviewed.calendarYearWindow] (the current calendar year, to date),
 * matching The Record's Wrapped and the drawer's annual report, with a permission-free shareable PNG rendered
 * through the existing DayCard/ProgressCard pipeline (guarded, never blank). Reachable from the Month
 * roll-up. Nothing leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YearReviewedScreen(
    anchorDay: Long, todayEd: Long, zone: ZoneId,
    dayLogs: List<com.todocompanion.app.data.entity.DayLogEntity>,
    habits: List<com.todocompanion.app.data.entity.HabitEntity>,
    checkins: List<com.todocompanion.app.data.entity.HabitCheckinEntity>,
    timeEntries: List<com.todocompanion.app.data.entity.TimeEntryEntity>,
    activities: List<com.todocompanion.app.data.entity.TimeActivityEntity>,
    accentArgb: Long?,
    periodShareCfg: PeriodShareConfig,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    // Honour the navigated anchor: stepping back to a past year in the roll-up and opening "Year, reviewed"
    // shows THAT year, not the current one. calendarYearWindow returns the full span for a past year.
    val year = LocalDate.ofEpochDay(anchorDay).year
    val (start, end) = YearReviewed.calendarYearWindow(year, todayEd)
    val recap = remember(dayLogs, habits, checkins, timeEntries, activities, end) {
        YearReviewed.compute(start, end, dayLogs, habits, checkins, timeEntries, activities, zone, System.currentTimeMillis())
    }
    val fromLabel = LocalDate.ofEpochDay(start)
    val toLabel = LocalDate.ofEpochDay(end)
    val windowLabel = "${fromLabel.dayOfMonth} ${fromLabel.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${fromLabel.year} – " +
        "${toLabel.dayOfMonth} ${toLabel.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${toLabel.year}"

    // Track 3.5 — the Ferriss Past-Year Review lives on this spine: same recap + the window's day logs.
    var showPastReview by remember { mutableStateOf(false) }
    val pastReview = remember(recap, dayLogs) { PastYearReview.compute(recap, dayLogs) }
    if (showPastReview) {
        PastYearReviewScreen(review = pastReview, windowLabel = windowLabel, onBack = { showPastReview = false })
        return
    }

    // Route the year share through the modular DayCard.renderPeriodShare, honouring the saved period-share
    // config (including the Personal / Professional style). The full modular flow with live toggles is
    // reachable from the day review's share (This year); this button is the one-tap share from the screen.
    fun shareYear() {
        runCatching {
            val pd = periodDataFromYear(recap, shareThemesFor(start, end, dayLogs), accentArgb, year.toString())
            val bmp = DayCard.renderPeriodShare(pd, periodShareCfg, DayCard.PeriodKind.YEAR)
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
                Text("$year", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("${recap.daysReviewed} days reviewed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(windowLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(10.dp))
                val tiles = buildList {
                    if (recap.longestStreakDays > 0) add(Triple("🔥", "${recap.longestStreakDays}", "review streak"))
                    if (recap.winsCount > 0) add(Triple("⭐", "${recap.winsCount}", "good things"))
                    if (recap.trackedMinutes > 0) add(Triple("⧗", "${recap.trackedHours}h", "tracked"))
                }
                if (tiles.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        tiles.forEach { (icon, value, label) -> StatTile(value = value, label = label, modifier = Modifier.weight(1f), icon = icon) }
                    }
                }
            }

            // Track 3.5 — enter the Ferriss Past-Year Review (scenes + two action lists at the end).
            if (pastReview.hasData) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = { showPastReview = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("📆  Do a Past Year Review")
                }
            }

            // How the year felt: rating + mood + dominant emotion, through the shared FeltReadout (Track 1). It
            // reads the recap's own [FeltState] summary; the per-calendar-month trend arrays are passed as
            // overrides so the yearly sparkline/strip stay a monthly roll-up, and the emotion line keeps the
            // year view's ">= 3 days" threshold and "(N days named it)" suffix.
            if (recap.ratedDays > 0 || recap.moodDays > 0) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("How your year felt")
                    FeltReadout(
                        recap.felt,
                        ratingTrend = recap.ratingTrend,
                        moodTrend = recap.moodTrend,
                        emotionMinCount = 3,
                        emotionShowDayCount = true,
                    )
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

/**
 * Track 3.5 — the Ferriss Past-Year Review: a calm, scrollable set of scenes ending in two action lists —
 * the positives to schedule MORE of, and a NOT-TO-DO list. Everything is derived by [PastYearReview] from
 * the felt recap and the window's day logs; data-adaptive, so a light year still reads as a short, honest
 * review. Nothing leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastYearReviewScreen(review: PastYearReview.Review, windowLabel: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            expandedHeight = 52.dp,
            title = { Text("Past Year Review") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(windowLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            Text("Ferriss's review: look back at what worked and what didn't, then do two things — schedule more of the good, and keep a not-to-do list for the rest.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // The scenes — one calm tinted panel each.
            val tints = listOf(
                MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary,
            )
            review.scenes.forEachIndexed { i, s ->
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(22.dp), color = tints[i % tints.size].copy(alpha = .14f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(s.emoji, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(s.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tints[i % tints.size])
                        Spacer(Modifier.height(4.dp))
                        Text(s.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // The two action lists at the end.
            if (review.moreOf.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                AppCard {
                    SectionTitle("＋  Schedule more of this")
                    review.moreOf.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Text("＋", Modifier.width(24.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(item, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (review.notToDo.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard {
                    SectionTitle("✕  Your not-to-do list")
                    review.notToDo.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Text("✕", Modifier.width(24.dp), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            Text(item, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Computed on your device from your own year. Keep the not-to-do list somewhere you'll see it.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}
