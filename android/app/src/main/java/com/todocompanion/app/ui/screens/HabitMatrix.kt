package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A dense "all habits × days" matrix (Loop / Table Habit / Tickmate style): rows are the active
 * habits, columns are the most recent N days (newest on the right). A frozen left column carries
 * each habit's emoji/colour + name; the day grid scrolls horizontally so the page body never does.
 * A grid-density control (Compact / Medium / Large) trades cell size for how many days fit — the
 * signature Tickmate idea. Fully offline, Material 3 only; every colour but the habit's own comes
 * from the theme.
 */

/** One density preset: cell edge in dp and how many trailing days to render. */
private class MatrixDensity(val cell: Dp, val days: Int, val fontSp: Int)
private val DENSITIES = listOf(
    MatrixDensity(30.dp, 35, 11),  // Compact — was the old "Large"; comfortably tappable
    MatrixDensity(42.dp, 24, 13),  // Medium — roomier cells, three-and-a-half weeks
    MatrixDensity(54.dp, 16, 15),  // Large — big cells for a fortnight-plus at a glance
)
private val DENSITY_LABELS = listOf("Compact", "Medium", "Large")

private val HEADER_HEIGHT = 26.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitMatrix(vm: AppViewModel, density: Int, onOpenHabit: (HabitEntity) -> Unit, modifier: Modifier = Modifier) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val today = LocalDate.now().toEpochDay()

    if (habits.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No habits yet — add one to see the grid.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val preset = DENSITIES[density.coerceIn(0, DENSITIES.lastIndex)]
    val cell = preset.cell
    val rowHeight = cell + 6.dp
    // The frozen name column widens with density so the larger name type has room.
    val labelWidth = when (density.coerceIn(0, 2)) { 0 -> 100.dp; 2 -> 132.dp; else -> 114.dp }
    // Oldest → newest, so the newest day lands on the right edge of the grid.
    val days = remember(today, preset.days) { ((today - preset.days + 1)..today).toList() }
    // Index check-ins by (habit, day) once so each cell is an O(1) lookup.
    val byKey = remember(checkins) { checkins.associateBy { it.habitId to it.epochDay } }

    // R31 #6 — the matrix isn't wrapped in a Surface, so any Text without an explicit color would
    // inherit the default LocalContentColor (Color.Black) and vanish on the AMOLED pure-black
    // background. Pin the whole grid's content colour to the theme's onSurface so labels, day numbers
    // and cell glyphs stay legible in light, dark AND amoled.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth()) {
                // Frozen left column: header spacer + one label per habit. The label width and its type
                // scale with the chosen density, so picking Large grows the habit *name* too — not just
                // the day cells (which was the mismatch users noticed).
                Column(Modifier.width(labelWidth)) {
                    Box(Modifier.height(HEADER_HEIGHT))
                    habits.forEach { h ->
                        HabitLabel(h, rowHeight, labelWidth, preset.fontSp, onOpenHabit)
                    }
                }
                // Horizontally-scrollable day grid: header row of day numbers + a row per habit.
                // R34: newest day (today) sits at the right edge, so a fresh scroll state starts at the
                // oldest day and today's tappable "check" cell is off-screen — users read that as "the
                // checkboxes vanished" when they switch to the grid. Auto-scroll to the end so today is
                // always the first thing visible, in every density.
                val hScroll = rememberScrollState()
                LaunchedEffect(hScroll.maxValue, density) {
                    if (hScroll.maxValue > 0) hScroll.scrollTo(hScroll.maxValue)
                }
                Column(Modifier.horizontalScroll(hScroll)) {
                    DayHeader(days, cell, preset.fontSp, today)
                    habits.forEach { h ->
                        val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                        Row(
                            // R64 — trailing gutter so today's raised border ring isn't shaved by the
                            // horizontal-scroll clip edge (scrollTo(maxValue) pins today flush right).
                            Modifier.height(rowHeight).padding(end = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            days.forEach { day ->
                                val c = byKey[h.id to day]
                                DayCell(h, c, day, today, color, cell) {
                                    val cur = c?.count ?: 0
                                    // Break habits log a relapse (tap toggles it); build habits cycle toward target.
                                    if (h.habitType == "break") {
                                        if (HabitStats.isRelapse(h, cur)) vm.clearHabitDay(h, day) else vm.setHabitValue(h, day, h.targetPerDay + 1)
                                    } else vm.cycleHabit(h, day, cur)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

/** Frozen left-column label: emoji (or a colour dot) + truncated name, tap to open the habit.
 *  The name font tracks the density preset so a bigger grid means bigger habit names, not just cells. */
@Composable
private fun HabitLabel(h: HabitEntity, rowHeight: Dp, labelWidth: Dp, fontSp: Int, onOpenHabit: (HabitEntity) -> Unit) {
    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Row(
        Modifier.width(labelWidth).height(rowHeight).clickable { onOpenHabit(h) }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (h.emoji != null) {
            Text(h.emoji, fontSize = (fontSp + 3).sp)
        } else {
            Box(Modifier.size((fontSp - 2).dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            h.name,
            style = MaterialTheme.typography.labelMedium,
            fontSize = (fontSp + 1).sp,
            lineHeight = (fontSp + 5).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Column headers: day-of-month numbers with a faint marker under weekends; today's number is
 *  emphasised (bold + primary) so the "check today here" column is unmistakable. */
@Composable
private fun DayHeader(days: List<Long>, cell: Dp, fontSp: Int, today: Long) {
    Row(
        Modifier.height(HEADER_HEIGHT).padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            val date = LocalDate.ofEpochDay(day)
            val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            val isToday = day == today
            Column(
                Modifier.width(cell),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = fontSp.sp,
                    maxLines = 1,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isToday) FontWeight.Bold else if (weekend) FontWeight.Normal else FontWeight.Medium,
                )
                Box(
                    Modifier.padding(top = 2.dp).width(cell * 0.5f).height(2.dp).clip(RoundedCornerShape(1.dp))
                        .background(when { isToday -> MaterialTheme.colorScheme.primary; weekend -> MaterialTheme.colorScheme.outlineVariant; else -> Color.Transparent }),
                )
            }
        }
    }
}

/**
 * One (habit, day) cell. Colour encodes the day's state:
 * done = habit colour; partial = colour .4α; skip = a hollow ring; expected-but-missed =
 * surfaceVariant; not-expected = surfaceVariant .25α; future = transparent (and not tappable).
 */
@Composable
private fun DayCell(
    h: HabitEntity,
    checkin: HabitCheckinEntity?,
    day: Long,
    today: Long,
    color: Color,
    cell: Dp,
    onToggle: () -> Unit,
) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val future = day > today
    val isToday = day == today
    // Days before the habit began / in the future are not loggable, but they still draw a FAINT box so
    // the grid always reads as a grid (blank-transparent cells made a fresh habit's whole row vanish).
    val preStart = day < h.startEpochDay()
    val cnt = checkin?.count ?: 0
    val skip = checkin?.status == "skip"
    val done = checkin?.status == "done" && HabitStats.meetsGoal(h, cnt)
    val bg = when {
        future || preStart -> surfaceVariant.copy(alpha = .12f)
        skip -> Color.Transparent
        done -> color
        cnt > 0 -> color.copy(alpha = .4f)
        HabitStats.isExpectedDay(h, day) -> surfaceVariant
        else -> surfaceVariant.copy(alpha = .25f)
    }
    var m = Modifier.size(cell).clip(RoundedCornerShape(if (isToday) 7.dp else 4.dp)).background(bg)
    // R34: today's cell is the live "checkbox" — give it a bold ring (habit colour) so it reads as the
    // tappable target, not just another history square. Skip keeps its own hollow outline.
    if (skip) m = m.border(1.5.dp, outline, RoundedCornerShape(4.dp))
    else if (isToday && !preStart) m = m.border(2.dp, color, RoundedCornerShape(7.dp))
    if (!future && !preStart) {
        // Accessibility: the whole day cell is the tap target, so announce the habit, the date, the current
        // state, and a clear action label. The visible check/ring inside is decorative (Icon stays null).
        val stateLabel = when { skip -> "skipped"; done -> "done"; cnt > 0 -> "logged"; else -> "not done" }
        val cellDesc = "${h.name}, ${java.time.LocalDate.ofEpochDay(day)}, $stateLabel"
        m = m.semantics { contentDescription = cellDesc; role = Role.Button }
            .clickable(onClickLabel = if (done) "Mark not done" else "Mark done") { onToggle() }
    }
    Box(m, contentAlignment = Alignment.Center) {
        // A visible check the moment today is done; an empty ring while it's still open — so the grid
        // reads like a row of checkboxes for the current day.
        if (isToday && !preStart) {
            if (done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(cell * 0.5f))
            else if (!skip) Box(Modifier.size((cell.value * 0.22f).dp).clip(CircleShape).background(primary.copy(alpha = .35f)))
        }
    }
}
