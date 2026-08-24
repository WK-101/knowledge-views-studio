package com.todocompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.priority.PriorityLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

fun priorityColor(level: PriorityLevel): Color = when (level) {
    PriorityLevel.HIGH -> Color(0xFFE53935)
    PriorityLevel.MEDIUM -> Color(0xFFFB8C00)
    PriorityLevel.LOW -> Color(0xFF1E88E5)
    PriorityLevel.NONE -> Color(0xFF9E9E9E)
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
    val bg = if (overdue) Color(0xFFE53935).copy(alpha = 0.15f)
    else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (overdue) Color(0xFFE53935) else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            formatDue(millis),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Dot(color: Color, sizeDp: Int = 8) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(color)
    )
}
