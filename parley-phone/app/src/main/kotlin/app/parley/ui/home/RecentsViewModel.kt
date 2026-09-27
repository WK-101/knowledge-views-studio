package app.parley.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.parley.R
import app.parley.RecentFilter
import app.parley.RecentGroup
import app.parley.common.CallEntry
import app.parley.common.CallPolicy
import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.TextSearch
import app.parley.common.calls.RecentsGrouping
import app.parley.common.calls.RecentsLayout
import app.parley.common.ux.CallGlance
import app.parley.common.ux.ListSections
import app.parley.data.DataContainer
import app.parley.data.NumberInfo
import app.parley.data.history.CallHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** One row of the Recents list: a day header or a call row. */
sealed interface RecentsRow {
    val key: String

    /** The header of the calls of one local day; [today] (a local day) changes at midnight, so the text is redone. */
    data class Day(override val key: String, val date: Long, val today: Long) : RecentsRow

    data class Call(val group: RecentGroup) : RecentsRow {
        override val key: String get() = group.key
    }

    companion object {
        const val TYPE_DAY = "day"
        const val TYPE_CALL = "call"
    }
}

/** The Recents list: its rows (filters and search applied) and the day headers between them, built off the main thread. */
data class RecentsList(val groups: List<RecentGroup>, val rows: List<RecentsRow>)

/**
 * Recents: the merged call list (system log, Parley's archive and private calls), the filter chips, the search, the
 * selection, the grouping and day headers, and the missed calls still to return. Shared by the Recents tab, the
 * docked keypad's idle list and the Recents menus (activity scope).
 */
@OptIn(FlowPreview::class)
class RecentsViewModel(private val c: DataContainer) : ViewModel() {
    private val directory = c.directory
    val countryIso: String = directory.countryIso
    private val settings = c.settings.settings

    val filter = MutableStateFlow(RecentFilter.ALL)

    /** Rows selected for bulk actions (keys of [RecentGroup]). */
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val query = MutableStateFlow("")

    fun toggleSelected(g: RecentGroup) {
        selection.value = selection.value.let { if (g.key in it) it - g.key else it + g.key }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** "Delete from call history" on a row: its calls in the log (and archive) and its private calls. */
    fun delete(g: RecentGroup) {
        viewModelScope.launch {
            c.history.delete(g.calls.filter { it.id > 0 })
            g.calls.filter { it.id < 0 }.forEach { c.vault.deletePrivateCall(-it.id) }
        }
    }

    /** "Show all calls": clears the chips and the call-history filter. */
    fun showAll() {
        filter.value = RecentFilter.ALL
        c.history.activeFilter.value = app.parley.common.history.HistoryFilter()
    }

    val historyFilter get() = c.history.activeFilter

    val voicemail get() = c.voicemail.state

    /** System call log (plus Parley's archive) + private (vault) calls, newest first. */
    // V11: until the full log (and the archive) have loaded, the first page of the call log is shown.
    private val allCalls: StateFlow<List<CallEntry>?> = combine(
        c.history.calls, c.callLog.preview, c.vault.privateCalls, settings.map { it.hideVault }.distinctUntilChanged(),
    ) { full, preview, priv, hidden ->
        val sys = full ?: preview ?: return@combine null
        if (hidden || priv.isEmpty()) return@combine sys
        (sys + priv.map(CallHistory::privateEntry)).sortedByDescending { it.date }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null) // merged once, shared by the list and the unreturned count

    // F7: keyed by line (E.164 with this phone's country), so a foreign number sharing the last 9 digits isn't shown as private.
    private val vaultByKey = c.vault.contacts.map { list -> list.flatMap { v -> v.numbers.map { PhoneIdentity.key(it, countryIso) to v.id } }.toMap() }

    /** [allCalls] with the filter chips of the call history applied (SIM, type, period, duration). */
    private val filteredCalls = combine(allCalls, c.history.activeFilter) { calls, f ->
        if (calls == null || f.isEmpty) calls else calls.filter(f.matcher(System.currentTimeMillis(), java.time.ZoneId.systemDefault()))
    }

    // P8: the call-list layout travels with the calls, so Recents regroups when it changes.
    private val callsAndLayout = combine(filteredCalls, settings.map { it.recentsLayout }.distinctUntilChanged()) { calls, layout -> calls to layout }

    val groups: StateFlow<List<RecentGroup>?> = combine(callsAndLayout, directory.numberIndex, filter, query.debounce(80), vaultByKey) { (calls, layout), index, filter, q, vaults ->
        calls?.let { group(it, index, filter, q, layout).map { g -> if (g.calls.first().id < 0) g.copy(vaultId = vaults[PhoneIdentity.key(g.number, countryIso)]) else g } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    /** The list with its day headers; redone at midnight so "Today" becomes "Yesterday". */
    val list: StateFlow<RecentsList?> = combine(groups, localDays()) { groups, today ->
        groups?.let { RecentsList(it, rows(it, today)) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    private fun rows(groups: List<RecentGroup>, today: Long): List<RecentsRow> {
        val tz = TimeZone.getDefault()
        return ListSections.interleave(groups) { ListSections.localDay(it.latest.date, tz) }.map { r ->
            when (r) {
                is ListSections.Row.Header -> RecentsRow.Day("h" + r.first.key, r.first.latest.date, today)
                is ListSections.Row.Item -> RecentsRow.Call(r.item)
            }
        }
    }

    /** R4: block rules that name numbers (the others go by name, region or line type). */
    private val numberRules = setOf(RuleType.EXACT, RuleType.PREFIX, RuleType.WILDCARD)

    /**
     * R4 (v3.3): ids of missed calls not returned yet, over every call (whatever the filters show), for the Recents
     * tint, the Call back pill and the Missed chip's count.
     */
    val unreturnedMissed: StateFlow<Set<Long>> = combine(allCalls, notWorthReturning(), hourly()) { calls, excluded, now ->
        calls?.let { CallGlance.unreturnedMissed(it, { n -> PhoneIdentity.key(n, countryIso) }, now, excluded = excluded) } ?: emptySet()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptySet())

    /**
     * R4: numbers whose missed calls aren't worth a "call back": on the system block list, caught by a block rule, or
     * last screened as blocked, reported or likely spam.
     */
    private fun notWorthReturning() = combine(c.blocks.systemList, c.blocks.rules, c.blocks.verdictIndex) { system, rules, verdicts ->
        val iso = countryIso
        // Verdicts are filed under line keys; system block-list entries are numbers.
        val listed = PhoneIdentity.LineSet(system.map { it.number }, iso)
        val flagged = PhoneIdentity.KeySet(verdicts.filter { (_, v) -> v.blocked || v.kind == "LIKELY_SPAM" || v.kind == "REPORTED" }.keys, iso)
        val blockRules = rules.filter { it.enabled && it.kind == RuleKind.BLOCK && it.type in numberRules }
        val test: (String) -> Boolean = { n -> n in listed || n in flagged || blockRules.any { r -> CallPolicy.ruleMatches(r, n, iso) } }
        test
    }

    private fun group(
        calls: List<CallEntry>, index: PhoneIdentity.LineMap<ContactSummary>, filter: RecentFilter, q: String,
        layout: RecentsLayout = RecentsLayout.GROUPED,
    ): List<RecentGroup> {
        val filtered = calls.filter {
            when (filter) {
                RecentFilter.ALL -> true
                RecentFilter.MISSED -> it.type == CallType.MISSED || it.type == CallType.REJECTED
                RecentFilter.INCOMING -> it.type == CallType.INCOMING || it.type == CallType.ANSWERED_EXTERNALLY
                RecentFilter.OUTGOING -> it.type == CallType.OUTGOING
                RecentFilter.BLOCKED -> it.type == CallType.BLOCKED
                RecentFilter.VOICEMAIL -> it.type == CallType.VOICEMAIL
            }
        }
        fun keyOf(e: CallEntry) = if (e.presentationHidden || e.number.isBlank()) "hidden" else PhoneIdentity.key(e.number, countryIso).ifEmpty { "hidden" }
        val tz = TimeZone.getDefault()
        val privateNumber = c.appContext.getString(R.string.main_private_number)
        val unknown = c.appContext.getString(R.string.main_unknown)
        // P8: grouped (consecutive calls on one day), chronological (one row per call) or one row per number per day.
        val rows = RecentsGrouping.group(filtered, layout, ::keyOf) { e -> ListSections.localDay(e.date, tz) }
        val grouped = rows.map { list ->
            val e = list.first()
            val key = keyOf(e)
            RecentGroup(
                key + ":" + e.id, e.number, if (key == "hidden") null else index[e.number], e.cachedName, list, key == "hidden",
                fallbackTitle = if (key == "hidden") privateNumber else unknown,
            )
        }
        if (q.isBlank()) return grouped
        return grouped.filter { TextSearch.matches(q, it.title, listOf(it.number)) }
    }

    init {
        // The place names under unknown numbers ("Paris, France") are looked up here, off the main thread, so the
        // rows find them ready in the cache instead of each starting its own lookup while the list scrolls.
        viewModelScope.launch {
            groups.collectLatest { list ->
                withContext(Dispatchers.IO) {
                    list.orEmpty().asSequence()
                        .filter { it.contact == null && it.vaultId == null && !it.hidden && it.number.isNotBlank() }
                        .map { it.number }.distinct().take(LOCATIONS_AHEAD)
                        .forEach { n -> runCatching { NumberInfo.location(n, countryIso) } }
                }
            }
        }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L

        /** Rows whose place name is looked up ahead of scrolling. */
        const val LOCATIONS_AHEAD = 300

        /** R4: the time the 7-day window of [unreturnedMissed] is measured from, moved on hourly. */
        fun hourly(): Flow<Long> = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60 * 60 * 1000L)
            }
        }

        /** Today's local day, emitted again just after each midnight. */
        fun localDays(): Flow<Long> = flow {
            while (true) {
                val tz = TimeZone.getDefault()
                val now = System.currentTimeMillis()
                val today = ListSections.localDay(now, tz)
                emit(today)
                val nextMidnight = (today + 1) * DAY_MS - tz.getOffset(now)
                delay((nextMidnight - now).coerceIn(1_000L, DAY_MS) + 1_000L)
            }
        }.distinctUntilChanged()

        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
