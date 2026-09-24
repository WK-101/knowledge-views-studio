package app.parley.telecom.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import android.content.res.Resources
import app.parley.telecom.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parley.telecom.CallClock
import app.parley.telecom.CallTiming
import app.parley.telecom.CallUi
import app.parley.ui.Bidi
import kotlinx.coroutines.delay

/** The caller's name, or their number kept left to right in right-to-left languages (L3). */
internal val CallUi.displayTitle: String get() = if (name == null) Bidi.ltr(title) else title

/** "12:05" or "1:02:05". */
internal fun clockText(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Spoken form, "12 minutes 5 seconds". */
internal fun spokenDuration(res: Resources, totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    val h = (s / 3600).toInt()
    val m = ((s % 3600) / 60).toInt()
    val sec = (s % 60).toInt()
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.duration_hours, it, it) },
        m.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.duration_minutes, it, it) },
        sec.takeIf { it > 0 || (h == 0 && m == 0) }?.let { res.getQuantityString(R.plurals.duration_seconds, it, it) },
    ).joinToString(" ")
}

/** The monotonic clock, refreshed twice a second while shown (UI only; notifications never tick). */
@Composable
internal fun rememberElapsedNow(): State<Long> = produceState(SystemClock.elapsedRealtime()) {
    while (true) {
        value = SystemClock.elapsedRealtime()
        delay(500)
    }
}

/** Seconds since the call connected, ticking while shown. */
@Composable
internal fun rememberCallSeconds(connectTimeMillis: Long): State<Long> = produceState(0L, connectTimeMillis) {
    while (true) {
        value = if (connectTimeMillis > 0) (System.currentTimeMillis() - connectTimeMillis) / 1000 else 0
        delay(500)
    }
}

/**
 * Remaining-time ring around the caller's photo (T5). Shows only for calls that will be ended; turns to the
 * error colour once the warning time is reached.
 */
@Composable
internal fun CallTimeRing(timing: CallTiming?, size: Dp, content: @Composable () -> Unit) {
    val cd = timing?.countdown
    val end = cd?.endAt
    if (cd == null || end == null) {
        content()
        return
    }
    val now by rememberElapsedNow()
    val total = (end - cd.startElapsed).coerceAtLeast(1)
    val left = (end - now).coerceAtLeast(0)
    val warn = cd.warnAt?.let { now >= it } == true
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size + 16.dp)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * left / total, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

/** "12:31 left · Limit for Ana", or the allowance note (T5, T6). */
@Composable
internal fun RemainingLine(timing: CallTiming?) {
    val cd = timing?.countdown ?: return
    val now by rememberElapsedNow()
    val left = cd.remainingMs(now)
    val scheme = MaterialTheme.colorScheme
    val res = LocalResources.current
    val sep = res.getString(R.string.tc_separator)
    val text: String
    val spoken: String
    val color: Color
    when {
        left != null -> {
            val warn = cd.warnAt?.let { now >= it } == true
            text = listOfNotNull(res.getString(R.string.calltime_left, clockText(left / 1000)), timing.source).joinToString(sep)
            spoken = listOfNotNull(res.getString(R.string.calltime_left, spokenDuration(res, left / 1000)), timing.source).joinToString(", ")
            color = if (warn) scheme.error else scheme.onSurfaceVariant
        }
        timing.quotaUsed -> {
            text = res.getString(R.string.calltime_allowance_used)
            spoken = text
            color = scheme.error
        }
        cd.dontEnd -> {
            text = res.getString(R.string.calltime_limit_off)
            spoken = text
            color = scheme.onSurfaceVariant
        }
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.padding(top = 4.dp).semantics { contentDescription = spoken },
    )
}

/**
 * The in-call "More" sheet (T2): wrap-up controls (+2 / +5 min, End in 1 min, Don't end) and call notes.
 * In supervised mode a limit can only be shortened.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CallMoreSheet(call: CallUi, timing: CallTiming?, onDismiss: () -> Unit, onNote: () -> Unit, onOpenContact: (() -> Unit)?) {
    val cd = timing?.countdown
    val now by rememberElapsedNow()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.calltime_title), style = MaterialTheme.typography.titleMedium)
            val sep = stringResource(R.string.tc_separator)
            val status = when {
                cd?.endAt != null -> stringResource(R.string.calltime_ends_in, clockText((cd.remainingMs(now) ?: 0) / 1000)) + (timing.source?.let { sep + it } ?: "")
                cd?.dontEnd == true -> stringResource(R.string.calltime_wont_end)
                else -> stringResource(R.string.calltime_no_limit)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canExtend = cd?.endAt != null && cd.canExtend
                if (canExtend) {
                    AssistChip(onClick = { CallClock.extend(call.id, 2) }, label = { Text(stringResource(R.string.calltime_plus_2)) }, leadingIcon = { Icon(Icons.Rounded.Timer, null) })
                    AssistChip(onClick = { CallClock.extend(call.id, 5) }, label = { Text(stringResource(R.string.calltime_plus_5)) }, leadingIcon = { Icon(Icons.Rounded.Timer, null) })
                }
                AssistChip(onClick = { CallClock.endIn(call.id, 1); onDismiss() }, label = { Text(stringResource(R.string.calltime_end_in_1)) }, leadingIcon = { Icon(Icons.Rounded.Timer, null) })
                if (canExtend) {
                    AssistChip(onClick = { CallClock.keepGoing(call.id); onDismiss() }, label = { Text(stringResource(R.string.calltime_dont_end)) }, leadingIcon = { Icon(Icons.Rounded.TimerOff, null) })
                }
            }
            if (cd?.endAt != null && !cd.canExtend) {
                Text(
                    stringResource(R.string.calltime_supervised),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ListItem(
            headlineContent = { Text(stringResource(R.string.incall_add_note)) },
            supportingContent = { Text(stringResource(R.string.calltime_note_saved)) },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Notes, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onDismiss(); onNote() },
        )
        if (onOpenContact != null) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.incall_open_contact)) },
                leadingContent = { Icon(Icons.Rounded.Person, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onDismiss(); onOpenContact() },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
