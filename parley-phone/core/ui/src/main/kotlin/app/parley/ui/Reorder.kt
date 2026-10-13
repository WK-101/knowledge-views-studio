package app.parley.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** The index maths of drag-to-reorder, apart from the gestures. */
object ReorderMath {
    /** [list] with the item at [from] moved to [to] (unchanged when either is out of range). */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** Where a dragged row goes: its new index, and how far the finger is from that row's place. */
    data class Step(val to: Int, val offset: Float)

    /**
     * A row at [index] of [count], dragged [offset] px from its place among rows [rowPx] tall: once the finger is past
     * half a row it takes the next row's place (one row at a time, never past either end), and stays under the finger.
     */
    fun rowStep(offset: Float, rowPx: Float, index: Int, count: Int): Step {
        if (rowPx <= 0f) return Step(index, offset)
        return when {
            offset > rowPx / 2 && index < count - 1 -> Step(index + 1, offset - rowPx)
            offset < -rowPx / 2 && index > 0 -> Step(index - 1, offset + rowPx)
            else -> Step(index, offset)
        }
    }
}

/**
 * One drag-to-reorder list's lifted item: which one ([lifted]) and how far it is from its place ([offset]). Lifting
 * gives a firm tick and each place it moves to a light one, as the system's own reorderable lists do.
 */
@Stable
class ReorderState<K : Any> internal constructor() {
    var lifted by mutableStateOf<K?>(null)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    internal var rowPx by mutableFloatStateOf(0f)
    internal var haptics: HapticFeedback? = null

    fun isLifted(key: K): Boolean = lifted == key

    fun lift(key: K) {
        lifted = key
        offset = Offset.Zero
        haptics?.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    fun dragBy(delta: Offset) {
        offset += delta
    }

    /** The lifted item took another place; [offsetThere] keeps it under the finger from there. */
    fun movedTo(offsetThere: Offset) {
        offset = offsetThere
        haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    fun drop() {
        lifted = null
        offset = Offset.Zero
    }
}

@Composable
fun <K : Any> rememberReorderState(): ReorderState<K> {
    val haptics = LocalHapticFeedback.current
    return remember { ReorderState<K>() }.also { it.haptics = haptics }
}

/** How much a lifted tile grows, and how high a lifted item is raised. */
internal const val LIFT_SCALE = 1.08f
internal val LiftElevation = 6.dp

/**
 * An item of a reorderable list: while lifted it is drawn above the others, under the finger, raised and (with
 * [grow], for tiles) a little larger; rows ([rowBackground]) get a raised surface of [shape] behind them, and measure
 * their height here for [reorderHandle].
 */
@Composable
fun <K : Any> Modifier.reorderable(
    state: ReorderState<K>,
    key: K,
    grow: Boolean = false,
    rowBackground: Boolean = true,
    shape: Shape = RectangleShape,
): Modifier {
    val lifted = state.isLifted(key)
    val raised = MaterialTheme.colorScheme.surfaceContainerHighest
    return this
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer {
            if (state.isLifted(key)) {
                translationX = state.offset.x
                translationY = state.offset.y
                shadowElevation = LiftElevation.toPx()
                this.shape = shape
                if (grow) {
                    scaleX = LIFT_SCALE
                    scaleY = LIFT_SCALE
                }
            }
        }
        .then(
            if (rowBackground) {
                Modifier.clip(shape).background(if (lifted) raised else Color.Transparent).onSizeChanged { state.rowPx = it.height.toFloat() }
            } else {
                Modifier
            },
        )
}

/**
 * A drag handle for a column of rows: dragging it moves [key]'s row one place at a time as the finger passes half a
 * row ([move] from, to); [onDrop] when the finger lifts, [onCancel] when the drag is cancelled. A tap does nothing (it
 * doesn't reach the row under it).
 */
@Composable
fun <K : Any> Modifier.reorderHandle(
    state: ReorderState<K>,
    key: K,
    index: () -> Int,
    count: () -> Int,
    move: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val latestIndex by rememberUpdatedState(index)
    val latestCount by rememberUpdatedState(count)
    val latestMove by rememberUpdatedState(move)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestCancel by rememberUpdatedState(onCancel)
    return this
        .pointerInput(Unit) { detectTapGestures { } }
        .pointerInput(key) {
            detectVerticalDragGestures(
                onDragStart = { state.lift(key) },
                onDragEnd = { state.drop(); latestDrop() },
                onDragCancel = { state.drop(); latestCancel() },
            ) { change, dy ->
                change.consume()
                state.dragBy(Offset(0f, dy))
                val from = latestIndex()
                val step = ReorderMath.rowStep(state.offset.y, state.rowPx, from, latestCount())
                if (step.to != from) {
                    latestMove(from, step.to)
                    state.movedTo(Offset(0f, step.offset))
                }
            }
        }
}

/**
 * TalkBack's way to reorder: "[earlier]" and "[later]" actions on the item at [index] of [count] (only those that
 * can move it), each moving it one place ([move] to the new index).
 */
fun Modifier.reorderActions(earlier: String, later: String, index: Int, count: Int, move: (to: Int) -> Unit): Modifier = semantics {
    customActions = listOfNotNull(
        if (index > 0) CustomAccessibilityAction(earlier) { move(index - 1); true } else null,
        if (index < count - 1) CustomAccessibilityAction(later) { move(index + 1); true } else null,
    )
}
