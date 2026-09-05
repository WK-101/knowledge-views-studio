package com.cairn.reader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.CollectionWithCount
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.db.TagWithCount
import com.cairn.reader.data.prefs.LibraryViewMode
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.repo.CollectionRepository
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import com.cairn.reader.data.repo.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which slice of the library is showing. */
sealed interface LibraryScope {
    data object All : LibraryScope
    data object Unsorted : LibraryScope
    data object Favorites : LibraryScope
    data object Archive : LibraryScope
    data object Offline : LibraryScope
    data class Collection(val id: String, val name: String) : LibraryScope
    data class Tag(val id: String, val name: String) : LibraryScope
}

enum class LibrarySort(val label: String) {
    NEWEST("Newest"), OLDEST("Oldest"), TITLE("Title A–Z"), SITE("Source")
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val collectionRepository: CollectionRepository,
    private val tagRepository: TagRepository,
    private val feedRepository: FeedRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    val collections: StateFlow<List<CollectionWithCount>> =
        collectionRepository.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<TagWithCount>> =
        tagRepository.allWithCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live counts on the system scopes (All / Unsorted / Favorites / Archive), Raindrop-style. */
    val counts: StateFlow<com.cairn.reader.data.db.LibraryCounts> =
        itemRepository.libraryCounts().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            com.cairn.reader.data.db.LibraryCounts(0, 0, 0, 0),
        )

    private val _scope = MutableStateFlow<LibraryScope>(LibraryScope.All)
    val scope: StateFlow<LibraryScope> = _scope.asStateFlow()

    /** null = every type; otherwise one of ARTICLE / LINK / VIDEO / IMAGE. */
    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()

    /** View mode resolves per scope (Raindrop-style memory), falling back to the global default. */
    val viewMode: StateFlow<LibraryViewMode> =
        combine(preferencesRepository.preferences, _scope) { prefs, scope ->
            prefs.libraryViewByScope[scopeKey(scope)] ?: prefs.libraryViewMode
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryViewMode.GRID)

    val savedSearches: StateFlow<List<String>> =
        preferencesRepository.preferences.map { it.savedSearches.sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sort = MutableStateFlow(LibrarySort.NEWEST)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    private val scopeRows: kotlinx.coroutines.flow.Flow<List<ItemListRow>> =
        _scope.flatMapLatest { scope ->
            when (scope) {
                LibraryScope.All -> itemRepository.libraryAll()
                LibraryScope.Unsorted -> itemRepository.unsorted()
                LibraryScope.Favorites -> itemRepository.favorites()
                LibraryScope.Archive -> itemRepository.archived()
                LibraryScope.Offline -> itemRepository.offlineCopies()
                is LibraryScope.Collection -> itemRepository.collectionItems(scope.id)
                is LibraryScope.Tag -> itemRepository.byTag(scope.id)
            }
        }

    /** The distinct item types present in the current scope, ordered for the filter chip row. */
    val availableTypes: StateFlow<List<String>> =
        scopeRows.map { rows -> TYPE_ORDER.filter { t -> rows.any { it.type == t } } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<ItemListRow>> =
        combine(scopeRows, _sort, _typeFilter) { rows, sort, type ->
            val filtered = if (type == null) rows else rows.filter { it.type == type }
            when (sort) {
                LibrarySort.NEWEST -> filtered.sortedByDescending { it.publishedAt ?: it.savedAt }
                LibrarySort.OLDEST -> filtered.sortedBy { it.publishedAt ?: it.savedAt }
                LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
                LibrarySort.SITE -> filtered.sortedBy { (it.sourceTitle ?: it.siteName ?: "").lowercase() }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<ItemListRow>>(emptyList())
    val results: StateFlow<List<ItemListRow>> = _results.asStateFlow()

    fun setScope(scope: LibraryScope) {
        _scope.value = scope
        _typeFilter.value = null // a fresh scope starts unfiltered
    }
    fun setSort(sort: LibrarySort) { _sort.value = sort }
    fun setTypeFilter(type: String?) { _typeFilter.value = type }
    fun saveSearch(query: String) = viewModelScope.launch { preferencesRepository.addSavedSearch(query) }
    fun removeSavedSearch(query: String) = viewModelScope.launch { preferencesRepository.removeSavedSearch(query) }
    fun setViewMode(mode: LibraryViewMode) = viewModelScope.launch {
        preferencesRepository.setLibraryViewForScope(scopeKey(_scope.value), mode)
    }

    private fun scopeKey(scope: LibraryScope): String = when (scope) {
        LibraryScope.All -> "all"
        LibraryScope.Unsorted -> "unsorted"
        LibraryScope.Favorites -> "favorites"
        LibraryScope.Archive -> "archive"
        LibraryScope.Offline -> "offline"
        is LibraryScope.Collection -> "col:${scope.id}"
        is LibraryScope.Tag -> "tag:${scope.id}"
    }

    /** Restore an archived item back to the reading flow (from the Archive scope). */
    fun unarchive(id: String) = viewModelScope.launch { itemRepository.setArchived(id, false) }

    companion object {
        val TYPE_ORDER = listOf("ARTICLE", "LINK", "VIDEO", "AUDIO", "IMAGE", "PDF")
    }

    fun setQuery(value: String) {
        _query.value = value
        viewModelScope.launch {
            _results.value = if (value.isBlank()) emptyList() else itemRepository.search(value)
        }
    }

    fun createCollection(name: String, parentId: String? = null) = viewModelScope.launch {
        if (name.isNotBlank()) collectionRepository.create(name, parentId)
    }

    /** Re-parent a collection (drag-into / "Move under…"); null lifts it back to the top level. */
    fun setCollectionParent(id: String, parentId: String?) = viewModelScope.launch {
        if (id != parentId) collectionRepository.setParent(id, parentId)
    }

    fun renameCollection(id: String, name: String) = viewModelScope.launch { collectionRepository.rename(id, name) }

    fun deleteCollection(id: String) = viewModelScope.launch {
        if (_scope.value.let { it is LibraryScope.Collection && it.id == id }) _scope.value = LibraryScope.All
        collectionRepository.delete(id)
    }

    fun moveItem(itemId: String, collectionId: String?) = viewModelScope.launch { collectionRepository.moveItem(itemId, collectionId) }

    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch { itemRepository.setReadLater(id, save) }

    fun saveLink(url: String) = viewModelScope.launch { feedRepository.saveUrl(url) }

    // -- Bulk selection --------------------------------------------------------

    fun toggleSelect(id: String) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { _selection.value = emptySet() }

    fun moveSelected(collectionId: String?) = viewModelScope.launch {
        val ids = _selection.value
        ids.forEach { collectionRepository.moveItem(it, collectionId) }
        _selection.value = emptySet()
    }

    /** Archive the selection (or unarchive it when viewing the Archive scope). */
    fun archiveSelected() = viewModelScope.launch {
        val archive = _scope.value != LibraryScope.Archive
        _selection.value.forEach { itemRepository.setArchived(it, archive) }
        _selection.value = emptySet()
    }

    fun removeSelectedFromLibrary() = viewModelScope.launch {
        val ids = _selection.value
        ids.forEach { id ->
            itemRepository.setReadLater(id, false)
            itemRepository.setStarred(id, false)
            collectionRepository.moveItem(id, null)
        }
        _selection.value = emptySet()
    }
}
