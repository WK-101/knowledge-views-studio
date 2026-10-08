// The rail and its measures live together.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parley.common.people.AlphabetIndex
import app.parley.common.people.FastScroll
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The A–Z index's measures, shared by the lists that make room for it. */
object AlphabetIndexDefaults {
    /** The index's own lane on the end edge: nothing else in the list takes touches there while it shows. */
    val Lane = 48.dp

    /**
     * End padding for the rows beside the index: with a row's own 16 dp end inset, its trailing actions (call,
     * message, ⋮) end where the lane starts, so the index never shadows them.
     */
    val RowEndPadding = 32.dp

    /** A shorter index isn't drawn (the list is short, or its letters are still too far down). */
    val MinHeight = 160.dp

    /** The smallest row a letter gets; below it the index draws dots between fewer letters. */
    val MinSlot = 14.dp
}

/**
 * The A–Z index of an alphabetical list ([AlphabetIndex]), on the end edge where thumbs and habits expect it (the
 * left in right-to-left languages). It belongs to the alphabetical part only: it starts below that part's first
 * letter header (later, below the pinned one) and fades in once the letters are on screen, so it never covers what
 * leads the list (chips, My card, favourites, the Circle); [start] is the lazy-list index of that first header. It
 * has its own lane ([AlphabetIndexDefaults.Lane]) and takes touches only while it shows. Dragging along it shows a
 * large letter in a bubble beside the finger, gives a light tick on each new letter and jumps the list there; on a
 * short screen it draws dots between fewer letters and still reaches every one. TalkBack reads it as one adjustable
 * control (swipe up or down) with "Next letter" and "Previous letter" actions, in place of the drag. Call it in the
 * [BoxScope] that holds the list. [headers]: the list has pinned letter headers ([start] is the first); without them
 * (a picker) [start] is the first row and the index starts beside it.
 */
@Composable
fun BoxScope.AlphabetIndexRail(
    state: LazyListState,
    entries: List<AlphabetIndex.Entry>,
    start: Int,
    modifier: Modifier = Modifier,
    headers: Boolean = true,
) {
    if (entries.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val drag = remember { IndexDrag() }
    // Scrolling changes the list's layout every frame: what follows reads it in derived states and in the layout
    // phase, so the index recomposes only when it shows or hides, or its letter changes.
    val placement = rememberIndexPlacement(state, start, headers, drag)
    val shown by remember(placement) { derivedStateOf { placement.value.shown } }
    val targets = remember(entries) { entries.map { it.target } }
    val current by remember(targets) { derivedStateOf { FastScroll.sectionAt(state.firstVisibleItemIndex, targets).coerceAtLeast(0) } }
    val latest by rememberUpdatedState(entries)
    val jump: (Int) -> Unit = remember(scope, state) { { i -> latest.getOrNull(i)?.let { e -> scope.launch { state.scrollToItem(e.target) } } } }
    // Kept for the fade-out, so the index doesn't jump to the top as it goes.
    val lastTop = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(placement) { snapshotFlow { placement.value }.collect { if (it.shown) lastTop.floatValue = it.top } }
    val top: () -> Float = remember(placement) { { placement.value.let { if (it.shown) it.top else lastTop.floatValue } } }
    val words = IndexWords(stringResource(R.string.ui_index), stringResource(R.string.ui_index_next), stringResource(R.string.ui_index_previous))
    val selected = if (drag.dragging && drag.touched >= 0) drag.touched else current
    val onPick: (Int) -> Unit = remember(haptics, jump) {
        { i ->
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            jump(i)
        }
    }
    val railSize = remember(drag) { Modifier.onSizeChanged { drag.railHeight = it.height } }

    AnimatedVisibility(
        shown,
        modifier = modifier.align(Alignment.TopEnd).offset { IntOffset(0, top().roundToInt()) },
        enter = fadeIn(ParleyMotion.fastEffects()),
        exit = fadeOut(ParleyMotion.fastEffects()),
    ) {
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                .width(AlphabetIndexDefaults.Lane)
                // From its top to the bottom of the list, measured in the layout phase.
                .layout { measurable, constraints ->
                    val h = constraints.constrainHeight((state.layoutInfo.viewportSize.height - top()).roundToInt().coerceAtLeast(0))
                    val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(p.width, p.height) { p.place(0, 0) }
                }
                .indexSemantics(entries, selected, current, words, jump)
                // Touches only while it shows: as it fades out, the rows under the lane are the rows' again.
                .then(if (shown) Modifier.indexDrag(drag, { placement.value }, { latest.size }, onPick) else Modifier),
            contentAlignment = Alignment.TopEnd,
        ) {
            IndexLetters(entries, selected, drag.dragging, railSize)
        }
    }
    IndexBubble(entries.getOrNull(drag.touched)?.label, drag.dragging && drag.touched >= 0, { top() + drag.touchY }, { top() + drag.railHeight.toFloat() })
}

/** What a finger on the index is doing: shared by the drag, the letters, the bubble and where the index sits. */
private class IndexDrag {
    var dragging by mutableStateOf(false)
    var touched by mutableIntStateOf(-1)
    var touchY by mutableFloatStateOf(0f)
    var railHeight by mutableIntStateOf(1)

    /** Where the index was when the finger came down: it stays there until the finger lifts. */
    var held by mutableStateOf<AlphabetIndex.Placement?>(null)
}

/** The index's TalkBack words. */
private class IndexWords(val name: String, val next: String, val previous: String)

/**
 * Where the index goes as the list scrolls ([AlphabetIndex.placement]). A letter header's height is the first one's
 * (the pinned one is the same kind of row); a list without headers has none.
 */
@Composable
private fun rememberIndexPlacement(state: LazyListState, start: Int, headers: Boolean, drag: IndexDrag): State<AlphabetIndex.Placement> {
    val density = LocalDensity.current
    val minPx = with(density) { AlphabetIndexDefaults.MinHeight.toPx() }
    var headerPx by remember { mutableFloatStateOf(with(density) { 40.dp.toPx() }) }
    LaunchedEffect(state, start, headers) {
        if (!headers) {
            headerPx = 0f
            return@LaunchedEffect
        }
        snapshotFlow { state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == start }?.size }
            .collect { size -> if (size != null && size > 0) headerPx = size.toFloat() }
    }
    return remember(state, start) {
        derivedStateOf {
            val info = state.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull { it.index == start }
            AlphabetIndex.placement(
                firstVisible = state.firstVisibleItemIndex, start = start,
                startTop = first?.let { (it.offset - info.viewportStartOffset).toFloat() },
                viewport = info.viewportSize.height.toFloat(), header = headerPx, minHeight = minPx,
                dragging = drag.dragging, held = drag.held,
            )
        }
    }
}

/**
 * One adjustable control for TalkBack (swipe up or down), with Next and Previous letter, in place of the drag. It
 * steps through the letters only ([AlphabetIndex.spoken]): "★" would scroll the list above them, where the index
 * hides and TalkBack's focus would be lost.
 */
private fun Modifier.indexSemantics(entries: List<AlphabetIndex.Entry>, selected: Int, current: Int, words: IndexWords, jump: (Int) -> Unit): Modifier {
    val range = AlphabetIndex.spoken(entries)
    if (range.isEmpty()) return clearAndSetSemantics { contentDescription = words.name }
    val first = range.first
    val n = range.last - first + 1
    val at = (selected - first).coerceIn(0, n - 1)
    return clearAndSetSemantics {
        contentDescription = words.name
        stateDescription = entries.getOrNull(first + at)?.label.orEmpty()
        progressBarRangeInfo = ProgressBarRangeInfo(at.toFloat(), 0f..(n - 1).coerceAtLeast(1).toFloat(), steps = (n - 2).coerceAtLeast(0))
        setProgress { v ->
            jump(first + v.roundToInt().coerceIn(0, n - 1))
            true
        }
        customActions = listOf(
            CustomAccessibilityAction(words.next) { (current + 1).takeIf { it in range }?.let(jump) != null },
            CustomAccessibilityAction(words.previous) { (current - 1).takeIf { it in range }?.let(jump) != null },
        )
    }
}

/** The drag: the entry under the finger, picked over the whole index ([count] entries), each new one [picked]. */
private fun Modifier.indexDrag(drag: IndexDrag, placement: () -> AlphabetIndex.Placement, count: () -> Int, picked: (Int) -> Unit): Modifier =
    pointerInput(Unit) {
        fun pick(y: Float) {
            drag.touchY = y
            val i = FastScroll.indexAt(y, drag.railHeight.toFloat(), count())
            if (i != drag.touched && i >= 0) {
                drag.touched = i
                picked(i)
            }
        }
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            drag.touched = -1
            drag.held = placement()
            drag.dragging = true
            pick(down.position.y)
            var pressed = true
            while (pressed) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                pressed = change?.pressed == true
                if (change != null && pressed) {
                    change.consume()
                    pick(change.position.y)
                }
            }
            drag.dragging = false
            drag.held = null
        }
    }

/** The letters (or letters and dots, on a short screen) in a quiet pill, the one at the top of the list in colour. */
@Composable
private fun IndexLetters(entries: List<AlphabetIndex.Entry>, selected: Int, dragging: Boolean, modifier: Modifier) {
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(0) }
    // Larger fonts get taller rows, so fewer letters with dots between them: never letters over each other.
    val minSlotPx = AlphabetIndex.minSlot(AlphabetIndexDefaults.MinSlot.value, density.fontScale) * density.density
    val slots = (height / minSlotPx).toInt()
    val shown = remember(entries.size, slots) { AlphabetIndex.compact(entries.size, slots.coerceAtLeast(1)) }
    // Sized in dp from the row, then shown in sp with the font scale taken out again, so it can't outgrow its row.
    val rowDp = height / shown.size.coerceAtLeast(1) / density.density
    val letterSp = AlphabetIndex.letterDp(rowDp, density.fontScale) / density.fontScale
    Column(
        modifier
            .padding(vertical = Spacing.xs, horizontal = Spacing.xxs)
            .width(28.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (dragging) 0.95f else 0.7f), ParleyShapes.tile)
            .onSizeChanged { height = it.height },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        shown.forEach { i ->
            val on = i != null && i == selected
            val dot = MaterialTheme.colorScheme.primary
            val dim = MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier.weight(1f).fillMaxWidth().drawBehind {
                    when {
                        i == null -> drawCircle(dim, radius = 2.dp.toPx())
                        on && dragging -> drawCircle(dot, radius = minOf(size.width, size.height) / 2f)
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                if (i != null) {
                    Text(
                        entries[i].label, fontSize = letterSp.sp, maxLines = 1,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = letterColour(on, dragging),
                    )
                }
            }
        }
    }
}

/** A letter's colour: on the dot under the finger, the colour of the section at the top of the list, or quiet. */
@Composable
private fun letterColour(on: Boolean, dragging: Boolean) = when {
    on && dragging -> MaterialTheme.colorScheme.onPrimary
    on -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The letter under the finger, large, beside the index's lane (its pointed corner towards the finger, clear of the
 * thumb). [touchY] and [bottom] are from the top of the list, read as it is placed.
 */
@Composable
private fun BoxScope.IndexBubble(letter: String?, visible: Boolean, touchY: () -> Float, bottom: () -> Float) {
    val density = LocalDensity.current
    val bubble = 72.dp
    val bubblePx = with(density) { bubble.toPx() }
    val gapPx = with(density) { Spacing.s.toPx() }
    val corner = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TransformOrigin(0f, 1f) else TransformOrigin(1f, 1f)
    val lanePx = with(density) { AlphabetIndexDefaults.Lane.toPx() }
    val fade = ParleyMotion.fastEffects<Float>()
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.TopEnd).offset {
            IntOffset(-(lanePx + gapPx).roundToInt(), FastScroll.bubbleTop(touchY(), bottom(), bubblePx).roundToInt())
        },
        enter = scaleIn(ParleyMotion.fastSpatial(), transformOrigin = corner) + fadeIn(ParleyMotion.fastEffects()),
        exit = scaleOut(ParleyMotion.fastEffects(), transformOrigin = corner) + fadeOut(ParleyMotion.fastEffects()),
    ) {
        Surface(
            shape = ParleyShapes.bubble,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier.size(bubble).clearAndSetSemantics { },
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(letter.orEmpty(), transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }, label = "letter") { l ->
                    Text(l, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
