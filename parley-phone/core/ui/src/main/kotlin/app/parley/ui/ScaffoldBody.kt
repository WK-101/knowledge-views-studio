package app.parley.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The body of a list screen, edge to edge: inset by the scaffold's top bar and side insets, but not by the bottom
 * one, so the list scrolls under the transparent navigation bar instead of stopping at a dead band above it. Pair it
 * with [scaffoldBottom] as the list's `contentPadding` so the last row can still scroll clear of the bar.
 *
 * With [bar] (a pinned scroll behaviour handed to the screen's ParleyTopBar too) the bar takes its "on scroll"
 * container colour while content is under it. Leave it null when the scaffold already wires that scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.scaffoldBody(p: PaddingValues, bar: TopAppBarScrollBehavior? = null): Modifier =
    (if (bar != null) nestedScroll(bar.nestedScrollConnection) else this).padding(TopAndSides(p))

/**
 * A list's `contentPadding` under [scaffoldBody]: the navigation bar's height plus [extra] at the end, and the
 * list's own [horizontal] and [top] padding when it has any.
 */
fun scaffoldBottom(p: PaddingValues, extra: Dp = Spacing.l, horizontal: Dp = 0.dp, top: Dp = 0.dp): PaddingValues =
    PaddingValues(start = horizontal, top = top, end = horizontal, bottom = p.calculateBottomPadding() + extra)

/** [p] without its bottom edge; stays a live view so insets that change (keyboard, rotation) still apply. */
private class TopAndSides(private val p: PaddingValues) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = p.calculateLeftPadding(layoutDirection)
    override fun calculateTopPadding(): Dp = p.calculateTopPadding()
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = p.calculateRightPadding(layoutDirection)
    override fun calculateBottomPadding(): Dp = 0.dp
    override fun equals(other: Any?): Boolean = other is TopAndSides && other.p == p
    override fun hashCode(): Int = p.hashCode()
}
