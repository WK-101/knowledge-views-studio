package app.parley.ui.people

import android.provider.ContactsContract.CommonDataKinds.Event
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.common.EventDate
import app.parley.common.people.LifeEvents
import app.parley.common.people.ProvenanceVerdict
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.EventItem
import app.parley.data.RawContactRef
import app.parley.data.people.ContactMover
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.contact.describeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Saved in" chips on the contact page, each with its actions: Edit this copy · Move to… · Unlink.
 * Messenger copies are read-only and only show where they come from.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountChips(vm: AppViewModel, d: ContactDetails, open: (String) -> Unit, onChanged: (Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf<RawContactRef?>(null) }
    var unlinking by remember { mutableStateOf<RawContactRef?>(null) }
    val writable = d.writableRawIds.toSet()
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        d.rawContacts.distinctBy { it.account to (it.id in writable) }.take(4).forEach { raw ->
            var menu by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { menu = true },
                    label = { Text(raw.account.displayLabel, style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Rounded.Sync, null, Modifier.size(14.dp)) },
                )
                DropdownMenu(menu, { menu = false }) {
                    if (raw.id in writable) {
                        DropdownMenuItem({ Text("Edit this copy") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                            menu = false; open(PeopleRoutes.editRaw(d.id, raw.id))
                        })
                        DropdownMenuItem({ Text("Move to…") }, leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) }, onClick = { menu = false; moving = raw })
                    } else {
                        DropdownMenuItem({ Text("Managed by its app (read-only)") }, enabled = false, onClick = {})
                    }
                    if (d.rawContacts.size > 1) {
                        DropdownMenuItem({ Text("Unlink") }, leadingIcon = { Icon(Icons.Rounded.CallSplit, null) }, onClick = { menu = false; unlinking = raw })
                    }
                }
            }
        }
    }
    moving?.let { raw ->
        var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
        LaunchedEffect(Unit) { accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() }.filter { it != raw.account } }
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move this copy to") },
            text = {
                Column {
                    Text(
                        "Everything in the ${raw.account.displayLabel} copy (numbers, photo, labels…) is written to the new account first; only then is the old copy removed. " +
                            "You can undo from Recently deleted & changed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    accounts.forEach { a ->
                        ListItem(headlineContent = { Text(vm.accountLabel(a)) }, modifier = Modifier.clickable {
                            moving = null
                            scope.launch {
                                when (val r = vm.c.people.mover.move(d.id, raw.id, a)) {
                                    is ContactMover.Result.Done -> { vm.toast("Moved to ${a.displayLabel}"); onChanged(r.contactId) }
                                    is ContactMover.Result.Failed -> vm.toast(r.reason)
                                }
                            }
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ moving = null }) { Text("Cancel") } },
        )
    }
    unlinking?.let { raw ->
        AlertDialog(
            onDismissRequest = { unlinking = null },
            title = { Text("Unlink the ${raw.account.displayLabel} copy?") },
            text = { Text("It becomes a separate contact. Nothing is deleted.") },
            confirmButton = {
                TextButton({
                    unlinking = null
                    scope.launch {
                        when (val r = vm.c.people.mover.unlink(d.id, raw.id)) {
                            is ContactMover.Result.Done -> { vm.toast("Unlinked"); onChanged(null) }
                            is ContactMover.Result.Failed -> vm.toast(r.reason)
                        }
                    }
                }) { Text("Unlink") }
            },
            dismissButton = { TextButton({ unlinking = null }) { Text("Cancel") } },
        )
    }
}

/** "Why did this change?": who last changed this contact (Parley, another app, or a sync account). */
@Composable
fun ProvenanceRow(vm: AppViewModel, contactId: Long, refreshKey: Any?, open: (String) -> Unit) {
    val context = LocalContext.current
    val verdict by produceState<ProvenanceVerdict?>(null, contactId, refreshKey) {
        value = vm.c.people.provenance.verdict(contactId) { Format.fullDate(context, it) }
    }
    val v = verdict ?: return
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "Version history") { open(Routes.versions(contactId)) },
        leadingContent = { Icon(Icons.Rounded.History, null) },
        headlineContent = { Text(v.text) },
        supportingContent = { Text("Why did this change? · Tap for every version") },
    )
}

/**
 * Event line for the contact page, aware of a date of death: the birthday of someone who died reads
 * "would have turned N", and the date of death shows the age they reached.
 */
fun describeLifeEvent(d: ContactDetails, ev: EventItem, today: LocalDate = LocalDate.now()): String {
    val death = LifeEvents.deathDate(d.events.map { Triple(it.type, it.label, it.date) })
    val isDeath = LifeEvents.isDeath(ev.type, ev.label)
    val parsed = EventDate.parse(ev.date)
    if (death == null || parsed == null) return describeEvent(ev.date, ev.type == Event.TYPE_BIRTHDAY)
    fun shown(e: EventDate) = if (e.year != null) LocalDate.of(e.year!!, e.month, e.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
    else java.time.MonthDay.of(e.month, e.day).format(DateTimeFormatter.ofPattern("d MMMM"))
    return when {
        isDeath -> {
            val birth = d.events.firstOrNull { it.type == Event.TYPE_BIRTHDAY }?.let { EventDate.parse(it.date) }
            listOfNotNull(shown(parsed), birth?.let { LifeEvents.ageAtDeath(it, parsed) }?.let { "aged $it" }).joinToString(" · ")
        }
        ev.type == Event.TYPE_BIRTHDAY -> listOfNotNull(shown(parsed), LifeEvents.wouldHaveTurned(parsed, today)?.let { "would have turned $it" }, dayText(parsed, today)).joinToString(" · ")
        else -> describeEvent(ev.date, false, today)
    }
}

private fun dayText(e: EventDate, today: LocalDate): String = when (val days = e.daysUntil(today)) {
    0L -> "today"
    1L -> "tomorrow"
    else -> "in $days days"
}

/** Label for an event row: "Date of death" and other custom labels as typed. */
fun eventLabel(res: android.content.res.Resources, ev: EventItem): String =
    if (ev.type == Event.TYPE_CUSTOM && !ev.label.isNullOrBlank()) ev.label!! else res.getString(Event.getTypeResource(ev.type))
