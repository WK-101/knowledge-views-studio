package com.todocompanion.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.todocompanion.app.domain.calendar.Availability
import com.todocompanion.app.domain.calendar.CalendarEngine
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * R55/R56 — "When am I free?" A private, offline free-time dashboard, now block-centric for expert use.
 * For a day / week / month it shows not just total open time but every open BLOCK — a proportional
 * busy/free timeline per day so gaps jump out, the best openings across the range (exact date + time +
 * duration, sorted), a duration filter that highlights blocks that fit and points to the next one, and an
 * over-commitment check (your task workload due in the range vs. the free time you actually have). It reads
 * only your local events, availability weekdays, working-hours window and buffer. Nothing is shared.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AvailabilitySheet(vm: AppViewModel, anchorDay: Long, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val events by vm.events.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val s by vm.settings.collectAsState()
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = LocalDate.now(zone)

    var range by remember { mutableStateOf("Week") }        // Day / Week / Month
    var anchor by remember { mutableStateOf(LocalDate.ofEpochDay(anchorDay)) }
    var minDur by remember { mutableStateOf(60) }           // duration-aware "openings" filter
    var openingsSort by remember { mutableStateOf("Longest") } // Longest / Soonest
    var timePref by remember { mutableStateOf("Any") }      // Wave C ranked slots: Any / Morning / Afternoon / Evening

    val cfg = Availability.Config(
        days = Availability.parseDays(s.availDays),
        startHour = s.workStartHour, endHour = s.workEndHour,
        minSlotMin = s.availMinSlotMin, bufferMin = s.availBufferMin,
    )
    val firstDow = if (s.weekStart in 1..7) DayOfWeek.of(s.weekStart) else WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val days: List<LocalDate> = when (range) {
        "Day" -> listOf(anchor)
        "Month" -> { val first = anchor.withDayOfMonth(1); (0 until anchor.lengthOfMonth()).map { first.plusDays(it.toLong()) } }
        else -> { val start = startOfWk(anchor, firstDow); (0..6).map { start.plusDays(it.toLong()) } }
    }
    // Don't count days already in the past for a fair "free time left".
    val free = Availability.forDays(events, days, cfg, zone).map { d ->
        if (d.date.isBefore(today)) d.copy(available = false, slots = emptyList(), busy = emptyList(), freeMin = 0, busyMin = 0) else d
    }
    val totalFree = Availability.totalFreeMin(free)
    val totalWindow = Availability.totalWindowMin(free)
    val longest = Availability.longest(free)
    val openingsCount = Availability.openingsOfAtLeast(free, minDur)
    val availCount = Availability.availableDayCount(free)
    val blockCount = Availability.totalBlockCount(free)
    val avgBlock = Availability.avgBlockMin(free)
    val nextFit = Availability.firstOpeningOfAtLeast(free, minDur)
    // Wave C — ranked slots: bucket each opening by time of day so a preferred window floats to the top.
    fun bucketOf(startMillis: Long): String {
        val h = Instant.ofEpochMilli(startMillis).atZone(zone).hour
        return when { h < 12 -> "Morning"; h < 17 -> "Afternoon"; else -> "Evening" }
    }
    fun bucketEmoji(b: String) = when (b) { "Morning" -> "☀️"; "Afternoon" -> "🌤️"; else -> "🌆" }
    val bestOpenings = remember(free, minDur, openingsSort, timePref) {
        var list = Availability.openings(free, minDur)
        if (openingsSort == "Soonest") list = list.sortedBy { it.slot.startMillis }
        // A preferred time-of-day ranks its matches first (stable), keeping the chosen sort within each group.
        if (timePref != "Any") list = list.sortedByDescending { bucketOf(it.slot.startMillis) == timePref }
        list
    }.take(8)

    // Wave A — over-commitment: task workload due within the range (future, available days) vs. free time.
    val rangeStart = free.firstOrNull()?.date ?: today
    val rangeEnd = free.lastOrNull()?.date ?: today
    val committedMin = remember(tasks, rangeStart, rangeEnd, today) {
        tasks.filter { t ->
            !t.completed && !t.trashed && !t.someday && t.dueDate != null
        }.sumOf { t ->
            val d = Instant.ofEpochMilli(t.dueDate!!).atZone(zone).toLocalDate()
            if (!d.isBefore(today) && !d.isBefore(rangeStart) && !d.isAfter(rangeEnd))
                (t.durationMin ?: t.estimateMin ?: t.estimateMax ?: 0) else 0
        }
    }

    val df = DateTimeFormatter.ofPattern("EEE d MMM")
    val tf = DateTimeFormatter.ofPattern("h:mm a")
    fun slotText(slot: CalendarEngine.Slot) =
        "${Instant.ofEpochMilli(slot.startMillis).atZone(zone).format(tf)}–${Instant.ofEpochMilli(slot.endMillis).atZone(zone).format(tf)}"

    val periodLabel = when (range) {
        "Day" -> anchor.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        "Month" -> anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        else -> "${rangeStart.format(DateTimeFormatter.ofPattern("d MMM"))} – ${rangeEnd.format(DateTimeFormatter.ofPattern("d MMM"))}"
    }
    fun shift(dir: Int) {
        anchor = when (range) {
            "Day" -> anchor.plusDays(dir.toLong())
            "Month" -> anchor.plusMonths(dir.toLong())
            else -> anchor.plusWeeks(dir.toLong())
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp).verticalScroll(rememberScrollState())) {
            Text("When am I free?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Your open blocks, at a glance — private, on-device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            // Range selector
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Day", "Week", "Month").forEach { r ->
                    FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r) })
                }
            }
            Spacer(Modifier.height(6.dp))
            // Date navigation: ‹ period › with a Today reset.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shift(-1) }) { Icon(Icons.Filled.ChevronLeft, "Previous") }
                Text(periodLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { shift(1) }) { Icon(Icons.Filled.ChevronRight, "Next") }
            }
            if (anchor != today && (range == "Day")) TextButton(onClick = { anchor = today }) { Text("Jump to today") }
            else if (range != "Day" && !(today in rangeStart..rangeEnd)) TextButton(onClick = { anchor = today }) { Text("Jump to this ${range.lowercase()}") }

            Spacer(Modifier.height(6.dp))
            // Headline
            AppCard {
                val pct = if (totalWindow > 0) (totalFree * 100 / totalWindow) else 0
                Text(Availability.fmtMinutes(totalFree) + " free", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("in $blockCount open block${if (blockCount == 1) "" else "s"} across $availCount available day${if (availCount == 1) "" else "s"} · $pct% of your window open",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (avgBlock > 0) Text("Average block ${Availability.fmtMinutes(avgBlock)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                longest?.let { (d, slot) ->
                    Spacer(Modifier.height(6.dp))
                    Text("Largest open block: ${Availability.fmtMinutes(slot.minutes.toInt())} on ${d.format(df)} (${slotText(slot)})",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Over-commitment (Wave A) — only shown when you actually have estimated work due in the range.
            if (committedMin > 0) {
                Spacer(Modifier.height(10.dp))
                val over = committedMin > totalFree
                AppCard {
                    Text(if (over) "⚠️ Over-committed" else "✅ It fits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text("${Availability.fmtMinutes(committedMin)} of estimated task work is due in this ${range.lowercase()} vs ${Availability.fmtMinutes(totalFree)} free.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    // A committed-vs-free bar.
                    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        val total = maxOf(committedMin, totalFree, 1)
                        Box(Modifier.weight(committedMin.toFloat().coerceAtLeast(0.001f) / total).fillMaxHeight().background(if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary))
                        Box(Modifier.weight(((total - committedMin).coerceAtLeast(0)).toFloat().coerceAtLeast(0.001f) / total).fillMaxHeight())
                    }
                    Text(if (over) "Over by ${Availability.fmtMinutes(committedMin - totalFree)} — defer, delegate, or shorten." else "${Availability.fmtMinutes(totalFree - committedMin)} to spare after your due work.",
                        style = MaterialTheme.typography.bodySmall, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            // Duration-aware "can I fit it?" + next opening (fit-this-here).
            Text("Openings of at least", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60, 90, 120).forEach { m ->
                    FilterChip(selected = minDur == m, onClick = { minDur = m }, label = { Text(Availability.fmtMinutes(m)) })
                }
            }
            Text("$openingsCount gap${if (openingsCount == 1) "" else "s"} of ${Availability.fmtMinutes(minDur)} or more in this ${range.lowercase()}.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            nextFit?.let {
                Text("→ Next: ${it.date.format(df)} at ${slotText(it.slot)} (${Availability.fmtMinutes(it.slot.minutes.toInt())})",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            }

            // Best openings — the block-centric list (date + exact time + duration), longest or soonest first.
            if (bestOpenings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Best openings", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { openingsSort = if (openingsSort == "Longest") "Soonest" else "Longest" }) {
                        Text(if (openingsSort == "Longest") "Longest first" else "Soonest first")
                    }
                }
                // Ranked slots: prefer a time of day (SavvyCal's "best times", turned inward).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Any", "Morning", "Afternoon", "Evening").forEach { p ->
                        FilterChip(selected = timePref == p, onClick = { timePref = p }, label = { Text(if (p == "Any") p else bucketEmoji(p) + " " + p) })
                    }
                }
                bestOpenings.forEach { o ->
                    val bucket = bucketOf(o.slot.startMillis)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(bucketEmoji(bucket), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(o.date.format(df), Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(slotText(o.slot), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Availability.fmtMinutes(o.slot.minutes.toInt()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            // Per-day visual timeline — the whole point: open blocks are seen, not just totalled.
            Text("Day-by-day", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            free.forEach { d ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.date.format(df), Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            color = if (d.date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        when {
                            !d.available -> Text(if (d.date.isBefore(today)) "—" else "Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            d.slots.isEmpty() -> Text("Fully booked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            else -> Text("${Availability.fmtMinutes(d.freeMin)} free · ${d.blockCount} block${if (d.blockCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (d.available) {
                        DayTimelineBar(d, minDur, zone)
                        if (d.slots.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
                                d.slots.forEach { slot ->
                                    val fits = slot.minutes >= minDur
                                    Text(slotText(slot) + " · " + Availability.fmtMinutes(slot.minutes.toInt()),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (fits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (fits) FontWeight.Medium else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            // Availability rules — compact, inline.
            Text("Availability rules", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val dowLabels = listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S")
            val selDays = Availability.parseDays(s.availDays)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                dowLabels.forEach { (n, l) ->
                    FilterChip(selected = n in selDays, onClick = {
                        val next = if (n in selDays) selDays - n else selDays + n
                        vm.saveSettings(s.copy(availDays = next.sorted().joinToString(",")))
                    }, label = { Text(l) })
                }
            }
            Text("Daily window: ${"%02d:00".format(s.workStartHour)}–${"%02d:00".format(s.workEndHour)} (your working hours) · min gap ${Availability.fmtMinutes(s.availMinSlotMin)} · buffer ${s.availBufferMin}m",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Min gap", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                listOf(15, 30, 45, 60).forEach { m ->
                    FilterChip(selected = s.availMinSlotMin == m, onClick = { vm.saveSettings(s.copy(availMinSlotMin = m)) }, label = { Text("${m}m") })
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Buffer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                listOf(0, 5, 10, 15).forEach { m ->
                    FilterChip(selected = s.availBufferMin == m, onClick = { vm.saveSettings(s.copy(availBufferMin = m)) }, label = { Text("${m}m") })
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = {
                val txt = buildString {
                    append("My availability (${range.lowercase()}, $periodLabel): ${Availability.fmtMinutes(totalFree)} free\n")
                    free.filter { it.available && it.slots.isNotEmpty() }.forEach { d ->
                        append("${d.date.format(df)}: ")
                        append(d.slots.joinToString(", ") { slotText(it) })
                        append("\n")
                    }
                }
                runCatching {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Availability", txt))
                    android.widget.Toast.makeText(ctx, "Availability copied.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Icon(Icons.Filled.ContentCopy, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy availability") }
        }
    }
}

/**
 * A proportional bar across the day's availability window: busy stretches shaded, free stretches in green
 * (bright when they fit the chosen duration, soft otherwise). This is what makes open blocks jump out.
 */
@Composable
private fun DayTimelineBar(d: Availability.DayFree, minDur: Int, zone: ZoneId) {
    val winStart = d.windowStartMillis; val winEnd = d.windowEndMillis
    val span = (winEnd - winStart).coerceAtLeast(1L)
    // Build an ordered sequence of segments (busy / free) covering the whole window.
    data class Seg(val minutes: Float, val busy: Boolean, val fits: Boolean)
    val segs = ArrayList<Seg>()
    var pos = winStart
    val busy = d.busy.sortedBy { it.startMillis }
    for (b in busy) {
        val bs = b.startMillis.coerceIn(winStart, winEnd); val be = b.endMillis.coerceIn(winStart, winEnd)
        if (bs > pos) { val mins = (bs - pos) / 60000f; segs.add(Seg(mins, false, mins >= minDur)) }
        if (be > bs) segs.add(Seg((be - bs) / 60000f, true, false))
        pos = maxOf(pos, be)
    }
    if (pos < winEnd) { val mins = (winEnd - pos) / 60000f; segs.add(Seg(mins, false, mins >= minDur)) }
    if (segs.isEmpty()) return
    val freeSoft = MaterialTheme.colorScheme.primary.copy(alpha = .30f)
    val freeFit = MaterialTheme.colorScheme.primary
    val busyCol = MaterialTheme.colorScheme.error.copy(alpha = .38f)
    val totalMin = span / 60000f
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(14.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        segs.forEach { seg ->
            Box(
                Modifier.weight((seg.minutes / totalMin).coerceAtLeast(0.001f)).fillMaxHeight()
                    .background(if (seg.busy) busyCol else if (seg.fits) freeFit else freeSoft)
            )
        }
    }
}

private fun startOfWk(d: LocalDate, firstDow: DayOfWeek): LocalDate {
    val wf = WeekFields.of(firstDow, 1)
    return d.with(wf.dayOfWeek(), 1)
}
