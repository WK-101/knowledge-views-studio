package com.cairn.reader.ui.following

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.FollowsRepository
import com.cairn.reader.data.repo.ItemRepository
import com.cairn.reader.domain.follow.FollowSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Following surface (Content Engine P5): the list of followed authors & topics, the one
 * currently selected, and its live stream of matching articles resolved across the whole archive.
 */
@HiltViewModel
class FollowingViewModel @Inject constructor(
    private val followsRepository: FollowsRepository,
    private val itemRepository: ItemRepository,
) : ViewModel() {

    val follows: StateFlow<List<FollowSpec>> =
        followsRepository.observeFollows().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<FollowSpec?>(null)
    val selected: StateFlow<FollowSpec?> = _selected.asStateFlow()

    private val _items = MutableStateFlow<List<ItemListRow>>(emptyList())
    val items: StateFlow<List<ItemListRow>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            follows.collect { list ->
                val cur = _selected.value
                // Keep the selection if it's still followed; otherwise fall to the first follow.
                if (cur == null || list.none { it == cur }) select(list.firstOrNull()) else refresh()
            }
        }
    }

    fun select(spec: FollowSpec?) {
        _selected.value = spec
        refresh()
    }

    private fun refresh() {
        val spec = _selected.value
        viewModelScope.launch {
            if (spec == null) {
                _items.value = emptyList()
                return@launch
            }
            _loading.value = true
            _items.value = followsRepository.resolve(spec)
            _loading.value = false
        }
    }

    fun unfollow(spec: FollowSpec) = viewModelScope.launch { followsRepository.unfollow(spec) }

    fun toggleSave(id: String, readLater: Boolean) = viewModelScope.launch {
        itemRepository.setReadLater(id, readLater)
    }
}
