package com.cairn.reader.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ReviewCard
import com.cairn.reader.data.repo.HighlightRepository
import com.cairn.reader.domain.review.Cloze
import com.cairn.reader.domain.review.Grade
import com.cairn.reader.domain.review.Sm2
import com.cairn.reader.domain.review.SrState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One card presented for review: a recall cue, the answer, and the SM-2 button intervals. */
data class ReviewFace(
    val card: ReviewCard,
    val prompt: String,          // cloze blank, or the plain recall cue
    val isCloze: Boolean,
    val intervals: Map<Grade, String>,
)

data class ReviewUiState(
    val loading: Boolean = true,
    val face: ReviewFace? = null,
    val revealed: Boolean = false,
    val reviewed: Int = 0,
    val remaining: Int = 0,       // cards left in this session (incl. current)
)

/**
 * A spaced-repetition review session over due highlights. Each highlight becomes a recall card —
 * a fill-in-the-blank cloze where a good blank exists, otherwise a "recall what you highlighted"
 * prompt — graded with SM-2. Entirely on-device.
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val highlightRepository: HighlightRepository,
) : ViewModel() {

    /** Live due badge, shown at the entry point even when no session is running. */
    val dueCount: StateFlow<Int> =
        highlightRepository.observeDueCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var queue: ArrayDeque<ReviewCard> = ArrayDeque()

    fun start() {
        viewModelScope.launch {
            _state.value = ReviewUiState(loading = true)
            queue = ArrayDeque(runCatching { highlightRepository.dueCards(60) }.getOrDefault(emptyList()))
            _state.value = ReviewUiState(loading = false, face = faceFor(queue.firstOrNull()), remaining = queue.size)
        }
    }

    fun reveal() { _state.value = _state.value.copy(revealed = true) }

    fun grade(grade: Grade) {
        val current = queue.firstOrNull() ?: return
        viewModelScope.launch {
            runCatching { highlightRepository.review(current, grade) }
            queue.removeFirst()
            // A lapse ("Again") comes back at the end of this session so you see it again today.
            if (grade == Grade.AGAIN) queue.addLast(current)
            _state.value = _state.value.copy(
                face = faceFor(queue.firstOrNull()),
                revealed = false,
                reviewed = _state.value.reviewed + 1,
                remaining = queue.size,
            )
        }
    }

    private fun faceFor(card: ReviewCard?): ReviewFace? {
        card ?: return null
        val cloze = Cloze.of(card.quote)
        val prompt = cloze?.prompt ?: "Recall what you highlighted" + (card.articleSite?.let { " in $it" } ?: "") + "…"
        val state = SrState(card.srInterval, card.srEase, card.srReps, card.srLapses)
        val intervals = Grade.entries.associateWith { Sm2.preview(state, it) }
        return ReviewFace(card, prompt, cloze != null, intervals)
    }
}
