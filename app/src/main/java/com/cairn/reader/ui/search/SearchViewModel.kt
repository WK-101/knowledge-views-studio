package com.cairn.reader.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<ItemListRow> = emptyList(),
    val searching: Boolean = false,
    val hasSearched: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Bumped after a mutation so the current query re-runs and the list reflects it. */
    private val _tick = MutableStateFlow(0)

    /** Debounced, prefix-matching search-as-you-type over the local FTS index. */
    val results: StateFlow<SearchUiState> =
        combine(_query.debounce(220).distinctUntilChanged(), _tick) { q, _ -> q }
            .flatMapLatest { q ->
                flow {
                    val trimmed = q.trim()
                    if (trimmed.length < 2) {
                        emit(SearchUiState(query = q, results = emptyList(), searching = false, hasSearched = false))
                    } else {
                        emit(SearchUiState(query = q, searching = true, hasSearched = true))
                        val hits = itemRepository.search(trimmed)
                        emit(SearchUiState(query = q, results = hits, searching = false, hasSearched = true))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch {
        itemRepository.setReadLater(id, save)
        _tick.value += 1
    }
}
