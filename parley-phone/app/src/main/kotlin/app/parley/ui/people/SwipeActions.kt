package app.parley.ui.people

import android.content.res.Resources
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.SwipeAction
import app.parley.common.people.SwipeConfig
import app.parley.common.people.SwipeGesture
import app.parley.common.people.SwipeIntent
import app.parley.ui.CallColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.parley.ui.ParleyMotion

fun SwipeAction.icon(): ImageVector = when (this) {
    SwipeAction.CALL -> Icons.Rounded.Call
    SwipeAction.MESSAGE -> Icons.AutoMirrored.Rounded.Message
    SwipeAction.MESSAGE_ON -> Icons.AutoMirrored.Rounded.Chat
    SwipeAction.BLOCK -> Icons.Rounded.Block
    SwipeAction.DELETE -> Icons.Rounded.Delete
    SwipeAction.NONE -> Icons.Rounded.Call
}

@Composable
private fun SwipeAction.colors(): Pair<Color, Color> = when (this) {
    SwipeAction.CALL -> CallColors.Accept to Color.White
    SwipeAction.DELETE, SwipeAction.BLOCK -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
}

/**
 * A contact or Recents row with swipe actions (off by default, Settings › Appearance). The same actions are
 * offered to TalkBack as custom actions. Nothing is dismissed by the gesture itself, so a Delete always goes
 * through the caller's undo path (and Block offers Undo too, [blockWithUndo]).
 *
 * A swipe only starts after a clear sideways move (past the touch slop and at least twice as sideways
 * as up or down, [SwipeGesture.classify]) and never while [listState] is still flinging, so it doesn't fight the
 * list's scrolling. It commits past a third of the row or with a quick flick; a tick is felt when the threshold is
 * crossed (and again if you go back), the action's colour and icon pop in at that point, and the row springs back.
 * Directions are physical ("swipe right" is rightwards in every language).
 */
@Composable
fun SwipeActionRow(
    config: SwipeConfig,
    hasNumber: Boolean,
    canDelete: Boolean,
    onAction: (SwipeAction) -> Unit,
    listState: ScrollableState? = null,
    content: @Composable () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Physical right and left: towards the end in left-to-right layouts, towards the start in right-to-left ones.
    val right = config.action(towardsEnd = !rtl, rtl = rtl).takeIf { config.available(it, hasNumber, canDelete) } ?: SwipeAction.NONE
    val left = config.action(towardsEnd = rtl, rtl = rtl).takeIf { config.available(it, hasNumber, canDelete) } ?: SwipeAction.NONE
    if (right == SwipeAction.NONE && left == SwipeAction.NONE) {
        content()
        return
    }
    val res = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    val latestAction by rememberUpdatedState(onAction)
    val latestList by rememberUpdatedState(listState)

    val springBack = ParleyMotion.spatial<Float>()
    val slideOut = ParleyMotion.fastSpatial<Float>()
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { width = it.width }
            .semantics {
                customActions = listOf(right, left).filter { it != SwipeAction.NONE }.distinct().map { a ->
                    CustomAccessibilityAction(swipeLabel(res, a)) { latestAction(a); true }
                }
            }
            .pointerInput(right, left) {
                val slop = viewConfiguration.touchSlop
                val flick = SwipeGesture.FLICK_DP_PER_S * density
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A touch that stops a fling only stops it.
                    if (latestList?.isScrollInProgress == true) return@awaitEachGesture
                    var dx = 0f
                    var dy = 0f
                    var intent = SwipeIntent.UNDECIDED
                    while (intent == SwipeIntent.UNDECIDED) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        val d = change.positionChange()
                        dx += d.x
                        dy += d.y
                        intent = SwipeGesture.classify(dx, dy, slop, latestList?.isScrollInProgress == true)
                    }
                    if (intent != SwipeIntent.HORIZONTAL) return@awaitEachGesture
                    // The row has the gesture now: the list and the row's own tap won't see it.
                    val w = width.toFloat().coerceAtLeast(1f)
                    val threshold = SwipeGesture.threshold(w, density)
                    val tracker = VelocityTracker()
                    var raw = offset.value
                    var shown = raw
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) break
                        raw += change.positionChange().x
                        change.consume()
                        shown = SwipeGesture.offset(raw, right != SwipeAction.NONE, left != SwipeAction.NONE, w, density)
                        val target = shown
                        scope.launch { offset.snapTo(target) }
                        val nowArmed = abs(shown) >= threshold && (if (shown > 0) right else left) != SwipeAction.NONE
                        if (nowArmed != armed) {
                            armed = nowArmed
                            haptics.performHapticFeedback(if (nowArmed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                        }
                    }
                    val velocity = tracker.calculateVelocity().x
                    val action = if (shown > 0) right else left
                    val commit = SwipeGesture.commits(shown, velocity, threshold, flick, action != SwipeAction.NONE)
                    // A flick that commits before the threshold still gets its tick.
                    if (commit && !armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    scope.launch {
                        if (commit && action == SwipeAction.DELETE) {
                            // The row slides out; the list removes it (with Undo). If it's still there, it comes back.
                            offset.animateTo(sign(shown) * w, slideOut, initialVelocity = velocity)
                            latestAction(action)
                            delay(900)
                            armed = false
                            offset.animateTo(0f, springBack)
                        } else {
                            if (commit) latestAction(action)
                            armed = false
                            offset.animateTo(0f, springBack, initialVelocity = velocity)
                        }
                    }
                }
            },
    ) {
        SwipeBackground(offset.value, right, left, armed, width, Modifier.matchParentSize())
        Box(Modifier.absoluteOffset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/** What the moving row reveals: muted until the threshold, then the action's colour with its icon popping in. */
@Composable
private fun SwipeBackground(o: Float, right: SwipeAction, left: SwipeAction, armed: Boolean, width: Int, modifier: Modifier) {
    val res = LocalResources.current
    val action = if (o > 0) right else if (o < 0) left else SwipeAction.NONE
    val (bg, fg) = (if (action == SwipeAction.NONE) SwipeAction.MESSAGE else action).colors()
    val container by animateColorAsState(if (armed) bg else MaterialTheme.colorScheme.surfaceContainerHighest, label = "swipe_bg")
    val onContainer by animateColorAsState(if (armed) fg else MaterialTheme.colorScheme.onSurfaceVariant, label = "swipe_fg")
    val pop by animateFloatAsState(if (armed) 1.15f else 1f, ParleyMotion.fastSpatial(), label = "swipe_pop")
    if (action == SwipeAction.NONE || o == 0f) return
    val density = LocalDensity.current.density
    val progress = (abs(o) / SwipeGesture.threshold(width.toFloat().coerceAtLeast(1f), density)).coerceIn(0f, 1f)
    Row(
        modifier.background(container).padding(horizontal = 24.dp),
        horizontalArrangement = if (o > 0) Arrangement.Absolute.Left else Arrangement.Absolute.Right,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            action.icon(), null, tint = onContainer,
            modifier = Modifier.graphicsLayer {
                val s = (0.6f + 0.4f * progress) * pop
                scaleX = s
                scaleY = s
                alpha = 0.4f + 0.6f * progress
            },
        )
        Text(
            swipeLabel(res, action, short = true), color = onContainer, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp).graphicsLayer { alpha = progress },
        )
    }
}

/** Block from a swipe: no question (a swipe is its own), Undo on the snackbar, the same block as everywhere else. */
fun blockWithUndo(vm: AppViewModel, numbers: List<String>) = app.parley.ui.blocking.blockWithUndo(vm, numbers)

/** Localised name of a swipe action; [short] drops the "(with undo)" part for the swipe background. */
internal fun swipeLabel(res: Resources, a: SwipeAction, short: Boolean = false): String = res.getString(
    when (a) {
        SwipeAction.NONE -> R.string.swipe_none
        SwipeAction.CALL -> R.string.circle_widget_call
        SwipeAction.MESSAGE -> R.string.circle_type_message
        SwipeAction.MESSAGE_ON -> R.string.contact_page_sec_messengers
        SwipeAction.BLOCK -> R.string.blk_block
        SwipeAction.DELETE -> if (short) R.string.blk_delete else R.string.swipe_delete
    },
)
