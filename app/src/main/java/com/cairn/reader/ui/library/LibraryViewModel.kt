package com.cairn.reader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
) : ViewModel() {

    val library: StateFlow<List<ItemListRow>> =
        itemRepository.library().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<ItemListRow>>(emptyList())
    val results: StateFlow<List<ItemListRow>> = _results.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
        viewModelScope.launch {
            _results.value = if (value.isBlank()) emptyList() else itemRepository.search(value)
        }
    }

    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch { itemRepository.setReadLater(id, save) }
}
