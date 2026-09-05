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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backing for the dedicated Manage Feeds panel. */
@HiltViewModel
class FeedsViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val feedRepository: FeedRepository,
    itemRepository: ItemRepository,
) : ViewModel() {

    val sources: StateFlow<List<SourceEntity>> =
        sourceRepository.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<String>> =
        sourceRepository.folders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unread: StateFlow<Map<String, Int>> =
        itemRepository.feedUnread()
            .map { list -> list.associate { it.sourceId to it.unread } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _snacks = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snacks = _snacks.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun rename(id: String, title: String) = viewModelScope.launch { sourceRepository.setTitle(id, title) }
    fun setFolder(id: String, folder: String?) = viewModelScope.launch { sourceRepository.setFolder(id, folder) }
    fun setFullText(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setFullText(id, enabled) }
    fun setNotify(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setNotify(id, enabled) }
    fun setPodcast(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setPodcast(id, enabled) }
    fun delete(id: String) = viewModelScope.launch {
        sourceRepository.delete(id)
        _snacks.emit("Feed removed")
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
