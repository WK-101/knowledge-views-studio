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
import app.parley.common.people.Archive
import app.parley.common.people.ArchivedCard
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.TextSearch
import app.parley.calls.NetworkNames
import app.parley.common.calls.NetworkName
import app.parley.common.calls.NetworkNameSeen
import app.parley.common.calls.RecentsCallers
import app.parley.common.calls.RecentsGrouping
import app.parley.common.calls.RecentsLayout
import app.parley.common.history.HistoryFilter
import app.parley.common.ux.CallGlance
import app.parley.common.ux.ListSections
import app.parley.data.DataContainer
import app.parley.data.NumberInfo
import app.parley.data.history.CallHistory
import app.parley.ui.calls.ToCallModel
import java.time.ZoneId
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** One row of the Recents list: a day header or a call row. */
sealed interface RecentsRow {
    val key: String

    /** The header of the calls of one local day; [today] (a local day) changes at midnight, so the text is redone. */
    data class Day(override val key: String, val date: Long, val today: Long) : RecentsRow

    /** A call row; [place] is where it sits in its day, for the Cards style's segmented corners. */
    data class Call(val group: RecentGroup, val place: ListSections.Place = ListSections.Place.ONLY) : RecentsRow {
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
 * docked keypad's idle list and the Recents menus (activity scope). [clock] is the time now (tests fix it, so a day
 * boundary never falls between their calls).
 */
@OptIn(FlowPreview::class)
class RecentsViewModel(private val c: DataContainer, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val directory = c.directory
    val countryIso: String = directory.countryIso
    private val settings = c.settings.settings

    val filter = MutableStateFlow(RecentFilter.ALL)

    /** A chip tapped in Recents: shown now, and remembered for the next launch (Settings › Recents & history). */
    fun setFilter(f: RecentFilter) {
        filter.value = f
        viewModelScope.launch { runCatching { c.settings.update { it.copy(recentsFilter = if (f == RecentFilter.ALL) "" else f.name) } } }
    }

    init {
        // Recents opens on the chip used last, unless something (a missed-call notification) already chose one.
        viewModelScope.launch {
            val s = c.settings.current()
            val name = RecentsCallers.restored(s.rememberRecentsFilter, s.recentsFilter, RecentFilter.ALL.name, TRANSIENT_CHIPS)
            val restored = RecentFilter.entries.firstOrNull { it.name == name } ?: RecentFilter.ALL
            if (filter.value == RecentFilter.ALL) filter.value = restored
        }
    }

    /** Rows selected for bulk actions (keys of [RecentGroup]). */
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val query = MutableStateFlow("")

    fun toggleSelected(g: RecentGroup) {
        selection.value = selection.value.let { if (g.key in it) it - g.key else it + g.key }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /**
     * "Delete from call history" on a row: its calls in the log (and archive) and its private calls. [done] gets the
     * number of calls and the way back, for the snackbar's Undo.
     */
    fun delete(g: RecentGroup, done: (count: Int, undo: suspend () -> Unit) -> Unit) {
        viewModelScope.launch {
            val batch = c.history.delete(g.calls.filter { it.id > 0 })
            val privateRows = c.vault.deletePrivateCallsForUndo(g.calls.filter { it.id < 0 }.map { -it.id })
            done(g.calls.size) {
                batch?.let { c.history.undoDelete(it) }
                c.vault.restorePrivateCalls(privateRows)
            }
        }
    }

    /** "Show all calls": clears the chips and the call-history filter. */
    fun showAll() {
        filter.value = RecentFilter.ALL
        c.history.activeFilter.value = HistoryFilter()
    }

    val historyFilter get() = c.history.activeFilter

    val voicemail get() = c.voicemail.state

    /** System call log (plus Parley's archive) + private (vault) calls, newest first. */
    // Until the full log (and the archive) have loaded, the first page of the call log is shown.
    private val allCalls: StateFlow<List<CallEntry>?> = combine(
        c.history.calls, c.callLog.preview, c.vault.privateCalls, settings.map { it.hideVault }.distinctUntilChanged(),
    ) { full, preview, priv, hidden ->
        val sys = full ?: preview ?: return@combine null
        if (hidden || priv.isEmpty()) return@combine sys
        (sys + priv.map(CallHistory::privateEntry)).sortedByDescending { it.date }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null) // merged once, shared by the list and the unreturned count

    // Keyed by line (E.164 with this phone's country), so a foreign number sharing the last 9 digits isn't shown as private.
    // In discreet mode private contacts count as unknown numbers everywhere, the Contacts chip included.
    private val vaultByKey = combine(c.vault.contacts, settings.map { it.hideVault }.distinctUntilChanged()) { list, hidden ->
        if (hidden) emptyMap() else list.flatMap { v -> v.numbers.map { PhoneIdentity.key(it, countryIso) to v.id } }.toMap()
    }

    /** [allCalls] with the filter chips of the call history applied (SIM, type, period, duration). */
    private val filteredCalls = combine(allCalls, c.history.activeFilter) { calls, f ->
        if (calls == null || f.isEmpty) calls else calls.filter(f.matcher(clock(), ZoneId.systemDefault()))
    }

    // The call-list layout travels with the calls, so Recents regroups when it changes.
    private val callsAndLayout = combine(filteredCalls, settings.map { it.recentsLayout }.distinctUntilChanged()) { calls, layout -> calls to layout }

    /** Archived contacts by line: out of the address book, still named in Recents. */
    private val archivedIndex = c.archive.cards.map { Archive.index(it, countryIso) }

    // Who the numbers belong to beside the address book: private contacts (by line key) and archived ones; and the
    // names the network sent for the others.
    private val savedElsewhere = combine(vaultByKey, archivedIndex, NetworkNames.readers(c).onStart { emit(NetworkNames.NONE) }) { v, a, n ->
        Triple(v, a, n)
    }

    val groups: StateFlow<List<RecentGroup>?> = combine(
        callsAndLayout, directory.numberIndex, filter, query.debounce(80), savedElsewhere,
    ) { (calls, layout), index, filter, q, (vaults, archived, network) ->
        calls?.let {
            group(it, index, filter, q, layout, vaults.keys, archived, network).map { g ->
                if (g.calls.first().id < 0) g.copy(vaultId = vaults[PhoneIdentity.key(g.number, countryIso)]) else g
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    /**
     * The place names under unknown numbers ("Paris, France") are looked up off the main thread, so the rows find them
     * ready in the cache instead of each starting its own lookup while the list scrolls. It runs only while [list] has
     * subscribers, so the pipeline stops with the screen instead of regrouping in the background.
     */
    private val locationPrefetch: Flow<Nothing> = flow {
        groups.collectLatest { list ->
            withContext(Dispatchers.IO) {
                list.orEmpty().asSequence()
                    .filter { it.contact == null && it.vaultId == null && !it.hidden && it.number.isNotBlank() }
                    .map { it.number }.distinct().take(LOCATIONS_AHEAD)
                    .forEach { n -> runCatching { NumberInfo.location(n, countryIso) } }
            }
        }
    }

    /** The list with its day headers; redone at midnight so "Today" becomes "Yesterday". */
    val list: StateFlow<RecentsList?> = merge(
        combine(groups, localDays(clock)) { groups, today -> groups?.let { RecentsList(it, rows(it, today)) } },
        locationPrefetch,
    ).flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    private fun rows(groups: List<RecentGroup>, today: Long): List<RecentsRow> {
        val tz = TimeZone.getDefault()
        val sections = ListSections.interleave(groups) { ListSections.localDay(it.latest.date, tz) }
        val places = ListSections.places(sections)
        return sections.mapIndexed { i, r ->
            when (r) {
                is ListSections.Row.Header -> RecentsRow.Day("h" + r.first.key, r.first.latest.date, today)
                is ListSections.Row.Item -> RecentsRow.Call(r.item, places[i] ?: ListSections.Place.ONLY)
            }
        }
    }

    /** Block rules that name numbers (the others go by name, region or line type). */
    private val numberRules = setOf(RuleType.EXACT, RuleType.PREFIX, RuleType.WILDCARD)

    /**
     * Ids of missed calls not returned yet, over every call (whatever the filters show), for the Recents
     * tint, the Call back pill and the Missed chip's count.
     */
    val unreturnedMissed: StateFlow<Set<Long>> = combine(allCalls, notWorthReturning(), hourly(clock)) { calls, excluded, now ->
        calls?.let { CallGlance.unreturnedMissed(it, { n -> PhoneIdentity.key(n, countryIso) }, now, excluded = excluded) } ?: emptySet()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptySet())

    /** The To call list (I9): its strip tops Recents; fed by these calls and the missed calls still to return. */
    val toCall: ToCallModel by lazy { ToCallModel(c, viewModelScope, allCalls, unreturnedMissed) }

    /**
     * Numbers whose missed calls aren't worth a "call back": on the system block list, caught by a block rule, or
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

    /** Whether the chip [filter] keeps call [e]: by its type, or by who called (Unknown, Contacts). */
    private fun chipKeeps(
        filter: RecentFilter, e: CallEntry, index: PhoneIdentity.LineMap<ContactSummary>, vaultKeys: Set<String>,
        archived: PhoneIdentity.LineMap<ArchivedCard>?,
    ): Boolean {
        // Who called: a contact, a private or archived contact (their calls in Parley's history, or their number), or nobody known.
        fun isContact() = e.id < 0 || index[e.number] != null || PhoneIdentity.key(e.number, countryIso) in vaultKeys || archived?.get(e.number) != null
        val hidden = e.presentationHidden || e.number.isBlank()
        return when (filter) {
            RecentFilter.UNKNOWN -> RecentsCallers.matches(RecentsCallers.Who.UNKNOWN, isContact(), hidden)
            RecentFilter.CONTACTS -> RecentsCallers.matches(RecentsCallers.Who.CONTACTS, isContact(), hidden)
            else -> filter.keepsType(e.type)
        }
    }

    /** The chips by call type. */
    private fun RecentFilter.keepsType(type: CallType): Boolean = when (this) {
        RecentFilter.MISSED -> type == CallType.MISSED || type == CallType.REJECTED
        RecentFilter.INCOMING -> type == CallType.INCOMING || type == CallType.ANSWERED_EXTERNALLY
        RecentFilter.OUTGOING -> type == CallType.OUTGOING
        RecentFilter.BLOCKED -> type == CallType.BLOCKED
        RecentFilter.VOICEMAIL -> type == CallType.VOICEMAIL
        else -> true
    }

    /** Each call's line for the network's names ([NetworkNames.line]), read once: lists regroup on every keystroke. */
    private val lines = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun lineOf(e: CallEntry): String =
        lines.getOrPut(e.accountId.orEmpty() + "\n" + e.number) { NetworkNames.line(c.appContext, e.number, e.accountId) }

    private fun group(
        calls: List<CallEntry>, index: PhoneIdentity.LineMap<ContactSummary>, filter: RecentFilter, q: String,
        layout: RecentsLayout = RecentsLayout.GROUPED,
        vaultKeys: Set<String> = emptySet(),
        archived: PhoneIdentity.LineMap<ArchivedCard>? = null,
        network: (String) -> List<NetworkNameSeen> = NetworkNames.NONE,
    ): List<RecentGroup> {
        val filtered = calls.filter { chipKeeps(filter, it, index, vaultKeys, archived) }
        fun keyOf(e: CallEntry) = if (e.presentationHidden || e.number.isBlank()) "hidden" else PhoneIdentity.key(e.number, countryIso).ifEmpty { "hidden" }
        val tz = TimeZone.getDefault()
        val privateNumber = c.appContext.getString(R.string.main_private_number)
        val unknown = c.appContext.getString(R.string.main_unknown)
        // Grouped (consecutive calls on one day), chronological (one row per call) or one row per number per day.
        val rows = RecentsGrouping.group(filtered, layout, ::keyOf) { e -> ListSections.localDay(e.date, tz) }
        val grouped = rows.map { list ->
            val e = list.first()
            val key = keyOf(e)
            val contact = if (key == "hidden") null else index[e.number]
            val archivedName = if (contact == null && key != "hidden") archived?.get(e.number)?.name else null
            // A number nobody saved: what the network called it (never on a private contact's calls or number).
            val unsaved = contact == null && archivedName == null && e.id >= 0
            // Asked as the call's SIM reads the number, as it was written.
            val names = if (unsaved && key != "hidden" && key !in vaultKeys) network(lineOf(e)) else emptyList()
            RecentGroup(
                key + ":" + e.id, e.number, contact, e.cachedName, list, key == "hidden",
                fallbackTitle = if (key == "hidden") privateNumber else unknown,
                archivedName = archivedName,
                networkName = NetworkName.latest(names)?.name,
            )
        }
        if (q.isBlank()) return grouped
        return grouped.filter { TextSearch.matches(q, it.title, listOf(it.number)) }
    }

    /** "3 unknown callers today", for the quiet line under the Unknown chip (counted over every call, whatever the filters). */
    val unknownToday: StateFlow<Int> = combine(allCalls, directory.numberIndex, vaultByKey, localDays(clock)) { calls, index, vaults, today ->
        val tz = TimeZone.getDefault()
        val since = today * DAY_MS - tz.getOffset(clock())
        calls?.let { list ->
            RecentsCallers.unknownCallersSince(list, since, { PhoneIdentity.key(it, countryIso) }) { e ->
                e.id < 0 || index[e.number] != null || PhoneIdentity.key(e.number, countryIso) in vaults
            }
        } ?: 0
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), 0)

    private companion object {
        const val STOP_AFTER_MS = 5_000L

        /** Chips for a look now and then: Recents never opens on them (it would look as if the calls had gone). */
        val TRANSIENT_CHIPS = setOf(RecentFilter.BLOCKED.name, RecentFilter.VOICEMAIL.name)

        /** Rows whose place name is looked up ahead of scrolling. */
        const val LOCATIONS_AHEAD = 300

        /** The time the 7-day window of [unreturnedMissed] is measured from, moved on hourly. */
        fun hourly(clock: () -> Long): Flow<Long> = flow {
            while (true) {
                emit(clock())
                delay(60 * 60 * 1000L)
            }
        }

        /** Today's local day, emitted again just after each midnight. */
        fun localDays(clock: () -> Long): Flow<Long> = flow {
            while (true) {
                val tz = TimeZone.getDefault()
                val now = clock()
                val today = ListSections.localDay(now, tz)
                emit(today)
                val nextMidnight = (today + 1) * DAY_MS - tz.getOffset(now)
                delay((nextMidnight - now).coerceIn(1_000L, DAY_MS) + 1_000L)
            }
        }.distinctUntilChanged()

        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
