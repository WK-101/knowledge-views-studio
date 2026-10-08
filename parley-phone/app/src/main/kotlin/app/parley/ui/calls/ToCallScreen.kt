// The feature's destinations and its graph live together with its screen.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.calls

import app.parley.ui.PrivateMarked
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallEntry
import app.parley.common.calls.ToCallKind
import app.parley.common.calls.ToCallState
import app.parley.common.ux.Tips
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.Destination
import app.parley.ui.EmptyState
import app.parley.ui.ListSectionHeader
import app.parley.ui.LocalSnackbar
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleyTopBar
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.activityViewModel
import app.parley.ui.appVm
import app.parley.ui.avatarSize
import app.parley.ui.common.CoachMark
import app.parley.ui.common.Format
import app.parley.ui.home.RecentsViewModel
import app.parley.telecom.ui.RemindTimes
import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.TimeZone

/** The To call list's destination. */
object ToCallRoutes {
    @Serializable data object List : Destination
}

/** The To call list (opened from the strip at the top of Recents and from its reminder). */
fun NavGraphBuilder.toCallGraph(nav: NavController) {
    composable<ToCallRoutes.List> { ToCallScreen(appVm(), back = { nav.popBackStack() }, open = { r -> nav.navigate(r) }) }
}

/**
 * The quiet strip at the top of Recents: "3 to call", with the first names; it folds to one line and never shows a
 * badge. Tap to open the list. Nothing at all when there is nothing to call.
 */
@Composable
fun ToCallStrip(open: (Destination) -> Unit) {
    val recents: RecentsViewModel = activityViewModel()
    val model = recents.toCall
    val rows by model.rows.collectAsStateWithLifecycle()
    val folded by model.folded.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.followCalls() }
    if (rows.isEmpty()) return
    val count = ToCall.count(rows.map { it.entry }, System.currentTimeMillis())
    val text = if (count.due > 0) {
        pluralStringResource(R.plurals.to_call_strip, count.due, count.due)
    } else {
        pluralStringResource(R.plurals.to_call_strip_later, count.later, count.later)
    }
    val names = rows.filter { count.due == 0 || it.entry.isDue(System.currentTimeMillis()) }.map { titleOf(it) }
    Column {
        CoachMark(Tips.TO_CALL, stringResource(R.string.to_call_tip))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = ParleyShapes.card,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs).animateContentSize(),
        ) {
            Row(
                Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.to_call_title)) { open(ToCallRoutes.List) }
                    .heightIn(min = 48.dp)
                    .padding(start = Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PhoneCallback, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f).padding(vertical = Spacing.s)) {
                    Text(text, style = MaterialTheme.typography.labelLarge)
                    if (!folded) {
                        Text(
                            names.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton({ model.setFolded(!folded) }) {
                    Icon(
                        if (folded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                        stringResource(if (folded) R.string.to_call_unfold else R.string.to_call_fold),
                    )
                }
            }
        }
    }
}

/** The name, or the number kept left to right. */
private fun titleOf(r: ToCallRow): String = r.name?.takeIf { it.isNotBlank() } ?: Bidi.ltr(r.number)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToCallScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val recents: RecentsViewModel = activityViewModel()
    val model = recents.toCall
    val rows by model.rows.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.followCalls() }
    val now = System.currentTimeMillis()
    val (due, later) = rows.partition { it.entry.isDue(now) }
    ParleyScaffold(topBar = { ParleyTopBar(stringResource(R.string.to_call_title), onBack = back) }) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p)) {
            if (rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.PhoneCallback, stringResource(R.string.to_call_empty), stringResource(R.string.to_call_empty_body),
                        Modifier.padding(top = Spacing.xxl),
                    )
                }
            }
            if (due.isNotEmpty() && later.isNotEmpty()) item(key = "h-now") { Header(stringResource(R.string.to_call_now)) }
            items(due, key = { "d" + it.entry.key }) { r -> ToCallRowItem(vm, model, r, open) }
            if (later.isNotEmpty()) item(key = "h-later") { Header(stringResource(R.string.to_call_later)) }
            items(later, key = { "l" + it.entry.key }) { r -> ToCallRowItem(vm, model, r, open) }
        }
    }
}

@Composable
private fun Header(text: String) {
    ListSectionHeader(text, Modifier.fillMaxWidth().semantics { heading() }, top = Spacing.m)
}

@Composable
private fun ToCallRowItem(vm: AppViewModel, model: ToCallModel, r: ToCallRow, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val res = LocalResources.current
    val title = titleOf(r)
    val e = r.entry
    ParleyListItem(
        modifier = Modifier.clickable {
            when {
                r.vaultId != null -> open(Routes.vault(r.vaultId))
                r.contact != null -> open(Routes.contact(r.contact.id))
                else -> open(Routes.history(r.number))
            }
        },
        // A private contact: the lock on the photo, as in Contacts.
        leadingContent = { PrivateMarked(r.vaultId != null) { Avatar(title, r.contact?.photoUri, avatarSize()) } },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(kindLine(context, e), maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.promise?.let { Text("☐ $it", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                theirTime(context, e)?.let {
                    Text(it, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.requestCall(r.number, r.name) }) {
                    Icon(Icons.Rounded.Call, stringResource(R.string.to_call_call_who, title), tint = MaterialTheme.colorScheme.primary)
                }
                RowMenu(model, r, title) { undoText, before ->
                    snackbar?.show(res.getString(undoText), res.getString(R.string.to_call_undo)) { model.undoDone(e, before) }
                }
            }
        },
    )
}

/** "Missed call · 14:05", "Reminder · 18:00", "Follow-up · Mon 09:00", or a missed call after the reminder. */
private fun kindLine(context: Context, e: ToCallEntry): String {
    e.missedAt?.let { return context.getString(R.string.to_call_missed_again, Format.shortWhen(context, it)) }
    return when (e.kind) {
        ToCallKind.MISSED -> context.getString(R.string.to_call_missed_at, Format.shortWhen(context, e.since))
        ToCallKind.REMINDER -> context.getString(R.string.to_call_remind_at, RemindTimes.whenText(context, e.dueAt ?: e.since))
        ToCallKind.FOLLOW_UP -> context.getString(R.string.to_call_follow_up_at, RemindTimes.whenText(context, e.dueAt ?: e.since))
    }
}

/** "19:30 for them", when their clock differs from yours. */
private fun theirTime(context: Context, e: ToCallEntry): String? {
    if (!ToCall.offersTheirEvening(e.zone, ZoneId.systemDefault(), System.currentTimeMillis())) return null
    val f = android.text.format.DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(e.zone) }
    return context.getString(R.string.to_call_their_time, f.format(java.util.Date()))
}

/** ⋮ on a row: remind me later (the fixed times), after 6 pm their time, done, remove. */
@Composable
private fun RowMenu(model: ToCallModel, r: ToCallRow, title: String, onUndoable: (Int, ToCallState) -> Unit) {
    val context = LocalContext.current
    val e = r.entry
    var open by remember { mutableStateOf(false) }
    var times by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true; times = false }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.to_call_more_for, title)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.to_call_later_menu)) },
                leadingIcon = { Icon(Icons.Rounded.AlarmAdd, null) },
                onClick = { times = !times },
            )
            if (times) {
                RemindTimes.choices().forEach { (choice, at) ->
                    DropdownMenuItem(
                        text = { Text(RemindTimes.label(context, choice, at)) },
                        onClick = { open = false; model.snooze(e, at) },
                        modifier = Modifier.padding(start = Spacing.xl),
                    )
                }
            }
            if (ToCall.offersTheirEvening(e.zone, ZoneId.systemDefault(), System.currentTimeMillis())) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.to_call_their_evening))
                            Text(
                                stringResource(R.string.to_call_their_evening_body),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = { Checkbox(e.theirEvening, onCheckedChange = null) },
                    onClick = { open = false; model.setTheirEvening(e, !e.theirEvening) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.to_call_done)) },
                leadingIcon = { Icon(Icons.Rounded.CheckCircle, null) },
                onClick = { open = false; onUndoable(R.string.to_call_done_undo, model.done(e)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.to_call_remove)) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                onClick = { open = false; onUndoable(R.string.to_call_removed_undo, model.done(e)) },
            )
        }
    }
}
