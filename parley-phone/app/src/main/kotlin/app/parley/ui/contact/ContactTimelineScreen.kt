package app.parley.ui.contact

import app.parley.common.people.ContactRef
import android.content.res.Resources
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import app.parley.common.PhoneIdentity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.circle.Timeline
import app.parley.common.circle.TimelineEntry
import app.parley.common.circle.TimelineFilter
import app.parley.common.circle.TimelineKind
import app.parley.data.ContactDetails
import app.parley.data.EventItem
import app.parley.data.PhoneEnv
import app.parley.data.circle.Interaction
import app.parley.ui.EmptyState
import app.parley.ui.circle.CircleText
import app.parley.ui.circle.LogInteractionDialog
import app.parley.ui.circle.TimelineEntryRow
import app.parley.ui.circle.rememberMonthFormat
import app.parley.ui.circle.saveInteraction
import app.parley.ui.circle.timelineEntries
import app.parley.ui.history.HistoryText
import app.parley.ui.people.eventLabel
import app.parley.ui.segmentShape
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyShapes
import app.parley.ui.ListSectionHeader
import app.parley.ui.Spacing

/**
 * A contact's whole timeline on its own screen ("Show all" on the contact page): search the notes,
 * numbers and kinds, filter by calls, missed calls, logged moments, notes and dates, one sticky heading per month.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactTimelineScreen(vm: AppViewModel, contactId: Long, back: () -> Unit) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val all by vm.contacts.collectAsStateWithLifecycle()
    var d by remember { mutableStateOf<ContactDetails?>(null) }
    val ref = remember(contactId) { ContactRef.ofNavId(contactId) }
    val privates by vm.c.vault.contacts.collectAsStateWithLifecycle()
    LaunchedEffect(contactId, all, privates) {
        d = when (ref) {
            // A private contact's timeline, like its page: its details under its Parley key (nothing while locked).
            is ContactRef.Private -> runCatching { vm.c.vault.details(ref.vaultId) }.getOrNull()
                ?.copy(id = contactId, lookupKey = ContactRef.privateKey(ref.vaultId))
            else -> vm.c.contacts.details(contactId)
        }
    }
    val dd = d
    // A private contact's calls are in the private call history.
    val calls by (if (ref is ContactRef.Private) vm.c.history.callsWithPrivate else vm.c.history.calls).collectAsStateWithLifecycle()
    val allNotes by vm.c.meta.allCallNotes().collectAsStateWithLifecycle(emptyList())
    val interactions by remember(dd?.lookupKey) { dd?.lookupKey?.takeIf { it.isNotEmpty() }?.let { vm.c.circle.interactions.interactions(it) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    // The same calls as the contact page (by E.164 with this phone's country).
    val history = remember(calls, dd?.phones) {
        val phones = dd?.phones.orEmpty()
        if (phones.isEmpty()) emptyList() else {
            val mine = PhoneIdentity.LineSet(phones.map { it.value }, PhoneEnv.countryIso(context))
            calls.orEmpty().filter { e -> e.number in mine }
        }
    }
    val keys = remember(dd?.phones) { dd?.phones.orEmpty().flatMap { PhoneIdentity.lookupKeys(it.value, vm.countryIso) }.toSet() }
    val notes = remember(allNotes, keys) { allNotes.filter { it.numberKey in keys } }
    var query by rememberSaveable { mutableStateOf("") }
    var kinds by rememberSaveable { mutableStateOf(emptyList<TimelineKind>()) }
    var editEntry by remember { mutableStateOf<Interaction?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val entries = remember(dd, history, interactions, notes) { dd?.let { timelineEntries(it, history, interactions, notes, zone) }.orEmpty() }
    val sep = stringResource(R.string.main_separator)
    val shown = remember(entries, query, kinds) {
        val f = TimelineFilter(query, kinds.toSet())
        Timeline.group(f.apply(entries) { e -> searchText(res, e, sep) }, zone)
    }
    val monthFormat = rememberMonthFormat()
    val bar = TopAppBarDefaults.pinnedScrollBehavior()

    ParleyScaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = {
            ParleyTopBar(
                dd?.let { stringResource(
                    R.string.contact_page_timeline_of,
                    it.given.ifBlank { it.displayName },
                ) } ?: stringResource(R.string.circle_timeline),
                onBack = back,
                scrollBehavior = bar,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    query, { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text(stringResource(R.string.contact_page_timeline_search)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (query.isEmpty()) null else ({ IconButton({ query = "" }) { Icon(Icons.Rounded.Clear, stringResource(R.string.contact_page_clear_search)) } }),
                    singleLine = true,
                    shape = ParleyShapes.sheet,
                )
            }
            item(key = "kinds") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TimelineKind.entries, key = { it.name }) { k ->
                        val on = k in kinds
                        FilterChip(on, { kinds = if (on) kinds - k else kinds + k }, label = { Text(kindLabel(res, k)) })
                    }
                }
            }
            item(key = "count") {
                val n = shown.sumOf { it.entries.size }
                Text(
                    pluralStringResource(R.plurals.contact_page_entries, n, n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
            if (shown.isEmpty() && dd != null) item(key = "empty") {
                EmptyState(
                    Icons.Rounded.Search, stringResource(if (entries.isEmpty()) R.string.circle_timeline_empty else R.string.contact_page_timeline_no_match),
                    modifier = Modifier.padding(top = 32.dp),
                    action = if (entries.isEmpty()) null else stringResource(R.string.contact_page_timeline_clear_filters),
                    onAction = { query = ""; kinds = emptyList() },
                )
            }
            shown.forEach { m ->
                stickyHeader(key = "m" + m.month) {
                    ListSectionHeader(m.month.atDay(1).format(monthFormat), sticky = true, inset = Spacing.xxl, top = Spacing.m, bottom = 6.dp)
                }
                val n = m.entries.size
                // Keys stay unique even if two entries look alike (a duplicate gets "#k" on its key).
                val seen = HashMap<String, Int>()
                m.entries.forEachIndexed { i, e ->
                    val base = entryKey(e)
                    val k = (seen[base] ?: 0) + 1
                    seen[base] = k
                    item(key = if (k == 1) base else "$base#$k") {
                        Surface(
                            shape = segmentShape(i, n), color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateItem(),
                        ) { TimelineEntryRow(vm, e, interactions) { editEntry = it } }
                    }
                }
            }
        }
    }
    val initial = editEntry
    if (initial != null && dd != null) {
        LogInteractionDialog(dd.given.ifBlank { dd.displayName }, initial, onDismiss = { editEntry = null }) { type, note, time ->
            editEntry = null
            scope.launch { saveInteraction(vm, dd, contactId, initial, type, note, time) }
        }
    }
}

private fun entryKey(e: TimelineEntry): String = when (e) {
    is TimelineEntry.Call -> "c" + e.call.id + "_" + e.time
    is TimelineEntry.Logged -> "l" + e.id
    is TimelineEntry.Note -> "n" + e.id
    is TimelineEntry.Date -> "d" + e.time + "_" + e.type + "_" + e.label
}

private fun kindLabel(res: Resources, k: TimelineKind): String = res.getString(
    when (k) {
        TimelineKind.CALL -> R.string.quality_subject_all
        TimelineKind.MISSED -> R.string.contact_page_kind_missed
        TimelineKind.LOGGED -> R.string.contact_page_kind_logged
        TimelineKind.NOTE -> R.string.contact_page_kind_notes
        TimelineKind.DATE -> R.string.contact_page_sec_dates
    },
)

/** The words an entry shows, localised, so "missed" or "coffee" finds it. */
private fun searchText(res: Resources, e: TimelineEntry, sep: String): String = when (e) {
    is TimelineEntry.Call -> res.getString(HistoryText.callType(e.call.type))
    is TimelineEntry.Logged -> CircleText.type(res, e.type) + (e.channel?.let { sep + CircleText.channel(res, it) } ?: "")
    is TimelineEntry.Note -> res.getString(R.string.circle_call_note)
    is TimelineEntry.Date -> eventLabel(res, EventItem(date = e.date.format(), type = e.type, label = e.label))
}
