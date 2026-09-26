// Split out of DayReviewScreen.kt (re-audit fix #17) — pure code movement, no behavior change.
// The unified period-spanning share subsystem (dialog, controls, period-share data mappers).
package com.todocompanion.app.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.DayShareConfig
import com.todocompanion.app.domain.HabitDetail
import com.todocompanion.app.domain.PeriodShareConfig
import com.todocompanion.app.domain.ShareStyle
import com.todocompanion.app.domain.TaskDetail
import com.todocompanion.app.domain.TimeDetail
import com.todocompanion.app.domain.ExecutionScore
import com.todocompanion.app.domain.Goals
import com.todocompanion.app.domain.ReviewRollup
import com.todocompanion.app.domain.TextInsights
import com.todocompanion.app.domain.YearReviewed
import com.todocompanion.app.ui.components.OptionChips
import com.todocompanion.app.util.DayCard
import java.util.Locale
import kotlin.math.roundToInt

/** Which period the unified share dialog is currently composing — the top selector spans the day and the
 *  three reviewed roll-ups, so YEAR is first-class in "share your day". */
internal enum class SharePeriod(val label: String) {
    DAY("This day"), WEEK("This week"), MONTH("This month"), YEAR("This year");

    /** The renderer's [DayCard.PeriodKind] for the roll-up periods; null for the day itself. */
    fun kind(): DayCard.PeriodKind? = when (this) {
        DAY -> null
        WEEK -> DayCard.PeriodKind.WEEK
        MONTH -> DayCard.PeriodKind.MONTH
        YEAR -> DayCard.PeriodKind.YEAR
    }
}

/**
 * The unified, period-spanning modular share dialog. A top period selector (This day · This week · This
 * month · This year) swaps between the day config ([DayShareConfig]) and the roll-up config
 * ([PeriodShareConfig]); a Personal / Professional style toggle is visible for every period. Below is a
 * LIVE bitmap preview that re-renders as the config, style or period changes, and grouped section controls
 * (a Switch per boolean section, a segmented [OptionChips] for tri-states). Sections with no data are
 * greyed / hinted, and the renderer skips any enabled-but-empty section so the preview never shows a gap.
 * Every change is persisted immediately; the actions hand off through the same FileProvider + ACTION_SEND
 * path (image) or ACTION_SEND text/plain (text). Theme-correct: only MaterialTheme.colorScheme tokens (the
 * card image keeps its own palette — it's a rendered PNG).
 */
@Composable
internal fun ShareDialog(
    initialPeriod: SharePeriod,
    dayInitial: DayShareConfig,
    dayData: DayCard.DayShareData,
    periodInitial: PeriodShareConfig,
    periodData: Map<DayCard.PeriodKind, DayCard.PeriodShareData>,
    onPersistDay: (DayShareConfig) -> Unit,
    onPersistPeriod: (PeriodShareConfig) -> Unit,
    onShareDayImage: (DayShareConfig) -> Unit,
    onShareDayText: (DayShareConfig) -> Unit,
    onSharePeriodImage: (PeriodShareConfig, DayCard.PeriodKind) -> Unit,
    onSharePeriodText: (PeriodShareConfig, DayCard.PeriodKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var period by remember { mutableStateOf(initialPeriod) }
    var dayCfg by remember { mutableStateOf(dayInitial) }
    var periodCfg by remember { mutableStateOf(periodInitial) }
    fun updateDay(next: DayShareConfig) { dayCfg = next; onPersistDay(next) }
    fun updatePeriod(next: PeriodShareConfig) { periodCfg = next; onPersistPeriod(next) }

    val kind = period.kind()
    val style = if (kind == null) dayCfg.style else periodCfg.style
    // Live preview — a cheap Canvas draw, recomputed whenever the period, config or style changes.
    val preview = remember(period, dayCfg, periodCfg, dayData, periodData) {
        val pd = kind?.let { periodData[it] }
        when {
            kind == null || pd == null -> DayCard.renderShare(dayData, dayCfg)
            else -> DayCard.renderPeriodShare(pd, periodCfg, kind)
        }.asImageBitmap()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f),
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("Share", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                // Period selector — day + the three reviewed roll-ups.
                OptionChips(options = SharePeriod.entries, selected = period, onSelect = { period = it }, wrap = false,
                    label = { it.label })
                Spacer(Modifier.height(8.dp))
                Text("Choose what to include. Everything stays on your device until you pick where to send it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                // Live preview of the rendered card.
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = preview, contentDescription = "Card preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Personal / Professional style toggle — visible for every period.
                ShareStyleToggle(style) { s ->
                    if (kind == null) updateDay(dayCfg.copy(style = s)) else updatePeriod(periodCfg.copy(style = s))
                }
                Spacer(Modifier.height(4.dp))
                // Grouped, scrollable section controls — the day controls, or the roll-up controls.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (kind == null) {
                        DayShareControls(dayCfg, dayData) { updateDay(it) }
                    } else {
                        PeriodShareControls(periodCfg, periodData[kind], kind) { updatePeriod(it) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = { if (kind == null) onShareDayText(dayCfg) else onSharePeriodText(periodCfg, kind) }) { Text("Share text") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (kind == null) onShareDayImage(dayCfg) else onSharePeriodImage(periodCfg, kind) }) { Text("Share image") }
                }
            }
        }
    }
}

/** The day card's grouped section controls (extracted so both the unified dialog and any future host can
 *  reuse the exact same modular UI). */
@Composable
private fun DayShareControls(cfg: DayShareConfig, data: DayCard.DayShareData, update: (DayShareConfig) -> Unit) {
    ShareGroup("Felt state") {
        ShareToggle("Rating", "★ stars", cfg.rating, data.rating in 1..5) { update(cfg.copy(rating = it)) }
        ShareToggle("Mood & emotion", "mood, energy, feeling", cfg.moodEnergyEmotion,
            data.moodEmoji.isNotBlank() || data.emotion.isNotBlank() || data.energy in 1..5) { update(cfg.copy(moodEnergyEmotion = it)) }
    }
    ShareGroup("Highlights") {
        ShareToggle("Wins", null, cfg.wins, data.wins.isNotEmpty()) { update(cfg.copy(wins = it)) }
        ShareToggle("Highlight", null, cfg.highlight, data.highlight.isNotBlank()) { update(cfg.copy(highlight = it)) }
        ShareToggle("Grateful for", "three good things", cfg.gratitude, data.gratitude.isNotEmpty()) { update(cfg.copy(gratitude = it)) }
        ShareToggle("Lesson", null, cfg.lesson, data.lesson.isNotBlank()) { update(cfg.copy(lesson = it)) }
    }
    ShareGroup("Reflection") {
        ShareToggle("Reflection", null, cfg.reflection, data.reflection.isNotBlank()) { update(cfg.copy(reflection = it)) }
        ShareToggle("Themes", "recurring words", cfg.themes, data.themes.isNotEmpty()) { update(cfg.copy(themes = it)) }
    }
    ShareGroup("Tasks") {
        ShareTriState(TaskDetail.entries, cfg.tasks, data.taskCount > 0, "No tasks completed",
            { when (it) { TaskDetail.OFF -> "Off"; TaskDetail.COUNT -> "Count"; TaskDetail.FULL -> "Full list" } }) { update(cfg.copy(tasks = it)) }
    }
    ShareGroup("Habits") {
        ShareTriState(HabitDetail.entries, cfg.habits, data.habitsExpected > 0, "No habits due",
            { when (it) { HabitDetail.OFF -> "Off"; HabitDetail.COUNT -> "Count"; HabitDetail.DETAILED -> "Detailed" } }) { update(cfg.copy(habits = it)) }
    }
    ShareGroup("Tracked time") {
        ShareTriState(TimeDetail.entries, cfg.time, data.trackedMin > 0, "Nothing tracked",
            { when (it) { TimeDetail.OFF -> "Off"; TimeDetail.TOTAL -> "Total"; TimeDetail.DETAILED -> "Detailed" } }) { update(cfg.copy(time = it)) }
    }
    ShareGroup("Assessments") {
        ShareToggle("Daily questions", "answered + scores", cfg.dailyQuestions, data.questions.isNotEmpty()) { update(cfg.copy(dailyQuestions = it)) }
        ShareToggle("Alignment", "goals & values", cfg.alignment, data.goalsAdvanced.isNotEmpty() || data.valuesHonored.isNotEmpty()) { update(cfg.copy(alignment = it)) }
    }
    ShareGroup("Tomorrow") {
        ShareToggle("Focus", "the one thing", cfg.tomorrowFocus, data.tomorrowFocus.isNotBlank()) { update(cfg.copy(tomorrowFocus = it)) }
        ShareToggle("If-then plan", "obstacle + plan", cfg.woop, data.woopObstacle.isNotBlank() || data.woopPlan.isNotBlank()) { update(cfg.copy(woop = it)) }
    }
    ShareGroup("Insights") {
        ShareToggle("A pattern", "a soft observation", cfg.pattern, data.pattern.isNotBlank()) { update(cfg.copy(pattern = it)) }
    }
    ShareGroup("Footer") {
        ShareToggle("Tagline", "Hexis · 100% offline", cfg.footerTagline, true) { update(cfg.copy(footerTagline = it)) }
    }
}

/** The roll-up card's grouped section controls, tailored to the selected [kind] (the execution score is a
 *  week-only measure; goals are hidden for the year). Sections with no data for the period are greyed. */
@Composable
private fun PeriodShareControls(cfg: PeriodShareConfig, data: DayCard.PeriodShareData?, kind: DayCard.PeriodKind, update: (PeriodShareConfig) -> Unit) {
    ShareGroup("Felt") {
        ShareToggle("Rating & mood", "averages over the period", cfg.feltTrend, (data?.avgRating ?: 0.0) > 0 || (data?.avgMood ?: 0.0) > 0) { update(cfg.copy(feltTrend = it)) }
        if (kind == DayCard.PeriodKind.WEEK) {
            ShareToggle("Execution score", "planned vs done", cfg.executionScore, data?.hasExec == true) { update(cfg.copy(executionScore = it)) }
        }
    }
    ShareGroup(if (kind == DayCard.PeriodKind.YEAR) "Highlight" else "Wins") {
        ShareToggle(if (kind == DayCard.PeriodKind.YEAR) "Highlights" else "Wins", null, cfg.wins,
            (data?.wins?.isNotEmpty() == true) || (data?.highlight?.isNotBlank() == true) || (data?.winsCount ?: 0) > 0) { update(cfg.copy(wins = it)) }
    }
    ShareGroup("Habits") {
        ShareTriState(HabitDetail.entries, cfg.habits, (data?.habitsExpected ?: 0) > 0, "No habits tracked",
            { when (it) { HabitDetail.OFF -> "Off"; HabitDetail.COUNT -> "Count"; HabitDetail.DETAILED -> "Detailed" } }) { update(cfg.copy(habits = it)) }
    }
    ShareGroup("Tracked time") {
        ShareTriState(TimeDetail.entries, cfg.time, (data?.trackedMin ?: 0) > 0, "Nothing tracked",
            { when (it) { TimeDetail.OFF -> "Off"; TimeDetail.TOTAL -> "Total"; TimeDetail.DETAILED -> "Detailed" } }) { update(cfg.copy(time = it)) }
    }
    ShareGroup("Progress") {
        ShareToggle("Tasks done", "completed count", cfg.tasks, (data?.tasksDone ?: 0) > 0) { update(cfg.copy(tasks = it)) }
        if (kind != DayCard.PeriodKind.YEAR) {
            ShareToggle("Goals advanced", null, cfg.goals, data?.goals?.isNotEmpty() == true) { update(cfg.copy(goals = it)) }
        }
        ShareToggle("Themes", "recurring words", cfg.themes, data?.themes?.isNotEmpty() == true) { update(cfg.copy(themes = it)) }
    }
    ShareGroup("Footer") {
        ShareToggle("Tagline", "Hexis · 100% offline", cfg.footerTagline, true) { update(cfg.copy(footerTagline = it)) }
    }
}

/** The Personal / Professional style toggle — the one control that reshapes the whole card. */
@Composable
private fun ShareStyleToggle(style: ShareStyle, onChange: (ShareStyle) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Style", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp))
        OptionChips(options = listOf(ShareStyle.PERSONAL, ShareStyle.PROFESSIONAL), selected = style, onSelect = onChange,
            wrap = false, label = { if (it == ShareStyle.PERSONAL) "Personal" else "Professional" })
    }
}

/** A titled group of share-section controls, closed with a subtle divider — the calm grouping idiom. */
@Composable
private fun ShareGroup(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
    content()
    HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** One boolean share section: a tappable row with a title, an optional hint, and a Switch. When the day
 *  has no data for it the row is greyed, hinted "Nothing recorded", and the Switch is disabled. */
@Composable
private fun ShareToggle(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            val sub = if (!enabled) "Nothing recorded" else subtitle
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked && enabled, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** One tri-state share section: a segmented [OptionChips] selector, or a greyed hint when the day has no
 *  data for it (so the preview can never show an empty section). */
@Composable
private fun <T> ShareTriState(options: List<T>, selected: T, enabled: Boolean, emptyHint: String, label: (T) -> String, onSelect: (T) -> Unit) {
    if (!enabled) {
        Text(emptyHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))
        return
    }
    OptionChips(options = options, selected = selected, onSelect = onSelect, wrap = false, label = label,
        modifier = Modifier.padding(vertical = 4.dp))
}

// ── Modular period-share mappers: fold a computed roll-up / year recap into the renderer's PeriodShareData ──

/** The period's three recurring theme words, extracted on-device from the window's own reflections (the
 *  same idiom the on-screen roll-up uses). */
internal fun shareThemesFor(start: Long, end: Long, dayLogs: List<com.todocompanion.app.data.entity.DayLogEntity>): List<String> {
    val docs = dayLogs.asSequence().filter { it.epochDay in start..end }.map { l ->
        listOf(l.pmReflection, l.highlight, l.gratitude, l.lesson, l.good1, l.good2, l.good3, l.promptAnswer, l.amIntention)
            .filter { it.isNotBlank() }.joinToString(" ")
    }.filter { it.isNotBlank() }.toList()
    return TextInsights.threeWords(docs)
}

/** Map a week / month [ReviewRollup.Rollup] (+ its execution score) to the renderer's data model. */
internal fun periodDataFromRollup(
    label: String,
    rollup: ReviewRollup.Rollup,
    exec: ExecutionScore.Score,
    themes: List<String>,
    accent: Long?,
): DayCard.PeriodShareData = DayCard.PeriodShareData(
    periodLabel = label,
    reviewedDays = rollup.reviewedDays,
    periodDays = rollup.periodDays,
    avgRating = rollup.avgRating,
    avgMood = rollup.avgMood,
    moodFace = if (rollup.moodCount > 0) moodFace(rollup.avgMood.roundToInt()) else "",
    hasExec = exec.hasData,
    execPlanned = exec.planned,
    execCompleted = exec.completed,
    execPct = exec.pct,
    wins = rollup.wins.map { it.text },
    winsCount = 0,
    highlight = "",
    habits = rollup.habitConsistency.map { DayCard.ConsistencyLine(it.name, it.kept, it.expected) },
    habitsKept = rollup.habitConsistency.sumOf { it.kept },
    habitsExpected = rollup.habitConsistency.sumOf { it.expected },
    activities = rollup.topActivities.map { DayCard.ActivityLine(it.name, it.minutes) },
    trackedMin = rollup.topActivities.sumOf { it.minutes },
    goals = rollup.goalsMoved.map { it.text + (if (it.count > 1) " · ${it.count} days" else "") },
    themes = themes,
    tasksDone = exec.doneTasks,
    accentArgb = accent,
)

/** Map a [YearReviewed.Recap] to the renderer's data model (a highlight + counts rather than win texts). */
internal fun periodDataFromYear(
    recap: YearReviewed.Recap,
    themes: List<String>,
    accent: Long?,
    label: String,
): DayCard.PeriodShareData = DayCard.PeriodShareData(
    periodLabel = label,
    reviewedDays = recap.daysReviewed,
    periodDays = recap.periodDays,
    avgRating = recap.avgRating,
    avgMood = recap.avgMood,
    moodFace = if (recap.moodDays > 0) moodFace(recap.avgMood.roundToInt()) else "",
    hasExec = false,
    execPlanned = 0,
    execCompleted = 0,
    execPct = 0,
    wins = emptyList(),
    winsCount = recap.winsCount,
    highlight = recap.highlightText,
    habits = recap.habitConsistency.map { DayCard.ConsistencyLine(it.name, it.kept, it.expected) },
    habitsKept = recap.habitConsistency.sumOf { it.kept },
    habitsExpected = recap.habitConsistency.sumOf { it.expected },
    activities = recap.topActivities.map { DayCard.ActivityLine(it.name, it.minutes) },
    trackedMin = recap.trackedMinutes,
    goals = emptyList(),
    themes = themes,
    tasksDone = recap.tasksFinished,
    accentArgb = accent,
)
