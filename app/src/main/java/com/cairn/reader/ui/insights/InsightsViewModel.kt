package com.cairn.reader.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.HygieneIssue
import com.cairn.reader.data.repo.InsightsRepository
import com.cairn.reader.data.repo.ReadingAnalytics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InsightsUiState(
    val loading: Boolean = true,
    val analytics: ReadingAnalytics? = null,
    val topPicks: List<ItemListRow> = emptyList(),
    val hygiene: List<HygieneIssue> = emptyList(),
    val topics: List<com.cairn.reader.data.repo.TopicCluster> = emptyList(),
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val insightsRepository: InsightsRepository,
    private val feedRepository: com.cairn.reader.data.repo.FeedRepository,
    private val semanticRepository: com.cairn.reader.data.repo.SemanticRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(InsightsUiState())
    val state: StateFlow<InsightsUiState> = _state.asStateFlow()

    private val _healing = MutableStateFlow(false)
    val healing: StateFlow<Boolean> = _healing.asStateFlow()

    init { refresh() }

    /** Try to recover every broken saved link from the Wayback Machine, then refresh. */
    fun healBrokenLinks(onDone: (Int) -> Unit) {
        if (_healing.value) return
        _healing.value = true
        viewModelScope.launch {
            val healed = runCatching { feedRepository.healBrokenLinks(40) }.getOrDefault(0)
            _healing.value = false
            onDone(healed)
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val analytics = runCatching { insightsRepository.analytics() }.getOrNull()
            val picks = runCatching { insightsRepository.topPicks(20) }.getOrDefault(emptyList())
            val hygiene = runCatching { insightsRepository.feedHygiene() }.getOrDefault(emptyList())
            val topics = runCatching { semanticRepository.clusters() }.getOrDefault(emptyList()).take(8)
            _state.value = InsightsUiState(loading = false, analytics = analytics, topPicks = picks, hygiene = hygiene, topics = topics)
        }
    }
}
