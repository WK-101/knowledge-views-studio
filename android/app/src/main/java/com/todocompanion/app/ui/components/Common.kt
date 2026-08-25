package com.todocompanion.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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

val FLAG_COLORS = listOf(0xFFE5484D, 0xFFF59E0B, 0xFF12A594, 0xFF3E7BFA, 0xFF8B5CF6)

fun nextFlagColor(current: Long?): Long? {
    if (current == null) return FLAG_COLORS.first()
    val i = FLAG_COLORS.indexOf(current)
    return if (i < 0 || i == FLAG_COLORS.lastIndex) null else FLAG_COLORS[i + 1]
}

fun rowVerticalPadding(d: Density): Dp = when (d) {
    Density.COMPACT -> 6.dp
    Density.DEFAULT -> 10.dp
    Density.RELAXED -> 14.dp
}

/**
 * MLO-style priority checkbox: a rounded *square* whose fill is a translucent tint of the
 * task's priority colour when unchecked (so high-priority rows read at a glance), filling to
 * a solid colour with an animated tick when checked.
 */
@Composable
fun PriorityCheckbox(checked: Boolean, level: PriorityLevel, onCheckedChange: () -> Unit) {
    val ring = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.outline else priorityColor(level)
    val prog by animateFloatAsState(if (checked) 1f else 0f, label = "check")
    val shape = RoundedCornerShape(6.dp)
    // Unchecked: faint priority tint (stronger for higher priority). Checked: solid fill.
    val restTint = when (level) {
        PriorityLevel.NONE -> 0f
        PriorityLevel.LOW -> 0.10f
        PriorityLevel.MEDIUM -> 0.14f
        PriorityLevel.HIGH -> 0.18f
    }
    Box(Modifier.size(40.dp).clip(shape).clickable { onCheckedChange() }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(22.dp).clip(shape).background(ring.copy(alpha = restTint + (1f - restTint) * prog)).border(2.dp, ring, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp).scale(prog))
        }
    }
}

/** Flag/star icon size scaled to the row density, MLO-style (bigger in roomier rows). */
fun flagStarSize(d: Density): Dp = when (d) {
    Density.COMPACT -> 22.dp
    Density.DEFAULT -> 26.dp
    Density.RELAXED -> 30.dp
}

/**
 * Trailing flag + star, MLO-style. Large, with a light "ghost" outline when unset
 * (transparent-filled) and a solid colour when set. Flag cycles colours; star toggles.
 */
@Composable
fun FlagStar(flagArgb: Long?, starred: Boolean, onCycleFlag: () -> Unit, onToggleStar: () -> Unit, iconSize: Dp = 26.dp) {
    val ghost = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val box = iconSize + 16.dp
    Box(Modifier.size(box).clip(CircleShape).clickable { onCycleFlag() }, contentAlignment = Alignment.Center) {
        if (flagArgb != null) Icon(Icons.Filled.Flag, "Flag", tint = Color(flagArgb), modifier = Modifier.size(iconSize))
        else Icon(Icons.Outlined.Flag, "Flag", tint = ghost, modifier = Modifier.size(iconSize))
    }
    Box(Modifier.size(box).clip(CircleShape).clickable { onToggleStar() }, contentAlignment = Alignment.Center) {
        if (starred) Icon(Icons.Filled.Star, "Star", tint = Color(0xFFF5A623), modifier = Modifier.size(iconSize + 1.dp))
        else Icon(Icons.Outlined.StarOutline, "Star", tint = ghost, modifier = Modifier.size(iconSize + 1.dp))
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
    // Countdown badge for dates beyond a week — an at-a-glance "days remaining".
    val daysOut = java.time.temporal.ChronoUnit.DAYS.between(today, d)
    val countdown = if (daysOut > 6) " · ${daysOut}d" else ""
    // Midnight (00:00) is the all-day sentinel; any other time (incl. 9:00 AM) is a real time.
    val hasTime = !(dt.hour == 0 && dt.minute == 0)
    return (if (hasTime) "$day ${"%02d:%02d".format(dt.hour, dt.minute)}" else day) + countdown
}

fun isOverdue(millis: Long): Boolean = millis < System.currentTimeMillis()

/** Compact, borderless date label (TickTick-style): coloured text, no chip background. */
@Composable
fun DueChip(millis: Long) {
    val overdue = isOverdue(millis)
    Text(
        formatDue(millis),
        style = MaterialTheme.typography.labelMedium,
        color = if (overdue) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Dot(color: Color, sizeDp: Int = 8) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(color))
}

/** A compact square checkbox (TickTick matrix style): coloured outline that fills with a tick when done. */
@Composable
fun SmallCheck(checked: Boolean, color: Color, onToggle: () -> Unit) {
    Box(Modifier.size(30.dp).clip(CircleShape).clickable { onToggle() }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                .background(if (checked) color else Color.Transparent)
                .border(1.5.dp, color, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
    }
}

/** Left-side meta under the title (MLO layout): date + repeat glyph, then a note preview. */
@Composable
fun TaskLeftMeta(dueMillis: Long?, note: String, repeating: Boolean) {
    if (dueMillis != null || repeating) {
        Spacer(Modifier.size(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            dueMillis?.let { DueChip(it) }
            if (repeating) { if (dueMillis != null) Spacer(Modifier.size(6.dp)); Icon(Icons.Filled.Repeat, "Repeats", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp)) }
        }
    }
    if (note.isNotBlank()) {
        Spacer(Modifier.size(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Notes, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(13.dp))
            Spacer(Modifier.size(4.dp))
            Text(note.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Right-side trailing labels under the flag+star (MLO layout): @contexts, #tags, list. */
@Composable
fun TaskTrailingLabels(contexts: List<Pair<String, Long?>>, tags: List<Pair<String, Long?>>, listName: String?) {
    if (contexts.isEmpty() && tags.isEmpty() && listName == null) return
    Spacer(Modifier.size(2.dp))
    Column(horizontalAlignment = Alignment.End) {
        contexts.take(2).forEach { (name, argb) ->
            Text("@$name", style = MaterialTheme.typography.labelMedium,
                color = argb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        tags.take(2).forEach { (name, argb) ->
            Text("#$name", style = MaterialTheme.typography.labelMedium,
                color = argb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (listName != null) Text(listName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * MLO-style per-task detail block: an optional note/description preview line,
 * then a wrapping meta line of due-date, @contexts and #tags. Renders nothing
 * when there's nothing to show, so simple tasks stay compact.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskMeta(
    dueMillis: Long?,
    contexts: List<Pair<String, Long?>>,
    tags: List<Pair<String, Long?>>,
    note: String,
    listName: String? = null,
    repeating: Boolean = false,
) {
    val hasNote = note.isNotBlank()
    val hasMeta = dueMillis != null || contexts.isNotEmpty() || tags.isNotEmpty() || listName != null || repeating
    if (!hasNote && !hasMeta) return

    if (hasNote) {
        Spacer(Modifier.size(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Notes, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(13.dp))
            Spacer(Modifier.size(4.dp))
            Text(
                note.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (hasMeta) {
        Spacer(Modifier.size(3.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            dueMillis?.let { DueChip(it) }
            if (repeating) Icon(Icons.Filled.Repeat, "Repeats", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
            contexts.forEach { (name, argb) ->
                Text("@$name", style = MaterialTheme.typography.labelMedium,
                    color = argb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            tags.forEach { (name, argb) ->
                Text("#$name", style = MaterialTheme.typography.labelMedium,
                    color = argb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (listName != null) Text(listName, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A white rounded card on the grey ground — the app's core surface grammar. */
@Composable
fun AppCard(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) { Column(Modifier.padding(padding), content = content) }
}

/** Small caps section label used inside cards. */
@Composable
fun CardLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
