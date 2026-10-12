package app.parley.ui.circle

import app.parley.common.catching
import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.calls.CatchUp
import app.parley.common.circle.CircleFeeds
import app.parley.common.circle.CircleGlance
import app.parley.common.circle.CircleRhythm
import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.CircleStatus
import app.parley.common.circle.CircleSuggestions
import app.parley.common.circle.Interactions
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.LastContact
import app.parley.common.circle.NaturalRhythm
import app.parley.common.circle.PeopleInsights
import app.parley.common.history.Period
import app.parley.data.DataContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** One person in the Circle, as the list shows them. */
data class CircleRow(
    val contact: ContactSummary,
    val days: Int,
    val rhythm: KeepRhythm,
    val last: LastContact?,
    val status: CircleStatus,
    /** The label or season behind [days] ("via Family", "until 30 Nov"). */
    val now: CircleRhythm = CircleRhythm(days),
)

/** One "Suggested for your Circle" entry. */
data class CircleSuggestion(val contact: ContactSummary, val calls: Int, val days: Int)

/**
 * The Circle's list state, a view over system contacts (private contacts are never system contacts, so they
 * can't appear here), contact metadata, interactions and the call-history index. Owned by
 * [app.parley.AppViewModel] (`vm.circle`).
 */
class CircleUi(c: DataContainer, scope: CoroutineScope, contacts: StateFlow<List<ContactSummary>?>) {
    /** Who labels keep in the Circle ("Keep in touch with everyone here"), read again when labels or contacts change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val feeds = combine(c.extras.policies, c.extras.chapters, contacts) { p, ch, _ -> p to ch }
        .mapLatest { catching { c.extras.circleFeeds() }.getOrDefault(emptyMap()) }

    /** Circle members (their own rhythm, or through a label), most urgent first; null while loading. */
    val rows: StateFlow<List<CircleRow>?> = combine(
        contacts, combine(c.meta.allMeta(), feeds) { m, f -> m to f }, c.circle.interactions.latest, c.history.index,
        c.toCall.state.onStart { catching { c.toCall.load() } },
    ) { list, (metas, fed), latest, idx, toCall ->
        list ?: return@combine null
        val iso = c.directory.countryIso
        val now = System.currentTimeMillis()
        val byKey = list.associateBy { it.lookupKey }
        val rowsByKey = metas.associateBy { it.lookupKey }
        val keys = metas.filter { it.reachOutDays != null }.map { it.lookupKey } + fed.keys
        val members = keys.distinct().mapNotNull { key ->
            val m = rowsByKey[key]
            val contact = byKey[key] ?: return@mapNotNull null
            val rhythm = KeepRhythm.decode(m?.rhythm)
            val eff = CircleFeeds.effective(m?.reachOutDays, rhythm, fed[key].orEmpty(), now) ?: return@mapNotNull null
            val call = idx?.calls(
                personKey = "c:$key",
            )?.firstOrNull { it.durationSec > 0 && (it.type == CallType.INCOMING || it.type == CallType.OUTGOING) }?.date
            val last = Interactions.lastContact(call, latest[key])
            // A next catch-up set after a call keeps them quiet until that day, as in Next up and the reminders.
            val catchUp = CatchUp.of(toCall, contact.phones.map { PhoneIdentity.key(it.number, iso) })
            val quiet = CatchUp.quietUntil(rhythm.snoozedUntil, catchUp)
            Triple(contact, rhythm, last) to (CirclePlanner.Member(key, eff.days, last?.time, quiet) to eff)
        }
        val order = CirclePlanner.sort(members.map { it.second.first }, now).map { it.lookupKey }.withIndex().associate { it.value to it.index }
        members.map { (info, pm) ->
            val (m, eff) = pm
            CircleRow(info.first, m.days, info.second, info.third, CirclePlanner.status(m, now), eff)
        }
            .sortedBy { order[it.contact.lookupKey] ?: Int.MAX_VALUE }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** The contacts you call most (last year) who aren't in the Circle, with the rhythm their history suggests. */
    val suggestions: StateFlow<List<CircleSuggestion>> = combine(contacts, c.meta.allMeta(), c.history.index, feeds) { list, metas, idx, fed ->
        if (list == null || idx == null) return@combine emptyList()
        val byKey = list.associateBy { it.lookupKey }
        val inCircle = metas.filter { it.reachOutDays != null }.map { it.lookupKey }.toSet() + fed.keys
        val candidates = idx.topByCount(Period.lastDays(365, System.currentTimeMillis()), 40).mapNotNull { t ->
            val key = t.person.lookupKey ?: return@mapNotNull null
            if (key !in byKey) return@mapNotNull null
            CircleSuggestions.Candidate(key, t.totals.answeredIn + t.totals.answeredOut, idx.rhythm(t.person.key)?.suggestedReminderDays)
        }
        CircleSuggestions.pick(
            candidates, inCircle,
        ).mapNotNull { cand -> byKey[cand.lookupKey]?.let { CircleSuggestion(it, cand.calls, CircleSuggestions.daysFor(cand)) } }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The quiet header over the Circle: the status counts, reach this month and the Circle's share of this month's
     * talk time. Null below [CircleGlance.MIN_MEMBERS]; only the counts while a duress unlock hides insights.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val glance: StateFlow<CircleGlance.Glance?> = combine(rows, c.history.index, c.privacy.flow) { r, idx, p -> Triple(r, idx, p) }.mapLatest { (r, idx, p) ->
        if (r == null || r.size < CircleGlance.MIN_MEMBERS) return@mapLatest null
        val statuses = r.map { it.status }
        if (!p.insightsShown) return@mapLatest CircleGlance.of(statuses, null, 0, 0, insightsShown = false)
        val now = System.currentTimeMillis()
        val keys = r.map { it.contact.lookupKey }.toSet()
        val touches = catching { c.circle.touches(now - 2 * PeopleInsights.REACH_DAYS * NaturalRhythm.DAY, idx) }.getOrDefault(emptyList())
        val month = Period.lastDays(PeopleInsights.REACH_DAYS, now)
        val all = idx?.totals(month)?.talkSec ?: 0L
        val circle = keys.sumOf { idx?.totals(month, personKey = "c:$it")?.talkSec ?: 0L }
        CircleGlance.of(statuses, PeopleInsights.reach(keys, touches, now), circle, all, insightsShown = true)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
}
