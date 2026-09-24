package app.parley.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.history.Heatmap
import app.parley.common.history.NumberKeys
import app.parley.common.history.TrendDirection
import app.parley.ui.common.Format
import app.parley.ui.contact.Section
import app.parley.ui.home.callTypeIcon
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * H6: per-person call insights for contact detail and number history: every number (E.164), last call,
 * monthly average, trend, weekday × hour heatmap, "usually answers after 6 pm", call rhythm, and the
 * per-person "Keep forever" switch of the archive (H1). [numbers] are all of the person's numbers.
 */
@Composable
fun CallInsightsSection(vm: AppViewModel, numbers: List<String>, title: String = "Calls") {
    val index by vm.c.history.index.collectAsStateWithLifecycle()
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val idx = index ?: return
    val first = numbers.firstOrNull { it.isNotBlank() } ?: return
    val key = remember(idx, first) { idx.personKeyFor(first) }
    val ins = remember(idx, key) { idx.insights(key) } ?: return
    if (ins.totals.total == 0) return

    Column {
        Section(title)
        val shown = ins.numbers.map { NumberKeys.e164(it) ?: it.removePrefix("#") }
        if (shown.size > 1 || shown.firstOrNull() != first) {
            Text(shown.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        ins.lastCall?.let { last ->
            val (icon, tint) = callTypeIcon(last.type)
            ListItem(
                leadingContent = { Icon(icon, null, tint = tint) },
                headlineContent = { Text("Last call " + android.text.format.DateUtils.getRelativeTimeSpanString(last.date, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)) },
                supportingContent = {
                    Text(
                        listOfNotNull(Format.fullDate(context, last.date), Format.duration(last.durationSec).ifBlank { null }).joinToString(" · ") +
                            "\n${ins.totals.total} calls · talk ${HistoryFormat.talk(ins.totals.talkSec)} (${HistoryFormat.talk(ins.totals.talkOutSec)} out, ${HistoryFormat.talk(ins.totals.talkInSec)} in) · " +
                            "about ${"%.1f".format(ins.averagePerMonth)} a month",
                    )
                },
            )
        }
        val t = ins.trend
        ListItem(
            leadingContent = {
                Icon(
                    when (t.direction) {
                        TrendDirection.UP -> Icons.AutoMirrored.Rounded.TrendingUp
                        TrendDirection.DOWN -> Icons.AutoMirrored.Rounded.TrendingDown
                        TrendDirection.STEADY -> Icons.AutoMirrored.Rounded.TrendingFlat
                    },
                    null,
                )
            },
            headlineContent = {
                Text(
                    when (t.direction) {
                        TrendDirection.UP -> "More calls lately"
                        TrendDirection.DOWN -> "Fewer calls lately"
                        TrendDirection.STEADY -> "About as often as before"
                    },
                )
            },
            supportingContent = { Text("${t.recent} calls in the last 90 days, ${t.previous} in the 90 days before") },
        )
        ins.rhythm?.let { r ->
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Update, null) },
                headlineContent = { Text("You usually talk every ${r.usualGapDays} ${if (r.usualGapDays == 1) "day" else "days"}") },
                supportingContent = { Text(if (r.daysSinceLast == 0) "Last talked today" else "Last talked ${r.daysSinceLast} ${if (r.daysSinceLast == 1) "day" else "days"} ago") },
            )
        }
        ins.answerWindow?.let { w ->
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Schedule, null) },
                headlineContent = { Text(w.label.replaceFirstChar { it.uppercase() }) },
                supportingContent = { Text("From how often your calls get answered") },
            )
        }
        if (ins.heatmap.total >= 3) {
            Text("When you talk", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            HeatmapGrid(ins.heatmap, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (prefs.archiveEnabled) KeepForeverRow(vm, numbers)
    }
}

@Composable
private fun KeepForeverRow(vm: AppViewModel, numbers: List<String>) {
    val scope = rememberCoroutineScope()
    val kept by vm.c.history.keptForever.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(numbers, kept) { on = numbers.isNotEmpty() && vm.c.history.isKeptForever(numbers.first()) }
    fun toggle(v: Boolean) {
        on = v
        scope.launch { vm.c.history.setKeepForever(numbers, v) }
    }
    ListItem(
        modifier = Modifier.clickable { toggle(!on) },
        leadingContent = { Icon(Icons.Rounded.AllInclusive, null) },
        headlineContent = { Text("Keep this call history forever") },
        supportingContent = { Text("Parley's archive keeps these calls even when history older than your retention setting is deleted") },
        trailingContent = { Switch(on, ::toggle) },
    )
}

/** Weekday × hour grid; darker = more calls. Rows follow Monday..Sunday. */
@Composable
fun HeatmapGrid(h: Heatmap, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val max = h.max.coerceAtLeast(1)
    val peak = h.peak()
    val locale = Locale.getDefault()
    Column(modifier) {
        Row {
            Column(Modifier.width(28.dp)) {
                DayOfWeek.entries.forEach { d ->
                    Text(d.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(14.dp))
                }
            }
            Canvas(
                Modifier.weight(1f).height(98.dp).semantics {
                    contentDescription = peak?.let { (d, hr) -> "Most calls on ${d.getDisplayName(TextStyle.FULL, locale)} around $hr:00" } ?: "No calls"
                },
            ) {
                val cw = size.width / 24f
                val ch = size.height / 7f
                val pad = 1.dp.toPx()
                DayOfWeek.entries.forEachIndexed { row, d ->
                    for (hr in 0..23) {
                        val n = h[d, hr]
                        val c = if (n == 0) empty else color.copy(alpha = 0.2f + 0.8f * n / max)
                        drawRoundRect(c, Offset(hr * cw + pad, row * ch + pad), Size(cw - 2 * pad, ch - 2 * pad), CornerRadius(2.dp.toPx()))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp)) {
            listOf("0", "6", "12", "18", "24").forEachIndexed { i, s ->
                Text(s, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (i < 4) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * H7: in the keep-in-touch editor, suggests an interval from the call rhythm ("you usually talk every 9 days").
 * Shows nothing without a clear rhythm.
 */
@Composable
fun RhythmSuggestion(vm: AppViewModel, numbers: List<String>, onPick: (Int) -> Unit) {
    val index by vm.c.history.index.collectAsStateWithLifecycle()
    val idx = index ?: return
    val first = numbers.firstOrNull { it.isNotBlank() } ?: return
    val r = remember(idx, first) { idx.rhythm(idx.personKeyFor(first)) } ?: return
    ListItem(
        modifier = Modifier.clickable { onPick(r.suggestedReminderDays) },
        leadingContent = { Icon(Icons.Rounded.Update, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text("If you haven't talked in ${r.suggestedReminderDays} days") },
        supportingContent = { Text("Suggested: you usually talk every ${r.usualGapDays} ${if (r.usualGapDays == 1) "day" else "days"}") },
    )
}
