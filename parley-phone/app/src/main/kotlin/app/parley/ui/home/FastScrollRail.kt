package app.parley.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parley.R
import app.parley.common.people.FastScroll
import kotlin.math.roundToInt
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleyMotion

/**
 * The Contacts A–Z rail. Like Google Contacts and Samsung's phonebook, dragging along it shows a large
 * letter bubble beside the finger (its pointed corner at the finger, so the thumb doesn't hide it), the letter
 * under the finger is highlighted on the rail, each new letter gives a light tick and the list jumps to it. Out of
 * a drag, the rail highlights the section at the top of the list. The entries are the list's own sections (other
 * scripts, "#" for digits, "★" for the favourites). TalkBack reads it as an adjustable control: swipe up or down to
 * move between letters. It sits on the end edge (the left in right-to-left languages) and the bubble towards the
 * middle.
 */
@Composable
fun FastScrollRail(letters: List<String>, current: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    if (letters.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var touched by remember { mutableIntStateOf(-1) }
    val latestLetters by rememberUpdatedState(letters)
    val latestPick by rememberUpdatedState(onPick)
    val selected = if (dragging && touched >= 0) touched else current
    // Letters as large as fit (8 to 13 sp), so a short alphabet isn't tiny and a long one doesn't overlap.
    val letterSp = with(density) { (height / letters.size.coerceAtLeast(1) * 0.62f).toSp().value }.coerceIn(8f, 13f)
    val bubble = 72.dp
    val bubblePx = with(density) { bubble.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val railAlpha by animateFloatAsState(if (dragging) 1f else 0f, ParleyMotion.fastEffects(), label = "rail")
    val indexLabel = stringResource(R.string.contacts_alphabet_index)
    // The bubble grows from its pointed corner, which is on the rail's side.
    val corner = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TransformOrigin(0f, 1f) else TransformOrigin(1f, 1f)
    val n = letters.size

    Box(modifier.fillMaxHeight().width(32.dp)) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(32.dp)
                .padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f * railAlpha), ParleyShapes.tile)
                .onSizeChanged { height = it.height }
                .clearAndSetSemantics {
                    contentDescription = indexLabel
                    stateDescription = letters.getOrNull(selected.coerceAtLeast(0)).orEmpty()
                    progressBarRangeInfo = ProgressBarRangeInfo(selected.coerceAtLeast(0).toFloat(), 0f..(n - 1).coerceAtLeast(1).toFloat(), steps = (n - 2).coerceAtLeast(0))
                    setProgress { v ->
                        val i = v.roundToInt().coerceIn(0, n - 1)
                        latestPick(i)
                        true
                    }
                }
                .pointerInput(Unit) {
                    fun pick(y: Float) {
                        touchY = y
                        val i = FastScroll.indexAt(y, height.toFloat(), latestLetters.size)
                        if (i != touched && i >= 0) {
                            touched = i
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            latestPick(i)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        touched = -1
                        dragging = true
                        pick(down.position.y)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(change.position.y)
                        }
                        dragging = false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEachIndexed { i, l ->
                val on = i == selected
                val dot = MaterialTheme.colorScheme.primary
                Box(
                    Modifier.weight(1f).fillMaxWidth().drawBehind { if (on && dragging) drawCircle(dot, radius = minOf(size.width, size.height) / 2f) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        l, fontSize = letterSp.sp, maxLines = 1,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            on && dragging -> MaterialTheme.colorScheme.onPrimary
                            on -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        // The bubble: its bottom corner at the finger, on the rail's inner side (offset follows the layout direction).
        val top = FastScroll.bubbleTop(touchY + with(density) { 8.dp.toPx() }, height.toFloat() + with(density) { 16.dp.toPx() }, bubblePx)
        val letterFade = ParleyMotion.fastEffects<Float>()
        AnimatedVisibility(
            dragging && touched >= 0,
            modifier = Modifier.offset { IntOffset(-(bubblePx + gapPx).roundToInt(), top.roundToInt()) },
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
                    AnimatedContent(letters.getOrNull(touched).orEmpty(), transitionSpec = { fadeIn(letterFade) togetherWith fadeOut(letterFade) }, label = "letter") { l ->
                        Text(l, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}
