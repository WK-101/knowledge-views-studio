package app.parley.telecom.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.common.calls.AutoAnswer
import app.parley.telecom.CallManager
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.ui.ParleyMotion
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import app.parley.ui.tabular
import kotlinx.coroutines.delay

/**
 * "Answering in 3 seconds · Cancel" above the answer controls while a ringing call is armed for auto-answer
 * (Settings › Calls › Answer automatically). A small overlay of its own, so the incoming screen itself is unchanged;
 * TalkBack hears the countdown politely and Cancel is a full-size button.
 */
@Composable
internal fun AutoAnswerCountdown(call: CallUi) {
    val deadline = call.autoAnswerAt
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (deadline != 0L) {
            now = SystemClock.elapsedRealtime()
            if (now >= deadline) break
            delay(TICK_MS)
        }
    }
    val left = if (deadline == 0L) 0 else AutoAnswer.secondsLeft(deadline, now)
    AnimatedVisibility(
        visible = deadline != 0L && left > 0,
        enter = expandVertically(ParleyMotion.spatial()) + fadeIn(ParleyMotion.effects()),
        exit = shrinkVertically(ParleyMotion.fastSpatial()) + fadeOut(ParleyMotion.fastEffects()),
    ) {
        val scheme = MaterialTheme.colorScheme
        val text = pluralStringResource(R.plurals.call_auto_answer_in, left.coerceAtLeast(1), left.coerceAtLeast(1))
        Surface(
            color = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            shape = ParleyShapes.pill,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Timer, null)
                Spacer(Modifier.width(Spacing.s))
                Text(text, style = MaterialTheme.typography.titleSmall.tabular(), modifier = Modifier.weight(1f))
                val cancelDesc = stringResource(R.string.call_auto_answer_cancel_desc)
                FilledTonalButton({ CallManager.cancelAutoAnswer(call.id) }, Modifier.semantics { contentDescription = cancelDesc }) {
                    Text(stringResource(R.string.call_auto_answer_cancel))
                }
            }
        }
    }
}

private const val TICK_MS = 200L

/** The caller's name in their own language ("Иван Петров") under their name on the call screen; nothing for null. */
@Composable
internal fun CallerNativeName(name: String?) {
    if (name == null) return
    Text(
        name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** The caller's pronouns ("she/her") under their name on the call screen, quiet like the lines below it. */
@Composable
internal fun CallerPronouns(pronouns: String) {
    Text(
        pronouns, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, modifier = Modifier.padding(top = Spacing.xxs),
    )
}
