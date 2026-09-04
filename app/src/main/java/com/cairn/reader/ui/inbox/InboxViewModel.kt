package com.cairn.reader.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.FeedUnread
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.prefs.ListViewMode
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The list lenses on the inbox. */
enum class InboxFilter(val label: String) {
    UNREAD("Unread"),
    STARRED("Starred"),
    SAVED("Saved"),
    ALL("All"),
}

/** What the drawer is currently pointing the inbox at: everything, one feed, or a folder. */
sealed interface DrawerSelection {
    data object All : DrawerSelection
    data class Feed(val sourceId: String, val title: String) : DrawerSelection
    data class Folder(val name: String) : DrawerSelection
}

enum class InboxSort(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first") }

data class InboxUiState(
    val loading: Boolean = true,
    val items: List<ItemListRow> = emptyList(),
    val unread: Int = 0,
    val filter: InboxFilter = InboxFilter.UNREAD,
    val sort: InboxSort = InboxSort.NEWEST,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val feedRepository: FeedRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val viewMode: StateFlow<ListViewMode> =
        preferencesRepository.preferences
            .map { it.listViewMode }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListViewMode.CARD)

    fun setViewMode(mode: ListViewMode) = viewModelScope.launch { preferencesRepository.setListViewMode(mode) }

    private val _filter = MutableStateFlow(InboxFilter.UNREAD)
    private val _sort = MutableStateFlow(InboxSort.NEWEST)

    /** What the drawer points the list at: All Articles, one feed, or a whole folder. */
    private val _selection = MutableStateFlow<DrawerSelection>(DrawerSelection.All)
    val selection: StateFlow<DrawerSelection> = _selection.asStateFlow()

    val feeds: StateFlow<List<FeedUnread>> =
        itemRepository.feedUnread().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        // First run: subscribe to a few real feeds, then pull them in.
        viewModelScope.launch {
            if (feedRepository.seedDefaultFeedsIfEmpty()) refresh()
        }
    }

    private val rows = combine(_filter, _selection) { filter, selection -> filter to selection }
        .flatMapLatest { (filter, selection) ->
            val source = (selection as? DrawerSelection.Feed)?.sourceId
            val folder = (selection as? DrawerSelection.Folder)?.name
            when (filter) {
                InboxFilter.UNREAD -> itemRepository.inbox(source, folder)
                InboxFilter.STARRED -> itemRepository.starred(source, folder)
                InboxFilter.SAVED -> itemRepository.saved(source, folder)
                InboxFilter.ALL -> itemRepository.all(source, folder)
            }
        }

    private val filteredRows = combine(rows, preferencesRepository.preferences, _sort) { list, prefs, sort ->
        val filtered = applyContentFilters(list, prefs.blockedKeywords, prefs.hideDuplicates)
        // The DAO returns newest-first; only OLDEST needs a reversal.
        if (sort == InboxSort.OLDEST) filtered.reversed() else filtered
    }

    val state: StateFlow<InboxUiState> =
        combine(filteredRows, itemRepository.unreadCount(), _filter, _sort) { items, unread, filter, sort ->
            InboxUiState(loading = false, items = items, unread = unread, filter = filter, sort = sort)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InboxUiState(),
        )

    fun setFilter(filter: InboxFilter) {
        _filter.value = filter
    }

    fun setSort(sort: InboxSort) {
        _sort.value = sort
    }

    /** Mark every unread item in the current drawer scope read. */
    fun markAllRead() = viewModelScope.launch {
        val sel = _selection.value
        itemRepository.markAllRead(
            sourceId = (sel as? DrawerSelection.Feed)?.sourceId,
            folder = (sel as? DrawerSelection.Folder)?.name,
        )
        _messages.emit("Marked all read")
    }

    /** Mark a specific feed or folder read from the drawer's long-press menu. */
    fun markFeedRead(sourceId: String) = viewModelScope.launch {
        itemRepository.markAllRead(sourceId = sourceId, folder = null)
        _messages.emit("Marked all read")
    }

    fun markFolderRead(folder: String) = viewModelScope.launch {
        itemRepository.markAllRead(sourceId = null, folder = folder)
        _messages.emit("Marked all read")
    }

    /** All Articles — the whole inbox, unread first. */
    fun selectAll() {
        _selection.value = DrawerSelection.All
        _filter.value = InboxFilter.UNREAD
    }

    /** Starred hub — every starred story, regardless of feed. */
    fun selectStarred() {
        _selection.value = DrawerSelection.All
        _filter.value = InboxFilter.STARRED
    }

    fun selectFeed(sourceId: String, title: String) {
        _selection.value = DrawerSelection.Feed(sourceId, title)
    }

    fun selectFolder(name: String) {
        _selection.value = DrawerSelection.Folder(name)
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { feedRepository.syncAll() }
            _refreshing.value = false
        }
    }

    fun addFeed(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _refreshing.value = true
            val result = feedRepository.addFeedByUrl(trimmed)
            _refreshing.value = false
            _messages.emit(
                result.fold(
                    onSuccess = { "Feed added" },
                    onFailure = { it.message ?: "Couldn't find a feed at that address" },
                ),
            )
        }
    }

    private fun applyContentFilters(list: List<ItemListRow>, blocked: Set<String>, dedup: Boolean): List<ItemListRow> {
        var out = list
        if (blocked.isNotEmpty()) {
            out = out.filter { row ->
                val hay = (row.title + " " + (row.excerpt ?: "")).lowercase()
                blocked.none { it.isNotBlank() && hay.contains(it) }
            }
        }
        if (dedup) {
            val seen = HashSet<String>()
            out = out.filter { seen.add(it.title.trim().lowercase()) }
        }
        return out
    }

    fun markRead(id: String, read: Boolean = true) = viewModelScope.launch { itemRepository.setRead(id, read) }
    fun toggleStar(id: String, starred: Boolean) = viewModelScope.launch { itemRepository.setStarred(id, starred) }

    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch {
        itemRepository.setReadLater(id, save)
        _messages.emit(if (save) "Saved for later" else "Removed from Saved")
    }

    fun archive(id: String) = viewModelScope.launch {
        itemRepository.setArchived(id, true)
        _messages.emit(ARCHIVE_UNDO_MARKER + id)
    }

    fun unarchive(id: String) = viewModelScope.launch { itemRepository.setArchived(id, false) }

    companion object {
        /** Prefix that tells the UI a message carries an item id for an "Undo archive" action. */
        const val ARCHIVE_UNDO_MARKER = "archived:"
    }
}
