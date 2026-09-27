package app.parley.ui.contact

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
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
import app.parley.ui.ParleyMotion

/**
 * "Reach via apps": one row per messenger (and per number when the person has several) with the actions that
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
    SegmentedGroup(stringResource(R.string.reach_reach_title), modifier) {
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
        g.message?.let { stringResource(R.string.reach_can_message) },
        g.voice?.let { stringResource(R.string.reach_can_voice) },
        g.video?.let { stringResource(R.string.reach_can_video) },
    ).joinToString(sep)
    val usual = listOfNotNull(
        g.message?.takeIf { prefs.isUsual(it) }?.let { stringResource(R.string.reach_usual_message) },
        g.voice?.takeIf { prefs.isUsual(it) }?.let { stringResource(R.string.reach_usual_voice) },
        g.video?.takeIf { prefs.isUsual(it) }?.let { stringResource(R.string.reach_usual_video) },
    )
    val sub = listOfNotNull(g.number?.takeIf { showNumber }?.let { Bidi.ltr(it) } ?: can.takeIf { it.isNotEmpty() }).plus(usual).joinToString(sep)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppBadge(g.appLabel, packageName = g.appKey)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(g.appLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            g.message?.let { r -> ReachActionButton(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.reach_message_on_app, g.appLabel), prefs.isUsual(r), { onOpen(r) }, { onToggleUsual(r) }) }
            g.voice?.let { r -> ReachActionButton(Icons.Rounded.Call, stringResource(R.string.reach_voice_on_app, g.appLabel), prefs.isUsual(r), { onOpen(r) }, { onToggleUsual(r) }) }
            g.video?.let { r -> ReachActionButton(Icons.Rounded.Videocam, stringResource(R.string.reach_video_on_app, g.appLabel), prefs.isUsual(r), { onOpen(r) }, { onToggleUsual(r) }) }
        }
    }
}

/**
 * A round badge for an app: its own launcher icon when [packageName] is installed and visible to Parley (every
 * messenger Parley knows is listed in the manifest's `<queries>`), otherwise a letter badge. Parley ships no brand
 * logos. icons load off the main thread and are cached for the process.
 */
@Composable
fun AppBadge(label: String, modifier: Modifier = Modifier, packageName: String? = null) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }
    val icon by produceState(packageName?.let { AppIcons.cached(it, px) }, packageName, px) {
        if (packageName != null && value == null) value = withContext(Dispatchers.IO) { AppIcons.load(context, packageName, px) }
    }
    val loaded = icon
    if (loaded != null) {
        Image(loaded, null, modifier.size(40.dp).clip(CircleShape).clearAndSetSemantics {})
        return
    }
    val letter = label.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    Box(
        modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Installed apps' icons by package and size. A missing app isn't remembered, so it shows once installed. */
private object AppIcons {
    private val cache = LruCache<String, ImageBitmap>(32)

    fun cached(pkg: String, px: Int): ImageBitmap? = cache.get("$pkg@$px")

    fun load(context: Context, pkg: String, px: Int): ImageBitmap? {
        cached(pkg, px)?.let { return it }
        val bitmap = try {
            context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return null
        cache.put("$pkg@$px", bitmap)
        return bitmap
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
        ParleyMotion.effects(), label = "reachContainer",
    )
    val content by animateColorAsState(
        if (usual) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        ParleyMotion.effects(), label = "reachContent",
    )
    val longLabel = stringResource(if (usual) R.string.reach_stop_usual else R.string.reach_make_usual)
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
