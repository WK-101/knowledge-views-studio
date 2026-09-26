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
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import java.util.Locale

private enum class InsightPeriod(@StringRes val label: Int) {
    WEEK(R.string.hist_insight_week), MONTH(R.string.hist_insight_month), QUARTER(R.string.hist_insight_quarter), YEAR(R.string.hist_insight_year), ALL(R.string.hist_insight_all);

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
        TopAppBar(title = { Text(stringResource(R.string.hist_insights_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } })
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
                    InsightPeriod.entries.forEach { c -> FilterChip(choice == c, { choice = c }, { Text(stringResource(c.label)) }) }
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    StatGrid(totals)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TalkFigure(stringResource(R.string.hist_talk_time_label), totals.talkSec)
                        TalkFigure(stringResource(R.string.hist_calls_you_made), totals.talkOutSec)
                        TalkFigure(stringResource(R.string.hist_calls_you_received), totals.talkInSec)
                    }
                    if (totals.outgoing > 0) {
                        Text(
                            stringResource(R.string.hist_answered_rates, totals.answeredOut, totals.outgoing, totals.answeredIn, totals.incoming + totals.missed + totals.rejected),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            // R6: the People card (its own windows: this month, open loops, the last year).
            item(key = "people") { PeopleCard(vm, idx, open) }
            if (weeks.size > 1) {
                item { Section(stringResource(R.string.hist_talk_per_week)) }
                item { WeeklyBars(weeks, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (byTime.isNotEmpty()) {
                item { Section(stringResource(R.string.hist_most_talk)) }
                byTime.forEach { pt -> item { PersonRow(vm, pt.person, pluralStringResource(R.plurals.hist_talk_and_calls, pt.totals.total, pt.totals.total, HistoryFormat.talk(pt.totals.talkSec)), open) } }
            }
            if (byCount.isNotEmpty()) {
                item { Section(stringResource(R.string.hist_most_calls)) }
                byCount.forEach { pt -> item { PersonRow(vm, pt.person, (pt.totals.total - pt.totals.blocked).let { n -> pluralStringResource(R.plurals.hist_calls_and_talk, n, n, HistoryFormat.talk(pt.totals.talkSec)) }, open) } }
            }
            if (perSim.size > 1) {
                item { Section(stringResource(R.string.hist_by_sim)) }
                val max = perSim.values.maxOf { it.total }.coerceAtLeast(1)
                perSim.entries.sortedByDescending { it.value.total }.forEach { (id, t) ->
                    item {
                        ListItem(
                            headlineContent = { Text(sims.firstOrNull { it.id == id }?.label ?: if (id == null) stringResource(R.string.hist_no_sim) else stringResource(R.string.hist_other_sim)) },
                            supportingContent = {
                                Column {
                                    Text(pluralStringResource(R.plurals.hist_sim_totals, t.total, t.total, t.outgoing, t.incoming, t.missed, HistoryFormat.talk(t.talkSec)))
                                    LinearProgressIndicator(progress = { t.total.toFloat() / max }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                            },
                        )
                    }
                }
            }
            item { Section(stringResource(R.string.hist_unreturned)) }
            if (unreturned.isEmpty()) {
                item { Text(stringResource(R.string.hist_unreturned_none), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            unreturned.take(20).forEach { u ->
                item {
                    val context = LocalContext.current
                    PersonRow(
                        vm, u.person,
                        pluralStringResource(R.plurals.hist_missed_last, u.count, u.count, Format.shortWhen(context, u.last.date)),
                        open,
                        trailing = { IconButton({ vm.requestCall(u.person.number, u.person.name) }) { Icon(Icons.Rounded.Call, stringResource(R.string.hist_call_back), tint = MaterialTheme.colorScheme.primary) } },
                    )
                }
            }
            item {
                Text(
                    if (!idx.contactsKnown) stringResource(R.string.hist_insights_footer_no_contacts) else stringResource(R.string.hist_insights_footer),
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
    // U3: the fixed call colours, as on the call icons.
    val outColor = app.parley.ui.CallTypeColors.of(app.parley.common.ux.CallHue.OUTGOING)
    val inColor = app.parley.ui.CallTypeColors.of(app.parley.common.ux.CallHue.INCOMING)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = weeks.maxOf { it.talkSec }.coerceAtLeast(60)
    val locale = Locale.getDefault()
    val fmt = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
    val busiest = weeks.maxBy { it.talkSec }
    val desc = stringResource(R.string.hist_weekly_desc, fmt.format(busiest.weekStart), HistoryFormat.talk(busiest.talkSec))
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).semantics {
                contentDescription = desc
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
            Text(stringResource(R.string.hist_weekly_scale, HistoryFormat.talk(max), HistoryFormat.talk(max / 2)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(fmt.format(weeks.last().weekStart), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(outColor, stringResource(R.string.hist_calls_you_made))
            Spacer(Modifier.width(16.dp))
            Legend(inColor, stringResource(R.string.hist_calls_you_received))
        }
    }
}

@Composable
internal fun Legend(color: Color, label: String) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelSmall)
}
