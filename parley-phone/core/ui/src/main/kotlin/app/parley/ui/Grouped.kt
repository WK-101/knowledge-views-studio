package app.parley.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The key of a row to scroll to and briefly highlight (Settings search opens a screen on the setting it found).
 * Provided by the screen; read by [SegmentedGroup].
 */
val LocalHighlightKey = compositionLocalOf<String?> { null }

/** M3 Expressive segmented shape: large outer corners on the first and last item, small ones in between. */
fun segmentShape(index: Int, count: Int, outer: Dp = 20.dp, inner: Dp = 4.dp): Shape = RoundedCornerShape(
    topStart = if (index == 0) outer else inner,
    topEnd = if (index == 0) outer else inner,
    bottomStart = if (index == count - 1) outer else inner,
    bottomEnd = if (index == count - 1) outer else inner,
)

class SegmentedGroupScope internal constructor() {
    internal val items = ArrayList<Pair<String?, @Composable () -> Unit>>()

    /** One row of the group; [key] lets search scroll to and highlight it. */
    fun item(key: String? = null, content: @Composable () -> Unit) {
        items += key to content
    }
}

/** Title above a group of rows ("Display", "Sounds"…). */
@Composable
fun GroupHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * A group of rows drawn as connected cards with 2 dp gaps (M3 Expressive "segmented" lists): the look of the
 * Settings screens. Rows should use a transparent container colour; the card provides the surface.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SegmentedGroup(title: String? = null, modifier: Modifier = Modifier, content: SegmentedGroupScope.() -> Unit) {
    val scope = SegmentedGroupScope().apply(content)
    if (scope.items.isEmpty()) return
    val highlight = LocalHighlightKey.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (title != null) GroupHeader(title)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val n = scope.items.size
            scope.items.forEachIndexed { i, (key, row) -> androidx.compose.runtime.key(key ?: "#$i") {
                val focused = key != null && key == highlight
                // Highlight once per screen visit, not again after rotation.
                var flash by rememberSaveable(key) { mutableStateOf(focused) }
                val requester = remember { BringIntoViewRequester() }
                if (focused) {
                    LaunchedEffect(Unit) {
                        delay(250)
                        requester.bringIntoView()
                        delay(1600)
                        flash = false
                    }
                }
                val color by animateColorAsState(
                    if (flash) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    animationSpec = tween(600),
                    label = "highlight",
                )
                Surface(
                    shape = segmentShape(i, n),
                    color = color,
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester),
                ) { row() }
            } }
        }
    }
}
