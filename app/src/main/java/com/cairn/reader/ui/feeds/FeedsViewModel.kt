package com.cairn.reader.ui.feeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import com.cairn.reader.data.repo.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How the Manage Feeds list is ordered. */
enum class FeedSort(val label: String) {
    TITLE("Title A–Z"),
    UNREAD("Most unread"),
    RECENT("Recently synced"),
    HEALTH("Failing first"),
}

/** Backing for the dedicated, extensive Manage Feeds panel: sort, filter, grouping, and
 *  multi-select bulk actions (move to folder, mark read, full-text, notify, unsubscribe). */
@HiltViewModel
class FeedsViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val feedRepository: FeedRepository,
    private val itemRepository: ItemRepository,
) : ViewModel() {

    private val sourcesRaw: StateFlow<List<SourceEntity>> =
        sourceRepository.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<String>> =
        sourceRepository.folders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unread: StateFlow<Map<String, Int>> =
        itemRepository.feedUnread()
            .map { list -> list.associate { it.sourceId to it.unread } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // -- View controls --------------------------------------------------------

    private val _sort = MutableStateFlow(FeedSort.TITLE)
    val sort: StateFlow<FeedSort> = _sort.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _grouped = MutableStateFlow(true)
    val grouped: StateFlow<Boolean> = _grouped.asStateFlow()

    private val _failingOnly = MutableStateFlow(false)
    val failingOnly: StateFlow<Boolean> = _failingOnly.asStateFlow()

    /** Folder to restrict to (a category filter); null = all folders. */
    private val _folderFilter = MutableStateFlow<String?>(null)
    val folderFilter: StateFlow<String?> = _folderFilter.asStateFlow()

    fun setSort(sort: FeedSort) { _sort.value = sort }
    fun setQuery(value: String) { _query.value = value }
    fun setGrouped(on: Boolean) { _grouped.value = on }
    fun setFailingOnly(on: Boolean) { _failingOnly.value = on }
    fun setFolderFilter(folder: String?) { _folderFilter.value = folder }

    /** Unexposed raw list (for total count in the title). */
    val sources: StateFlow<List<SourceEntity>> = sourcesRaw

    /** The list actually shown: filtered by query / failing / folder, then sorted. */
    val displayed: StateFlow<List<SourceEntity>> =
        combine(sourcesRaw, unread, _sort, _query, combine(_failingOnly, _folderFilter) { f, fd -> f to fd }) { list, unread, sort, q, (failingOnly, folder) ->
            val term = q.trim()
            list.asSequence()
                .filter { term.isBlank() || it.title.contains(term, true) || it.feedUrl.contains(term, true) || (it.siteUrl?.contains(term, true) == true) }
                .filter { !failingOnly || it.consecutiveErrors > 0 }
                .filter { folder == null || it.folder == folder }
                .sortedWith(
                    when (sort) {
                        FeedSort.TITLE -> compareBy { it.title.lowercase() }
                        FeedSort.UNREAD -> compareByDescending { unread[it.id] ?: 0 }
                        FeedSort.RECENT -> compareByDescending { it.lastSyncedAt ?: 0L }
                        FeedSort.HEALTH -> compareByDescending { it.consecutiveErrors }
                    },
                )
                .toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // -- Multi-select ---------------------------------------------------------

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    fun toggleSelect(id: String) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }
    fun clearSelection() { _selection.value = emptySet() }
    fun selectAllVisible() { _selection.value = displayed.value.map { it.id }.toSet() }

    private val _snacks = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snacks = _snacks.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // -- Single-feed actions --------------------------------------------------

    fun rename(id: String, title: String) = viewModelScope.launch { sourceRepository.setTitle(id, title) }
    fun setFolder(id: String, folder: String?) = viewModelScope.launch { sourceRepository.setFolder(id, folder) }
    fun setFullText(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setFullText(id, enabled) }
    fun setNotify(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setNotify(id, enabled) }
    fun setPodcast(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setPodcast(id, enabled) }
    fun markFeedRead(id: String) = viewModelScope.launch { itemRepository.markAllRead(sourceId = id, folder = null) }
    fun delete(id: String) = viewModelScope.launch {
        sourceRepository.delete(id)
        _snacks.emit("Feed removed")
    }

    // -- Bulk actions (operate on the current selection) ----------------------

    fun bulkMoveToFolder(folder: String?) = viewModelScope.launch {
        val ids = _selection.value
        ids.forEach { sourceRepository.setFolder(it, folder) }
        _selection.value = emptySet()
        _snacks.emit(if (folder != null) "Moved ${ids.size} to $folder" else "Removed ${ids.size} from their folder")
    }

    fun bulkMarkRead() = viewModelScope.launch {
        val ids = _selection.value
        ids.forEach { itemRepository.markAllRead(sourceId = it, folder = null) }
        _selection.value = emptySet()
        _snacks.emit("Marked ${ids.size} feed${if (ids.size == 1) "" else "s"} read")
    }

    fun bulkSetFullText(enabled: Boolean) = viewModelScope.launch {
        val ids = _selection.value
        ids.forEach { sourceRepository.setFullText(it, enabled) }
        _selection.value = emptySet()
        _snacks.emit(if (enabled) "Full-text on for ${ids.size}" else "Full-text off for ${ids.size}")
    }

    fun bulkSetNotify(enabled: Boolean) = viewModelScope.launch {
        val ids = _selection.value
        ids.forEach { sourceRepository.setNotify(it, enabled) }
        _selection.value = emptySet()
        _snacks.emit(if (enabled) "Notifications on for ${ids.size}" else "Notifications off for ${ids.size}")
    }

    fun bulkDelete() = viewModelScope.launch {
        val ids = _selection.value
        ids.forEach { sourceRepository.delete(it) }
        _selection.value = emptySet()
        _snacks.emit("Removed ${ids.size} feed${if (ids.size == 1) "" else "s"}")
    }

    fun addFeed(url: String) = viewModelScope.launch {
        if (url.isBlank()) return@launch
        _busy.value = true
        val result = feedRepository.addFeedByUrl(url.trim())
        _busy.value = false
        _snacks.emit(result.fold(onSuccess = { "Feed added" }, onFailure = { it.message ?: "No feed found there" }))
    }

    fun followViaGoogleNews(url: String) = viewModelScope.launch {
        if (url.isBlank()) return@launch
        _busy.value = true
        val result = feedRepository.followViaGoogleNews(url.trim())
        _busy.value = false
        _snacks.emit(result.fold(onSuccess = { "Now following via Google News" }, onFailure = { it.message ?: "Couldn't follow that site" }))
    }
}
