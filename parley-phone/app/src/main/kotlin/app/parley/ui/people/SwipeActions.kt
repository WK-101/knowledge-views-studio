package app.parley.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.common.people.SwipeAction
import app.parley.common.people.SwipeConfig
import app.parley.ui.CallColors
import kotlinx.coroutines.launch
import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext
import app.parley.R

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
 * U4: a contact or Recents row with swipe actions (off by default, Settings › Appearance). The row springs back
 * after the action; nothing is dismissed by the gesture itself, so a Delete always goes through the caller's
 * undo path. The same actions are offered to TalkBack as custom actions.
 */
@Composable
fun SwipeActionRow(
    config: SwipeConfig,
    hasNumber: Boolean,
    canDelete: Boolean,
    onAction: (SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val towardsEnd = config.action(true).takeIf { config.available(it, hasNumber, canDelete) } ?: SwipeAction.NONE
    val towardsStart = config.action(false).takeIf { config.available(it, hasNumber, canDelete) } ?: SwipeAction.NONE
    if (towardsEnd == SwipeAction.NONE && towardsStart == SwipeAction.NONE) {
        content()
        return
    }
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState()
    val res = androidx.compose.ui.platform.LocalResources.current
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.semantics {
            customActions = listOf(towardsEnd, towardsStart).filter { it != SwipeAction.NONE }.distinct().map { a ->
                CustomAccessibilityAction(swipeLabel(res, a)) { onAction(a); true }
            }
        },
        enableDismissFromStartToEnd = towardsEnd != SwipeAction.NONE,
        enableDismissFromEndToStart = towardsStart != SwipeAction.NONE,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onAction(towardsEnd)
                SwipeToDismissBoxValue.EndToStart -> onAction(towardsStart)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { state.reset() }
        },
        backgroundContent = {
            val dir = state.dismissDirection
            val action = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> towardsEnd
                SwipeToDismissBoxValue.EndToStart -> towardsStart
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            if (action != SwipeAction.NONE) {
                val (bg, fg) = action.colors()
                Row(
                    Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
                    horizontalArrangement = if (dir == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(action.icon(), null, tint = fg)
                    Text(swipeLabel(res, action, short = true), color = fg, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp).width(120.dp))
                }
            }
        },
    ) { content() }
}

/** Localised name of a swipe action; [short] drops the "(with undo)" part for the swipe background. */
internal fun swipeLabel(res: Resources, a: SwipeAction, short: Boolean = false): String = res.getString(
    when (a) {
        SwipeAction.NONE -> R.string.swipe_none
        SwipeAction.CALL -> R.string.swipe_call
        SwipeAction.MESSAGE -> R.string.swipe_message
        SwipeAction.MESSAGE_ON -> R.string.swipe_message_on
        SwipeAction.BLOCK -> R.string.swipe_block
        SwipeAction.DELETE -> if (short) R.string.swipe_delete_short else R.string.swipe_delete
    },
)
