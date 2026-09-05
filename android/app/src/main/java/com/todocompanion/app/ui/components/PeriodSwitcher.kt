package com.todocompanion.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.todocompanion.app.domain.PeriodRange

/**
 * Coherence Move 7 — the ONE period switcher every review / analytics surface renders. Day Review,
 * Statistics, Recap and The Record used to each pick a time span with a different chip row and option
 * set; this is the single control they all share, so the affordance, the labels (Day·Week·Month·Year·All)
 * and its position (near the top of the surface) read identically everywhere.
 *
 * A deliberately thin wrapper over the app's one single-choice chip row ([OptionChips]) — kept trivial on
 * purpose so no surface can drift into a bespoke variant. A surface that only supports a subset of periods
 * passes its own [periods] list (e.g. Day Review, whose DAY option keeps the full close-the-day screen).
 */
@Composable
fun PeriodSwitcher(
    selected: PeriodRange,
    onSelect: (PeriodRange) -> Unit,
    modifier: Modifier = Modifier,
    periods: List<PeriodRange> = PeriodRange.ALL_PERIODS,
) {
    OptionChips(
        options = periods,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        wrap = false,
        spacing = 6,
        label = { it.label },
    )
}
