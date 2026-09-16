package com.cairn.reader.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.ReviewCard
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.prefs.ReviewScheduler
import com.cairn.reader.data.repo.HighlightRepository
import com.cairn.reader.domain.review.Cloze
import com.cairn.reader.domain.review.Fsrs
import com.cairn.reader.domain.review.FsrsState
import com.cairn.reader.domain.review.Grade
import com.cairn.reader.domain.review.Sm2
import com.cairn.reader.domain.review.SrPhase
import com.cairn.reader.domain.review.SrState
import com.cairn.reader.domain.review.intervalLabel
import com.cairn.reader.util.coRunCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One card presented for review: a recall cue, the answer, and the per-grade next-interval labels. */
data class ReviewFace(
    val card: ReviewCard,
    val clozePrompt: String?,    // the fill-in-the-blank cue when a cloze exists; null → plain recall
    val isCloze: Boolean,
    val intervals: Map<Grade, String>,
)

data class ReviewUiState(
    val loading: Boolean = true,
    val face: ReviewFace? = null,
    val revealed: Boolean = false,
    val reviewed: Int = 0,
    val remaining: Int = 0,       // cards left in this session (incl. current)
    // Which engine is running, for the on-screen badge and the reveal card's memory readout.
    val advanced: Boolean = true,
    val retention: Float = 0.90f,
    // Session tallies, for the progress strip and the end-of-session summary.
    val again: Int = 0,
    val hard: Int = 0,
    val good: Int = 0,
    val easy: Int = 0,
) {
    /** Cards graded on the first try (anything but Again), as a fraction of all grades made. */
    val accuracy: Float get() = if (reviewed == 0) 0f else (reviewed - again).toFloat() / reviewed
}

/**
 * A spaced-repetition review session over due highlights. Each highlight becomes a recall card —
 * a fill-in-the-blank cloze where a good blank exists, otherwise a "recall what you highlighted"
 * prompt. Grading runs whichever scheduler the user chose (FSRS by default, SM-2 as the basic
 * option), and the per-grade previews on the buttons come from that same engine. Entirely on-device.
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val highlightRepository: HighlightRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    /** Live due badge, shown at the entry point even when no session is running. */
    val dueCount: StateFlow<Int> =
        highlightRepository.observeDueCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var queue: ArrayDeque<ReviewCard> = ArrayDeque()

    // Scheduler config, snapshotted at the start of each session so it stays stable mid-session.
    private var advanced = true
    private var retention = 0.90
    private var maxIntervalDays = 3650

    fun start() {
        viewModelScope.launch {
            _state.value = ReviewUiState(loading = true)
            val prefs = coRunCatching { preferencesRepository.preferences.first() }.getOrNull()
            advanced = prefs?.reviewScheduler != ReviewScheduler.BASIC
            retention = (prefs?.reviewRetention ?: 0.90f).toDouble()
            maxIntervalDays = prefs?.reviewMaxIntervalDays ?: 3650
            val sessionSize = prefs?.reviewSessionSize ?: 40
            queue = ArrayDeque(coRunCatching { highlightRepository.dueCards(sessionSize) }.getOrDefault(emptyList()))
            _state.value = ReviewUiState(
                loading = false,
                face = faceFor(queue.firstOrNull()),
                remaining = queue.size,
                advanced = advanced,
                retention = retention.toFloat(),
            )
        }
    }

    fun reveal() { _state.value = _state.value.copy(revealed = true) }

    fun grade(grade: Grade) {
        val current = queue.firstOrNull() ?: return
        viewModelScope.launch {
            coRunCatching { highlightRepository.review(current, grade, advanced, retention, maxIntervalDays) }
            queue.removeFirst()
            // A lapse ("Again") comes back at the end of this session so you see it again today.
            if (grade == Grade.AGAIN) queue.addLast(current)
            val s = _state.value
            _state.value = s.copy(
                face = faceFor(queue.firstOrNull()),
                revealed = false,
                reviewed = s.reviewed + 1,
                remaining = queue.size,
                again = s.again + if (grade == Grade.AGAIN) 1 else 0,
                hard = s.hard + if (grade == Grade.HARD) 1 else 0,
                good = s.good + if (grade == Grade.GOOD) 1 else 0,
                easy = s.easy + if (grade == Grade.EASY) 1 else 0,
            )
        }
    }

    private fun faceFor(card: ReviewCard?): ReviewFace? {
        card ?: return null
        // The cloze prompt is derived from the highlight text itself, so it is already in the
        // reader's language; the plain-recall fallback is a UI string localized at render time.
        val cloze = Cloze.of(card.quote)
        val intervals: Map<Grade, String> = if (advanced) {
            val fs = FsrsState(
                stability = card.srStability,
                difficulty = card.srDifficulty,
                phase = SrPhase.entries.getOrElse(card.srPhase) { SrPhase.NEW },
                reps = card.srReps,
                lapses = card.srLapses,
            )
            Grade.entries.associateWith {
                intervalLabel(
                    Fsrs.previewIntervalDays(
                        fs, it, card.srLastReviewedAt,
                        requestRetention = retention, maxIntervalDays = maxIntervalDays,
                    ),
                )
            }
        } else {
            val ss = SrState(card.srInterval, card.srEase, card.srReps, card.srLapses)
            Grade.entries.associateWith { Sm2.preview(ss, it) }
        }
        return ReviewFace(card, cloze?.prompt, cloze != null, intervals)
    }
}
