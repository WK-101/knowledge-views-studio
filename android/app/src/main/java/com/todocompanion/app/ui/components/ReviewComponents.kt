package com.todocompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The ONE stat-tile grammar for every review/analytics surface. A tonal rounded box with a big
 * value and a small caption — the "tonal at-a-glance box" first grown in Day review. It folds in the
 * needs the six near-identical clones used to carry separately:
 *   • [icon]     — an optional emoji glyph on top (Day review's at-a-glance tiles).
 *   • [sub]      — an optional third line (Statistics' "N sessions", the trend "▲ 12% vs baseline").
 *   • [subColor] — the sub line's colour (the trend tint: rising/easing/level); defaults to a muted outline.
 * The value stays [onSurface] bold so it reads the same as [MetricTile] beside it — one look everywhere.
 */
@Composable
internal fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    sub: String? = null,
    subColor: Color? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(appTileColor())
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            // The app's "done" glyph is the modern filled disc, not a raw "✓" — so a checkmark icon
            // renders as a small [DoneTick] (Day review's at-a-glance / Close-the-day "done" tiles).
            if (icon == "✓" || icon == "✔") DoneTick(Modifier.size(20.dp))
            else Text(icon, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
        }
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.labelSmall, color = subColor ?: MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A metric shown as a self-contained tile in a grid (habits, tracked activities): leading glyph/dot,
 *  name, a big value and a slim progress meter — the same tonal-box language as [StatTile]. */
internal data class Metric(val emoji: String?, val name: String, val value: String, val frac: Float, val color: Color)

@Composable
internal fun MetricTile(m: Metric, modifier: Modifier = Modifier) {
    // Name at the top, value + meter anchored to the BOTTOM (Arrangement.SpaceBetween). In a 2-up row the
    // row is measured at IntrinsicSize.Min and each tile fills that height (see [MetricTileGrid]), so when
    // one tile's name wraps to two lines and its neighbour's doesn't, the shorter tile stretches to match
    // and its value + meter still line up along the bottom with its neighbour's — no ragged, mid-height bars.
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(appTileColor()).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (m.emoji != null) Text(m.emoji, style = MaterialTheme.typography.titleMedium)
            else Box(Modifier.size(12.dp).clip(CircleShape).background(m.color))
            Spacer(Modifier.width(7.dp))
            // Names wrap to two lines so long activity/habit names stay readable (the tile grows to fit);
            // beyond two lines they ellipsize — but a name that long is given its OWN full-width column by
            // [metricColumnsFor], where two lines is plenty of room, so truncation is effectively never hit.
            Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text(m.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxWidth(m.frac.coerceIn(0.04f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(m.color))
            }
        }
    }
}

/** Column count for laying [metrics] out as tiles: a single metric or ANY long name takes the full row
 *  width (1 column) so the name has room to wrap cleanly; otherwise the compact 2-up grid. Used by both the
 *  Day-review "Time tracked" and "Habits" cards so long activity/habit names never render cramped. */
internal fun metricColumnsFor(metrics: List<Metric>): Int =
    if (metrics.size <= 1 || metrics.any { it.name.length > 16 }) 1 else 2

/** Lay a list of [Metric]s out as tiles, matching the at-a-glance box row. [columns] is normally 2; pass 1
 *  to give each tile the full row width (used for long activity names, where a cramped 2-up would truncate) —
 *  see [metricColumnsFor] for the adaptive choice. Within a 2-up chunk the row is measured at
 *  [IntrinsicSize.Min] and each tile fills that height, so a tile whose name wraps to two lines and its
 *  shorter neighbour stay the same height. */
@Composable
internal fun MetricTileGrid(metrics: List<Metric>, columns: Int = 2) {
    if (columns <= 1) {
        metrics.forEachIndexed { i, m ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            MetricTile(m, Modifier.fillMaxWidth())
        }
        return
    }
    metrics.chunked(2).forEachIndexed { i, row ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { m -> MetricTile(m, Modifier.weight(1f).fillMaxHeight()) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** A "dynamic card" row — leading marker, name, trailing value and a proportional meter bar, matching
 *  the activity time-tracking breakdown. */
@Composable
internal fun MeterRow(leading: @Composable () -> Unit, name: String, trailing: String, frac: Float, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) { leading() }
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0.03f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

/** Small bold section heading used inside review cards. */
@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
}

/** A modern filled-circle check (completed), à la TickTick/Things. */
@Composable
internal fun DoneTick(modifier: Modifier = Modifier) {
    Box(modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** An outlined circle for an open / not-done item. */
@Composable
internal fun OpenTick(modifier: Modifier = Modifier) {
    Icon(Icons.Outlined.Circle, null, modifier.size(22.dp), tint = MaterialTheme.colorScheme.outline)
}

/** A modern filled check in a tinted circle — the app's single "done" glyph, used for read-back ticks
 *  (three good things, etc.) so no raw "✓" characters remain. Smaller sibling of [DoneTick]. */
@Composable
internal fun MiniCheck(modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** A small tonal pill naming a day's precise emotion, rendered beside the mood face in read-backs. */
@Composable
internal fun EmotionChip(word: String) {
    Text(
        word, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
