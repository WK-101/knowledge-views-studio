package com.cairn.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The Offline surface: the concrete list of articles readable without a network — explicit
 * archival "Save offline" copies plus articles auto-cached when opened. From here the user can
 * remove just the download (keeping the entry) or delete the entry entirely (to Trash).
 */
@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val blobStore: BlobStore,
) : ViewModel() {

    val items: StateFlow<List<ItemListRow>> =
        feedRepository.observeCached().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storageBytes = MutableStateFlow(-1L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    init { refreshStorage() }

    fun refreshStorage() = viewModelScope.launch {
        _storageBytes.value = withContext(Dispatchers.IO) { runCatching { blobStore.storageBytes() }.getOrDefault(0L) }
    }

    /** Remove just the offline download; the entry stays and re-fetches on next open. */
    fun removeCache(id: String) = viewModelScope.launch {
        feedRepository.removeOfflineCopy(id)
        refreshStorage()
    }

    /** Move the whole entry to the Trash (removes it from lists; its cache goes too when purged). */
    fun deleteEntry(id: String) = viewModelScope.launch {
        feedRepository.trashItem(id)
        refreshStorage()
    }

    /** Promote an auto-cached item to a permanent, archival-grade offline copy (downloads images). */
    fun makePermanent(id: String) = viewModelScope.launch {
        feedRepository.saveOffline(id)
        refreshStorage()
    }

    // -- Multi-select (bulk actions) -------------------------------------------
    private val _picked = MutableStateFlow<Set<String>>(emptySet())
    val picked: StateFlow<Set<String>> = _picked.asStateFlow()

    fun togglePick(id: String) { _picked.value = _picked.value.let { if (id in it) it - id else it + id } }
    fun clearPicks() { _picked.value = emptySet() }
    fun pickAll() { _picked.value = items.value.map { it.id }.toSet() }
    private fun consumePicks(): Set<String> = _picked.value.also { _picked.value = emptySet() }

    fun makePermanentPicked() = viewModelScope.launch {
        consumePicks().forEach { feedRepository.saveOffline(it) }
        refreshStorage()
    }

    fun removeCachePicked() = viewModelScope.launch {
        consumePicks().forEach { feedRepository.removeOfflineCopy(it) }
        refreshStorage()
    }

    fun deleteEntriesPicked() = viewModelScope.launch {
        feedRepository.trashItems(consumePicks())
        refreshStorage()
    }
}
