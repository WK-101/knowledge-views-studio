package app.parley.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What a [ParleyTag] says about what it marks: a plain fact, something to know, or something to be careful about. */
enum class TagTone { NEUTRAL, INFO, WARN }

/** The tags' look, in one place: one height, one shape and one type size for every marker. */
object ParleyTagTokens {
    /** A small tag beside a name (from the network, usual); a [ParleyTag] with an action grows to [actionHeight]. */
    val height: Dp = 20.dp
    val actionHeight: Dp = 32.dp
    val iconSize: Dp = 14.dp
    val actionIconSize: Dp = 18.dp
    val horizontalPadding: Dp = 6.dp
}

/**
 * A marker beside a name or on a page header ("From the network", "Usual", "Private", "Temporary · 3 days",
 * "Archived"): one look for all of them, so they read as the same kind of thing. [outlined] is the quiet form, a line
 * around the words; otherwise it is tonal, coloured by [tone]. With [onClick] it is a 48 dp target that says what it
 * means ([description], also what TalkBack reads instead of [text]).
 */
@Composable
fun ParleyTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.NEUTRAL,
    outlined: Boolean = false,
    icon: ImageVector? = null,
    description: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when {
        outlined -> Color.Transparent to cs.onSurfaceVariant
        tone == TagTone.INFO -> cs.secondaryContainer to cs.onSecondaryContainer
        tone == TagTone.WARN -> cs.errorContainer to cs.onErrorContainer
        else -> cs.surfaceContainerHigh to cs.onSurfaceVariant
    }
    val border = if (outlined) BorderStroke(1.dp, cs.outline) else null
    val described = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    val big = onClick != null
    val body: @Composable () -> Unit = {
        Row(
            Modifier.heightIn(min = if (big) ParleyTagTokens.actionHeight else ParleyTagTokens.height)
                .padding(horizontal = if (big) Spacing.s else ParleyTagTokens.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(if (big) ParleyTagTokens.actionIconSize else ParleyTagTokens.iconSize))
            Text(
                text, style = if (big) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (big) Modifier.padding(end = Spacing.xs) else Modifier,
            )
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick, shape = ParleyShapes.pill, color = container, contentColor = content, border = border,
            modifier = modifier.minimumInteractiveComponentSize().then(described),
        ) { body() }
    } else {
        Surface(shape = ParleyShapes.tag, color = container, contentColor = content, border = border, modifier = modifier.then(described)) { body() }
    }
}
