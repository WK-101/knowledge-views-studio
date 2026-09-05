package com.cairn.reader.ui.readlater

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.CollectionWithCount
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.CollectionRepository
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How the Read Later list is ordered. */
enum class ReadLaterSort(val label: String) {
    NEWEST("Newest saved"), OLDEST("Oldest saved"), TITLE("Title A–Z"),
    LONGEST("Longest read"), SHORTEST("Shortest read"), UNREAD_FIRST("Unread first"),
}

/**
 * Read Later is the temporary staging list — everything you've saved but not yet filed into the
 * Library. From here an item is either promoted to the Library (filed into a collection or
 * favourited, which removes it from Read Later) or dropped.
 */
@HiltViewModel
class ReadLaterViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val collectionRepository: CollectionRepository,
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val raw: StateFlow<List<ItemListRow>> =
        itemRepository.readLater().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _sort = MutableStateFlow(ReadLaterSort.NEWEST)
    val sort: StateFlow<ReadLaterSort> = _sort.asStateFlow()
    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()
    private val _unreadOnly = MutableStateFlow(false)
    val unreadOnly: StateFlow<Boolean> = _unreadOnly.asStateFlow()
    private val _offlineOnly = MutableStateFlow(false)
    val offlineOnly: StateFlow<Boolean> = _offlineOnly.asStateFlow()

    fun setQuery(v: String) { _query.value = v }
    fun setSort(s: ReadLaterSort) { _sort.value = s }
    fun setTypeFilter(t: String?) { _typeFilter.value = t }
    fun setUnreadOnly(v: Boolean) { _unreadOnly.value = v }
    fun setOfflineOnly(v: Boolean) { _offlineOnly.value = v }

    /** The distinct item types present, for the type-filter chips. */
    val availableTypes: StateFlow<List<String>> =
        raw.stateInTypes()

    private fun StateFlow<List<ItemListRow>>.stateInTypes(): StateFlow<List<String>> =
        combine(this, _query) { list, _ -> list.map { it.type }.distinct().sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<ItemListRow>> =
        combine(raw, _query, _sort, _typeFilter, combine(_unreadOnly, _offlineOnly) { u, o -> u to o }) {
            list, query, sort, type, flags ->
            val (unreadOnly, offlineOnly) = flags
            val q = query.trim()
            var out = list
            if (q.length >= 2) out = out.filter {
                it.title.contains(q, true) || (it.sourceTitle?.contains(q, true) == true) ||
                    (it.siteName?.contains(q, true) == true) || (it.excerpt?.contains(q, true) == true)
            }
            if (type != null) out = out.filter { it.type == type }
            if (unreadOnly) out = out.filter { !it.isRead }
            if (offlineOnly) out = out.filter { it.cacheStatus == "PERMANENT" }
            when (sort) {
                ReadLaterSort.NEWEST -> out.sortedByDescending { it.savedAt }
                ReadLaterSort.OLDEST -> out.sortedBy { it.savedAt }
                ReadLaterSort.TITLE -> out.sortedBy { it.title.lowercase() }
                ReadLaterSort.LONGEST -> out.sortedByDescending { it.readingMinutes }
                ReadLaterSort.SHORTEST -> out.sortedBy { it.readingMinutes }
                ReadLaterSort.UNREAD_FIRST -> out.sortedWith(compareBy({ it.isRead }, { -it.savedAt }))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collections: StateFlow<List<CollectionWithCount>> =
        collectionRepository.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Promote to the Library: file into a collection (or favourite when none is chosen), then
     *  clear the read-later flag so it leaves this staging list. */
    fun saveToLibrary(id: String, collectionId: String?) = viewModelScope.launch {
        collectionRepository.moveItem(id, collectionId)
        if (collectionId == null) itemRepository.setStarred(id, true)
        itemRepository.setReadLater(id, false)
    }

    fun createCollection(name: String) = viewModelScope.launch { if (name.isNotBlank()) collectionRepository.create(name) }

    /** Drop from Read Later without saving. */
    fun remove(id: String) = viewModelScope.launch { itemRepository.setReadLater(id, false) }

    fun archive(id: String) = viewModelScope.launch { itemRepository.setArchived(id, true) }

    fun saveLink(url: String) = viewModelScope.launch { feedRepository.saveUrl(url) }
}
