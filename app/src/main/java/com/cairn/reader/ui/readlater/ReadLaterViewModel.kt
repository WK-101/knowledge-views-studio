package com.cairn.reader.ui.readlater

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.CollectionWithCount
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.CollectionRepository
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    val items: StateFlow<List<ItemListRow>> =
        itemRepository.readLater().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
