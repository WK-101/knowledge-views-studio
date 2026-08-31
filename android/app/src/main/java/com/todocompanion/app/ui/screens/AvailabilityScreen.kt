package com.todocompanion.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.calendar.Availability
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * R55 — "When am I free?" A private, offline free-time dashboard. For a chosen day / week / month it
 * shows your total open time, the largest contiguous block, and how many gaps of a chosen length exist —
 * so you can answer "do I have a free 90 minutes this week?" at a glance, without hand-checking the
 * calendar. It reads only your local events (busy ones), your availability weekdays, working-hours window
 * and buffer. Nothing is shared; there is no link and no account — the whole category, turned inward.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AvailabilitySheet(vm: AppViewModel, anchorDay: Long, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val events by vm.events.collectAsState()
    val s by vm.settings.collectAsState()
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = LocalDate.now(zone)
    val anchor = LocalDate.ofEpochDay(anchorDay)

    var range by remember { mutableStateOf("Week") }        // Day / Week / Month
    var minDur by remember { mutableStateOf(60) }           // duration-aware "openings" filter

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
    val fair = days.map { if (it.isBefore(today)) it to false else it to true }
    val free = Availability.forDays(events, days, cfg, zone).mapIndexed { i, d ->
        if (!fair[i].second) d.copy(available = false, slots = emptyList(), freeMin = 0, busyMin = 0) else d
    }
    val totalFree = Availability.totalFreeMin(free)
    val totalWindow = Availability.totalWindowMin(free)
    val longest = Availability.longest(free)
    val openings = Availability.openingsOfAtLeast(free, minDur)
    val availCount = Availability.availableDayCount(free)

    val df = DateTimeFormatter.ofPattern("EEE d MMM")
    val tf = DateTimeFormatter.ofPattern("h:mm a")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp).verticalScroll(rememberScrollState())) {
            Text("When am I free?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Your open time, at a glance — private, on-device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            // Range selector
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Day", "Week", "Month").forEach { r ->
                    FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r) })
                }
            }
            Spacer(Modifier.height(12.dp))
            // Headline
            AppCard {
                val pct = if (totalWindow > 0) (totalFree * 100 / totalWindow) else 0
                Text(Availability.fmtMinutes(totalFree) + " free", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("across ${availCount} available day${if (availCount == 1) "" else "s"} · $pct% of your window open",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                longest?.let { (d, slot) ->
                    Spacer(Modifier.height(6.dp))
                    Text("Largest open block: ${Availability.fmtMinutes(slot.minutes.toInt())} on ${d.format(df)} (${java.time.Instant.ofEpochMilli(slot.startMillis).atZone(zone).format(tf)}–${java.time.Instant.ofEpochMilli(slot.endMillis).atZone(zone).format(tf)})",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            // Duration-aware "can I fit it?"
            Text("Openings of at least", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60, 90, 120).forEach { m ->
                    FilterChip(selected = minDur == m, onClick = { minDur = m }, label = { Text(Availability.fmtMinutes(m)) })
                }
            }
            Text("$openings gap${if (openings == 1) "" else "s"} of ${Availability.fmtMinutes(minDur)} or more in this ${range.lowercase()}.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(12.dp))
            Text("Per day", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            free.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Text(d.date.format(df), Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        color = if (d.date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Column(Modifier.weight(1f)) {
                        when {
                            !d.available -> Text(if (d.date.isBefore(today)) "—" else "Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            d.slots.isEmpty() -> Text("Fully booked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            else -> {
                                Text(Availability.fmtMinutes(d.freeMin) + " free", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    d.slots.forEach { slot ->
                                        Text("${java.time.Instant.ofEpochMilli(slot.startMillis).atZone(zone).format(tf)}–${java.time.Instant.ofEpochMilli(slot.endMillis).atZone(zone).format(tf)}",
                                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
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
                    append("My availability (${range.lowercase()}): ${Availability.fmtMinutes(totalFree)} free\n")
                    free.filter { it.available && it.slots.isNotEmpty() }.forEach { d ->
                        append("${d.date.format(df)}: ")
                        append(d.slots.joinToString(", ") { "${java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).format(tf)}–${java.time.Instant.ofEpochMilli(it.endMillis).atZone(zone).format(tf)}" })
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

private fun startOfWk(d: LocalDate, firstDow: DayOfWeek): LocalDate {
    val wf = WeekFields.of(firstDow, 1)
    return d.with(wf.dayOfWeek(), 1)
}
