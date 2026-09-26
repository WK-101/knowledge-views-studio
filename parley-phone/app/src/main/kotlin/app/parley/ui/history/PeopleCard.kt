package app.parley.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.circle.PeopleInsights
import app.parley.common.history.CallLogIndex
import app.parley.ui.Avatar
import app.parley.ui.Routes
import app.parley.ui.common.Format

/** R6: everything the People card shows, worked out once per history change. */
private data class PeopleData(
    val reach: PeopleInsights.Reach?,
    val loops: List<PeopleInsights.Loop>,
    val firstMovers: List<Pair<String, PeopleInsights.FirstMover>>,
    val review: PeopleInsights.Review?,
)

/**
 * R6: the "People" card in Insights (never on the home screen): reach in your circle this month against the month
 * before, open loops (their call you haven't returned, your call they haven't answered; any later contact closes
 * them), who usually reaches out first (private, hideable) and a year in review once there are 20 entries. The
 * whole card can be turned off here or in Settings › Recents & history. Private contacts are never in it.
 */
@Composable
fun PeopleCard(vm: AppViewModel, idx: CallLogIndex, open: (String) -> Unit) {
    val cfg by vm.c.circle.config.collectAsStateWithLifecycle()
    if (!cfg.peopleCard) return
    val all by vm.contacts.collectAsStateWithLifecycle()
    val contacts = remember(all) { all.orEmpty().associateBy { it.lookupKey } }
    val data by produceState<PeopleData?>(null, idx, contacts) {
        value = runCatching {
            val now = System.currentTimeMillis()
            val circle = vm.c.circle.members().map { it.lookupKey }.filter { it in contacts }.toSet()
            val touches = vm.c.circle.touches(now - 400 * CallLogIndex.DAY, idx).filter { it.key in contacts }
            PeopleData(
                reach = if (circle.isEmpty()) null else PeopleInsights.reach(circle, touches, now),
                loops = PeopleInsights.openLoops(touches, now).take(5),
                firstMovers = touches.filter { it.key in circle }.groupBy { it.key }.mapNotNull { (k, list) -> PeopleInsights.firstMover(list)?.let { k to it } }
                    .sortedBy { contacts[it.first]?.displayName },
                review = PeopleInsights.yearInReview(touches, circle, now),
            )
        }.getOrNull()
    }
    val d = data ?: return
    if (d.reach == null && d.loops.isEmpty() && d.review == null) return
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.c2_people_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        { Text(stringResource(if (cfg.firstMover) R.string.c2_hide_first_mover else R.string.c2_show_first_mover)) },
                        onClick = { menu = false; vm.c.circle.updateConfig { it.copy(firstMover = !it.firstMover) } },
                    )
                    DropdownMenuItem({ Text(stringResource(R.string.c2_hide_people_card)) }, onClick = { menu = false; vm.c.circle.updateConfig { it.copy(peopleCard = false) } })
                }
            }
        }
        d.reach?.let { r ->
            val (icon, trend) = when (r.trend) {
                PeopleInsights.Trend.UP -> Icons.AutoMirrored.Rounded.TrendingUp to stringResource(R.string.c2_reach_up, r.before)
                PeopleInsights.Trend.DOWN -> Icons.AutoMirrored.Rounded.TrendingDown to stringResource(R.string.c2_reach_down, r.before)
                PeopleInsights.Trend.SAME -> Icons.AutoMirrored.Rounded.TrendingFlat to stringResource(R.string.c2_reach_same)
            }
            ListItem(
                headlineContent = { Text(pluralStringResource(R.plurals.c2_reach, r.circle, r.now, r.circle)) },
                supportingContent = { Text(trend) },
                trailingContent = { Icon(icon, trend, tint = MaterialTheme.colorScheme.primary) },
            )
        }
        if (d.loops.isNotEmpty()) {
            SubHeader(stringResource(R.string.c2_open_loops))
            d.loops.forEach { l ->
                val ct = contacts[l.key] ?: return@forEach
                val line = when (l.kind) {
                    PeopleInsights.LoopKind.THEIR_CALL -> pluralStringResource(R.plurals.c2_loop_their_call, l.count, l.count, Format.shortWhen(context, l.time))
                    PeopleInsights.LoopKind.YOUR_TRY -> pluralStringResource(R.plurals.c2_loop_your_try, l.count, l.count, Format.shortWhen(context, l.time))
                }
                ContactLine(vm, ct, line, open)
            }
        }
        if (cfg.firstMover && d.firstMovers.isNotEmpty()) {
            SubHeader(stringResource(R.string.c2_first_mover))
            d.firstMovers.forEach { (key, who) ->
                val ct = contacts[key] ?: return@forEach
                val name = ct.displayName.substringBefore(' ')
                ContactLine(
                    vm, ct,
                    when (who) {
                        PeopleInsights.FirstMover.THEM -> stringResource(R.string.c2_first_them, name)
                        PeopleInsights.FirstMover.YOU -> stringResource(R.string.c2_first_you)
                        PeopleInsights.FirstMover.BOTH -> stringResource(R.string.c2_first_both)
                    },
                    open, call = false,
                )
            }
            Text(stringResource(R.string.c2_first_mover_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        d.review?.let { r ->
            SubHeader(stringResource(R.string.c2_year_review))
            val most = r.most.mapNotNull { (k, n) -> contacts[k]?.let { it.displayName.substringBefore(' ') + " (" + app.parley.ui.Bidi.ltr(n.toString()) + ")" } }
            if (most.isNotEmpty()) ListItem(headlineContent = { Text(stringResource(R.string.c2_review_most, most.joinToString(", "))) })
            r.longestGap?.let { (k, days) ->
                contacts[k]?.let { ct -> ListItem(headlineContent = { Text(pluralStringResource(R.plurals.c2_review_gap, days, days, ct.displayName)) }) }
            }
            if (r.occasions > 0) ListItem(headlineContent = { Text(pluralStringResource(R.plurals.c2_review_occasions, r.occasions, r.occasions)) })
            ListItem(headlineContent = { Text(pluralStringResource(R.plurals.c2_review_entries, r.entries, r.entries)) })
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
}

@Composable
private fun ContactLine(vm: AppViewModel, ct: ContactSummary, sub: String, open: (String) -> Unit, call: Boolean = true) {
    val phone = (ct.phones.firstOrNull { it.isPrimary } ?: ct.phones.firstOrNull())?.number
    ListItem(
        modifier = Modifier.clickable { open(Routes.contact(ct.id)) },
        leadingContent = { Avatar(ct.displayName, ct.photoUri, 40.dp) },
        headlineContent = { Text(ct.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(sub) },
        trailingContent = if (call && phone != null) ({
            IconButton({ vm.requestCall(phone, ct.displayName) }) { Icon(Icons.Rounded.Call, stringResource(R.string.hist_call_back), tint = MaterialTheme.colorScheme.primary) }
        }) else null,
    )
}
