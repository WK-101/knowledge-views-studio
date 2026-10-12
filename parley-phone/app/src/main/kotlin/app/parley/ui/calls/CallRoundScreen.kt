// The feature's destination and its graph live together with its screen.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.CallRound
import app.parley.common.calls.CallRounds
import app.parley.common.calls.RoundMark
import app.parley.common.calls.RoundPerson
import app.parley.common.who.Who
import app.parley.common.who.WhoPlace
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.ListSectionHeader
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleyTopBar
import app.parley.ui.PrivateMarked
import app.parley.ui.Spacing
import app.parley.ui.appVm
import app.parley.ui.avatarSize
import app.parley.ui.situations.WhoRow
import app.parley.ui.situations.rememberLabelRows
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** The call round's destination. */
object CallRoundRoutes {
    @Serializable data object Round : Destination
}

/** The call round (from Tools, and from Contacts' selection: "Start a call round"). */
fun NavGraphBuilder.callRoundGraph(nav: NavController) {
    composable<CallRoundRoutes.Round> { CallRoundScreen(appVm(), back = { nav.popBackStack() }) }
}

/**
 * A call round: one thing to tell several people. Before it starts: what to say and who (Favourites, Your Circle, a
 * label, or people chosen in Contacts). Under way: the one thing to say, who's next with Call (Parley never calls by
 * itself), Told them · No answer · Later, and everyone with their mark. The one thing to say also shows on the call
 * screen while you call them (never while the phone is locked); a duress unlock hides it here and there, like notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallRoundScreen(vm: AppViewModel, back: () -> Unit) {
    val ui = vm.callRound
    LaunchedEffect(ui) { ui.load() }
    val round by remember(ui) { ui.shown }.collectAsStateWithLifecycle(CallRound())
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    var ending by rememberSaveable { mutableStateOf(false) }
    ParleyScaffold(
        topBar = {
            ParleyTopBar(stringResource(R.string.round_title), onBack = back, actions = {
                if (round.active) TextButton({ ending = true }) { Text(stringResource(R.string.round_end)) }
            })
        },
    ) { p ->
        Box(Modifier.fillMaxSize().padding(p)) {
            if (round.active) RoundUnderWay(vm, round, messageShown = privacy.notesShown) else RoundSetup(vm)
        }
    }
    if (ending) {
        ConfirmDialog(
            title = stringResource(R.string.round_end_title),
            text = stringResource(R.string.round_end_body),
            confirmLabel = stringResource(R.string.round_end),
            onConfirm = {
                ending = false
                ui.clear()
            },
            onDismiss = { ending = false },
        )
    }
}

/** What to say and who to call; Start the round. */
@Composable
private fun RoundSetup(vm: AppViewModel) {
    val ui = vm.callRound
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val chosen by ui.chosen.collectAsStateWithLifecycle()
    val everyone by vm.everyone.collectAsStateWithLifecycle()
    val circle by vm.circle.rows.collectAsStateWithLifecycle()
    val index by vm.people.index.collectAsStateWithLifecycle()
    val labels = rememberLabelRows(vm)
    var message by rememberSaveable { mutableStateOf("") }
    var who by remember { mutableStateOf<Who?>(null) }
    val picked = remember(chosen, who, everyone, circle, index) {
        if (chosen.isNotEmpty()) {
            chosen
        } else {
            val w = who ?: return@remember emptyList()
            val circleKeys = circle.orEmpty().map { it.contact.lookupKey }.toSet()
            CallRounds.pick(w, everyone.orEmpty(), circleKeys) { id -> index.extras[id]?.labels.orEmpty() }.mapNotNull(CallRounds::personOf)
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "intro") {
            Text(
                stringResource(R.string.round_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
            )
        }
        item(key = "message") {
            OutlinedTextField(
                message, { message = it.take(CallRounds.MAX_MESSAGE) },
                label = { Text(stringResource(R.string.round_message_label)) },
                placeholder = { Text(stringResource(R.string.round_message_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
        }
        item(key = "who") {
            if (chosen.isNotEmpty()) {
                ParleyListItem(
                    leadingContent = { Icon(Icons.Rounded.Groups, null) },
                    headlineContent = { Text(stringResource(R.string.round_who)) },
                    supportingContent = {
                        val count = pluralStringResource(R.plurals.round_people_count, chosen.size, chosen.size)
                        Text(stringResource(R.string.format_dot_pair, count, stringResource(R.string.round_who_chosen_sub)))
                    },
                    trailingContent = { TextButton({ ui.chosen.value = emptyList() }) { Text(stringResource(R.string.action_clear)) } },
                )
            } else {
                val count = if (who == null) null else pluralStringResource(R.plurals.round_people_count, picked.size, picked.size)
                WhoRow(
                    stringResource(R.string.round_who), count, Icons.Rounded.Groups, who, WhoPlace.ROUND, labels.keys,
                    none = stringResource(R.string.round_nobody),
                ) { who = it }
                Text(
                    stringResource(R.string.round_choose_in_contacts), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xs),
                )
            }
        }
        item(key = "start") {
            Button(
                onClick = {
                    scope.launch {
                        if (!ui.start(message, picked)) vm.toast(res.getString(R.string.round_couldnt_start))
                    }
                },
                enabled = message.isNotBlank() && picked.isNotEmpty(),
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.l).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.round_start)) }
        }
    }
}

/** The round under way: the message, the score, who's next and everyone. */
@Composable
private fun RoundUnderWay(vm: AppViewModel, round: CallRound, messageShown: Boolean) {
    val ui = vm.callRound
    val score = CallRounds.score(round)
    val next = CallRounds.next(round)
    LazyColumn(Modifier.fillMaxSize()) {
        if (messageShown) {
            item(key = "message") {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = ParleyShapes.card,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                ) {
                    Column(Modifier.padding(Spacing.l)) {
                        Text(stringResource(R.string.round_message_label), style = MaterialTheme.typography.labelLarge)
                        Text(round.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
            }
        }
        item(key = "score") {
            val parts = listOfNotNull(
                stringResource(R.string.round_score, score.reached, score.total),
                score.noAnswer.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.round_score_no_answer, it, it) },
                score.later.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.round_score_later, it, it) },
            )
            Text(
                parts.joinToString(" · "), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
            )
        }
        item(key = "next") {
            if (next == null) AllTold(ui) else NextCard(vm, next)
        }
        item(key = "h-everyone") {
            ListSectionHeader(stringResource(R.string.round_everyone), Modifier.fillMaxWidth().semantics { heading() }, top = Spacing.m)
        }
        items(round.people, key = { it.key }) { person -> PersonRow(vm, person) }
    }
}

@Composable
private fun AllTold(ui: CallRoundUi) {
    Column(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.m)) {
        Text(stringResource(R.string.round_all_told), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.round_all_told_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs),
        )
        OutlinedButton({ ui.clear() }, Modifier.padding(top = Spacing.m).heightIn(min = 48.dp)) { Text(stringResource(R.string.round_end)) }
    }
}

/** "Next: Ben", Call (the only way a round's call starts), then how it went. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextCard(vm: AppViewModel, next: RoundPerson) {
    val ui = vm.callRound
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ParleyShapes.card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        Column(Modifier.padding(Spacing.l)) {
            Text(
                stringResource(R.string.round_next, next.name), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
            )
            if (next.mark != RoundMark.WAITING) {
                Text(
                    stringResource(R.string.round_try_again), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Bidi.ltr(next.number), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { vm.requestCall(next.number, next.name) },
                modifier = Modifier.padding(top = Spacing.m).heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Call, null)
                Text(
                    stringResource(R.string.round_call_named, next.name), modifier = Modifier.padding(start = Spacing.s),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.s)) {
                OutlinedButton({ ui.mark(next.key, RoundMark.REACHED) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.round_told)) }
                OutlinedButton({ markNoAnswer(vm, next) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.round_no_answer)) }
                OutlinedButton({ ui.mark(next.key, RoundMark.LATER) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.round_later)) }
            }
        }
    }
}

/** One person: their mark, Call, and ⋮ (Told them · No answer · Later · Add to To call after no answer · Take out of the round). */
@Composable
private fun PersonRow(vm: AppViewModel, person: RoundPerson) {
    val ui = vm.callRound
    var menu by remember { mutableStateOf(false) }
    ParleyListItem(
        leadingContent = { PrivateMarked(person.private) { Avatar(person.name, null, avatarSize()) } },
        headlineContent = { Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(markText(person.mark)) },
        trailingContent = {
            androidx.compose.foundation.layout.Row {
                IconButton({ vm.requestCall(person.number, person.name) }) {
                    Icon(Icons.Rounded.Call, stringResource(R.string.round_call_named, person.name), tint = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.round_more_for, person.name)) }
                    DropdownMenu(menu, { menu = false }) {
                        listOf(RoundMark.REACHED, RoundMark.NO_ANSWER, RoundMark.LATER).forEach { m ->
                            DropdownMenuItem({ Text(markText(m)) }, onClick = {
                                menu = false
                                if (m == RoundMark.NO_ANSWER) markNoAnswer(vm, person) else ui.mark(person.key, m)
                            })
                        }
                        if (person.mark == RoundMark.NO_ANSWER) {
                            DropdownMenuItem({ Text(stringResource(R.string.round_add_to_call)) }, onClick = { menu = false; addToCall(vm, person.number) })
                        }
                        DropdownMenuItem(
                            { Text(stringResource(R.string.round_take_out)) },
                            onClick = { menu = false; ui.remove(person.key) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun markText(mark: RoundMark): String = when (mark) {
    RoundMark.WAITING -> stringResource(R.string.round_waiting)
    RoundMark.REACHED -> stringResource(R.string.round_told)
    RoundMark.NO_ANSWER -> stringResource(R.string.round_no_answer)
    RoundMark.LATER -> stringResource(R.string.round_later)
}
