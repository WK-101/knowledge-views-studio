package app.parley.ui.birthdays

import app.parley.ui.Destination
import android.provider.ContactsContract.CommonDataKinds.Event
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.EventDate
import app.parley.common.StartTab
import app.parley.common.people.LifeEvents
import app.parley.data.ContactEvent
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.common.Intents
import app.parley.ui.contact.Section
import app.parley.ui.contact.describeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyListItem
import app.parley.ui.avatarSize

data class UpcomingEvent(val event: ContactEvent, val days: Long, val parsed: EventDate)

fun upcoming(events: List<ContactEvent>, today: LocalDate = LocalDate.now()): List<UpcomingEvent> =
    events.mapNotNull { e -> EventDate.parse(e.date)?.let { UpcomingEvent(e, it.daysUntil(today), it) } }.sortedBy { it.days }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdaysScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val all by vm.contacts.collectAsStateWithLifecycle()
    val list by produceState<List<UpcomingEvent>?>(null, all) { value = withContext(Dispatchers.IO) { upcoming(vm.c.contacts.events()) } }
    // Scroll-linked top-bar tint.
    val barTint = TopAppBarDefaults.pinnedScrollBehavior()
    ParleyScaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        ParleyTopBar(stringResource(R.string.bday_title), onBack = back, scrollBehavior = barTint)
    }) { p ->
        val items = list
        if (items != null && items.isEmpty()) {
            // Dates live on contacts; the way on is the contact list.
            EmptyState(
                Icons.Rounded.Cake, stringResource(R.string.bday_empty_title), stringResource(R.string.bday_empty_text), Modifier.padding(p),
                action = stringResource(R.string.ux_empty_open_contacts), onAction = { vm.navigate(NavEvent.Tab(StartTab.CONTACTS)) },
            )
            return@ParleyScaffold
        }
        val deceased = items.orEmpty().filter { LifeEvents.isDeath(it.event.type, it.event.label) }.map { it.event.contactId }.toSet()
        LazyColumn(Modifier.padding(p)) {
            val groups = items.orEmpty().groupBy {
                when {
                    it.days == 0L -> R.string.bday_today
                    it.days <= 7 -> R.string.bday_this_week
                    it.days <= 31 -> R.string.bday_this_month
                    else -> R.string.bday_later
                }
            }
            listOf(R.string.bday_today, R.string.bday_this_week, R.string.bday_this_month, R.string.bday_later).forEach { title ->
                val g = groups[title].orEmpty()
                if (g.isNotEmpty()) {
                    item { Section(stringResource(title)) }
                    g.forEach { u ->
                        item {
                            val e = u.event
                            val kind = if (LifeEvents.isDeath(e.type, e.label)) resources.getString(R.string.life_date_of_death) else if (e.type == Event.TYPE_CUSTOM && !e.label.isNullOrBlank()) e.label!! else resources.getString(Event.getTypeResource(e.type))
                            ParleyListItem(
                                modifier = Modifier.clickable { open(Routes.contact(e.contactId)) },
                                leadingContent = { Avatar(e.name, e.photoUri, avatarSize()) },
                                headlineContent = { Text(e.name) },
                                supportingContent = {
                                    val birth = EventDate.parse(e.date)
                                    Text(
                                        "$kind · " + if (e.type == Event.TYPE_BIRTHDAY && e.contactId in deceased && birth != null) {
                                            describeEvent(e.date, false, res = resources).substringBefore(" ·") +
                                                (LifeEvents.wouldHaveTurned(birth, LocalDate.now())?.let { " · " + resources.getString(R.string.bday_would_have_turned, it) } ?: "") +
                                                " · " + describeEvent(e.date, false, res = resources).substringAfterLast(" · ")
                                        } else {
                                            describeEvent(e.date, e.type == Event.TYPE_BIRTHDAY, res = resources)
                                        },
                                    )
                                },
                                trailingContent = {
                                    e.phone?.let { n ->
                                        Row {
                                            IconButton({ Intents.sms(context, n) }) { Icon(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.bday_message, e.name)) }
                                            IconButton({ vm.requestCall(n, e.name) }) { Icon(Icons.Rounded.Call, stringResource(R.string.bday_call, e.name), tint = MaterialTheme.colorScheme.primary) }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
