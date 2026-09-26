package app.parley.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.common.CallEntry
import app.parley.common.ux.CallClass
import app.parley.common.ux.CallGlance
import app.parley.common.ux.RecentsStyle
import app.parley.ui.CallClassBadge
import app.parley.ui.CallDurationBar
import app.parley.ui.CallTypeColors
import kotlinx.coroutines.flow.MutableStateFlow

/** R4 (v3.3): Rich or Simple call rows, from Settings › Recents style (provided by ParleyRoot). */
val LocalRecentsStyle = staticCompositionLocalOf { RecentsStyle.RICH }

/** Whether call rows use the rich look. */
@Composable
fun richCalls(): Boolean = LocalRecentsStyle.current == RecentsStyle.RICH

/** The words for a call class: "Missed call", "No answer"… (TalkBack reads these; the legend shows them). */
@StringRes
fun callClassLabel(cls: CallClass): Int = when (cls) {
    CallClass.MISSED -> R.string.hist_type_missed
    CallClass.DECLINED -> R.string.hist_type_rejected
    CallClass.INCOMING -> R.string.hist_type_incoming
    CallClass.ANSWERED_ELSEWHERE -> R.string.hist_type_answered_elsewhere
    CallClass.VOICEMAIL -> R.string.hist_type_voicemail
    CallClass.OUTGOING -> R.string.hist_type_outgoing
    CallClass.NO_ANSWER -> R.string.v33_class_no_answer
    CallClass.BLOCKED -> R.string.hist_type_blocked
    CallClass.UNKNOWN -> R.string.hist_type_unknown
}

/** What each badge means, for the legend. */
@StringRes
private fun callClassMeaning(cls: CallClass): Int = when (cls) {
    CallClass.MISSED -> R.string.v33_legend_missed
    CallClass.DECLINED -> R.string.v33_legend_declined
    CallClass.INCOMING -> R.string.v33_legend_incoming
    CallClass.ANSWERED_ELSEWHERE -> R.string.v33_legend_elsewhere
    CallClass.VOICEMAIL -> R.string.v33_legend_voicemail
    CallClass.OUTGOING -> R.string.v33_legend_outgoing
    CallClass.NO_ANSWER -> R.string.v33_legend_no_answer
    CallClass.BLOCKED -> R.string.v33_legend_blocked
    CallClass.UNKNOWN -> R.string.v33_legend_unknown
}

/** The two Recents styles' names, in [RecentsStyle] order. */
@Composable
fun recentsStyleLabels(): List<String> = listOf(stringResource(R.string.v33_style_rich), stringResource(R.string.v33_style_simple))

/**
 * R4: a thin bar in the call's colour along the row's leading edge (right in right-to-left languages), drawn over the
 * row's own background.
 */
fun Modifier.callAccent(color: Color): Modifier = drawWithContent {
    drawContent()
    val w = 4.dp.toPx()
    val inset = 10.dp.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
    drawRoundRect(color, topLeft = Offset(x, inset), size = Size(w, (size.height - 2 * inset).coerceAtLeast(w)), cornerRadius = CornerRadius(w / 2, w / 2))
}

/** R4: "3×" coloured by the row's latest call, next to the name of a row with several calls. */
@Composable
fun CallCountChip(count: Int, latest: CallClass) {
    val color = CallTypeColors.of(latest.hue)
    Surface(
        color = color.copy(alpha = 0.14f), contentColor = color, shape = RoundedCornerShape(8.dp),
        // The row's sequence dots say the count in words.
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        Text(stringResource(R.string.v33_count, count), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** R4: "3 calls: missed call, missed call, outgoing call", read for a row's sequence dots. */
@Composable
fun sequenceDescription(total: Int, classes: List<CallClass>): String {
    val words = classes.map { stringResource(callClassLabel(it)) }.joinToString(", ")
    return pluralStringResource(R.plurals.v33_calls_sequence, total, total, words)
}

/** R4: the Call back pill that replaces the call icon on a missed call not returned yet. */
@Composable
fun CallBackPill(who: String, onClick: () -> Unit) {
    val color = CallTypeColors.of(CallClass.MISSED.hue)
    val label = stringResource(R.string.v33_call_back_who, who)
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = color.copy(alpha = 0.16f), contentColor = color),
        modifier = Modifier.heightIn(min = 36.dp).semantics { contentDescription = label },
    ) {
        Icon(Icons.Rounded.Call, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.v33_call_back), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * R4: the trailing part of a call row in history lists (number history, contact timeline, private contacts): a
 * proportional bar and the length for a talked call, "No answer" for an outgoing call nobody picked up. Nothing in
 * the Simple style (those rows already say the length in words).
 */
@Composable
fun CallLengthGlance(e: CallEntry) {
    if (!richCalls()) return
    val cls = CallClass.of(e)
    when {
        cls.answered -> CallDurationBar(CallGlance.durationFraction(e.durationSec), cls)
        cls == CallClass.NO_ANSWER -> Text(stringResource(R.string.v33_class_no_answer), style = MaterialTheme.typography.labelMedium, color = CallTypeColors.of(cls.hue))
    }
}

/** A badge with its words, for a list of call classes (the legend). */
@Composable
private fun LegendRow(cls: CallClass) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CallClassBadge(cls, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(callClassLabel(cls)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(callClassMeaning(cls)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val legendRequested = MutableStateFlow(false)

/** R4: Recents ⋮ › "What do the colours mean?". */
@Composable
fun RecentsLegendMenuItem(closeMenu: () -> Unit) {
    DropdownMenuItem({ Text(stringResource(R.string.v33_legend_menu)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, null) }, onClick = {
        closeMenu()
        legendRequested.value = true
    })
}

/** Shows the legend when asked from the Recents ⋮ menu. */
@Composable
fun RecentsLegendHost() {
    val shown by legendRequested.collectAsStateWithLifecycle()
    if (!shown) return
    AlertDialog(
        onDismissRequest = { legendRequested.value = false },
        title = { Text(stringResource(R.string.v33_legend_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CallClass.entries.filter { it != CallClass.UNKNOWN }.forEach { LegendRow(it) }
                Text(
                    stringResource(R.string.v33_legend_footer),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton({ legendRequested.value = false }) { Text(stringResource(R.string.main_close)) } },
    )
}
