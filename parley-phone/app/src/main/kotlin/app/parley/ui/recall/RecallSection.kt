package app.parley.ui.recall

import app.parley.ui.calls.NetworkNameTag
import android.content.Context
import androidx.annotation.StringRes
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.parley.data.circle.AgendaTarget
import app.parley.ui.circle.AgendaAddDialog
import app.parley.ui.circle.addToAgenda
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.parley.AppViewModel
import app.parley.R
import app.parley.ui.history.HistoryRoutes
import app.parley.common.CallType
import app.parley.common.memory.MemorySource
import app.parley.common.people.ContactRef
import kotlinx.coroutines.CoroutineScope
import app.parley.common.recall.RecallGroup
import app.parley.common.recall.RecallHit
import app.parley.common.recall.RecallQuery
import app.parley.common.recall.RecallSource
import app.parley.security.AppLock
import app.parley.ui.Avatar
import app.parley.ui.Banner
import app.parley.ui.Bidi
import app.parley.ui.Destination
import app.parley.ui.ListSectionHeader
import app.parley.ui.ParleyListItem
import app.parley.ui.Routes
import app.parley.ui.people.PeopleRoutes
import app.parley.ui.Spacing
import app.parley.ui.avatarSize
import app.parley.ui.calls.ToCallRoutes
import app.parley.ui.common.Format
import app.parley.ui.history.HistoryText
import app.parley.ui.home.CallTypeIcon
import app.parley.ui.journal.HistoryTab
import app.parley.ui.memory.NumberMemoryText
import app.parley.ui.memory.noteTarget
import app.parley.ui.memory.restoreAndOpen
import app.parley.ui.people.matchHint
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Rows shown per group before "Show n more". */
private const val FIRST_ROWS = 5

/**
 * Recall's results under the Contacts search: a heading (what was understood), then each source's group with its
 * rows, the words that matched in bold. [fallback]: the contact list found nobody and Recall ran by itself.
 * [expanded] groups show every row kept; [onExpand] opens one.
 */
fun LazyListScope.recallSection(
    vm: AppViewModel,
    state: RecallUi.State,
    query: String,
    fallback: Boolean,
    expanded: Set<RecallSource>,
    onExpand: (RecallSource) -> Unit,
    open: (Destination) -> Unit,
    /** The heading when Recall ran on its own: the Contacts search's, unless the tab says otherwise (Recents). */
    @StringRes fallbackHeader: Int = R.string.recall_fallback_header,
) {
    val result = state.result
    item(key = "recall-header", contentType = "recall-header") { RecallHeader(state, fallback, fallbackHeader) }
    if (state.privateLocked) item(key = "recall-locked", contentType = "recall-banner") { PrivateLocked(vm) }
    if (result == null) return
    if (result.isEmpty) {
        if (!fallback) item(key = "recall-nothing", contentType = "recall-note") { Note(stringResource(R.string.recall_nothing, query.trim())) }
        return
    }
    result.groups.forEach { g ->
        val all = g.source in expanded
        val rows = if (all) g.hits else g.hits.take(FIRST_ROWS)
        item(key = "rg-${g.source}", contentType = "recall-group") { GroupHeader(g) }
        itemsIndexed(rows, key = { i, _ -> "r-${g.source}-$i" }, contentType = { _, _ -> "recall-row" }) { _, hit -> RecallRow(vm, hit, open) }
        val hidden = g.hits.size - rows.size
        if (hidden > 0) {
            item(key = "rm-${g.source}", contentType = "recall-more") {
                TextButton({ onExpand(g.source) }, Modifier.padding(start = Spacing.s)) {
                    Text(pluralStringResource(R.plurals.recall_show_more, hidden, hidden))
                }
            }
        } else if (all && g.total > g.hits.size) {
            item(key = "rt-${g.source}", contentType = "recall-note") {
                Note(pluralStringResource(R.plurals.recall_more_found, g.hits.size, g.hits.size))
            }
        }
    }
}

@Composable
private fun RecallHeader(state: RecallUi.State, fallback: Boolean, @StringRes fallbackHeader: Int) {
    val context = LocalContext.current
    val understood = state.result?.query?.takeIf { it.interpreted }?.let { describe(context, it) }
    ListSectionHeader(
        stringResource(if (fallback) fallbackHeader else R.string.recall_header),
        inset = Spacing.xl,
        top = Spacing.m,
    )
    when {
        state.searching && state.result == null -> Note(stringResource(R.string.recall_searching))
        understood != null -> Note(stringResource(R.string.recall_understood, understood))
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.xl, end = Spacing.l, bottom = Spacing.s),
    )
}

/** Private contacts are left out while their details are locked: one tap unlocks, then Recall reads again. */
@Composable
private fun PrivateLocked(vm: AppViewModel) {
    val activity = LocalActivity.current as? ComponentActivity
    Banner(
        stringResource(R.string.recall_private_locked),
        icon = Icons.Rounded.Lock,
        action = stringResource(R.string.cs_private_unlock).takeIf { activity != null },
        onAction = { activity?.let { a -> AppLock.authenticateForVault(a) { ok -> if (ok) vm.reloadRecall() } } },
    )
}

@Composable
private fun GroupHeader(g: RecallGroup) {
    ListSectionHeader(stringResource(R.string.rst_with_count, stringResource(groupLabel(g.source)), g.total), inset = Spacing.xl)
}

private fun groupLabel(s: RecallSource): Int = when (s) {
    RecallSource.CONTACT -> R.string.rst_contacts
    RecallSource.ARCHIVED -> R.string.recall_group_archived
    RecallSource.CALL -> R.string.quality_subject_all
    RecallSource.AGENDA -> R.string.agenda_title
    RecallSource.PROMISE -> R.string.circle_promises
    RecallSource.NOTE -> R.string.contact_page_kind_notes
    RecallSource.CALL_NOTE -> R.string.recall_group_call_notes
    RecallSource.CASE_FILE -> R.string.recall_group_case_files
    RecallSource.MESSAGED -> R.string.recall_group_messaged
    RecallSource.DELETED -> R.string.recall_group_deleted
    RecallSource.DELETED_PRIVATE -> R.string.jr_storage_private
    RecallSource.SNAPSHOT -> R.string.recall_group_snapshots
    RecallSource.REMEMBERED -> R.string.recall_group_remembered
}

/**
 * One result: its title and line with the matched words in bold, and a tap that opens where it lives. Press and hold
 * adds something to talk about with whoever it is about.
 */
@Composable
private fun RecallRow(vm: AppViewModel, hit: RecallHit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current as? ComponentActivity
    val target = remember(hit) { targetOf(vm, hit) }
    val isNumber = hit.title == hit.number && hit.number != null
    val title = when {
        hit.title.isBlank() -> AnnotatedString(stringResource(R.string.blk_private_number))
        else -> highlighted(hit.title, hit.titleMarks, ltr = isNumber)
    }
    val line = supporting(context, vm, hit)
    val restore = restoreAction(vm, hit, open, context, scope, activity)
    val openLabel = stringResource(R.string.blk_open)
    // Its ⋮: something to talk about with whoever the result is about (a visible menu, not a hidden long-press).
    val res = LocalResources.current
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        AgendaAddDialog(
            name = hit.title.takeIf { it.isNotBlank() && hit.source == RecallSource.CONTACT },
            keepOnRotation = !hit.private,
            onAdd = { text ->
                adding = false
                scope.launch {
                    val said = agendaTargetOf(vm, hit)?.let { addToAgenda(vm.c, it, text) } ?: R.string.agenda_add_failed
                    vm.toast(res.getString(said))
                }
            },
            onDismiss = { adding = false },
        )
    }
    ParleyListItem(
        modifier = if (target != null) Modifier.clickable(onClickLabel = openLabel) { open(target) } else Modifier,
        leadingContent = { Leading(hit) },
        headlineContent = { RecallTitle(title, hit.fromNetwork) },
        supportingContent = line?.let { l -> { Text(l, maxLines = if (hit.source in LONG_LINES) 3 else 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = when {
            restore != null -> ({ TextButton(restore) { Text(stringResource(R.string.number_memory_restore)) } })
            offersAgenda(hit) -> ({ RecallRowMenu(hit.title) { adding = true } })
            else -> null
        },
    )
}

/** A result's ⋮, where it can take something to talk about. */
@Composable
private fun RecallRowMenu(title: String, onAddAgenda: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.recall_more_for, title)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                { Text(stringResource(R.string.agenda_add)) }, leadingIcon = { Icon(Icons.Rounded.AddComment, null) },
                onClick = { menu = false; onAddAgenda() },
            )
        }
    }
}

/** A result's title; a name the network sent (not one you saved) says so. */
@Composable
private fun RecallTitle(title: AnnotatedString, fromNetwork: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (fromNetwork) NetworkNameTag(Modifier.padding(start = Spacing.s))
    }
}

/** Whether a long press can add to the agenda of whoever [hit] is about: a contact, a note's owner, a number. */
private fun offersAgenda(hit: RecallHit): Boolean = when (hit.source) {
    RecallSource.CONTACT -> hit.contactId != null
    RecallSource.AGENDA, RecallSource.PROMISE, RecallSource.NOTE -> hit.ref != null || !hit.number.isNullOrBlank()
    // Gone: there is nobody to talk to under that name now.
    RecallSource.DELETED, RecallSource.DELETED_PRIVATE, RecallSource.SNAPSHOT -> false
    else -> !hit.number.isNullOrBlank()
}

/** Whose agenda [offersAgenda] adds to, read when the item is added. */
private suspend fun agendaTargetOf(vm: AppViewModel, hit: RecallHit): AgendaTarget? = when (hit.source) {
    RecallSource.CONTACT -> hit.contactId?.let { id -> vm.c.agenda.targetFor(id, vm.c.contacts.contacts.value?.firstOrNull { it.id == id }?.lookupKey) }
    RecallSource.AGENDA, RecallSource.PROMISE, RecallSource.NOTE ->
        hit.ref?.let(AgendaTarget::forKey) ?: hit.number?.takeIf { it.isNotBlank() }?.let { vm.c.agenda.targetFor(it) }
    RecallSource.DELETED, RecallSource.DELETED_PRIVATE, RecallSource.SNAPSHOT -> null
    else -> hit.number?.takeIf { it.isNotBlank() }?.let { vm.c.agenda.targetFor(it) }
}

/** Notes show more of themselves. */
private val LONG_LINES = setOf(RecallSource.NOTE, RecallSource.CALL_NOTE)

/** A deleted contact's Restore: it comes back and opens (a private one after the vault's own unlock, as in History & undo). */
private fun restoreAction(
    vm: AppViewModel,
    hit: RecallHit,
    open: (Destination) -> Unit,
    context: Context,
    scope: CoroutineScope,
    activity: ComponentActivity?,
): (() -> Unit)? = when (hit.source) {
    RecallSource.DELETED -> hit.ref?.toLongOrNull()?.let { id ->
        { restoreAndOpen(vm, context, scope, hit.title, open, vm.recall::reload) { vm.c.journal.restore(id) } }
    }
    RecallSource.DELETED_PRIVATE -> hit.ref?.let { file -> activity?.let { a -> file to a } }?.let { (file, a) ->
        {
            AppLock.authenticateForVault(a) { ok ->
                if (ok) {
                    restoreAndOpen(vm, context, scope, hit.title, open, vm.recall::reload) {
                        vm.c.privateTrash.restore(file)?.let { ContactRef.Private(it).navId }
                    }
                }
            }
        }
    }
    else -> null
}

@Composable
@Suppress("CyclomaticComplexMethod") // One icon per group.
private fun Leading(hit: RecallHit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (hit.source) {
        RecallSource.CONTACT -> Avatar(hit.title, null, avatarSize())
        RecallSource.ARCHIVED -> Icon(Icons.Rounded.Archive, null, tint = tint)
        RecallSource.CALL -> CallTypeIcon(hit.callType ?: CallType.UNKNOWN, durationSec = hit.durationSec)
        RecallSource.AGENDA -> Icon(Icons.Rounded.Checklist, null, tint = tint)
        RecallSource.PROMISE -> Icon(Icons.Rounded.CheckBoxOutlineBlank, null, tint = tint)
        RecallSource.NOTE -> Icon(Icons.AutoMirrored.Rounded.Notes, null, tint = tint)
        RecallSource.CALL_NOTE -> Icon(Icons.Rounded.EditNote, null, tint = tint)
        RecallSource.CASE_FILE -> Icon(Icons.Rounded.FolderOpen, null, tint = tint)
        RecallSource.MESSAGED -> Icon(Icons.AutoMirrored.Rounded.Chat, null, tint = tint)
        RecallSource.DELETED, RecallSource.DELETED_PRIVATE -> Icon(Icons.Rounded.RestoreFromTrash, null, tint = tint)
        RecallSource.SNAPSHOT -> Icon(Icons.Rounded.History, null, tint = tint)
        RecallSource.REMEMBERED -> Icon(if (hit.memory?.source == MemorySource.CALLS) Icons.Rounded.Phone else Icons.Rounded.ManageSearch, null, tint = tint)
    }
}

/** The row's second line, with the matched words in bold where it quotes the found text. */
@Composable
@Suppress("CyclomaticComplexMethod") // One line per group.
private fun supporting(context: Context, vm: AppViewModel, hit: RecallHit): AnnotatedString? {
    val sep = stringResource(R.string.main_separator)
    val res = context.resources
    val number = hit.number?.takeIf { it.isNotBlank() && it != hit.title }?.let { Bidi.ltr(Format.number(it, vm.countryIso)) }
    fun plain(vararg parts: String?) = AnnotatedString(parts.filterNot { it.isNullOrBlank() }.joinToString(sep))
    return when (hit.source) {
        // A saved contact found by the name the network sent for their number says so ("Network: Ravi Traders").
        RecallSource.CONTACT -> hit.field?.let { AnnotatedString(matchHint(res, it)) }
            ?: hit.networkMatch?.let { AnnotatedString(res.getString(R.string.network_name_under, it)) }
        RecallSource.ARCHIVED -> plain(stringResource(R.string.archive_row_when, dayText(hit.at)), number)
        // A name from the network keeps its number in sight.
        RecallSource.CALL -> AnnotatedString(listOfNotNull(callLine(context, hit, sep), number.takeIf { hit.fromNetwork }).joinToString(sep))
        RecallSource.AGENDA, RecallSource.PROMISE -> highlighted(hit.detail, hit.detailMarks)
        RecallSource.NOTE, RecallSource.CALL_NOTE -> noteLine(context, hit, sep)
        RecallSource.CASE_FILE -> plain(stringResource(R.string.recall_case_line, Format.shortWhen(context, hit.at)), number)
        RecallSource.MESSAGED -> plain(stringResource(R.string.archive_page_work, hit.detail, Format.shortWhen(context, hit.at)))
        RecallSource.DELETED, RecallSource.DELETED_PRIVATE -> plain(stringResource(R.string.recall_deleted_on, dayText(hit.at)), number)
        RecallSource.SNAPSHOT -> plain(stringResource(R.string.recall_snapshot_until, dayText(hit.at)), number)
        RecallSource.REMEMBERED -> hit.memory?.let { AnnotatedString(NumberMemoryText.line(context, it)) }
    }
}

/** A note's line: when (if it has a date), then the words around the match. */
@Composable
private fun noteLine(context: Context, hit: RecallHit, sep: String): AnnotatedString {
    val text = highlighted(hit.detail, hit.detailMarks)
    if (hit.at <= 0) return text
    return buildAnnotatedString {
        append(whenText(context, hit.at) + sep)
        append(text)
    }
}

/** "Incoming · 12 Mar, 14:05 · 3m 5s", and which field of the caller matched. */
private fun callLine(context: Context, hit: RecallHit, sep: String): String = listOfNotNull(
    hit.callType?.let { context.getString(HistoryText.callType(it)) },
    whenText(context, hit.at),
    Format.duration(hit.durationSec).takeIf { hit.callType != CallType.MISSED && it.isNotEmpty() },
    hit.field?.let { matchHint(context.resources, it) },
    hit.networkMatch?.let { context.getString(R.string.network_name_under, it) },
).joinToString(sep)

/** [text] with [marks] in bold and the accent colour; [ltr] keeps a number left to right in any language. */
@Composable
private fun highlighted(text: String, marks: List<IntRange>, ltr: Boolean = false): AnnotatedString {
    val style = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    val shown = if (ltr) Bidi.ltr(text) else text
    val shift = shown.indexOf(text).coerceAtLeast(0)
    return buildAnnotatedString {
        append(shown)
        marks.forEach { r ->
            val from = r.first + shift
            val to = r.last + 1 + shift
            if (from >= 0 && to <= shown.length && from < to) addStyle(style, from, to)
        }
    }
}

/** Where a result opens: the contact, the number's history, the note's page, History & undo or To call. */
@Suppress("CyclomaticComplexMethod") // One target per group.
private fun targetOf(vm: AppViewModel, hit: RecallHit): Destination? {
    val number = hit.number?.takeIf { it.isNotBlank() }
    return when (hit.source) {
        RecallSource.CONTACT -> hit.contactId?.let(Routes::contact)
        // An archived contact's calls, by its number; the Archived list when it has none.
        RecallSource.ARCHIVED -> number?.let(Routes::history) ?: PeopleRoutes.Archived
        // A call with a private contact opens their page; any other, the number's calls and notes.
        RecallSource.CALL -> hit.contactId?.takeIf { it < 0 }?.let(Routes::contact) ?: number?.let(Routes::history)
        RecallSource.AGENDA, RecallSource.PROMISE, RecallSource.NOTE -> hit.ref?.let { noteTarget(vm, it) } ?: number?.let(Routes::history)
        RecallSource.CALL_NOTE, RecallSource.MESSAGED -> number?.let(Routes::history)
        RecallSource.CASE_FILE -> hit.ref?.let { HistoryRoutes.Case(it) }
        RecallSource.DELETED, RecallSource.DELETED_PRIVATE -> Routes.journal(HistoryTab.CONTACTS)
        RecallSource.SNAPSHOT -> Routes.journal(HistoryTab.SNAPSHOTS)
        RecallSource.REMEMBERED -> if (hit.memory?.source == MemorySource.TO_CALL) ToCallRoutes.List else number?.let(Routes::history)
    }
}

/** "12 Mar, 14:05", with the year when it isn't this year's. */
private fun whenText(context: Context, at: Long): String {
    val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.YEAR)
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME or
        if (then == thisYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateTime(context, at, flags)
}

/** "12 March 2026" in the user's order. */
private fun dayText(at: Long): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())
    .format(java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

/** What the search understood, said back: "Missed calls · March 2026 · “plumber”". */
private fun describe(context: Context, q: RecallQuery): String {
    val res = context.resources
    val parts = ArrayList<String>()
    val types = q.callTypes
    if (types != null) {
        parts += types.sortedBy { it.ordinal }.joinToString(res.getString(R.string.contact_page_list_separator)) { res.getString(HistoryText.callType(it)) }
    } else if (q.callsOnly) {
        parts += res.getString(R.string.quality_subject_all)
    }
    q.dates?.let { parts += span(context, it) }
    if (!q.search.isEmpty) parts += res.getString(R.string.callfacts_subject, q.words)
    return parts.joinToString(res.getString(R.string.main_separator))
}

private fun span(context: Context, d: RecallQuery.DateSpan): String {
    val res = context.resources
    val zone = ZoneId.systemDefault()
    fun skeleton(s: String) = android.icu.text.DateFormat.getInstanceForSkeleton(s, Locale.getDefault()).format(java.util.Date(d.startMillis(zone)))
    return when (d.kind) {
        RecallQuery.DateSpan.Kind.TODAY -> res.getString(R.string.blk_dry_today)
        RecallQuery.DateSpan.Kind.YESTERDAY -> res.getString(R.string.main_yesterday)
        RecallQuery.DateSpan.Kind.WEEK_SO_FAR -> res.getString(R.string.bday_this_week)
        RecallQuery.DateSpan.Kind.SINCE_LAST_WEEK -> res.getString(R.string.recall_span_since_last_week)
        RecallQuery.DateSpan.Kind.SINCE_LAST_MONTH -> res.getString(R.string.recall_span_since_last_month)
        RecallQuery.DateSpan.Kind.SINCE_LAST_YEAR -> res.getString(R.string.recall_span_since_last_year)
        RecallQuery.DateSpan.Kind.DAY -> skeleton("dMMMMyyyy")
        RecallQuery.DateSpan.Kind.MONTH -> skeleton("LLLLyyyy")
        RecallQuery.DateSpan.Kind.YEAR -> skeleton("yyyy")
    }
}
