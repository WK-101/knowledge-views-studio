package com.todocompanion.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.BookmarkBorder
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.Density
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.theme.LightKairoColors
import com.todocompanion.app.ui.theme.LocalKairoColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// Priority hues route through the one semantic palette (single source; MEDIUM now matches `warn`
// instead of a near-duplicate amber). Fixed to the light-palette values — priority tint reads the same
// in light/dark/AMOLED, which is intentional for at-a-glance ranking.
fun priorityColor(level: PriorityLevel): Color = when (level) {
    PriorityLevel.HIGH -> LightKairoColors.bad
    PriorityLevel.MEDIUM -> LightKairoColors.warn
    PriorityLevel.LOW -> LightKairoColors.info
    PriorityLevel.NONE -> LightKairoColors.neutral
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
 *
 * Tap completes; long-press (when [onSetLevel] is supplied) opens a quick priority-colour picker.
 */
private fun SemanticsPropertyReceiver.checkboxSemantics(desc: String, on: Boolean) {
    contentDescription = desc
    role = Role.Checkbox
    toggleableState = if (on) ToggleableState.On else ToggleableState.Off
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PriorityCheckbox(checked: Boolean, level: PriorityLevel, onCheckedChange: () -> Unit, onSetLevel: ((PriorityLevel) -> Unit)? = null) {
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
    var picker by remember { mutableStateOf(false) }
    // Accessibility (F2): announce completion state + role so TalkBack reads a real toggle.
    val a11y = if (checked) "Completed. Double-tap to mark incomplete." else "Mark complete."
    Box(
        // 48dp is the Material minimum touch target; the visual box below stays 22dp, so only the
        // tappable area grows (a11y — was a 40dp sub-spec target).
        Modifier.size(48.dp).clip(shape)
            .semantics { checkboxSemantics(a11y, checked) }
            .combinedClickable(onClick = onCheckedChange, onLongClick = { if (onSetLevel != null) picker = true }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).clip(shape).background(ring.copy(alpha = restTint + (1f - restTint) * prog)).border(2.dp, ring, shape),
            contentAlignment = Alignment.Center,
        ) {
            // The check must contrast with whatever fills the box — a saturated priority colour, or the
            // neutral `outline` for a no-priority task. Pick black on light fills, white on dark, so it stays
            // legible in light, dark and AMOLED (a hardcoded white vanished on the light-ish `outline` fill).
            Icon(Icons.Filled.Check, null, tint = if (ring.luminance() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(15.dp).scale(prog))
        }
    }
    // A bottom sheet, not a checkbox-anchored dropdown — a dropdown on a bottom row lands under
    // the add button. A sheet slides up over everything (including the FAB) and is the pattern
    // TickTick itself uses, so it can never collide with the add button.
    if (onSetLevel != null && picker) {
        PrioritySheet(current = level, onPick = { onSetLevel(it); picker = false }, onDismiss = { picker = false })
    }
}

/** Bottom-sheet priority picker — full-width rows with a coloured flag, a label, and a tick on the
 *  current level. Slides over the FAB, so it never overlaps the add button. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PrioritySheet(current: PriorityLevel, onPick: (PriorityLevel) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Set priority", Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            listOf(PriorityLevel.HIGH, PriorityLevel.MEDIUM, PriorityLevel.LOW, PriorityLevel.NONE).forEach { lvl ->
                val c = priorityColor(lvl)
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(lvl) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (lvl == PriorityLevel.NONE) Icons.Outlined.Flag else Icons.Filled.Flag, null,
                        tint = c, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(16.dp))
                    Text(lvl.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                    if (lvl == current) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
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
    val box = maxOf(iconSize + 16.dp, 48.dp)
    // The "flag" marker uses a BOOKMARK so it's visually distinct from PRIORITY (which is the coloured
    // flag, the TickTick/Todoist convention) — the two used to share the flag icon and looked identical.
    Box(Modifier.size(box).clip(CircleShape).clickable { onCycleFlag() }, contentAlignment = Alignment.Center) {
        if (flagArgb != null) Icon(Icons.Filled.Bookmark, "Flag", tint = Color(flagArgb), modifier = Modifier.size(iconSize))
        else Icon(Icons.Outlined.BookmarkBorder, "Flag", tint = ghost, modifier = Modifier.size(iconSize))
    }
    Box(Modifier.size(box).clip(CircleShape).clickable { onToggleStar() }, contentAlignment = Alignment.Center) {
        if (starred) Icon(Icons.Filled.Star, "Star", tint = LocalKairoColors.current.star, modifier = Modifier.size(iconSize + 1.dp))
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

/** Like [formatDue] but appends the block end time when a duration is set on a timed task,
 *  e.g. "Today 14:00–15:30" — the TickTick-style time span. */
fun formatDueSpan(millis: Long, durationMin: Int?): String {
    val base = formatDue(millis)
    if (durationMin == null || durationMin <= 0) return base
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(millis).atZone(zone)
    if (start.hour == 0 && start.minute == 0) return base   // all-day: no span
    val end = start.plusMinutes(durationMin.toLong())
    return "$base–${"%02d:%02d".format(end.hour, end.minute)}"
}

fun isOverdue(millis: Long): Boolean = millis < System.currentTimeMillis()

/** Days-remaining label: "Today" / "3d left" / "2d ago". */
fun countdownLabel(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), d)
    return when {
        days == 0L -> "Today"
        days > 0 -> "${days}d left"
        else -> "${-days}d ago"
    }
}

/** Compact, borderless date label (TickTick-style): coloured text, no chip background.
 *  Tap to toggle between the date and a live days-remaining countdown. */
@Composable
fun DueChip(millis: Long) {
    val overdue = isOverdue(millis)
    var countdown by remember(millis) { mutableStateOf(false) }
    Text(
        if (countdown) countdownLabel(millis) else formatDue(millis),
        modifier = Modifier.clickable { countdown = !countdown },
        style = MaterialTheme.typography.labelMedium,
        color = if (overdue) LocalKairoColors.current.bad else MaterialTheme.colorScheme.onSurfaceVariant,
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
    // Accessibility: announce the same real toggle (role + state) as PriorityCheckbox, so TalkBack reads
    // this matrix/compact checkbox instead of an unlabelled tap target.
    val a11y = if (checked) "Completed. Double-tap to mark incomplete." else "Mark complete."
    Box(
        // Expanded tap area (was 30dp, a sub-spec touch target); the visual check below stays 18dp.
        Modifier.size(48.dp).clip(CircleShape).semantics { checkboxSemantics(a11y, checked) }.clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                .background(if (checked) color else Color.Transparent)
                .border(1.5.dp, color, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Contrast: the tick sits on the (caller-supplied) fill colour, which may be light or dark —
            // pick black on light fills, white on dark, so it never vanishes (matches PriorityCheckbox).
            if (checked) Icon(Icons.Filled.Check, null, tint = if (color.luminance() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(13.dp))
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

/** Right-side trailing labels under the flag+star (MLO layout): @contexts, #tags, list.
 *  When a nav callback is supplied, tapping a label jumps to that list/context/tag view. */
@Composable
fun TaskTrailingLabels(
    contexts: List<Pair<String, Long?>>, tags: List<Pair<String, Long?>>, listName: String?,
    onListClick: (() -> Unit)? = null, onContextClick: ((String) -> Unit)? = null, onTagClick: ((String) -> Unit)? = null,
) {
    if (contexts.isEmpty() && tags.isEmpty() && listName == null) return
    Spacer(Modifier.size(2.dp))
    Column(horizontalAlignment = Alignment.End) {
        contexts.take(2).forEach { (name, argb) ->
            Text("@$name", style = MaterialTheme.typography.labelMedium,
                color = argb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (onContextClick != null) Modifier.clickable { onContextClick(name) } else Modifier)
        }
        tags.take(2).forEach { (name, argb) ->
            Text("#$name", style = MaterialTheme.typography.labelMedium,
                color = argb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (onTagClick != null) Modifier.clickable { onTagClick(name) } else Modifier)
        }
        if (listName != null) Text(listName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (onListClick != null) Modifier.clickable { onListClick() } else Modifier)
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

/**
 * The app's one text-field grammar — a calm, borderless, filled field (no hard outline), rounded to
 * match cards and chips. It's a drop-in for M3's OutlinedTextField (same parameter names), so every
 * input across the app reads as one soft tonal surface rather than a boxed outline. This is the
 * "borderless, compact but calm" direction the modern reference apps (Todoist, TickTick, Notion) share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun borderlessFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
    errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    textStyle: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
) {
    TextField(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
        label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        isError = isError, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        textStyle = textStyle, shape = shape, colors = borderlessFieldColors(),
    )
}

/**
 * The card surface — one step brighter than the ground in light mode (a white card on grey), one
 * step *lifted* off the ground in dark/AMOLED (so cards never vanish into a black background). This
 * uses the modern M3 tonal container roles rather than the pre-2023 `surface + tonalElevation` tint.
 */
@Composable
fun appCardColor(): Color {
    val cs = MaterialTheme.colorScheme
    return if (cs.surface.luminance() < 0.5f) cs.surfaceContainer else cs.surfaceContainerLowest
}

/** A nested tile *inside* a card (a stat tile, an inset row) — one further tonal step from the card. */
@Composable
fun appTileColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/**
 * The one label-and-switch row. A leading [title] (with optional [subtitle]) and a trailing Material
 * Switch — the single grammar for every on/off setting, replacing the per-screen Toggle / SwitchRow /
 * EditorToggle / EditorSwitch / ModToggle clones.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A white rounded card on the grey ground — the app's one card grammar. [verticalArrangement]
 *  spaces the card's children (defaults to flush, i.e. the caller inserts its own Spacers). Pass
 *  [onClick] for a tappable card, [shape]/[color] only to deviate from the canonical look. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    color: Color = appCardColor(),
    onClick: (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color) {
            Column(Modifier.padding(padding), verticalArrangement = verticalArrangement, content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color) {
            Column(Modifier.padding(padding), verticalArrangement = verticalArrangement, content = content)
        }
    }
}

/** A collapsible section: a tappable header (title + a chevron that rotates on expand) with an optional
 *  one-line [summary] shown while collapsed, and [content] revealed when expanded. Open state is
 *  remembered per [title] within the composition. Use to fold away secondary/advanced content so a
 *  screen opens as a scannable summary, not a wall of cards. */
@Composable
fun ExpandableSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                if (!expanded && summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "chev")
            Icon(Icons.Filled.KeyboardArrowDown, if (expanded) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
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

/**
 * PC2 — a warm, consistent empty state: a big emoji in a soft disc, a title, a line of encouragement,
 * and one clear call-to-action. Used wherever a list, grid or report has nothing in it yet, so the
 * newcomer's first impression across the whole app is inviting rather than blank.
 */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, style = MaterialTheme.typography.displaySmall) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(actionLabel, Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/**
 * PC4 — a dismissible, in-context discoverability hint. Surfaces a powerful-but-hidden capability with a
 * light bulb, a one-line tip, and a "Got it" that remembers (per [tipKey]) so it never nags twice. The
 * caller gates visibility on whether [tipKey] has been dismissed.
 */
@Composable
fun TipBanner(text: String, onDismiss: () -> Unit, onAction: (() -> Unit)? = null, actionLabel: String? = null) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("💡", Modifier.padding(end = 10.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (actionLabel != null && onAction != null) {
                Text(actionLabel, Modifier.clickable(role = Role.Button) { onAction() }.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("Got it", Modifier.clickable(role = Role.Button, onClickLabel = "Dismiss tip") { onDismiss() }.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
