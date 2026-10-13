package app.parley.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

/** How far a back swipe over a surface inside a screen has gone: 0 at rest, up to 1 just before it lets go. */
@Stable
class BackPreview {
    var progress by mutableFloatStateOf(0f)
        internal set
}

@Composable
fun rememberBackPreview(): BackPreview = remember { BackPreview() }

/**
 * Back for a surface inside a screen (a search, the page beside a list, a docked panel), previewed while the back
 * gesture is held, as Android 14+ previews leaving a screen: [preview] (and [onProgress]) follow the swipe, a cancelled
 * swipe settles back ([onCancel]), and a finished one calls [onBack]. The Back button and key call [onBack] at once.
 * With animations removed nothing follows the swipe; Back still works.
 */
@Composable
fun PreviewedBackHandler(
    enabled: Boolean,
    preview: BackPreview? = null,
    onProgress: (Float) -> Unit = {},
    onCancel: () -> Unit = {},
    onBack: () -> Unit,
) {
    val still = ParleyMotion.reducedMotion()
    val back by rememberUpdatedState(onBack)
    val progress by rememberUpdatedState(onProgress)
    val cancel by rememberUpdatedState(onCancel)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { e ->
                if (!still) {
                    preview?.progress = e.progress
                    progress(e.progress)
                }
            }
            back()
        } catch (c: CancellationException) {
            cancel()
            throw c
        } finally {
            // Settles back (animated in backPreview) as the surface closes, or as a cancelled swipe returns.
            preview?.progress = 0f
        }
    }
}

/**
 * Draws the surface as [preview] says: it shrinks a little (to 90 %) and, with [rounded], its corners round, like a
 * page being swiped away; it springs back when the swipe is let go or cancelled.
 */
@Composable
fun Modifier.backPreview(preview: BackPreview, rounded: Boolean = true): Modifier {
    val p by animateFloatAsState(preview.progress, ParleyMotion.spatial(), label = "back-preview")
    return graphicsLayer {
        val s = 1f - PREVIEW_SHRINK * p
        scaleX = s
        scaleY = s
        if (rounded) {
            shape = animatedCorners((PREVIEW_CORNER_DP * p).dp)
            clip = p > 0f
        }
    }
}

/** How much a surface shrinks at the end of a back swipe, and how round its corners get (Material's back preview). */
internal const val PREVIEW_SHRINK = 0.1f
internal const val PREVIEW_CORNER_DP = 28f
