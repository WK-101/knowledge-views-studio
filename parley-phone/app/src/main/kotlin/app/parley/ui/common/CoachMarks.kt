package app.parley.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.common.ux.Tips
import app.parley.data.UxPrefs

/**
 * U2: one-time, dismissible coach marks for gestures nobody finds on their own. Any screen can add one with
 * [CoachMark] (an inline card) or [CoachMarkAnchor] (a small bubble under a button), with an id from [Tips].
 * Only one shows at a time; a dismissed one stays gone until Settings › Appearance › Reset tips.
 */
@Stable
class CoachMarks(internal val prefs: UxPrefs) {
    private val requested = mutableStateListOf<String>()
    internal var showing by mutableStateOf<String?>(null)

    internal fun request(id: String) { if (id !in requested) requested += id }

    internal fun release(id: String) {
        requested -= id
        if (showing == id) showing = null
    }

    internal fun visible(seen: Set<String>): String? = Tips.visible(requested, seen, showing)

    fun dismiss(id: String) {
        prefs.dismissTip(id)
        if (showing == id) showing = null
    }
}

/** Provided by the app root; null (no tips) anywhere else, such as the call screen or a picker. */
val LocalCoachMarks = staticCompositionLocalOf<CoachMarks?> { null }

/** Whether mark [id] is the one to show now; registers it while it is composed. */
@Composable
private fun rememberMarkVisible(id: String, enabled: Boolean): Pair<CoachMarks, Boolean>? {
    val marks = LocalCoachMarks.current ?: return null
    val seen by marks.prefs.state.collectAsStateWithLifecycle()
    DisposableEffect(marks, id, enabled) {
        if (enabled) marks.request(id)
        onDispose { marks.release(id) }
    }
    val visible = enabled && id !in seen.seenTips && marks.visible(seen.seenTips) == id
    if (visible) SideEffect { marks.showing = id }
    return marks to visible
}

@Composable
private fun MarkContent(id: String, text: String, marks: CoachMarks, action: String?, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lightbulb, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.inversePrimary)
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                if (action != null && onAction != null) {
                    TextButton({ marks.dismiss(id); onAction() }) { Text(action, color = MaterialTheme.colorScheme.inversePrimary) }
                }
                TextButton({ marks.dismiss(id) }) { Text(stringResource(R.string.ux_tip_got_it), color = MaterialTheme.colorScheme.inversePrimary) }
            }
        }
    }
}

/** U2: an inline tip card, shown once until dismissed ([enabled] false keeps it away, e.g. while a list is empty). */
@Composable
fun CoachMark(id: String, text: String, modifier: Modifier = Modifier, enabled: Boolean = true, action: String? = null, onAction: (() -> Unit)? = null) {
    val (marks, visible) = rememberMarkVisible(id, enabled) ?: return
    if (visible) MarkContent(id, text, marks, action, onAction, modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

/** U2: a tip bubble under [content] (a header button, say), shown once until dismissed. */
@Composable
fun CoachMarkAnchor(id: String, text: String, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box {
        content()
        val state = rememberMarkVisible(id, enabled)
        if (state != null && state.second) {
            val density = LocalDensity.current
            val provider = BelowAnchor(with(density) { 4.dp.roundToPx() }, with(density) { 12.dp.roundToPx() })
            Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
                MarkContent(id, text, state.first, null, null, Modifier.widthIn(max = 300.dp))
            }
        }
    }
}

/** Under the anchor, centred on it and kept inside the window. */
private class BelowAnchor(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin, maxX)
        return IntOffset(x, anchorBounds.bottom + gap)
    }
}
