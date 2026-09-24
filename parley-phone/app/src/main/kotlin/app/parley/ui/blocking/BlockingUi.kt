package app.parley.ui.blocking

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.parley.common.BlockAction
import app.parley.common.NotifyLevel

/** A switch row whose whole line is the touch target. */
@Composable
fun ToggleRow(title: String, help: String?, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, role = Role.Switch) { onChange(!value) },
        headlineContent = { Text(title) },
        supportingContent = help?.let { { Text(it) } },
        trailingContent = { Switch(value, onChange, enabled = enabled) },
    )
}

/**
 * Collapsed section with summary chips (SpamBlocker's good idea without its endless page): the header tells
 * you what's on, the body opens on tap.
 */
@Composable
fun CollapsibleSection(title: String, help: String, summary: List<String>, expanded: Boolean, onToggle: () -> Unit, icon: ImageVector? = null, content: @Composable () -> Unit) {
    // U2: each section is one inset card (M3 Expressive grouped surfaces).
    BlockingCard {
        ListItem(
            modifier = Modifier
                .clickable(onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } },
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                Column {
                    Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (summary.isNotEmpty()) SummaryChips(summary, Modifier.padding(top = 6.dp))
                }
            },
            trailingContent = { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) },
        )
        AnimatedVisibility(expanded) { Column { content() } }
    }
}

/** U2: an inset rounded card for a group of Blocking rows; shared rows blend into it. */
@Composable
fun BlockingCard(shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), vertical: androidx.compose.ui.unit.Dp = 6.dp, content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        shape = shape, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = vertical),
    ) { app.parley.ui.OnGroupSurface { Column { content() } } }
}

@Composable
fun SummaryChips(items: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { t ->
            AssistChip(
                onClick = {}, label = { Text(t, style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                border = null, modifier = Modifier.semantics { contentDescription = t },
            )
        }
    }
}

@Composable
fun ActionChoice(value: BlockAction, onChange: (BlockAction) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        BlockAction.entries.forEachIndexed { i, a ->
            SegmentedButton(value == a, { onChange(a) }, SegmentedButtonDefaults.itemShape(i, BlockAction.entries.size)) {
                Text(if (a == BlockAction.REJECT) "Reject" else "Silence")
            }
        }
    }
}

fun notifyLabel(n: NotifyLevel) = when (n) {
    NotifyLevel.DEFAULT -> "Default"
    NotifyLevel.NONE -> "None"
    NotifyLevel.QUIET -> "Quiet"
    NotifyLevel.NORMAL -> "Normal"
}

@Composable
fun NotifyChoice(value: NotifyLevel, allowDefault: Boolean, onChange: (NotifyLevel) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        NotifyLevel.entries.filter { allowDefault || it != NotifyLevel.DEFAULT }.forEach { n ->
            FilterChip(value == n, { onChange(n) }, label = { Text(notifyLabel(n)) })
        }
    }
}

fun ringtoneTitle(context: Context, uri: String?): String? =
    uri?.let { u -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(u))?.getTitle(context) }.getOrNull() }

/** System ringtone picker; [onPicked] gets null for "Default". */
@Composable
fun rememberRingtonePicker(onPicked: (String?) -> Unit): (current: String?) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            onPicked(uri?.toString())
        }
    }
    return { current ->
        launcher.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let(Uri::parse)),
        )
    }
}

fun ago(millis: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()

fun leftText(ms: Long): String {
    val min = ((ms + 59_999) / 60_000).toInt()
    return when {
        min >= 24 * 60 -> "${min / (24 * 60)} d ${(min / 60) % 24} h"
        min >= 60 -> "${min / 60} h ${min % 60} min"
        else -> "$min min"
    }
}

/** Help lines under each feature, with a real use case (not a mechanism). */
object Help {
    const val ALLOW = "Numbers that always ring, even when strict mode is on. Example: your child's school, the plumber you're waiting for."
    const val BLOCK = "Your own rules. Example: block numbers starting with +1 900, or silence one pushy caller."
    const val LISTS = "Offline spam lists you add yourself. Parley looks numbers up on the phone; nothing is sent anywhere."
    const val OFF_HOURS = "Only the people who matter ring at night or at weekends. Everyone else is silenced and shows as a missed call."
    const val MORE = "Extra checks for tricks spammers use: numbers that look like yours, spoofed caller ID, numbers that can't exist."
    const val SOUNDS = "Know who it is before you look: a different ringtone for repeat callers or likely spam, full volume for favourites."
    const val EMERGENCY = "After you call emergency services, nothing is blocked for an hour so they can call you back."
    const val TOOLS = "Try a number, see what your rules would have done last week, import a list or share yours."
    const val LOG = "Calls Parley stopped. Tap one to see why; mark mistakes as \"Not spam\"."
}
