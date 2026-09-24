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
import kotlinx.coroutines.flow.map
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

/** A subscribed site the user can search the full archive of. */
data class ArchiveSite(val id: String, val title: String, val siteUrl: String)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val feedRepository: FeedRepository,
    private val sourceRepository: com.cairn.reader.data.repo.SourceRepository,
    private val semanticRepository: com.cairn.reader.data.repo.SemanticRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow(SearchState.ALL)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _since = MutableStateFlow(SearchSince.ANY)
    val since: StateFlow<SearchSince> = _since.asStateFlow()

    private val _type = MutableStateFlow<String?>(null)
    val type: StateFlow<String?> = _type.asStateFlow()

    /** When on, results are re-ranked by semantic closeness to the query (meaning, not just keyword). */
    private val _sortByMeaning = MutableStateFlow(false)
    val sortByMeaning: StateFlow<Boolean> = _sortByMeaning.asStateFlow()

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
                    // Optionally re-rank by meaning: keyword FTS finds the candidates, the on-device
                    // TF-IDF engine reorders them by semantic closeness to the query (stable — unscored
                    // items keep their recency order).
                    val ordered = if (_sortByMeaning.value && hits.size > 1) {
                        val scores = semanticRepository.rankByQuery(
                            trimmed,
                            hits.map { com.cairn.reader.data.db.ItemText(it.id, it.title, it.excerpt, it.sourceTitle) },
                        )
                        hits.sortedByDescending { scores[it.id] ?: 0.0 }
                    } else {
                        hits
                    }
                    emit(SearchUiState(query = q, results = ordered, searching = false, hasSearched = true))
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

    // Distinguish the three states the UI has to tell apart: never searched (show the prompt), searched
    // and got nothing (show "no results"), and searched but the network failed (show an error).
    private val _webSearched = MutableStateFlow(false)
    val webSearched: StateFlow<Boolean> = _webSearched.asStateFlow()
    private val _webError = MutableStateFlow(false)
    val webError: StateFlow<Boolean> = _webError.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
        _web.value = emptyList(); _webSearched.value = false; _webError.value = false
    }
    fun setState(s: SearchState) { _state.value = s }
    fun setSince(s: SearchSince) { _since.value = s }
    fun setType(t: String?) { _type.value = t }
    fun setSortByMeaning(on: Boolean) { _sortByMeaning.value = on; _tick.value += 1 }

    /** Search the whole web (Google News) for the current query — far beyond what's stored. */
    fun searchWeb() = viewModelScope.launch {
        val q = _query.value.trim()
        if (q.length < 2) return@launch
        _webBusy.value = true; _webError.value = false
        com.cairn.reader.util.coRunCatching {
            feedRepository.webSearch(q).mapNotNull { p ->
                val url = p.link ?: return@mapNotNull null
                WebHit(p.title ?: url, url, hostOf(url), p.publishedAt)
            }
        }.fold(
            onSuccess = { _web.value = it },
            onFailure = { _web.value = emptyList(); _webError.value = true },
        )
        _webBusy.value = false
        _webSearched.value = true
    }

    fun saveWebHit(url: String) = viewModelScope.launch { feedRepository.saveUrl(url) }

    /** Whether a bulk "save all results" is running (drives the button's busy state). */
    private val _savingAll = MutableStateFlow(false)
    val savingAll: StateFlow<Boolean> = _savingAll.asStateFlow()

    /** Persist every current web hit so it enters the library and the offline full-text index. */
    fun saveAllWebHits(onDone: (Int) -> Unit = {}) = saveAll(_web.value.map { it.url }, onDone)

    /** Persist every current archive hit — the bridge that makes archive matches permanently
     *  searchable offline (until the deep-archive crawler stores whole sites automatically). */
    fun saveAllArchiveHits(onDone: (Int) -> Unit = {}) = saveAll(_archive.value.map { it.url }, onDone)

    private fun saveAll(urls: List<String>, onDone: (Int) -> Unit) = viewModelScope.launch {
        if (urls.isEmpty()) { onDone(0); return@launch }
        _savingAll.value = true
        var saved = 0
        urls.forEach { url -> if (feedRepository.saveUrl(url).isSuccess) saved++ }
        _savingAll.value = false
        _tick.value += 1
        onDone(saved)
    }

    // -- Full-archive search (a single site's entire published history) --------

    /** Sites the user is subscribed to, resolved to a base URL for archive crawling. */
    val archiveSites: StateFlow<List<ArchiveSite>> =
        sourceRepository.sources().map { list ->
            list.mapNotNull { s ->
                val base = (s.siteUrl?.takeIf { it.isNotBlank() } ?: s.feedUrl).takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                ArchiveSite(s.id, s.title, base)
            }.distinctBy { hostOf(it.siteUrl) }.sortedBy { it.title.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _archive = MutableStateFlow<List<WebHit>>(emptyList())
    val archive: StateFlow<List<WebHit>> = _archive.asStateFlow()

    private val _archiveBusy = MutableStateFlow(false)
    val archiveBusy: StateFlow<Boolean> = _archiveBusy.asStateFlow()
    private val _archiveSearched = MutableStateFlow(false)
    val archiveSearched: StateFlow<Boolean> = _archiveSearched.asStateFlow()
    private val _archiveError = MutableStateFlow(false)
    val archiveError: StateFlow<Boolean> = _archiveError.asStateFlow()

    fun searchArchive(site: ArchiveSite) = viewModelScope.launch {
        val q = _query.value.trim()
        if (q.length < 2) return@launch
        _archiveBusy.value = true; _archiveError.value = false
        com.cairn.reader.util.coRunCatching {
            feedRepository.searchArchive(site.siteUrl, q).mapNotNull { p ->
                val url = p.link ?: return@mapNotNull null
                WebHit(p.title ?: url, url, hostOf(url), p.publishedAt)
            }
        }.fold(
            onSuccess = { _archive.value = it },
            onFailure = { _archive.value = emptyList(); _archiveError.value = true },
        )
        _archiveBusy.value = false
        _archiveSearched.value = true
    }

    fun clearArchive() { _archive.value = emptyList(); _archiveSearched.value = false; _archiveError.value = false }

    fun toggleSave(id: String, save: Boolean) = viewModelScope.launch {
        itemRepository.setReadLater(id, save)
        _tick.value += 1
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)
}
