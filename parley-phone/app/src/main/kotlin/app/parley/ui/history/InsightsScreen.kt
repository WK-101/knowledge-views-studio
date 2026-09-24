package app.parley.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.history.CallLogIndex
import app.parley.common.history.Period
import app.parley.common.history.Person
import app.parley.common.history.WeekBucket
import app.parley.ui.Avatar
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.contact.Section
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class InsightPeriod(val label: String) {
    WEEK("7 days"), MONTH("30 days"), QUARTER("90 days"), YEAR("This year"), ALL("All time");

    fun period(now: Long, zone: ZoneId): Period = when (this) {
        WEEK -> Period.lastDays(7, now)
        MONTH -> Period.lastDays(30, now)
        QUARTER -> Period.lastDays(90, now)
        YEAR -> Period(LocalDate.now(zone).withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli(), Long.MAX_VALUE)
        ALL -> Period.ALL
    }
}

/** H5: offline call insights, opened from the Recents top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val index by vm.c.history.index.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    var choice by rememberSaveable { mutableStateOf(InsightPeriod.MONTH) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Insights") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        val idx = index
        if (idx == null) {
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val now = remember(idx) { System.currentTimeMillis() }
        val period = choice.period(now, idx.zone)
        val totals = remember(idx, choice) { idx.totals(period) }
        val weeks = remember(idx, choice) {
            val bounded = if (choice == InsightPeriod.ALL || choice == InsightPeriod.YEAR) Period(maxOf(period.from, now - 52 * 7 * CallLogIndex.DAY), period.until) else period
            idx.weeklyTalk(bounded, now = now)
        }
        val byTime = remember(idx, choice) { idx.topByTalkTime(period) }
        val byCount = remember(idx, choice) { idx.topByCount(period) }
        val perSim = remember(idx, choice) { idx.perSim(period) }
        val unreturned = remember(idx, choice) { idx.unreturned(period) }

        LazyColumn(Modifier.padding(p)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InsightPeriod.entries.forEach { c -> FilterChip(choice == c, { choice = c }, { Text(c.label) }) }
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    StatGrid(totals)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TalkFigure("Talk time", totals.talkSec)
                        TalkFigure("Calls you made", totals.talkOutSec)
                        TalkFigure("Calls you received", totals.talkInSec)
                    }
                    if (totals.outgoing > 0) {
                        Text(
                            "${totals.answeredOut} of ${totals.outgoing} calls you made were answered · ${totals.answeredIn} of ${totals.incoming + totals.missed + totals.rejected} incoming calls answered",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (weeks.size > 1) {
                item { Section("Talk time per week") }
                item { WeeklyBars(weeks, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (byTime.isNotEmpty()) {
                item { Section("Most talk time") }
                byTime.forEach { pt -> item { PersonRow(vm, pt.person, HistoryFormat.talk(pt.totals.talkSec) + " · ${pt.totals.total} calls", open) } }
            }
            if (byCount.isNotEmpty()) {
                item { Section("Most calls") }
                byCount.forEach { pt -> item { PersonRow(vm, pt.person, "${pt.totals.total - pt.totals.blocked} calls · " + HistoryFormat.talk(pt.totals.talkSec), open) } }
            }
            if (perSim.size > 1) {
                item { Section("By SIM") }
                val max = perSim.values.maxOf { it.total }.coerceAtLeast(1)
                perSim.entries.sortedByDescending { it.value.total }.forEach { (id, t) ->
                    item {
                        ListItem(
                            headlineContent = { Text(sims.firstOrNull { it.id == id }?.label ?: if (id == null) "No SIM recorded" else "Other SIM") },
                            supportingContent = {
                                Column {
                                    Text("${t.total} calls · ${t.outgoing} made · ${t.incoming} received · ${t.missed} missed · talk ${HistoryFormat.talk(t.talkSec)}")
                                    LinearProgressIndicator(progress = { t.total.toFloat() / max }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                            },
                        )
                    }
                }
            }
            item { Section("Calls you didn't return") }
            if (unreturned.isEmpty()) {
                item { Text("None. Nice.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            unreturned.take(20).forEach { u ->
                item {
                    val context = LocalContext.current
                    PersonRow(
                        vm, u.person,
                        (if (u.count > 1) "${u.count} missed calls · last " else "Missed ") + Format.shortWhen(context, u.last.date),
                        open,
                        trailing = { IconButton({ vm.requestCall(u.person.number, u.person.name) }) { Icon(Icons.Rounded.Call, "Call back", tint = MaterialTheme.colorScheme.primary) } },
                    )
                }
            }
            item {
                Text(
                    "Worked out on this phone from your call history" + if (!idx.contactsKnown) ". Contacts couldn't be read, so people are shown by number." else ".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TalkFigure(label: String, sec: Long) {
    Column {
        Text(HistoryFormat.talk(sec), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PersonRow(vm: AppViewModel, person: Person, sub: String, open: (String) -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val title = person.name ?: Format.number(person.number, vm.countryIso)
    ListItem(
        modifier = Modifier.clickable {
            val id = person.contactId
            if (id != null) open(Routes.contact(id)) else open(Routes.history(person.number))
        },
        leadingContent = { Avatar(title, null, 40.dp) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(sub) },
        trailingContent = trailing,
    )
}

/** Stacked weekly bars: calls you made (primary) on top of calls you received (tertiary). No chart library. */
@Composable
private fun WeeklyBars(weeks: List<WeekBucket>, modifier: Modifier = Modifier) {
    val outColor = MaterialTheme.colorScheme.primary
    val inColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = weeks.maxOf { it.talkSec }.coerceAtLeast(60)
    val fmt = DateTimeFormatter.ofPattern("d MMM")
    val busiest = weeks.maxBy { it.talkSec }
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).semantics {
                contentDescription = "Weekly talk time, busiest week ${fmt.format(busiest.weekStart)} with ${HistoryFormat.talk(busiest.talkSec)}"
            },
        ) {
            val gap = 3.dp.toPx()
            val bw = ((size.width - gap * (weeks.size - 1)) / weeks.size).coerceAtLeast(1f)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 0.5.dp.toPx())
            weeks.forEachIndexed { i, w ->
                val x = i * (bw + gap)
                val hOut = size.height * w.talkOutSec / max
                val hIn = size.height * w.talkInSec / max
                val r = CornerRadius(minOf(bw / 2, 3.dp.toPx()))
                if (hIn > 0) drawRoundRect(inColor, Offset(x, size.height - hIn), Size(bw, hIn), r)
                if (hOut > 0) drawRoundRect(outColor, Offset(x, size.height - hIn - hOut), Size(bw, hOut), r)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(fmt.format(weeks.first().weekStart), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("Top line = ${HistoryFormat.talk(max)} · half = ${HistoryFormat.talk(max / 2)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(fmt.format(weeks.last().weekStart), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(outColor, "Calls you made")
            Spacer(Modifier.width(16.dp))
            Legend(inColor, "Calls you received")
        }
    }
}

@Composable
internal fun Legend(color: Color, label: String) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelSmall)
}
