package com.cairn.reader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.CollectionWithCount
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.CollectionRepository
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which slice of the library is showing. */
sealed interface LibraryScope {
    data object All : LibraryScope
    data object Unsorted : LibraryScope
    data class Collection(val id: String, val name: String) : LibraryScope
    data class Tag(val id: String, val name: String) : LibraryScope
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val collectionRepository: CollectionRepository,
) : ViewModel() {

    val collections: StateFlow<List<CollectionWithCount>> =
        collectionRepository.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scope = MutableStateFlow<LibraryScope>(LibraryScope.All)
    val scope: StateFlow<LibraryScope> = _scope.asStateFlow()

    val items: StateFlow<List<ItemListRow>> =
        _scope.flatMapLatest { scope ->
            when (scope) {
                LibraryScope.All -> itemRepository.libraryAll()
                LibraryScope.Unsorted -> itemRepository.unsorted()
                is LibraryScope.Collection -> itemRepository.collectionItems(scope.id)
                is LibraryScope.Tag -> itemRepository.byTag(scope.id)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<ItemListRow>>(emptyList())
    val results: StateFlow<List<ItemListRow>> = _results.asStateFlow()

    fun setScope(scope: LibraryScope) { _scope.value = scope }

    fun setQuery(value: String) {
        _query.value = value
        viewModelScope.launch {
            _results.value = if (value.isBlank()) emptyList() else itemRepository.search(value)
        }
    }

    fun createCollection(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) collectionRepository.create(name)
    }

    fun renameCollection(id: String, name: String) = viewModelScope.launch { collectionRepository.rename(id, name) }

    fun deleteCollection(id: String) = viewModelScope.launch {
        if (_scope.value.let { it is LibraryScope.Collection && it.id == id }) _scope.value = LibraryScope.All
        collectionRepository.delete(id)
    }

    fun moveItem(itemId: String, collectionId: String?) = viewModelScope.launch { collectionRepository.moveItem(itemId, collectionId) }

    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch { itemRepository.setReadLater(id, save) }
}
