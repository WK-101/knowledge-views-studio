package com.cairn.reader.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxUiState(
    val loading: Boolean = true,
    val items: List<ItemListRow> = emptyList(),
    val unread: Int = 0,
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        // First run: subscribe to a few real feeds, then pull them in.
        viewModelScope.launch {
            if (feedRepository.seedDefaultFeedsIfEmpty()) refresh()
        }
    }

    val state: StateFlow<InboxUiState> =
        combine(itemRepository.inbox(), itemRepository.unreadCount()) { items, unread ->
            InboxUiState(loading = false, items = items, unread = unread)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InboxUiState(),
        )

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
    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch { itemRepository.setReadLater(id, save) }
    fun archive(id: String) = viewModelScope.launch { itemRepository.setArchived(id, true) }
}
