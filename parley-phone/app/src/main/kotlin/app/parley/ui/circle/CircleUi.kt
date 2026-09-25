package app.parley.ui.circle

import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.CircleStatus
import app.parley.common.circle.CircleSuggestions
import app.parley.common.circle.Interactions
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.LastContact
import app.parley.common.history.Period
import app.parley.data.DataContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** R1: one person in the Circle, as the list shows them. */
data class CircleRow(
    val contact: ContactSummary,
    val days: Int,
    val rhythm: KeepRhythm,
    val last: LastContact?,
    val status: CircleStatus,
)

/** R1: one "Suggested from your calls" entry. */
data class CircleSuggestion(val contact: ContactSummary, val calls: Int, val days: Int)

/**
 * R1: the Circle's list state, a view over system contacts (private contacts are never system contacts, so they
 * can't appear here), contact metadata, interactions and the call-history index. Owned by
 * [app.parley.AppViewModel] (`vm.circle`).
 */
class CircleUi(c: DataContainer, scope: CoroutineScope, contacts: StateFlow<List<ContactSummary>?>) {
    /** Circle members, most urgent first; null while loading. */
    val rows: StateFlow<List<CircleRow>?> = combine(contacts, c.meta.allMeta(), c.circle.interactions.latest, c.history.index) { list, metas, latest, idx ->
        list ?: return@combine null
        val now = System.currentTimeMillis()
        val byKey = list.associateBy { it.lookupKey }
        val members = metas.mapNotNull { m ->
            val every = m.reachOutDays ?: return@mapNotNull null
            val contact = byKey[m.lookupKey] ?: return@mapNotNull null
            val rhythm = KeepRhythm.decode(m.rhythm)
            val call = idx?.calls(personKey = "c:${m.lookupKey}")?.firstOrNull { it.durationSec > 0 && (it.type == CallType.INCOMING || it.type == CallType.OUTGOING) }?.date
            val last = Interactions.lastContact(call, latest[m.lookupKey])
            Triple(contact, rhythm, last) to CirclePlanner.Member(m.lookupKey, rhythm.days(every), last?.time, rhythm.snoozedUntil)
        }
        val order = CirclePlanner.sort(members.map { it.second }, now).map { it.lookupKey }.withIndex().associate { it.value to it.index }
        members.map { (info, m) -> CircleRow(info.first, m.days, info.second, info.third, CirclePlanner.status(m, now)) }
            .sortedBy { order[it.contact.lookupKey] ?: Int.MAX_VALUE }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** The contacts you call most (last year) who aren't in the Circle, with the rhythm their history suggests. */
    val suggestions: StateFlow<List<CircleSuggestion>> = combine(contacts, c.meta.allMeta(), c.history.index) { list, metas, idx ->
        if (list == null || idx == null) return@combine emptyList()
        val byKey = list.associateBy { it.lookupKey }
        val inCircle = metas.filter { it.reachOutDays != null }.map { it.lookupKey }.toSet()
        val candidates = idx.topByCount(Period.lastDays(365, System.currentTimeMillis()), 40).mapNotNull { t ->
            val key = t.person.lookupKey ?: return@mapNotNull null
            if (key !in byKey) return@mapNotNull null
            CircleSuggestions.Candidate(key, t.totals.answeredIn + t.totals.answeredOut, idx.rhythm(t.person.key)?.suggestedReminderDays)
        }
        CircleSuggestions.pick(candidates, inCircle).mapNotNull { cand -> byKey[cand.lookupKey]?.let { CircleSuggestion(it, cand.calls, CircleSuggestions.daysFor(cand)) } }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
