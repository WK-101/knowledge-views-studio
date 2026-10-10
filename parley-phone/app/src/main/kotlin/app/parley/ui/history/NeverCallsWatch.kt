package app.parley.ui.history

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.calls.NeverCallsYouFacts
import app.parley.common.calls.NeverCallsYou
import app.parley.common.catching
import app.parley.ui.Spacing

/**
 * On a saved organisation's number history, beside "First call from them to you": whether Parley would warn about a
 * call faking this number ("This number never calls you"), and when it can't yet, from when it can and what to do.
 * Nothing for anyone else. [calls] re-reads it as calls come in.
 */
@Composable
fun NeverCallsWatchLine(vm: AppViewModel, number: String, calls: Int?) {
    val watch by produceState<NeverCallsYou.Watch?>(null, number, calls) {
        value = catching { NeverCallsYouFacts.watch(vm.c, number, vm.countryIso) }.getOrNull()
    }
    val w = watch ?: return
    val context = LocalContext.current
    val text = when (w) {
        NeverCallsYou.Watch.Chosen -> stringResource(R.string.never_calls_watch_chosen)
        NeverCallsYou.Watch.Armed -> stringResource(R.string.never_calls_watch_armed)
        is NeverCallsYou.Watch.From -> stringResource(
            R.string.never_calls_watch_from, DateUtils.formatDateTime(context, w.since, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
        )
        NeverCallsYou.Watch.NoCopy -> stringResource(R.string.never_calls_watch_off)
    }
    val armed = w == NeverCallsYou.Watch.Chosen || w == NeverCallsYou.Watch.Armed
    Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s), verticalAlignment = Alignment.Top) {
        Icon(
            if (armed) Icons.Rounded.VerifiedUser else Icons.Rounded.GppMaybe, null,
            Modifier.padding(end = Spacing.m).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
