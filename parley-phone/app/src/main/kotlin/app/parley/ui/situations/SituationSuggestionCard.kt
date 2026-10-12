package app.parley.ui.situations

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.Schedule
import app.parley.common.situations.Situation
import app.parley.common.situations.SituationKind
import app.parley.common.situations.SituationSuggestion
import app.parley.common.situations.SuggestionKind
import app.parley.situations.SituationSuggester
import app.parley.situations.SituationTriggers
import app.parley.ui.Destination
import app.parley.ui.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * A Situation that suggests itself, on the home screen ([SituationSuggester]): at most one card a week, from your own
 * calls. Set it up gives the Situation the time or the car kit and opens its page, where who may ring is chosen; Not
 * now and Don't suggest this end it. Not shown while a duress unlock hides things.
 */
@Composable
fun SituationSuggestionCard(vm: AppViewModel, open: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    var suggestion by remember { mutableStateOf<SituationSuggestion?>(null) }
    LaunchedEffect(Unit) { suggestion = SituationSuggester.find(context) }
    val s = suggestion?.takeIf { !privacy.hiding } ?: return
    val (title, text) = remember(s) { words(context, s) }
    fun answer(never: Boolean) {
        suggestion = null
        scope.launch(Dispatchers.IO) { SituationSuggester.answer(context, s.kind, never) }
    }
    Card(
        modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(situationIcon(kindOf(s)), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(Spacing.m))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = Spacing.s))
            Text(
                stringResource(R.string.sit_suggest_source), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(end = Spacing.s),
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.Center) {
                TextButton({ answer(never = true) }) { Text(stringResource(R.string.sit_suggest_never)) }
                TextButton({ answer(never = false) }) { Text(stringResource(R.string.label_not_now)) }
                Button({
                    suggestion = null
                    scope.launch {
                        if (SituationSuggester.accept(context, s)) open(SituationRoutes.Edit(s.situationId))
                    }
                }) { Text(stringResource(R.string.sit_suggest_accept)) }
            }
        }
    }
}

private fun kindOf(s: SituationSuggestion): SituationKind = when (s.kind) {
    SuggestionKind.CAR -> SituationKind.DRIVING
    SuggestionKind.NIGHT -> SituationKind.NIGHT
    SuggestionKind.MEETING -> SituationKind.MEETING
}

/** The card's title and text, naming the built-in Situation as its row does. */
private fun words(context: Context, s: SituationSuggestion): Pair<String, String> {
    val name = SituationTriggers.name(context, Situation(s.situationId, kindOf(s)))
    val schedule = s.schedule
    return when {
        s.kind == SuggestionKind.CAR -> {
            val device = s.device.orEmpty()
            context.getString(R.string.sit_suggest_car_title, device, name) to context.getString(R.string.sit_suggest_car_text, device, name)
        }
        schedule == null -> name to name
        s.kind == SuggestionKind.NIGHT -> {
            val from = Schedule.hm(schedule.startMinute)
            context.getString(R.string.sit_suggest_night_title, name, from) to
                context.getString(R.string.sit_suggest_night_text, from, name, Schedule.hm(schedule.endMinute))
        }
        else -> {
            val day = DayOfWeek.entries.firstOrNull { schedule.hasDay(it) }?.getDisplayName(TextStyle.FULL, Locale.getDefault()).orEmpty()
            context.getString(R.string.sit_suggest_meeting_title, name, day, Schedule.hm(schedule.startMinute), Schedule.hm(schedule.endMinute)) to
                context.getString(R.string.sit_suggest_meeting_text, name)
        }
    }
}
