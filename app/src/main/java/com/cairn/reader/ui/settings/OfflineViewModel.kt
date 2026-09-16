package com.cairn.reader.ui.settings

import com.cairn.reader.util.MultiSelectStore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.prefs.SwipeAction
import com.cairn.reader.data.prefs.SwipeConfig
import com.cairn.reader.data.prefs.swipeConfig
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.cairn.reader.data.db.CacheStatus

/** How the Offline list is ordered. */
enum class OfflineSort(val label: String) {
    RECENT("Recently saved"),
    OLDEST("Oldest saved"),
    TITLE("Title A–Z"),
    SOURCE("Source A–Z"),
    LONGEST("Longest read"),
    SHORTEST("Shortest read"),
}

/** Which offline copies to show: everything, only permanent archives, or only read-cached. */
enum class OfflineKind(val label: String) { ALL("All"), PERMANENT("Permanent"), CACHED("Cached") }

/**
 * The Offline surface: the concrete list of articles readable without a network — explicit
 * archival "Save offline" copies plus articles auto-cached when opened. From here the user can
 * remove just the download (keeping the entry) or delete the entry entirely (to Trash).
 */
@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val itemRepository: ItemRepository,
    private val preferencesRepository: PreferencesRepository,
    private val blobStore: BlobStore,
) : ViewModel() {

    private val raw: StateFlow<List<ItemListRow>> =
        feedRepository.observeCached().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The user's two-stage swipe actions, shared with the Inbox so list swipes behave the same. */
    val swipeActions: StateFlow<SwipeConfig> =
        preferencesRepository.preferences
            .map { it.swipeConfig }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SwipeConfig())

    /** True while an offline pack is being pulled down, so the UI can show progress. */
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    /** Commute Mode: pull the next batch of likely-reads fully offline. */
    fun prepareOfflinePack(onDone: (Int) -> Unit) {
        if (_preparing.value) return
        _preparing.value = true
        viewModelScope.launch {
            val saved = runCatching { feedRepository.prepareOfflinePack(25) }.getOrDefault(0)
            _preparing.value = false
            onDone(saved)
        }
    }

    /** Full count regardless of filters, for the header readout. */
    val totalCount: StateFlow<Int> =
        raw.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _sort = MutableStateFlow(OfflineSort.RECENT)
    val sort: StateFlow<OfflineSort> = _sort.asStateFlow()
    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()
    private val _kind = MutableStateFlow(OfflineKind.ALL)
    val kind: StateFlow<OfflineKind> = _kind.asStateFlow()
    private val _groupBySource = MutableStateFlow(false)
    val groupBySource: StateFlow<Boolean> = _groupBySource.asStateFlow()

    fun setQuery(v: String) { _query.value = v }
    fun setSort(s: OfflineSort) { _sort.value = s }
    fun setTypeFilter(t: String?) { _typeFilter.value = t }
    fun setKind(k: OfflineKind) { _kind.value = k }
    fun setGroupBySource(v: Boolean) { _groupBySource.value = v }
    fun clearFilters() { _query.value = ""; _typeFilter.value = null; _kind.value = OfflineKind.ALL }

    val availableTypes: StateFlow<List<String>> =
        raw.map { list -> list.map { it.type }.distinct().sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filters = combine(_query, _sort, _typeFilter, _kind) { q, s, t, k -> Quad(q, s, t, k) }
    private data class Quad(val q: String, val s: OfflineSort, val t: String?, val k: OfflineKind)

    val items: StateFlow<List<ItemListRow>> =
        combine(raw, filters) { list, f ->
            var out = list
            val q = f.q.trim()
            if (q.length >= 2) out = out.filter {
                it.title.contains(q, true) || (it.sourceTitle?.contains(q, true) == true) ||
                    (it.siteName?.contains(q, true) == true) || (it.excerpt?.contains(q, true) == true)
            }
            f.t?.let { t -> out = out.filter { it.type == t } }
            when (f.k) {
                OfflineKind.PERMANENT -> out = out.filter { CacheStatus.isPermanent(it.cacheStatus) }
                OfflineKind.CACHED -> out = out.filter { !CacheStatus.isPermanent(it.cacheStatus) }
                OfflineKind.ALL -> {}
            }
            when (f.s) {
                OfflineSort.RECENT -> out.sortedByDescending { it.savedAt }
                OfflineSort.OLDEST -> out.sortedBy { it.savedAt }
                OfflineSort.TITLE -> out.sortedBy { it.title.lowercase() }
                OfflineSort.SOURCE -> out.sortedBy { (it.sourceTitle ?: it.siteName ?: "").lowercase() }
                OfflineSort.LONGEST -> out.sortedByDescending { it.readingMinutes }
                OfflineSort.SHORTEST -> out.sortedBy { it.readingMinutes }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storageBytes = MutableStateFlow(-1L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    init { refreshStorage() }

    fun refreshStorage() = viewModelScope.launch {
        _storageBytes.value = withContext(Dispatchers.IO) { runCatching { blobStore.storageBytes() }.getOrDefault(0L) }
    }

    /** Remove just the offline download; the entry stays and re-fetches on next open. */
    fun removeCache(id: String) = viewModelScope.launch {
        feedRepository.removeOfflineCopy(id)
        refreshStorage()
    }

    /** Move the whole entry to the Trash (removes it from lists; its cache goes too when purged). */
    fun deleteEntry(id: String) = viewModelScope.launch {
        feedRepository.trashItem(id)
        refreshStorage()
    }

    /** Promote an auto-cached item to a permanent, archival-grade offline copy (downloads images). */
    fun makePermanent(id: String) = viewModelScope.launch {
        feedRepository.saveOffline(id)
        refreshStorage()
    }

    // -- Single-item ops wired to list-row swipes (mirrors the Inbox) ----------
    fun markRead(id: String, read: Boolean) = viewModelScope.launch { itemRepository.setRead(id, read) }
    fun toggleStar(id: String, starred: Boolean) = viewModelScope.launch { itemRepository.setStarred(id, starred) }
    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch { itemRepository.setReadLater(id, save) }
    fun archive(id: String) = viewModelScope.launch { itemRepository.setArchived(id, true) }

    /** Perform a configurable swipe action on a row. SHARE / OPEN_ORIGINAL need UI context, so the
     *  list host intercepts those before delegating here. */
    fun swipe(row: ItemListRow, action: SwipeAction) {
        when (action) {
            SwipeAction.MARK_READ -> markRead(row.id, !row.isRead)
            SwipeAction.SAVE -> toggleSave(row.id, !row.isReadLater)
            SwipeAction.STAR -> toggleStar(row.id, !row.isStarred)
            SwipeAction.ARCHIVE -> archive(row.id)
            // On the Offline surface, "delete" removes the whole entry (to Trash), not just the copy.
            SwipeAction.DELETE -> deleteEntry(row.id)
            // Already-offline items just re-affirm a permanent copy; a no-op-ish but harmless promote.
            SwipeAction.SAVE_OFFLINE -> makePermanent(row.id)
            SwipeAction.LIBRARY -> toggleStar(row.id, true)
            SwipeAction.OPEN_ORIGINAL, SwipeAction.SHARE, SwipeAction.NONE -> Unit
        }
    }

    // -- Multi-select (bulk actions) -------------------------------------------
    private val picks = MultiSelectStore<String>()
    val picked: StateFlow<Set<String>> = picks.selected

    fun togglePick(id: String) = picks.toggle(id)
    fun clearPicks() = picks.clear()
    fun pickAll() = picks.selectAll(items.value.map { it.id })
    private fun consumePicks(): Set<String> = picks.consume()

    fun makePermanentPicked() = viewModelScope.launch {
        consumePicks().forEach { feedRepository.saveOffline(it) }
        refreshStorage()
    }

    fun removeCachePicked() = viewModelScope.launch {
        consumePicks().forEach { feedRepository.removeOfflineCopy(it) }
        refreshStorage()
    }

    fun deleteEntriesPicked() = viewModelScope.launch {
        feedRepository.trashItems(consumePicks())
        refreshStorage()
    }
}
