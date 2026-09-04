package com.cairn.reader.ui.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.HighlightWithArticle
import com.cairn.reader.data.repo.HighlightRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One article's worth of highlights, for the grouped notebook list. */
data class NotebookGroup(
    val itemId: String,
    val title: String,
    val url: String,
    val image: String?,
    val site: String?,
    val highlights: List<HighlightWithArticle>,
)

@HiltViewModel
class NotebookViewModel @Inject constructor(
    private val highlightRepository: HighlightRepository,
) : ViewModel() {

    val groups: StateFlow<List<NotebookGroup>> =
        highlightRepository.observeAllWithArticle()
            .map { rows ->
                rows.groupBy { it.itemId }.map { (itemId, items) ->
                    NotebookGroup(
                        itemId = itemId,
                        title = items.first().articleTitle,
                        url = items.first().articleUrl,
                        image = items.first().articleImage,
                        site = items.first().articleSite,
                        highlights = items,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(id: String, itemId: String) = viewModelScope.launch { highlightRepository.remove(id, itemId) }

    /** Builds shareable Markdown for every highlight, off the main thread. */
    fun exportAll(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(highlightRepository.exportAll()) }
    }
}
