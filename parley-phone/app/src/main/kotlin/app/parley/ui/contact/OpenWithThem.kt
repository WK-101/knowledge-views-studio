package app.parley.ui.contact

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.parley.R
import app.parley.common.calls.ToCallItem
import app.parley.common.circle.Promises
import app.parley.data.circle.CircleRepository
import app.parley.telecom.ui.RemindTimes
import app.parley.ui.SegmentedGroup
import app.parley.ui.SegmentedGroupScope
import app.parley.ui.Spacing
import app.parley.ui.calls.ToCallRoutes
import app.parley.ui.circle.tickPromise
import kotlinx.coroutines.launch

/** Whether anything is open with them: a To call item, something to talk about, or a promise in their notes. */
internal fun hasOpenWithThem(ctx: ContactPageContext, toCall: ToCallItem?): Boolean =
    toCall != null || ctx.ui.memory.agenda.isNotEmpty() || ctx.ui.memory.owed.isNotEmpty()

/**
 * "Open with them": one card for what's open with this person, each kept where it always was. Their To call item (the
 * time you said you'd call, a next catch-up, or a reminder) opens the To call list; the things to talk about (from the
 * note for calls) and the promises in their other notes are ticked off with one tap. The call screen shows only the
 * things to talk about.
 */
@Composable
internal fun OpenWithThemCard(ctx: ContactPageContext, toCall: ToCallItem?) {
    if (!hasOpenWithThem(ctx, toCall)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memory = ctx.ui.memory
    val canAdd = ctx.d.lookupKey.isNotEmpty() && (!ctx.isPrivate || ctx.ui.access == PrivateAccess.OPEN)
    SegmentedGroup(stringResource(R.string.open_with_them)) {
        toCall?.let { item ->
            item {
                InfoRow(
                    modifier = Modifier.clickable { ctx.open(ToCallRoutes.List) },
                    leading = { Icon(Icons.Rounded.PhoneCallback, null) },
                    headline = { Text(toCallLine(context, item)) },
                    supporting = { Text(stringResource(R.string.open_with_them_to_call)) },
                )
            }
        }
        val tick: (CircleRepository.PersonNote, Promises.Item) -> Unit = { n, p -> scope.launch { tickPromise(ctx.vm, ctx.d.lookupKey, n, p, true) } }
        tickRows(memory.agenda, Icons.Rounded.Checklist, R.string.label_to_talk_about, tick)
        tickRows(memory.owed, Icons.Rounded.TaskAlt, R.string.open_with_them_you_promised, tick)
        if (canAdd) {
            item {
                InfoRow(
                    modifier = Modifier.clickable { ctx.show(ContactDialog.AddAgenda) },
                    headline = { Text(stringResource(R.string.agenda_add)) },
                    supporting = { Text(stringResource(R.string.agenda_add_hint)) },
                    trailing = { Icon(Icons.Rounded.Add, null, Modifier.padding(end = Spacing.m)) },
                )
            }
        }
    }
}

/** "You said you'd call · 15:30", "Next catch-up · Sat 09:00", or "Reminder · 18:00". */
private fun toCallLine(context: Context, item: ToCallItem): String {
    val promised = item.promised
    return when {
        promised != null && promised.told -> context.getString(R.string.to_call_promised_at, RemindTimes.whenText(context, promised.at))
        promised != null -> context.getString(R.string.to_call_catch_up_at, RemindTimes.whenText(context, promised.at))
        else -> context.getString(R.string.to_call_remind_at, RemindTimes.whenText(context, item.dueAt))
    }
}

/** Open items, each ticked off with one tap ([onTick]); the first names what they are ([label]). */
private fun SegmentedGroupScope.tickRows(
    items: List<Pair<CircleRepository.PersonNote, Promises.Item>>,
    icon: ImageVector,
    label: Int,
    onTick: (CircleRepository.PersonNote, Promises.Item) -> Unit,
) {
    items.forEachIndexed { i, (n, p) ->
        item {
            InfoRow(
                modifier = Modifier.toggleable(value = false, role = Role.Checkbox) { onTick(n, p) },
                leading = { if (i == 0) Icon(icon, null) },
                headline = { Text(p.text) },
                supporting = if (i == 0) ({ Text(stringResource(label)) }) else null,
                trailing = { Checkbox(checked = false, onCheckedChange = null, modifier = Modifier.padding(end = Spacing.m)) },
            )
        }
    }
}
