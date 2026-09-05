package com.cairn.reader.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.FeedRepository
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

/** Which stored items to keep in the results. */
enum class SearchState(val label: String) { ALL("All"), UNREAD("Unread"), SAVED("Saved"), STARRED("Starred") }

/** Recency window for the results. */
enum class SearchSince(val label: String, val days: Int) {
    ANY("Any time", 0), WEEK("Past week", 7), MONTH("Past month", 30), YEAR("Past year", 365)
}

/** A web-search hit (from the online "search the whole web" mode) — not a stored item. */
data class WebHit(val title: String, val url: String, val site: String, val publishedAt: Long?)

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
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow(SearchState.ALL)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _since = MutableStateFlow(SearchSince.ANY)
    val since: StateFlow<SearchSince> = _since.asStateFlow()

    private val _type = MutableStateFlow<String?>(null)
    val type: StateFlow<String?> = _type.asStateFlow()

    /** Bumped after a mutation or filter change so the current query re-runs. */
    private val _tick = MutableStateFlow(0)

    /** Debounced, prefix-matching search-as-you-type over the local FTS index, then filtered. */
    val results: StateFlow<SearchUiState> =
        combine(_query.debounce(200).distinctUntilChanged(), _tick, _state, _since, _type) { q, _, st, since, type ->
            Triple(q, st, since to type)
        }.flatMapLatest { (q, st, sinceType) ->
            val (since, type) = sinceType
            flow {
                val trimmed = q.trim()
                if (trimmed.length < 2) {
                    emit(SearchUiState(query = q, results = emptyList(), searching = false, hasSearched = false))
                } else {
                    emit(SearchUiState(query = q, searching = true, hasSearched = true))
                    val cutoff = if (since.days > 0) System.currentTimeMillis() - since.days * 86_400_000L else 0L
                    val hits = itemRepository.search(trimmed).filter { row ->
                        (when (st) {
                            SearchState.ALL -> true
                            SearchState.UNREAD -> !row.isRead
                            SearchState.SAVED -> row.isReadLater
                            SearchState.STARRED -> row.isStarred
                        }) &&
                            (type == null || row.type == type) &&
                            (cutoff == 0L || (row.publishedAt ?: row.savedAt) >= cutoff)
                    }
                    emit(SearchUiState(query = q, results = hits, searching = false, hasSearched = true))
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    /** The distinct types present across everything, for the type filter row. */
    val availableTypes: StateFlow<List<String>> =
        results.let { flow ->
            combine(flow, _query) { s, _ -> s.results.map { it.type }.distinct() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // -- Online (web) search --------------------------------------------------

    private val _web = MutableStateFlow<List<WebHit>>(emptyList())
    val web: StateFlow<List<WebHit>> = _web.asStateFlow()

    private val _webBusy = MutableStateFlow(false)
    val webBusy: StateFlow<Boolean> = _webBusy.asStateFlow()

    fun setQuery(value: String) { _query.value = value; _web.value = emptyList() }
    fun setState(s: SearchState) { _state.value = s }
    fun setSince(s: SearchSince) { _since.value = s }
    fun setType(t: String?) { _type.value = t }

    /** Search the whole web (Google News) for the current query — far beyond what's stored. */
    fun searchWeb() = viewModelScope.launch {
        val q = _query.value.trim()
        if (q.length < 2) return@launch
        _webBusy.value = true
        val hits = feedRepository.webSearch(q).mapNotNull { p ->
            val url = p.link ?: return@mapNotNull null
            WebHit(p.title ?: url, url, hostOf(url), p.publishedAt)
        }
        _web.value = hits
        _webBusy.value = false
    }

    fun saveWebHit(url: String) = viewModelScope.launch { feedRepository.saveUrl(url) }

    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch {
        itemRepository.setReadLater(id, save)
        _tick.value += 1
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)
}
