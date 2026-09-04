package com.cairn.reader.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The three list lenses on the inbox. */
enum class InboxFilter(val label: String) {
    UNREAD("Unread"),
    SAVED("Saved"),
    ALL("All"),
}

data class InboxUiState(
    val loading: Boolean = true,
    val items: List<ItemListRow> = emptyList(),
    val unread: Int = 0,
    val filter: InboxFilter = InboxFilter.UNREAD,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _filter = MutableStateFlow(InboxFilter.UNREAD)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        // First run: subscribe to a few real feeds, then pull them in.
        viewModelScope.launch {
            if (feedRepository.seedDefaultFeedsIfEmpty()) refresh()
        }
    }

    private val rows = _filter.flatMapLatest { filter ->
        when (filter) {
            InboxFilter.UNREAD -> itemRepository.inbox()
            InboxFilter.SAVED -> itemRepository.saved()
            InboxFilter.ALL -> itemRepository.all()
        }
    }

    val state: StateFlow<InboxUiState> =
        combine(rows, itemRepository.unreadCount(), _filter) { items, unread, filter ->
            InboxUiState(loading = false, items = items, unread = unread, filter = filter)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InboxUiState(),
        )

    fun setFilter(filter: InboxFilter) {
        _filter.value = filter
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
                    onFailure = { "Couldn't find a feed at that address" },
                ),
            )
        }
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
