package app.parley.ui.birthdays

import android.provider.ContactsContract.CommonDataKinds.Event
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.EventDate
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

data class UpcomingEvent(val event: ContactEvent, val days: Long, val parsed: EventDate)

fun upcoming(events: List<ContactEvent>, today: LocalDate = LocalDate.now()): List<UpcomingEvent> =
    events.mapNotNull { e -> EventDate.parse(e.date)?.let { UpcomingEvent(e, it.daysUntil(today), it) } }.sortedBy { it.days }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdaysScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val all by vm.contacts.collectAsStateWithLifecycle()
    val list by produceState<List<UpcomingEvent>?>(null, all) { value = withContext(Dispatchers.IO) { upcoming(vm.c.contacts.events()) } }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Birthdays & dates") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        val items = list
        if (items != null && items.isEmpty()) {
            EmptyState(Icons.Rounded.Cake, "No dates yet", "Add birthdays and anniversaries to contacts to see them here and get a reminder on the day.", Modifier.padding(p))
            return@Scaffold
        }
        val deceased = items.orEmpty().filter { app.parley.common.people.LifeEvents.isDeath(it.event.type, it.event.label) }.map { it.event.contactId }.toSet()
        LazyColumn(Modifier.padding(p)) {
            val groups = items.orEmpty().groupBy {
                when {
                    it.days == 0L -> "Today"
                    it.days <= 7 -> "This week"
                    it.days <= 31 -> "This month"
                    else -> "Later"
                }
            }
            listOf("Today", "This week", "This month", "Later").forEach { title ->
                val g = groups[title].orEmpty()
                if (g.isNotEmpty()) {
                    item { Section(title) }
                    g.forEach { u ->
                        item {
                            val e = u.event
                            val kind = if (e.type == Event.TYPE_CUSTOM && !e.label.isNullOrBlank()) e.label!! else resources.getString(Event.getTypeResource(e.type))
                            ListItem(
                                modifier = Modifier.clickable { open(Routes.contact(e.contactId)) },
                                leadingContent = { Avatar(e.name, e.photoUri, 44.dp) },
                                headlineContent = { Text(e.name) },
                                supportingContent = {
                                    val birth = EventDate.parse(e.date)
                                    Text(
                                        "$kind · " + if (e.type == Event.TYPE_BIRTHDAY && e.contactId in deceased && birth != null) {
                                            describeEvent(e.date, false).substringBefore(" ·") +
                                                (app.parley.common.people.LifeEvents.wouldHaveTurned(birth, java.time.LocalDate.now())?.let { " · would have turned $it" } ?: "") +
                                                " · " + describeEvent(e.date, false).substringAfterLast(" · ")
                                        } else {
                                            describeEvent(e.date, e.type == Event.TYPE_BIRTHDAY)
                                        },
                                    )
                                },
                                trailingContent = {
                                    e.phone?.let { n ->
                                        androidx.compose.foundation.layout.Row {
                                            IconButton({ Intents.sms(context, n) }) { Icon(Icons.AutoMirrored.Rounded.Message, "Message ${e.name}") }
                                            IconButton({ vm.requestCall(n, e.name) }) { Icon(Icons.Rounded.Call, "Call ${e.name}", tint = MaterialTheme.colorScheme.primary) }
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
