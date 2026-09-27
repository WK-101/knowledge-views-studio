package app.parley.ui.contact

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.ReachGroup
import app.parley.common.ReachKind
import app.parley.common.ReachRow
import app.parley.common.people.MessengerPrefs
import app.parley.ui.Bidi
import app.parley.ui.SegmentedGroup
import app.parley.ui.SegmentedGroupScope

/**
 * V34: "Reach via apps": one row per messenger (and per number when the person has several) with the actions that
 * app added for them: Message, Voice, Video. Tapping opens the app's own row; long-press makes it the usual way
 * (the Message, Call and Video buttons at the top then use it). Only apps that registered this person show up, so
 * nothing here pretends a call is possible.
 *
 * The contact page hosts it with [reachViaAppsRows] inside a group, or [ReachViaApps] as a group of its own.
 */
@Composable
fun ReachViaApps(
    groups: List<ReachGroup>,
    prefs: MessengerPrefs,
    showNumbers: Boolean,
    onOpen: (ReachRow) -> Unit,
    onToggleUsual: (ReachRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return
    SegmentedGroup(stringResource(R.string.v34msg_reach_title), modifier) {
        reachViaAppsRows(groups, prefs, showNumbers, onOpen, onToggleUsual)
    }
}

/** [ReachViaApps]'s rows, for a group that also holds other rows (typed-in handles). */
fun SegmentedGroupScope.reachViaAppsRows(
    groups: List<ReachGroup>,
    prefs: MessengerPrefs,
    showNumbers: Boolean,
    onOpen: (ReachRow) -> Unit,
    onToggleUsual: (ReachRow) -> Unit,
) {
    groups.forEach { g ->
        item("reach_${g.appKey}_${g.number.orEmpty()}") { ReachAppRow(g, prefs, showNumbers, onOpen, onToggleUsual) }
    }
}

/** Whether [row] is the remembered way for its kind. */
fun MessengerPrefs.isUsual(row: ReachRow): Boolean = when (row.kind) {
    ReachKind.MESSAGE -> message == row.appKey
    ReachKind.VOICE -> call == row.appKey
    ReachKind.VIDEO -> video == row.appKey
    else -> false
}

/** [this] with [row] made the usual way for its kind, or no longer the usual one when it was. */
fun MessengerPrefs.toggleUsual(row: ReachRow): MessengerPrefs {
    val on = !isUsual(row)
    val v = row.appKey.takeIf { on }
    return when (row.kind) {
        ReachKind.MESSAGE -> copy(message = v)
        ReachKind.VOICE -> copy(call = v)
        ReachKind.VIDEO -> copy(video = v)
        else -> this
    }
}

@Composable
private fun ReachAppRow(g: ReachGroup, prefs: MessengerPrefs, showNumber: Boolean, onOpen: (ReachRow) -> Unit, onToggleUsual: (ReachRow) -> Unit) {
    val sep = stringResource(R.string.main_separator)
    val can = listOfNotNull(
        g.message?.let { stringResource(R.string.v34msg_can_message) },
        g.voice?.let { stringResource(R.string.v34msg_can_voice) },
        g.video?.let { stringResource(R.string.v34msg_can_video) },
    ).joinToString(sep)
    val usual = listOfNotNull(
        g.message?.takeIf { prefs.isUsual(it) }?.let { stringResource(R.string.v34msg_usual_message) },
        g.voice?.takeIf { prefs.isUsual(it) }?.let { stringResource(R.string.v34msg_usual_voice) },
        g.video?.takeIf { prefs.isUsual(it) }?.let { stringResource(R.string.v34msg_usual_video) },
    )
    val sub = listOfNotNull(g.number?.takeIf { showNumber }?.let { Bidi.ltr(it) } ?: can.takeIf { it.isNotEmpty() }).plus(usual).joinToString(sep)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppBadge(g.appLabel)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(g.appLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            g.message?.let { r -> ReachActionButton(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.v34msg_message_on_app, g.appLabel), prefs.isUsual(r), { onOpen(r) }, { onToggleUsual(r) }) }
            g.voice?.let { r -> ReachActionButton(Icons.Rounded.Call, stringResource(R.string.v34msg_voice_on_app, g.appLabel), prefs.isUsual(r), { onOpen(r) }, { onToggleUsual(r) }) }
            g.video?.let { r -> ReachActionButton(Icons.Rounded.Videocam, stringResource(R.string.v34msg_video_on_app, g.appLabel), prefs.isUsual(r), { onOpen(r) }, { onToggleUsual(r) }) }
        }
    }
}

/** A round letter badge for an app (no brand logos: Parley can't see other apps' icons and doesn't ship them). */
@Composable
fun AppBadge(label: String, modifier: Modifier = Modifier) {
    val letter = label.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    Box(
        modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * A 48 dp round action: tonal, or filled with the primary colour when it's the usual way. Long-press (when
 * [onLongClick] is set) toggles "usual".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReachActionButton(icon: ImageVector, description: String, usual: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val container by animateColorAsState(
        if (usual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        spring(stiffness = Spring.StiffnessMediumLow), label = "reachContainer",
    )
    val content by animateColorAsState(
        if (usual) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        spring(stiffness = Spring.StiffnessMediumLow), label = "reachContent",
    )
    val longLabel = stringResource(if (usual) R.string.v34msg_stop_usual else R.string.v34msg_make_usual)
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(container)
            .combinedClickable(
                role = Role.Button,
                indication = ripple(),
                interactionSource = null,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) longLabel else null,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = content)
    }
}
