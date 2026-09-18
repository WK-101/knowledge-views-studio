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

/** One article's worth of highlights, for the grouped notebook (cards) view. */
data class NotebookGroup(
    val itemId: String,
    val title: String,
    val url: String,
    val image: String?,
    val site: String?,
    val highlights: List<HighlightWithArticle>,
)

/** A list-view section: a run of highlights under one header (an article, a colour, or none). */
data class NotebookSection(
    val key: String,
    val title: String?,
    val subtitle: String?,
    val color: Int?,
    val itemId: String?,
    val highlights: List<HighlightWithArticle>,
)

enum class NoteSort { RECENT, OLDEST, TITLE, COUNT }
enum class NoteGroup { ARTICLE, COLOR, NONE }
enum class NoteView { CARDS, LIST, COMPACT }
enum class NoteType { ALL, ARTICLE, TRANSCRIPT }

/** Every view knob for the Annotations panel, applied together over the raw highlight rows. */
data class NoteOptions(
    val query: String = "",
    val colorFilter: Int? = null,
    val type: NoteType = NoteType.ALL,
    val sort: NoteSort = NoteSort.RECENT,
    val group: NoteGroup = NoteGroup.ARTICLE,
    val view: NoteView = NoteView.CARDS,
)

/** The fully-resolved panel content: cards for the cards view, sections for the list view. */
data class NotebookContent(
    val cards: List<NotebookGroup> = emptyList(),
    val sections: List<NotebookSection> = emptyList(),
    val total: Int = 0,
)

private fun HighlightWithArticle.isTranscript() = startSelector?.startsWith("t:") == true

@HiltViewModel
class NotebookViewModel @Inject constructor(
    private val highlightRepository: HighlightRepository,
) : ViewModel() {

    private val _options = MutableStateFlow(NoteOptions())
    val options: StateFlow<NoteOptions> = _options.asStateFlow()

    fun setQuery(q: String) { _options.value = _options.value.copy(query = q) }
    fun setColorFilter(color: Int?) { _options.value = _options.value.copy(colorFilter = color) }
    fun setType(type: NoteType) { _options.value = _options.value.copy(type = type) }
    fun setSort(sort: NoteSort) { _options.value = _options.value.copy(sort = sort) }
    fun setGroup(group: NoteGroup) { _options.value = _options.value.copy(group = group) }
    fun setView(view: NoteView) { _options.value = _options.value.copy(view = view) }

    /** Kept for callers that still read the color filter directly. */
    val colorFilter: StateFlow<Int?> = _options.map { it.colorFilter }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every colour actually in use, so the filter row only offers real options. */
    val usedColors: StateFlow<List<Int>> =
        highlightRepository.observeAllWithArticle()
            .map { rows -> rows.map { it.color }.distinct() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val content: StateFlow<NotebookContent> =
        combine(highlightRepository.observeAllWithArticle(), _options) { rows, opt -> resolve(rows, opt) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotebookContent())

    private fun resolve(rows: List<HighlightWithArticle>, opt: NoteOptions): NotebookContent {
        val q = opt.query.trim().lowercase()
        val filtered = rows.filter { h ->
            (opt.colorFilter == null || h.color == opt.colorFilter) &&
                when (opt.type) {
                    NoteType.ALL -> true
                    NoteType.ARTICLE -> !h.isTranscript()
                    NoteType.TRANSCRIPT -> h.isTranscript()
                } &&
                (q.isEmpty() ||
                    h.quote.lowercase().contains(q) ||
                    (h.note?.lowercase()?.contains(q) == true) ||
                    h.articleTitle.lowercase().contains(q) ||
                    (h.articleSite?.lowercase()?.contains(q) == true))
        }
        val total = filtered.size

        // Cards view — one card per article, ordered by the chosen sort.
        val byItem = filtered.groupBy { it.itemId }
        var cards = byItem.map { (itemId, items) ->
            NotebookGroup(
                itemId = itemId,
                title = items.first().articleTitle,
                url = items.first().articleUrl,
                image = items.first().articleImage,
                site = items.first().articleSite,
                highlights = items,
            )
        }
        cards = when (opt.sort) {
            NoteSort.RECENT -> cards.sortedByDescending { g -> g.highlights.maxOf { it.createdAt } }
            NoteSort.OLDEST -> cards.sortedBy { g -> g.highlights.minOf { it.createdAt } }
            NoteSort.TITLE -> cards.sortedBy { it.title.lowercase() }
            NoteSort.COUNT -> cards.sortedByDescending { it.highlights.size }
        }

        // List view — flat rows, sorted then grouped into sections.
        val sortedFlat = when (opt.sort) {
            NoteSort.RECENT -> filtered.sortedByDescending { it.createdAt }
            NoteSort.OLDEST -> filtered.sortedBy { it.createdAt }
            NoteSort.TITLE -> filtered.sortedWith(compareBy({ it.articleTitle.lowercase() }, { it.createdAt }))
            NoteSort.COUNT -> filtered.sortedByDescending { it.createdAt }
        }
        val sections = when (opt.group) {
            NoteGroup.NONE -> if (sortedFlat.isEmpty()) emptyList()
                else listOf(NotebookSection("all", null, null, null, null, sortedFlat))
            NoteGroup.COLOR -> sortedFlat.groupBy { it.color }.entries
                .sortedByDescending { it.value.size }
                .map { (color, hs) -> NotebookSection("c$color", null, null, color, null, hs) }
            NoteGroup.ARTICLE -> cards.map { g ->
                NotebookSection(
                    key = g.itemId, title = g.title, subtitle = g.site, color = null,
                    itemId = g.itemId, highlights = g.highlights,
                )
            }
        }
        return NotebookContent(cards = cards, sections = sections, total = total)
    }

    fun remove(id: String, itemId: String) = viewModelScope.launch { highlightRepository.remove(id, itemId) }

    fun setColor(id: String, itemId: String, color: Int) = viewModelScope.launch { highlightRepository.setColor(id, itemId, color) }

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
