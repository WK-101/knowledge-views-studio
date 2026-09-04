package com.cairn.reader.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val repository: ItemRepository,
) : ViewModel() {

    init {
        viewModelScope.launch { repository.seedIfEmpty() }
    }

    val state: StateFlow<InboxUiState> =
        combine(repository.inbox(), repository.unreadCount()) { items, unread ->
            InboxUiState(loading = false, items = items, unread = unread)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InboxUiState(),
        )

    fun markRead(id: String, read: Boolean = true) = viewModelScope.launch { repository.setRead(id, read) }
    fun toggleStar(id: String, starred: Boolean) = viewModelScope.launch { repository.setStarred(id, starred) }
    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch { repository.setReadLater(id, save) }
    fun archive(id: String) = viewModelScope.launch { repository.setArchived(id, true) }
}
