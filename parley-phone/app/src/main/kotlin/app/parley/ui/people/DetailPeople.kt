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
import android.content.res.Resources
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.people.ProvenanceKind

/**
 * "Saved in" chips on the contact page, each with its actions: Edit this copy · Move to… · Unlink.
 * Messenger copies are read-only and only show where they come from.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountChips(vm: AppViewModel, d: ContactDetails, open: (String) -> Unit, onChanged: (Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
                        DropdownMenuItem({ Text(stringResource(R.string.ppl_edit_copy)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                            menu = false; open(PeopleRoutes.editRaw(d.id, raw.id))
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.ppl_move_to)) }, leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) }, onClick = { menu = false; moving = raw })
                    } else {
                        DropdownMenuItem({ Text(stringResource(R.string.ppl_read_only)) }, enabled = false, onClick = {})
                    }
                    if (d.rawContacts.size > 1) {
                        DropdownMenuItem({ Text(stringResource(R.string.ppl_unlink)) }, leadingIcon = { Icon(Icons.Rounded.CallSplit, null) }, onClick = { menu = false; unlinking = raw })
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
            title = { Text(stringResource(R.string.ppl_move_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.ppl_move_text, raw.account.displayLabel),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    accounts.forEach { a ->
                        ListItem(headlineContent = { Text(vm.accountLabel(a)) }, modifier = Modifier.clickable {
                            moving = null
                            scope.launch {
                                when (val r = vm.c.people.mover.move(d.id, raw.id, a)) {
                                    is ContactMover.Result.Done -> { vm.toast(context.getString(R.string.ppl_moved_to, a.displayLabel)); onChanged(r.contactId) }
                                    is ContactMover.Result.Failed -> vm.toast(r.reason)
                                }
                            }
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ moving = null }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
    unlinking?.let { raw ->
        AlertDialog(
            onDismissRequest = { unlinking = null },
            title = { Text(stringResource(R.string.ppl_unlink_title, raw.account.displayLabel)) },
            text = { Text(stringResource(R.string.ppl_unlink_text)) },
            confirmButton = {
                TextButton({
                    unlinking = null
                    scope.launch {
                        when (val r = vm.c.people.mover.unlink(d.id, raw.id)) {
                            is ContactMover.Result.Done -> { vm.toast(context.getString(R.string.ppl_unlinked)); onChanged(null) }
                            is ContactMover.Result.Failed -> vm.toast(r.reason)
                        }
                    }
                }) { Text(stringResource(R.string.ppl_unlink)) }
            },
            dismissButton = { TextButton({ unlinking = null }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
}

/** "Why did this change?": who last changed this contact (Parley, another app, or a sync account). */
@Composable
fun ProvenanceRow(vm: AppViewModel, contactId: Long, refreshKey: Any?, open: (String) -> Unit) {
    val context = LocalContext.current
    val verdict by produceState<ProvenanceVerdict?>(null, contactId, refreshKey) {
        value = vm.c.people.provenance.verdict(contactId)
    }
    val v = verdict ?: return
    ListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.ppl_version_history)) { open(Routes.versions(contactId)) },
        leadingContent = { Icon(Icons.Rounded.History, null) },
        headlineContent = { Text(provenanceText(context.resources, v) { Format.fullDate(context, it) }) },
        supportingContent = { Text(stringResource(R.string.ppl_why_changed)) },
    )
}

/** Words a [ProvenanceVerdict] from core:common ("Changed by Parley on 3 May · only changed fields were written (Phone)"). */
private fun provenanceText(res: Resources, v: ProvenanceVerdict, formatTime: (Long) -> String): String {
    val at = v.time?.let(formatTime)
    val account = v.account.orEmpty()
    return when (v.kind) {
        ProvenanceKind.OTHER_APP_AFTER_PARLEY_UNSYNCED ->
            at?.let { res.getString(R.string.prov_other_app_unsynced_at, it) } ?: res.getString(R.string.prov_other_app_unsynced)
        ProvenanceKind.SYNC_AFTER_PARLEY -> at?.let { res.getString(R.string.prov_sync_at, account, it) } ?: res.getString(R.string.prov_sync, account)
        ProvenanceKind.OTHER_APP_AFTER_PARLEY -> at?.let { res.getString(R.string.prov_other_app_at, it) } ?: res.getString(R.string.prov_other_app)
        ProvenanceKind.PARLEY -> {
            val time = at.orEmpty()
            val base = if (v.fields.isEmpty()) res.getString(R.string.prov_parley, time)
            else res.getString(R.string.prov_parley_fields, time, v.fields.joinToString(res.getString(R.string.dc_list_separator)) { fieldLabel(res, it) })
            val other = v.otherAccount ?: return base
            res.getString(if (v.otherSynced) R.string.prov_other_copy_sync else R.string.prov_other_copy_apps, base, other)
        }
        ProvenanceKind.LAST_OTHER_APP_UNSYNCED -> at?.let { res.getString(R.string.prov_last_other_app_at, it) } ?: res.getString(R.string.prov_last_other_app)
        ProvenanceKind.LAST_SYNC -> at?.let { res.getString(R.string.prov_last_sync_at, it, account) } ?: res.getString(R.string.prov_last_sync, account)
        ProvenanceKind.LAST_UNKNOWN -> res.getString(R.string.prov_last_unknown, at.orEmpty())
    }
}

/** Field names Parley records for its own saves (stored in English) → localised names. */
private fun fieldLabel(res: Resources, field: String): String = when (field) {
    "Name" -> res.getString(R.string.prov_field_name)
    "Nickname" -> res.getString(R.string.prov_field_nickname)
    "Company" -> res.getString(R.string.prov_field_company)
    "Note" -> res.getString(R.string.prov_field_note)
    "Phone" -> res.getString(R.string.prov_field_phone)
    "Email" -> res.getString(R.string.prov_field_email)
    "Website" -> res.getString(R.string.prov_field_website)
    "Relation" -> res.getString(R.string.prov_field_relation)
    "Messenger handles" -> res.getString(R.string.prov_field_handles)
    "Dates" -> res.getString(R.string.prov_field_dates)
    "Address" -> res.getString(R.string.prov_field_address)
    "Labels" -> res.getString(R.string.prov_field_labels)
    "Other" -> res.getString(R.string.prov_field_other)
    else -> field
}

/**
 * Event line for the contact page, aware of a date of death: the birthday of someone who died reads
 * "would have turned N", and the date of death shows the age they reached.
 */
fun describeLifeEvent(res: Resources, d: ContactDetails, ev: EventItem, today: LocalDate = LocalDate.now()): String {
    val death = LifeEvents.deathDate(d.events.map { Triple(it.type, it.label, it.date) })
    val isDeath = LifeEvents.isDeath(ev.type, ev.label)
    val parsed = EventDate.parse(ev.date)
    if (death == null || parsed == null) return describeEvent(ev.date, ev.type == Event.TYPE_BIRTHDAY)
    fun shown(e: EventDate) = if (e.year != null) LocalDate.of(e.year!!, e.month, e.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
    else java.time.MonthDay.of(e.month, e.day).format(
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMMM")),
    )
    return when {
        isDeath -> {
            val birth = d.events.firstOrNull { it.type == Event.TYPE_BIRTHDAY }?.let { EventDate.parse(it.date) }
            listOfNotNull(shown(parsed), birth?.let { LifeEvents.ageAtDeath(it, parsed) }?.let { res.getString(R.string.life_aged, it) }).joinToString(" · ")
        }
        ev.type == Event.TYPE_BIRTHDAY -> listOfNotNull(shown(parsed), LifeEvents.wouldHaveTurned(parsed, today)?.let { res.getString(R.string.life_would_have_turned, it) }, dayText(res, parsed, today)).joinToString(" · ")
        else -> describeEvent(ev.date, false, today)
    }
}

private fun dayText(res: Resources, e: EventDate, today: LocalDate): String = when (val days = e.daysUntil(today)) {
    0L -> res.getString(R.string.life_today)
    1L -> res.getString(R.string.life_tomorrow)
    else -> days.toInt().let { res.getQuantityString(R.plurals.life_in_days, it, it) }
}

/** Label for an event row: "Date of death" and other custom labels as typed. */
fun eventLabel(res: android.content.res.Resources, ev: EventItem): String =
    if (ev.type == Event.TYPE_CUSTOM && !ev.label.isNullOrBlank()) ev.label!! else res.getString(Event.getTypeResource(ev.type))
