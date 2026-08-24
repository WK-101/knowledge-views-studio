package com.todocompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.Density
import com.todocompanion.app.domain.priority.PriorityLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

fun priorityColor(level: PriorityLevel): Color = when (level) {
    PriorityLevel.HIGH -> Color(0xFFE5484D)
    PriorityLevel.MEDIUM -> Color(0xFFF59E0B)
    PriorityLevel.LOW -> Color(0xFF3E7BFA)
    PriorityLevel.NONE -> Color(0xFF9AA3B2)
}

/** Flag colours cycled by tapping a row's flag (MLO-style). */
val FLAG_COLORS = listOf(0xFFE5484D, 0xFFF59E0B, 0xFF12A594, 0xFF3E7BFA, 0xFF8B5CF6)

fun nextFlagColor(current: Long?): Long? {
    if (current == null) return FLAG_COLORS.first()
    val i = FLAG_COLORS.indexOf(current)
    return if (i < 0 || i == FLAG_COLORS.lastIndex) null else FLAG_COLORS[i + 1]
}

fun rowVerticalPadding(d: Density): Dp = when (d) {
    Density.COMPACT -> 5.dp
    Density.DEFAULT -> 9.dp
    Density.RELAXED -> 13.dp
}

/** A checkbox whose outline/fill is tinted by task priority — replaces a separate colour dot. */
@Composable
fun PriorityCheckbox(checked: Boolean, level: PriorityLevel, onCheckedChange: () -> Unit) {
    val c = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.outline else priorityColor(level)
    Checkbox(
        checked = checked,
        onCheckedChange = { onCheckedChange() },
        colors = CheckboxDefaults.colors(checkedColor = c, uncheckedColor = c, checkmarkColor = Color.White),
    )
}

/** Trailing flag + star, MLO-style. Flag cycles colours; star toggles. */
@Composable
fun FlagStar(flagArgb: Long?, starred: Boolean, onCycleFlag: () -> Unit, onToggleStar: () -> Unit) {
    Box(Modifier.size(30.dp).clip(CircleShape).clickable { onCycleFlag() }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (flagArgb != null) Icon(Icons.Filled.Flag, "Flag", tint = Color(flagArgb), modifier = Modifier.size(17.dp))
        else Icon(Icons.Outlined.Flag, "Flag", tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(17.dp))
    }
    Box(Modifier.size(30.dp).clip(CircleShape).clickable { onToggleStar() }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (starred) Icon(Icons.Filled.Star, "Star", tint = Color(0xFFF5A623), modifier = Modifier.size(18.dp))
        else Icon(Icons.Filled.StarBorder, "Star", tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(18.dp))
    }
}

fun formatDue(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    val d = dt.toLocalDate()
    val today = LocalDate.now()
    val day = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        in today..today.plusDays(6) -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        else -> "${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.dayOfMonth}"
    }
    val hasTime = !(dt.hour == 9 && dt.minute == 0)
    return if (hasTime) "$day ${"%02d:%02d".format(dt.hour, dt.minute)}" else day
}

fun isOverdue(millis: Long): Boolean = millis < System.currentTimeMillis()

@Composable
fun DueChip(millis: Long) {
    val overdue = isOverdue(millis)
    val bg = if (overdue) Color(0xFFE5484D).copy(alpha = 0.15f) else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (overdue) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSecondaryContainer
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(formatDue(millis), style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Dot(color: Color, sizeDp: Int = 8) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(color))
}
