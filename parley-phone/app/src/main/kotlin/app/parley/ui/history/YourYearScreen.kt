package app.parley.ui.history

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.catching
import app.parley.common.circle.YourYear
import app.parley.ui.Destination
import app.parley.ui.EmptyState
import app.parley.ui.ListSectionHeader
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.PersonRow
import app.parley.ui.Routes
import app.parley.ui.Spacing
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Month

/**
 * Your year: one calendar year looked back on, chosen from a row of the years with history. In touch how often, most
 * in touch, closer and quieter than the year before, the longest gap in the Circle with its dates, occasions
 * acknowledged, and calls against chats started from Parley; then one action, Add to your Circle, for the quieter
 * people who aren't in it. Worked out when shown, one year at a time ([YourYearUi]). After a duress unlock it reads as
 * a phone with nothing to look back on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourYearScreen(vm: AppViewModel, initialYear: Int, back: () -> Unit, open: (Destination) -> Unit) {
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    val everyone by vm.everyone.collectAsStateWithLifecycle()
    val contacts = remember(everyone) { everyone.orEmpty().associateBy { it.lookupKey } }
    val keys = contacts.keys
    val years by produceState<YourYearUi.Years?>(null, keys, privacy.insightsShown, privacy.privateShown) {
        value = if (everyone == null) null else catching { vm.yourYear.years(keys, privacy) }.getOrNull() ?: YourYearUi.Years(emptyList(), null)
    }
    var chosen by rememberSaveable { mutableStateOf(initialYear) }
    val list = years?.years.orEmpty()
    val year = chosen.takeIf { it in list } ?: defaultYear(list)
    // Bumped after Add to your Circle, so the page reads the Circle again.
    var reads by remember { mutableIntStateOf(0) }
    val page by produceState<YourYearUi.Page?>(null, year, years, reads) {
        // Another year's page never shows under this year's chip while it loads.
        if (value?.review?.year != year) value = null
        value = year?.let { y -> catching { vm.yourYear.page(y, keys, privacy, years?.historyFrom) }.getOrNull() }
    }
    ParleyScaffold(topBar = { ParleyTopBar(stringResource(R.string.circle_year_review), onBack = back) }) { p ->
        val loaded = years ?: return@ParleyScaffold
        if (loaded.years.isEmpty()) {
            EmptyState(Icons.Rounded.Event, stringResource(R.string.year_empty_title), stringResource(R.string.year_empty_body), Modifier.padding(p))
            return@ParleyScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = p) {
            item(key = "years") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    items(loaded.years, key = { it }) { y ->
                        FilterChip(y == year, { chosen = y }, label = { Text(y.toString()) })
                    }
                }
            }
            page?.let { pg -> yearPage(vm, pg, contacts, open) { reads++ } }
        }
    }
}

/** In January the year just ended, else the newest. */
private fun defaultYear(years: List<Int>): Int? {
    val today = LocalDate.now()
    val last = today.year - 1
    return if (today.month == Month.JANUARY && last in years) last else years.firstOrNull()
}

private fun androidx.compose.foundation.lazy.LazyListScope.yearPage(
    vm: AppViewModel, pg: YourYearUi.Page, contacts: Map<String, ContactSummary>, open: (Destination) -> Unit, added: () -> Unit,
) {
    val r = pg.review
    item(key = "total") {
        val context = LocalContext.current
        ParleyListItem(
            headlineContent = { Text(pluralStringResource(R.plurals.year_in_touch, r.inTouch, r.inTouch), style = MaterialTheme.typography.titleLarge) },
            supportingContent = pg.thinFrom?.let { from -> { Text(stringResource(R.string.year_thin, monthYear(context, from))) } },
        )
    }
    people(R.string.year_most, r.most.map { it.key to it.count }, contacts, open, "most")
    people(R.string.year_closer, r.closer.map { it.key to it.now }, contacts, open, "closer", r.closer.associate { it.key to it.before })
    people(R.string.year_quieter, r.quieter.map { it.key to it.now }, contacts, open, "quieter", r.quieter.associate { it.key to it.before })
    r.gap?.let { gap ->
        val ct = contacts[gap.key] ?: return@let
        item(key = "gap") {
            val context = LocalContext.current
            Column {
                Header(stringResource(R.string.year_gap))
                ParleyListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.year_gap_days, gap.days, gap.days, ct.displayName)) },
                    supportingContent = { Text(DateUtils.formatDateRange(context, gap.from, gap.to, DATE_FLAGS)) },
                    modifier = Modifier.clickable { open(Routes.contact(ct.id)) },
                )
            }
        }
    }
    item(key = "how") {
        Column {
            Header(stringResource(R.string.year_how))
            ParleyListItem(
                headlineContent = {
                    Text(
                        stringResource(
                            R.string.format_dot_pair, pluralStringResource(R.plurals.year_calls, r.calls, r.calls),
                            pluralStringResource(R.plurals.year_chats, r.chats, r.chats),
                        ),
                    )
                },
                supportingContent = if (r.occasions > 0) ({ Text(pluralStringResource(R.plurals.circle_review_occasions, r.occasions, r.occasions)) }) else null,
            )
        }
    }
    // The one action: the quieter people who aren't in the Circle.
    val outside = r.quieter.filter { it.key !in pg.circle && it.key in contacts }
    if (outside.isNotEmpty()) item(key = "add") {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val names = outside.mapNotNull { contacts[it.key]?.displayName }.joinToString(stringResource(R.string.dc_list_separator))
        ParleyListItem(
            leadingContent = { Icon(Icons.Rounded.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.circle_add_to_circle), color = MaterialTheme.colorScheme.primary) },
            supportingContent = { Text(stringResource(R.string.year_add_quieter_sub, names)) },
            modifier = Modifier.padding(top = Spacing.s).clickable {
                scope.launch {
                    val done = outside.count { ch ->
                        val ct = contacts[ch.key] ?: return@count false
                        catching { vm.yourYear.addToCircle(ch.key, ct.id, ch) }.isSuccess
                    }
                    vm.toast(context.resources.getQuantityString(R.plurals.year_added, done, done))
                    added()
                }
            },
        )
    }
}

/** A titled list of people with their conversation counts ([before]: the year before's, for closer and quieter). */
private fun androidx.compose.foundation.lazy.LazyListScope.people(
    title: Int, rows: List<Pair<String, Int>>, contacts: Map<String, ContactSummary>, open: (Destination) -> Unit, key: String,
    before: Map<String, Int>? = null,
) {
    val shown = rows.filter { it.first in contacts }
    if (shown.isEmpty()) return
    item(key = "$key-header") { Header(stringResource(title)) }
    items(shown, key = { "$key-${it.first}" }) { (k, n) ->
        val ct = contacts.getValue(k)
        val count = pluralStringResource(R.plurals.year_conversations, n, n)
        val sub = before?.get(k)?.let { b -> stringResource(R.string.format_dot_pair, count, stringResource(R.string.year_change_before, b)) } ?: count
        PersonRow(ct.displayName, ct.photoUri, modifier = Modifier.clickable { open(Routes.contact(ct.id)) }, supportingContent = { Text(sub) })
    }
}

@Composable
private fun Header(text: String) {
    ListSectionHeader(text, color = MaterialTheme.colorScheme.onSurfaceVariant, top = Spacing.l, bottom = 0.dp)
}

private fun monthYear(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR)

private const val DATE_FLAGS = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
