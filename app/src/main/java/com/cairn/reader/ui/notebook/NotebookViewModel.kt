package com.cairn.reader.ui.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.HighlightWithArticle
import com.cairn.reader.data.repo.HighlightRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    /** Active highlight-color filter (ARGB), or null for "all colours". */
    private val _colorFilter = MutableStateFlow<Int?>(null)
    val colorFilter: StateFlow<Int?> = _colorFilter.asStateFlow()
    fun setColorFilter(color: Int?) { _colorFilter.value = color }

    /** Every colour actually in use, so the filter row only offers real options. */
    val usedColors: StateFlow<List<Int>> =
        highlightRepository.observeAllWithArticle()
            .map { rows -> rows.map { it.color }.distinct() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<NotebookGroup>> =
        combine(highlightRepository.observeAllWithArticle(), _colorFilter) { rows, color ->
            val filtered = if (color == null) rows else rows.filter { it.color == color }
            filtered.groupBy { it.itemId }.map { (itemId, items) ->
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

    /** Remove every highlight belonging to one article (the whole notebook card). */
    fun removeGroup(group: NotebookGroup) = viewModelScope.launch {
        group.highlights.forEach { highlightRepository.remove(it.id, group.itemId) }
    }

    /** Builds shareable Markdown for every highlight, off the main thread. */
    fun exportAll(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(highlightRepository.exportAll()) }
    }

    // -- Per-entry & per-highlight sharing, in several formats --------------------

    /** Share text for one article's whole set of annotations, in the chosen [format]. */
    fun renderGroup(group: NotebookGroup, format: ShareFormat): String = when (format) {
        ShareFormat.MARKDOWN -> buildString {
            append("## ").append(group.title).append('\n')
            if (group.url.isNotBlank()) append(group.url).append('\n')
            group.highlights.forEach { h ->
                append('\n')
                h.quote.trim().split("\n").forEach { line -> append("> ").append(line).append('\n') }
                h.note?.takeIf { it.isNotBlank() }?.let { append("\n_Note:_ ").append(it.trim()).append('\n') }
            }
        }.trimEnd()
        ShareFormat.PLAIN -> buildString {
            group.highlights.forEach { h ->
                append('“').append(h.quote.trim()).append('”')
                h.note?.takeIf { it.isNotBlank() }?.let { append("\n\nNote: ").append(it.trim()) }
                append("\n\n")
            }
            append("— ").append(group.title)
            if (group.url.isNotBlank()) append('\n').append(group.url)
        }.trimEnd()
        ShareFormat.QUOTE -> buildString {
            group.highlights.forEach { h -> append('“').append(h.quote.trim()).append("”\n\n") }
            append("— ").append(group.title)
            group.site?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
        }.trimEnd()
    }

    /** Share text for a single highlight (+ its note), in the chosen [format]. */
    fun renderHighlight(h: HighlightWithArticle, format: ShareFormat): String = when (format) {
        ShareFormat.MARKDOWN -> buildString {
            h.quote.trim().split("\n").forEach { line -> append("> ").append(line).append('\n') }
            h.note?.takeIf { it.isNotBlank() }?.let { append("\n_Note:_ ").append(it.trim()).append('\n') }
            append("\n— *").append(h.articleTitle).append('*')
            if (h.articleUrl.isNotBlank()) append('\n').append(h.articleUrl)
        }.trimEnd()
        ShareFormat.PLAIN -> buildString {
            append('“').append(h.quote.trim()).append('”')
            h.note?.takeIf { it.isNotBlank() }?.let { append("\n\nNote: ").append(it.trim()) }
            append("\n\n— ").append(h.articleTitle)
            if (h.articleUrl.isNotBlank()) append('\n').append(h.articleUrl)
        }.trimEnd()
        ShareFormat.QUOTE -> buildString {
            append('“').append(h.quote.trim()).append('”')
            append("\n\n— ").append(h.articleTitle)
            h.articleSite?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
        }.trimEnd()
    }
}

/** The formats an annotation (or a whole entry's worth) can be shared as. */
enum class ShareFormat(val label: String) {
    MARKDOWN("Markdown"),
    PLAIN("Plain text"),
    QUOTE("Quote + source"),
}
