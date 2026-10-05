package app.parley.ui.people

import app.parley.common.catching
import app.parley.common.PhoneIdentity
import app.parley.common.ContactSummary
import app.parley.common.people.FavoriteOrder
import app.parley.common.people.FavoriteSort
import app.parley.common.people.ContactListSearch
import app.parley.common.people.ContactSearch
import app.parley.data.messaging.Romanizer
import app.parley.common.people.Facet
import app.parley.common.people.FacetChoice
import app.parley.common.people.FacetChoices
import app.parley.common.people.FieldFilter
import app.parley.common.people.SearchDocs
import app.parley.common.people.LabelFilter
import app.parley.common.people.NameOrder
import app.parley.common.people.ContactRef
import app.parley.common.people.Collation
import app.parley.common.people.PrivateLabels
import app.parley.common.people.PersonExtra
import app.parley.common.people.SecondLines
import app.parley.common.people.ContactSort
import app.parley.common.people.ContactSorting
import app.parley.common.people.SortFacts
import app.parley.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import app.parley.common.ux.ListSections
import app.parley.data.DataContainer
import app.parley.data.people.PeopleIndexData
import app.parley.data.people.PeopleSettings
import app.parley.ui.common.Format
import app.parley.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /** The "Private" filter chip: only private contacts (negative ids) are listed. */
    privateOnly: StateFlow<Boolean> = MutableStateFlow(false),
    /** Private contacts are in Parley's lists (false in discreet mode): their labels count then too. */
    includePrivate: StateFlow<Boolean> = MutableStateFlow(true),
) {

    val settings: StateFlow<PeopleSettings> = c.people.prefs.settings

    /**
     * The address book's per-contact index with private contacts' labels added under their list ids, so label pages,
     * the label filters ("any", "all", "Unlabelled") and label counts treat them like everyone else. Followed only
     * while a screen shows it, so the index can stop with the app in the background.
     */
    val index: StateFlow<PeopleIndexData> = combine(c.people.index.data, c.privateLabels.titles, includePrivate) { idx, private, include ->
        if (!include || private.isEmpty()) return@combine idx
        val extras = HashMap(idx.extras)
        private.forEach { (vaultId, titles) -> extras[ContactRef.Private(vaultId).navId] = PersonExtra(labels = titles) }
        val counts = HashMap(idx.labelCounts)
        PrivateLabels.counts(private).forEach { (t, n) -> counts[t] = (counts[t] ?: 0) + n }
        idx.copy(extras = extras, labelCounts = counts)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), c.people.index.data.value)

    /** Label/account filter of the Contacts tab (AND/OR comes from the saved preference). */
    val filter = MutableStateFlow(LabelFilter())

    /** Collators aren't thread-safe and the flows below run concurrently on Default: one per flow. */
    private val filteredCollator = Collation.Order()
    private val favoritesCollator = Collation.Order()

    /** The Contacts search is open: the Filters chip shows (set by the home screen). */
    val searchOpen = MutableStateFlow(false)

    /** Private contacts' details, opened in memory while a search or filter is in use ([PrivateSearch]). */
    val privateSearch = PrivateSearch(
        c, scope,
        combine(searchOpen, query, filter, includePrivate) { open, q, f, include -> include && (open || q.isNotBlank() || !f.fields.isEmpty) }
            .stateIn(scope, SharingStarted.Eagerly, false),
        combine(query, filter, searchOpen) { q, f, open -> Triple(q, f, open) },
    )

    /**
     * Every listed contact's search doc: the address book's from the index, a private contact's from its opened
     * details while they may be searched ([SearchDocs]); the rest are found by what the list shows.
     */
    private val docs: StateFlow<Map<Long, ContactSearch.Doc>> = combine(
        c.people.index.data, privateSearch.docs, includePrivate, AppLock.locked,
    ) { idx, priv, include, appLocked ->
        val searchable = SearchDocs.privateDetailsSearchable(vaultOpen = priv.isNotEmpty(), discreet = !include, appLocked = appLocked)
        SearchDocs.combine(idx.search, priv, searchable, discreet = !include)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** List ids of temporary contacts (the "Temporary" filter). */
    private val temporaryIds: StateFlow<Set<Long>> = combine(contacts, c.temporaries.all, c.vault.contacts) { list, temps, vault ->
        val byKey = list.orEmpty().associateBy({ it.lookupKey }, { it.id })
        temps.mapNotNull { t -> byKey[t.lookupKey] ?: t.contactId }.toSet() +
            vault.filter { it.expiresAt != null }.map { ContactRef.Private(it.id).navId }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * The listed contacts each with its doc and its name folded once per list change, so a keystroke folds only the
     * query. A contact without a doc (a private one whose details are closed, or before the index loaded) gets one
     * from what the list shows.
     */
    val prepared: StateFlow<List<ContactListSearch.Entry>?> = combine(
        contacts.combine(privateOnly) { l, only -> if (only) l?.filter { it.id < 0 } else l }, docs,
    ) { list, d ->
        list?.map { ct ->
            val doc = d[ct.id] ?: ContactSearch.Builder(ct.id, countryIso, latin = Romanizer).apply {
                // A shown name that is really a number or an email isn't a name ("No name" filter).
                if (ct.displayName.any { it.isLetter() } && '@' !in ct.displayName) name(ct.displayName) else shownName(ct.displayName)
                ct.phones.forEach { number(it.number) }
                ct.emails.forEach { email(it) }
            }.build()
            ContactListSearch.Entry(ct, ContactSearch.fold(listOfNotNull(ct.displayName, ct.displayNameAlt, ct.phoneticName).joinToString(" ")), doc)
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** What each filter offers, from the values the listed contacts actually have (countries in use…). */
    val filterChoices: StateFlow<Map<Facet, List<FacetChoice>>> = prepared.map { list ->
        FacetChoices.from(list.orEmpty().map { it.doc.facets })
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Contacts with nickname display applied, filtered by search, labels, account and the field filters, with
     * "Matched: address" hints for contacts found by a field other than the name or number.
     */
    private val searched: StateFlow<Pair<List<ContactSummary>, Map<Long, String>>?> = combine(
        prepared, query.debounce(80), filter, combine(index, temporaryIds, ::Pair), settings,
    ) { list, q, f, (idx, temporary), s ->
        list ?: return@combine null
        val f2 = f.copy(matchAll = s.labelMatchAll)
        val r = ContactListSearch.run(list, ContactSearch.Query(q), f2, idx.extras, temporary, s.preferNickname, filteredCollator)
        val res = c.appContext.resources
        // Every field: addresses, notes, dates, relations, custom fields… (Contacts search only, never the keypad's T9).
        val hints = r.explained.mapValues { (_, field) -> matchHint(res, field) }
        val shown = r.shown
        shown to hints
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val filtered: StateFlow<List<ContactSummary>?> = searched.map { it?.first }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** How the Contacts list is ordered (Contacts ⋮ › Sort by), remembered. */
    val sort: StateFlow<ContactSort> = settings.map { it.contactSort }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), settings.value.contactSort)

    /** The Contacts list's sort sheet is open (from the tab's ⋮ or its sort chip). */
    val sortSheet = MutableStateFlow(false)

    fun setSort(sort: ContactSort) = update { it.copy(contactSort = sort) }

    private val sortCollator = Collation.Order()

    /** What the chosen order reads, only for the order that needs it (by name reads nothing). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sortFacts: Flow<SortFacts> = sort.flatMapLatest { s ->
        when (s) {
            ContactSort.NAME -> flowOf(SortFacts())
            // A device contact's last change stands in for when it was added (Android keeps no such date); a private
            // contact's save time is its own.
            ContactSort.RECENTLY_ADDED -> combine(contacts, c.vault.contacts) { list, vault ->
                val added = HashMap<Long, Long>()
                list.orEmpty().forEach { ct -> if (ct.id > 0) c.contacts.lastUpdated(ct.id)?.let { added[ct.id] = it } }
                vault.forEach { v -> added[ContactRef.Private(v.id).navId] = v.createdAt }
                SortFacts(addedAt = added)
            }
            // Calls with private contacts are kept apart from the call history (in the vault): counted too while
            // private contacts are listed.
            ContactSort.MOST_CALLED -> combine(contacts, c.history.calls, c.vault.privateCalls, includePrivate) { list, calls, private, include ->
                val privateCalls = if (include) private.map { it.vaultId } else emptyList()
                SortFacts(calls = ContactSorting.callCounts(list.orEmpty(), calls.orEmpty().map { it.number }, privateCalls, countryIso))
            }
            ContactSort.COMPANY -> combine(c.people.index.data, c.vault.contacts, includePrivate) { idx, vault, include ->
                val company = HashMap<Long, String>()
                idx.extras.forEach { (id, e) -> if (e.company.isNotBlank()) company[id] = e.company }
                if (include) vault.forEach { v -> if (v.company.isNotBlank()) company[ContactRef.Private(v.id).navId] = v.company }
                SortFacts(company = company)
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * [filtered] in the chosen order with its headers (by name: the letters of the name it is sorted by, so the A–Z rail
     * matches "Sort by"), worked out once per list change here instead of in the list's builder (which runs again on
     * selection, hint and settings changes).
     */
    val listing: StateFlow<List<ListSections.Row<String, ContactSummary>>?> = combine(filtered, sort, sortFacts) { list, s, facts ->
        val res = c.appContext.resources
        list?.let { ContactSorting.rows(it, s, facts, sortCollator, res.getString(R.string.cs_sort_no_company), res.getString(R.string.cs_sort_not_called)) }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The last list's first screenful with its headers, shown on a cold start until [listing] first arrives, so a large
     * address book shows rows at once instead of a spinner. Null once the real list is there (or nothing was kept).
     */
    val listHead = MutableStateFlow<List<ListSections.Row<String, ContactSummary>>?>(null)

    init {
        scope.launch(Dispatchers.IO) {
            val head = c.people.listHead.load() ?: return@launch
            if (listing.value == null) listHead.value = ListSections.interleave(head) { ListSections.letterOf(it.sortName) }
        }
        scope.launch {
            listing.first { it != null }
            listHead.value = null
        }
        // Kept a while after the list settles, and only when its first screenful changed.
        scope.launch(Dispatchers.IO) {
            contacts.filterNotNull().debounce(LIST_HEAD_QUIET_MS).collect { catching { c.people.listHead.save(it) } }
        }
    }

    /** "Matched: address" for contacts the search found by another field than the name or number. */
    val searchHints: StateFlow<Map<Long, String>> = searched.map { it?.second.orEmpty() }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Second line for each visible contact (collisions among visible names are resolved automatically). */
    val secondLines: StateFlow<Map<Long, String>> = combine(filtered, index, settings) { list, idx, s ->
        SecondLines.compute(list.orEmpty(), idx.extras, s.secondLine, { Format.number(it, countryIso) }, nativeNames = s.nativeNames)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Calls per contact over the loaded call history (for "Most called"). */
    private val callCounts: StateFlow<Map<Long, Int>> = combine(contacts, c.history.calls) { list, calls ->
        val byKey = PhoneIdentity.LineMap<Long>(countryIso)
        list.orEmpty().filter { it.starred }.forEach { ct -> ct.phones.forEach { p -> byKey.putIfAbsent(p.number, ct.id) } }
        val counts = HashMap<Long, Int>()
        calls.orEmpty().forEach { e -> byKey[e.number]?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        counts as Map<Long, Int>
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val favorites: StateFlow<List<ContactSummary>> = combine(contacts, settings, callCounts, index) { list, s, counts, idx ->
        val favs = list.orEmpty().filter { it.starred }
            .map { ct -> if (s.preferNickname) NameOrder.renamed(ct, SecondLines.displayName(ct, idx.extras[ct.id], true)) else ct }
        FavoriteOrder.sort(favs, s.favoriteSort, s.favoriteOrder, counts, favoritesCollator)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** Adds or removes one field filter's value (a country, "Has an email"…). */
    fun toggleField(facet: Facet, key: String, display: String? = null) {
        filter.value = filter.value.let { it.copy(fields = it.fields.toggle(facet, key, display)) }
    }

    fun clearFields() {
        filter.value = filter.value.copy(fields = FieldFilter())
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
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** How long the contact list must be quiet before its first screenful is kept for the next cold start. */
private const val LIST_HEAD_QUIET_MS = 10_000L
