package app.parley.ui.circle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.calls.CatchUp
import app.parley.common.circle.CircleConfig
import app.parley.common.circle.CircleGlance
import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.CircleStatus
import app.parley.common.circle.Interactions
import app.parley.common.circle.KeepInTouch
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.PeopleInsights
import app.parley.data.circle.Interaction
import app.parley.ui.Spacing
import app.parley.ui.common.Format
import java.time.ZoneId

/**
 * How you two keep in touch, ready to show: the paragraph of facts (the Stay in touch card and the first line of the
 * pre-call peek), the last year's summary, and whether it's their turn to hear from you (which alone is reason enough
 * for the peek). Null while a duress unlock hides relationship insights.
 */
data class KeepInTouchLines(val sentence: String?, val summary: String?, val yourTurn: Boolean) {
    val isEmpty: Boolean get() = sentence == null && summary == null
}

/** Whether these lines alone are reason to show the pre-call peek: it's your turn. */
val KeepInTouchLines?.asksForPeek: Boolean get() = this?.yourTurn == true

/** The [KeepInTouchLines] of one contact, from the page's own [history] and [interactions]. */
@Composable
@Suppress("LongParameterList") // The contact page's state for one person, each piece used.
fun rememberKeepInTouch(
    vm: AppViewModel,
    lookupKey: String,
    name: String,
    /** Their "every N days" (contact_meta's `reachOutDays`), null outside the Circle. */
    everyDays: Int?,
    /** Their stored rhythm ([KeepRhythm.encode]). */
    rhythmText: String?,
    history: List<CallEntry>,
    interactions: List<Interaction>,
    config: CircleConfig,
    /** Their next catch-up ([CatchUp.of]): the rhythm stays quiet about them until then. */
    catchUpAt: Long? = null,
): KeepInTouchLines? {
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = LocalResources.current
    val firstMover = config.firstMoverShown
    return remember(lookupKey, name, everyDays, rhythmText, history, interactions, firstMover, privacy, catchUpAt) {
        if (!privacy.insightsShown || lookupKey.isEmpty()) return@remember null
        val now = System.currentTimeMillis()
        val touches = history.mapNotNull { PeopleInsights.touchOf(lookupKey, it) } +
            interactions.map { PeopleInsights.Touch(lookupKey, it.time, PeopleInsights.loggedKind(it.dedupeKey)) }
        val due = everyDays?.let { every ->
            val rhythm = KeepRhythm.decode(rhythmText)
            val last = Interactions.lastContact(
                history.firstOrNull { it.durationSec > 0 && it.type.isAnsweredKind() }?.date,
                interactions.firstOrNull()?.let { it.time to it.type },
            )
            CirclePlanner.status(CirclePlanner.Member(lookupKey, rhythm.days(every), last?.time, CatchUp.quietUntil(rhythm.snoozedUntil, catchUpAt)), now) ==
                CircleStatus.DUE
        } ?: false
        val facts = KeepInTouch.facts(touches, due, firstMover, now, ZoneId.systemDefault())
        KeepInTouchLines(
            sentence = CircleText.keepInTouch(res, name, facts, { Format.shortWhen(context, it) }, privacy),
            summary = CircleText.keepSummary(res, KeepInTouch.summary(history, touches, now), privacy),
            yourTurn = facts.any { it is KeepInTouch.Fact.YourTurn },
        ).takeUnless { it.isEmpty }
    }
}

private fun CallType.isAnsweredKind() = this == CallType.INCOMING || this == CallType.OUTGOING

/**
 * The quiet header at the top of the Circle: "2 due · 3 soon · 11 fine" as filters ([only] is the one chosen, tapping
 * it again shows everyone), then reach this month and the share of talk time, which open Call insights. While a duress
 * unlock hides insights only the counts show (they repeat the rows' chips).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CircleGlanceHeader(glance: CircleGlance.Glance, only: CircleStatus?, choose: (CircleStatus?) -> Unit, openInsights: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val chipsLabel = stringResource(R.string.circle_glance_filters)
        FlowRow(
            Modifier.semantics { contentDescription = chipsLabel },
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            listOf(
                CircleStatus.DUE to pluralStringResource(R.plurals.circle_glance_due, glance.due, glance.due),
                CircleStatus.SOON to pluralStringResource(R.plurals.circle_glance_soon, glance.soon, glance.soon),
                CircleStatus.FINE to pluralStringResource(R.plurals.circle_glance_fine, glance.fine, glance.fine),
            ).forEach { (status, label) ->
                val count = when (status) {
                    CircleStatus.DUE -> glance.due
                    CircleStatus.SOON -> glance.soon
                    CircleStatus.FINE -> glance.fine
                }
                FilterChip(
                    selected = only == status,
                    onClick = { choose(if (only == status) null else status) },
                    label = { Text(label) },
                    enabled = count > 0 || only == status,
                )
            }
        }
        val reach = glance.reach
        if (reach != null || glance.talkShare != null) {
            Column(
                Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.circle_glance_open), onClick = openInsights)
                    .padding(vertical = Spacing.s),
            ) {
                reach?.let { r ->
                    val line = pluralStringResource(R.plurals.circle_glance_reach, r.circle, r.now, r.circle)
                    val up = if (r.trend == PeopleInsights.Trend.UP) {
                        stringResource(R.string.main_separator) + stringResource(R.string.circle_glance_up, r.before)
                    } else {
                        ""
                    }
                    Text(line + up, style = MaterialTheme.typography.bodyMedium)
                }
                glance.talkShare?.let {
                    Text(
                        stringResource(R.string.circle_glance_talk, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
