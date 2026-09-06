package com.cairn.reader.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.cairn.reader.data.db.CacheStatus

/** How the Trash list is ordered. The default keeps the DAO's recently-trashed-first order. */
enum class TrashSort(val label: String) {
    RECENTLY_TRASHED("Recently trashed"),
    OLDEST_TRASHED("Oldest trashed"),
    TITLE("Title A–Z"),
    SOURCE("Source A–Z"),
    NEWEST("Newest published"),
    OLDEST("Oldest published"),
    LONGEST("Longest read"),
    SHORTEST("Shortest read"),
}

/** Which read-state a trashed item must be in to show. */
enum class TrashReadState(val label: String) { ANY("All"), UNREAD("Unread"), READ("Read") }

/**
 * The Trash: everything the user soft-deleted. Items sit here — hidden from feeds and the Library
 * but kept intact, offline copy and all — until they're restored or the Trash is emptied. Old
 * items auto-purge after [FeedRepository.trashRetentionDays]. Mirrors Read Later's search / sort /
 * advanced-filter / view-mode toolkit so a big Trash stays navigable.
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val preferencesRepository: com.cairn.reader.data.prefs.PreferencesRepository,
) : ViewModel() {

    /** User-set days a trashed item is kept before auto-purge (0 = never). Shown in the empty-state
     *  copy and adjustable from the Trash menu. */
    val retentionDays: StateFlow<Int> =
        preferencesRepository.preferences.map { it.trashRetentionDays }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    fun setRetentionDays(days: Int) = viewModelScope.launch { preferencesRepository.setTrashRetentionDays(days) }

    // Raw list preserves the DAO order (most-recently-trashed first), which the default sort keeps.
    private val raw: StateFlow<List<ItemListRow>> =
        feedRepository.observeTrash().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _sort = MutableStateFlow(TrashSort.RECENTLY_TRASHED)
    val sort: StateFlow<TrashSort> = _sort.asStateFlow()
    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()
    private val _sourceFilter = MutableStateFlow<String?>(null)
    val sourceFilter: StateFlow<String?> = _sourceFilter.asStateFlow()
    private val _readState = MutableStateFlow(TrashReadState.ANY)
    val readState: StateFlow<TrashReadState> = _readState.asStateFlow()
    private val _offlineOnly = MutableStateFlow(false)
    val offlineOnly: StateFlow<Boolean> = _offlineOnly.asStateFlow()
    private val _starredOnly = MutableStateFlow(false)
    val starredOnly: StateFlow<Boolean> = _starredOnly.asStateFlow()

    fun setQuery(v: String) { _query.value = v }
    fun setSort(s: TrashSort) { _sort.value = s }
    fun setTypeFilter(t: String?) { _typeFilter.value = t }
    fun setSourceFilter(s: String?) { _sourceFilter.value = s }
    fun setReadState(r: TrashReadState) { _readState.value = r }
    fun setOfflineOnly(v: Boolean) { _offlineOnly.value = v }
    fun setStarredOnly(v: Boolean) { _starredOnly.value = v }

    fun clearFilters() {
        _query.value = ""; _typeFilter.value = null; _sourceFilter.value = null
        _readState.value = TrashReadState.ANY; _offlineOnly.value = false; _starredOnly.value = false
    }

    /** The distinct item types present in the Trash, for the type-filter chips. */
    val availableTypes: StateFlow<List<String>> =
        raw.map { list -> list.map { it.type }.distinct().sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The distinct sources present in the Trash, for the source-filter menu. */
    val availableSources: StateFlow<List<String>> =
        raw.map { list -> list.mapNotNull { it.sourceTitle ?: it.siteName }.distinct().sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filters = combine(_typeFilter, _sourceFilter, _readState, _offlineOnly, _starredOnly) {
        type, source, read, offline, starred -> Filters(type, source, read, offline, starred)
    }

    private data class Filters(
        val type: String?, val source: String?, val read: TrashReadState,
        val offline: Boolean, val starred: Boolean,
    )

    val items: StateFlow<List<ItemListRow>> =
        combine(raw, _query, _sort, filters) { list, query, sort, f ->
            val q = query.trim()
            var out = list
            if (q.length >= 2) out = out.filter {
                it.title.contains(q, true) || (it.sourceTitle?.contains(q, true) == true) ||
                    (it.siteName?.contains(q, true) == true) || (it.excerpt?.contains(q, true) == true) ||
                    (it.author?.contains(q, true) == true)
            }
            f.type?.let { t -> out = out.filter { it.type == t } }
            f.source?.let { s -> out = out.filter { (it.sourceTitle ?: it.siteName) == s } }
            when (f.read) {
                TrashReadState.UNREAD -> out = out.filter { !it.isRead }
                TrashReadState.READ -> out = out.filter { it.isRead }
                TrashReadState.ANY -> {}
            }
            if (f.offline) out = out.filter { CacheStatus.isPermanent(it.cacheStatus) }
            if (f.starred) out = out.filter { it.isStarred }
            when (sort) {
                // The raw list is already most-recently-trashed first.
                TrashSort.RECENTLY_TRASHED -> out
                TrashSort.OLDEST_TRASHED -> out.reversed()
                TrashSort.TITLE -> out.sortedBy { it.title.lowercase() }
                TrashSort.SOURCE -> out.sortedBy { (it.sourceTitle ?: it.siteName ?: "").lowercase() }
                TrashSort.NEWEST -> out.sortedByDescending { it.publishedAt ?: it.savedAt }
                TrashSort.OLDEST -> out.sortedBy { it.publishedAt ?: it.savedAt }
                TrashSort.LONGEST -> out.sortedByDescending { it.readingMinutes }
                TrashSort.SHORTEST -> out.sortedBy { it.readingMinutes }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The full Trash size, ignoring filters, for the empty-trash confirmation. */
    val totalCount: StateFlow<Int> =
        raw.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // -- Actions ---------------------------------------------------------------

    fun restore(id: String) = viewModelScope.launch { feedRepository.restoreFromTrash(id) }

    fun deleteForever(id: String) = viewModelScope.launch { feedRepository.deleteForever(id) }

    /** Restore everything currently shown (respects the active filters). */
    fun restoreVisible() = viewModelScope.launch {
        feedRepository.restoreFromTrash(items.value.map { it.id })
    }

    /** Empty the whole Trash, permanently. */
    fun emptyTrash() = viewModelScope.launch { feedRepository.emptyTrash() }

    // -- Multi-select (bulk actions) -------------------------------------------
    private val _picked = MutableStateFlow<Set<String>>(emptySet())
    val picked: StateFlow<Set<String>> = _picked.asStateFlow()

    fun togglePick(id: String) { _picked.value = _picked.value.let { if (id in it) it - id else it + id } }
    fun clearPicks() { _picked.value = emptySet() }
    fun pickAll() { _picked.value = items.value.map { it.id }.toSet() }
    private fun consumePicks(): Set<String> = _picked.value.also { _picked.value = emptySet() }

    fun restorePicked() = viewModelScope.launch { feedRepository.restoreFromTrash(consumePicks()) }
    fun deletePickedForever() = viewModelScope.launch { feedRepository.deleteForever(consumePicks()) }
}
