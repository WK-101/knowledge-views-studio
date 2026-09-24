package app.parley.ui.people

import app.parley.common.ContactSummary
import app.parley.common.PhoneNumbers
import app.parley.common.people.FavoriteOrder
import app.parley.common.people.FavoriteSort
import app.parley.common.people.BroadSearch
import app.parley.common.people.LabelFilter
import app.parley.common.people.PersonExtra
import app.parley.common.people.SecondLines
import app.parley.data.DataContainer
import app.parley.data.people.PeopleIndexData
import app.parley.data.people.PeopleSettings
import app.parley.ui.common.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator

/**
 * Contacts-tab and Favorites state for the contacts features: label/account filters, the second line under names,
 * "prefer nickname", and the favourites order. Owned by [app.parley.AppViewModel] (`vm.people`).
 */
@OptIn(FlowPreview::class)
class PeopleUi(
    private val c: DataContainer,
    private val scope: CoroutineScope,
    contacts: StateFlow<List<ContactSummary>?>,
    query: StateFlow<String>,
    private val countryIso: String,
) {
    val settings: StateFlow<PeopleSettings> = c.people.prefs.settings
    val index: StateFlow<PeopleIndexData> = c.people.index.data

    /** Label/account filter of the Contacts tab (AND/OR comes from the saved preference). */
    val filter = MutableStateFlow(LabelFilter())

    /** Collators aren't thread-safe and the flows below run concurrently on Default: one per flow. */
    private fun newCollator(): Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    private val filteredCollator = newCollator()
    private val favoritesCollator = newCollator()

    /**
     * Contacts with nickname display applied, filtered by search, labels and account, with I8's "Matched: address"
     * hints for contacts found by a field other than the name or number.
     */
    private val searched: StateFlow<Pair<List<ContactSummary>, Map<Long, String>>?> = combine(contacts, query.debounce(80), filter, index, settings) { list, q, f, idx, s ->
        list ?: return@combine null
        val f2 = f.copy(matchAll = s.labelMatchAll)
        val hints = HashMap<Long, String>()
        var shown = list.filter { ct ->
            val e = idx.extras[ct.id]
            if (!(f2.isEmpty || f2.matches(e))) return@filter false
            if (q.isBlank()) return@filter true
            // I8: addresses, notes, company, websites and handles too (Contacts search only, never the keypad).
            val extra = idx.search[ct.id] ?: e?.let { BroadSearch.Extra(nickname = it.nickname, company = it.company, title = it.title) }
            val field = BroadSearch.match(q, ct.displayName, ct.phones.map { it.number }, ct.emails, extra) ?: return@filter false
            BroadSearch.hint(field)?.let { hints[ct.id] = it }
            true
        }
        if (s.preferNickname) {
            shown = shown.map { ct -> ct.copy(displayName = SecondLines.displayName(ct, idx.extras[ct.id], true)) }
                .sortedWith { a, b -> filteredCollator.compare(a.displayName, b.displayName) }
        }
        shown to (hints as Map<Long, String>)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    val filtered: StateFlow<List<ContactSummary>?> = searched.map { it?.first }.stateIn(scope, SharingStarted.Eagerly, null)

    /** I8: "Matched: address" for contacts the search found by another field than the name or number. */
    val searchHints: StateFlow<Map<Long, String>> = searched.map { it?.second.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Second line for each visible contact (collisions among visible names are resolved automatically). */
    val secondLines: StateFlow<Map<Long, String>> = combine(filtered, index, settings) { list, idx, s ->
        SecondLines.compute(list.orEmpty(), idx.extras, s.secondLine) { Format.number(it, countryIso) }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Calls per contact over the loaded call history (for "Most called"). */
    private val callCounts: StateFlow<Map<Long, Int>> = combine(contacts, c.callLog.calls) { list, calls ->
        val byKey = HashMap<String, Long>()
        list.orEmpty().filter { it.starred }.forEach { ct -> ct.phones.forEach { p -> byKey.putIfAbsent(PhoneNumbers.matchKey(p.number), ct.id) } }
        val counts = HashMap<Long, Int>()
        calls.orEmpty().forEach { e -> byKey[PhoneNumbers.matchKey(e.number)]?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        counts as Map<Long, Int>
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val favorites: StateFlow<List<ContactSummary>> = combine(contacts, settings, callCounts, index) { list, s, counts, idx ->
        val favs = list.orEmpty().filter { it.starred }.map { ct -> if (s.preferNickname) ct.copy(displayName = SecondLines.displayName(ct, idx.extras[ct.id], true)) else ct }
        FavoriteOrder.sort(favs, s.favoriteSort, s.favoriteOrder, counts) { a, b -> favoritesCollator.compare(a, b) }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun extra(id: Long): PersonExtra? = index.value.extras[id]

    fun update(f: (PeopleSettings) -> PeopleSettings) {
        scope.launch { c.people.prefs.update(f) }
    }

    fun toggleLabel(title: String) {
        filter.value = filter.value.toggle(title)
    }

    fun setUnlabelled(on: Boolean) {
        filter.value = filter.value.copy(unlabelled = on)
    }

    fun setAccount(label: String?) {
        filter.value = filter.value.copy(account = label)
    }

    fun clearFilter() {
        filter.value = LabelFilter()
    }

    /** Stores a new custom favourites order (and switches the sort to Custom). */
    fun moveFavorite(keys: List<String>, from: Int, to: Int) {
        val next = FavoriteOrder.move(keys, from, to)
        update { it.copy(favoriteOrder = next, favoriteSort = FavoriteSort.CUSTOM) }
    }

    fun setFavoriteOrder(keys: List<String>) = update { it.copy(favoriteOrder = keys, favoriteSort = FavoriteSort.CUSTOM) }

    /** Account labels that hold contacts, with counts ("Google · me@x (212)"). */
    val accountChoices: StateFlow<List<Pair<String, Int>>> = index.map { idx ->
        idx.accountCounts.entries.sortedByDescending { it.value }.map { it.key.displayLabel to it.value }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
}
