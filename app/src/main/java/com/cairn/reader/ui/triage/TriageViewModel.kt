package com.cairn.reader.ui.triage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TriageUiState(
    val loading: Boolean = true,
    /** The remaining deck, in order; the head is the top card. */
    val deck: List<ItemListRow> = emptyList(),
    val done: Int = 0,
)

/**
 * Swipe-deck triage with a time budget: "I've got 15 minutes" builds a deck of unread stories that
 * fit, and you flick through — right to save for later, left to dismiss (mark read), tap to read now.
 * A fast, calm way to clear the inbox without a wall of rows.
 */
@HiltViewModel
class TriageViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TriageUiState())
    val state: StateFlow<TriageUiState> = _state.asStateFlow()

    /** Time budget in minutes; 0 = no limit. */
    private val _budget = MutableStateFlow(0)
    val budget: StateFlow<Int> = _budget.asStateFlow()

    /** All unread candidates, freshest first, cached so budget changes are instant. */
    private var candidates: List<ItemListRow> = emptyList()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            candidates = runCatching { itemRepository.inbox().first() }.getOrDefault(emptyList())
            rebuild(resetDone = true)
        }
    }

    fun setBudget(minutes: Int) {
        _budget.value = minutes
        rebuild(resetDone = false)
    }

    private fun rebuild(resetDone: Boolean) {
        val budget = _budget.value
        val deck = if (budget <= 0) candidates else {
            var acc = 0
            candidates.takeWhile { row ->
                val m = row.readingMinutes.coerceAtLeast(1)
                if (acc + m <= budget || acc == 0) { acc += m; true } else false
            }
        }
        _state.value = TriageUiState(loading = false, deck = deck, done = if (resetDone) 0 else _state.value.done)
    }

    private fun advance() {
        val rest = _state.value.deck.drop(1)
        _state.value = _state.value.copy(deck = rest, done = _state.value.done + 1)
        candidates = candidates.drop(1)
    }

    /** Right swipe: keep it — save to Read Later — and move on. */
    fun save() {
        val top = _state.value.deck.firstOrNull() ?: return
        viewModelScope.launch { itemRepository.setReadLater(top.id, true) }
        advance()
    }

    /** Left swipe: dismiss — mark read — and move on. */
    fun dismiss() {
        val top = _state.value.deck.firstOrNull() ?: return
        viewModelScope.launch { itemRepository.setRead(top.id, true) }
        advance()
    }

    /** Opening counts as triaged too, so the card doesn't reappear when you come back. */
    fun opened() {
        if (_state.value.deck.isNotEmpty()) advance()
    }
}
