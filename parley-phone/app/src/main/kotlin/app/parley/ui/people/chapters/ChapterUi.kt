package app.parley.ui.people.chapters

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.people.Chapter
import app.parley.common.people.Chapters
import app.parley.common.people.ContactRef
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import app.parley.ui.people.archive.ArchiveActions
import app.parley.work.ChapterNotices
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A label's chapter on its page: "Give it an end", then what is left of it ("3 weeks left", the date), then, once it
 * has ended, the one card that asks what to do (Keep, Archive who joined for it, Remove the label, Delete the temporary
 * contacts made for it). Nothing happens until a choice is made; deleting goes through the usual delete, with Undo.
 * For a shared label only the phone that owns it decides (the others see why they can't).
 */
@Composable
fun ChapterSection(vm: AppViewModel, title: String, members: List<ContactSummary>, removeLabel: () -> Unit) {
    val res = LocalResources.current
    val chapters by vm.c.extras.chapters.collectAsStateWithLifecycle()
    val shared by vm.c.sharedLabels.states.collectAsStateWithLifecycle()
    val isShared = shared.any { it.title == title }
    val owner = remember(shared, title) { vm.c.sharedLabels.isOwner(title) }
    val temporaries by vm.c.temporaries.all.collectAsStateWithLifecycle(emptyList())
    val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val chapter = chapters[title]
    val now = remember(chapter) { System.currentTimeMillis() }
    val zone = ZoneId.systemDefault()
    var setting by rememberSaveable { mutableStateOf(false) }
    val people = remember(members, temporaries, vault) {
        val privateTemps = vault.filter { it.expiresAt != null }.map { it.id }.toSet()
        memberFacts(members, temporaries.map { it.lookupKey }.toSet(), temporaries.map { it.contactId }.toSet(), privateTemps)
    }

    if (!Chapters.decidesHere(isShared, owner)) {
        ParleyListItem(
            leadingContent = { Icon(Icons.Rounded.HourglassBottom, null) },
            headlineContent = { Text(stringResource(R.string.chapter_give_end)) },
            supportingContent = { Text(stringResource(R.string.chapter_owner_only)) },
        )
        return
    }
    when {
        chapter == null -> ParleyListItem(
            modifier = Modifier.clickable { setting = true },
            leadingContent = { Icon(Icons.Rounded.HourglassBottom, null) },
            headlineContent = { Text(stringResource(R.string.chapter_give_end)) },
            supportingContent = { Text(stringResource(R.string.chapter_give_end_text)) },
        )
        Chapters.isOver(chapter, now) -> ChapterEndCard(title, chapter, members, people) { outcome ->
            decide(vm, res, title, chapter, people, outcome, removeLabel)
        }
        else -> ParleyListItem(
            modifier = Modifier.clickable(onClickLabel = stringResource(R.string.chapter_change_end)) { setting = true },
            leadingContent = { Icon(Icons.Rounded.Event, null) },
            headlineContent = { Text(stringResource(R.string.chapter_ends_on, dateText(Chapters.lastDay(chapter, zone)))) },
            supportingContent = { Text(chapterLeft(res, chapter, now, zone)) },
            trailingContent = {
                // Ended here and now: the card below asks at once, so no notification follows.
                TextButton({
                    val t = System.currentTimeMillis()
                    vm.c.extras.updateChapters { m -> m[title]?.let { m + (title to it.copy(endsAt = t, askedAt = t)) } ?: m }
                }) {
                    Text(stringResource(R.string.chapter_end_now))
                }
            },
        )
    }
    if (setting) {
        ChapterEndDialog(
            initial = chapter?.let { Chapters.lastDay(it, zone) },
            onDismiss = { setting = false },
            onRemove = if (chapter == null) null else ({ setting = false; vm.c.extras.updateChapters { it - title } }),
        ) { length ->
            setting = false
            val at = System.currentTimeMillis()
            val ends = Chapters.endsAt(length, at, zone)
            vm.c.extras.updateChapters { m ->
                val old = m[title]
                m + (title to (if (old != null) Chapters.withEnd(old, ends, at) else Chapters.begin(people, ends, at)))
            }
        }
    }
}

/** What a choice on the ended chapter's card does. */
private fun decide(
    vm: AppViewModel, res: Resources, title: String, chapter: Chapter, people: List<Chapters.Member>, outcome: Chapters.Outcome, removeLabel: () -> Unit,
) {
    when (outcome) {
        Chapters.Outcome.KEEP -> {
            vm.c.extras.updateChapters { it - title }
            ChapterNotices.cancel(vm.c.appContext)
            vm.toast(res.getString(R.string.chapter_kept, title))
        }
        // The card stays for the other choices; those archived leave the label, so this one goes.
        Chapters.Outcome.ARCHIVE_ADDED -> vm.c.scope.launch {
            val done = ArchiveActions.archive(vm, Chapters.toArchive(chapter, people).map { it.id })
            vm.toast(res.getQuantityString(R.plurals.archive_n_done, done.size, done.size))
        }
        Chapters.Outcome.REMOVE_LABEL -> {
            ChapterNotices.cancel(vm.c.appContext)
            removeLabel()
        }
        // The usual delete: History & undo (or Recently deleted for a private one) keeps them, with Undo.
        Chapters.Outcome.DELETE_TEMPORARY -> vm.deleteContacts(Chapters.toDelete(chapter, people).map { it.id })
    }
}

/** The label's members as the chapter rules see them: Parley key, and whether each is temporary or private. */
internal fun memberFacts(members: List<ContactSummary>, tempKeys: Set<String>, tempIds: Set<Long>, privateTemps: Set<Long>): List<Chapters.Member> =
    members.map { m ->
        val private = m.id < 0
        val key = if (private) ContactRef.privateKey(-m.id) else m.lookupKey
        val temporary = if (private) -m.id in privateTemps else m.lookupKey in tempKeys || m.id in tempIds
        Chapters.Member(m.id, key, temporary, private)
    }

/** "3 weeks left", "Last day today" or "Chapter ended". */
fun chapterLeft(res: Resources, chapter: Chapter, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    when (val r = Chapters.remaining(chapter, now, zone)) {
        Chapters.Remaining.Ended -> res.getString(R.string.chapter_ended_short)
        Chapters.Remaining.LastDay -> res.getString(R.string.chapter_last_day)
        is Chapters.Remaining.Days -> res.getQuantityString(R.plurals.chapter_days_left, r.n, r.n)
        is Chapters.Remaining.Weeks -> res.getQuantityString(R.plurals.chapter_weeks_left, r.n, r.n)
        is Chapters.Remaining.Months -> res.getQuantityString(R.plurals.chapter_months_left, r.n, r.n)
    }

private fun dateText(day: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(day)

/** The one question at a chapter's end, as a card on the label: only the choices that would do something. */
@Composable
private fun ChapterEndCard(
    title: String,
    chapter: Chapter,
    members: List<ContactSummary>,
    people: List<Chapters.Member>,
    onOutcome: (Chapters.Outcome) -> Unit,
) {
    val names = members.associate { it.id to it.displayName }
    val archive = Chapters.toArchive(chapter, people)
    val delete = Chapters.toDelete(chapter, people)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer, shape = ParleyShapes.card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row {
                Icon(Icons.Rounded.EventAvailable, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.chapter_ended_title, title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.chapter_ended_text), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Spacing.xs))
            Chapters.outcomes(chapter, people).forEach { o ->
                when (o) {
                    Chapters.Outcome.KEEP -> FilledTonalButton({ onOutcome(o) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.chapter_keep)) }
                    Chapters.Outcome.ARCHIVE_ADDED -> {
                        OutlinedButton({ onOutcome(o) }, Modifier.fillMaxWidth()) {
                            Text(pluralStringResource(R.plurals.chapter_archive_added, archive.size, archive.size))
                        }
                        // Who exactly, so nobody is archived by surprise.
                        Text(
                            archive.mapNotNull { names[it.id] }.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Chapters.Outcome.REMOVE_LABEL -> OutlinedButton({ onOutcome(o) }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chapter_remove_label))
                    }
                    Chapters.Outcome.DELETE_TEMPORARY -> {
                        OutlinedButton({ onOutcome(o) }, Modifier.fillMaxWidth()) {
                            Text(pluralStringResource(R.plurals.chapter_delete_temporary, delete.size, delete.size))
                        }
                        Text(
                            delete.mapNotNull { names[it.id] }.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/** The end chosen in the dialog: [day] (an epoch day) when [onDate], else [count] weeks or [months]. */
private fun lengthOf(onDate: Boolean, day: Long, count: Int, months: Boolean): Chapters.Length =
    if (onDate) Chapters.Length.OnDate(LocalDate.ofEpochDay(day)) else Chapters.Length.After(count, if (months) Chapters.Span.MONTHS else Chapters.Span.WEEKS)

/** "After 4 weeks": how many, and weeks or months. */
@Composable
private fun AfterChoice(count: String, months: Boolean, onCount: (String) -> Unit, onMonths: (Boolean) -> Unit) {
    Row(Modifier.padding(start = Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            count, { v -> onCount(v.filter(Char::isDigit).take(2)) },
            Modifier.padding(top = Spacing.xs).fillMaxWidth(COUNT_WIDTH),
            label = { Text(stringResource(R.string.chapter_count)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Column {
            FilterChip(!months, { onMonths(false) }, label = { Text(stringResource(R.string.chapter_weeks)) })
            FilterChip(months, { onMonths(true) }, label = { Text(stringResource(R.string.chapter_months)) })
        }
    }
}

/** The share of the row the count field takes. */
private const val COUNT_WIDTH = 0.35f

/** "When does this chapter end?": on a date, or after a number of weeks or months; "No end" for one that has an end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterEndDialog(initial: LocalDate?, onDismiss: () -> Unit, onRemove: (() -> Unit)?, onSet: (Chapters.Length) -> Unit) {
    val today = LocalDate.now()
    var onDate by rememberSaveable { mutableStateOf(initial != null) }
    var day by rememberSaveable { mutableStateOf(initial?.toEpochDay()) }
    var count by rememberSaveable { mutableStateOf("4") }
    var months by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val n = count.toIntOrNull()
    val max = if (months) Chapters.MAX_MONTHS else Chapters.MAX_WEEKS
    val valid = if (onDate) day?.let { !LocalDate.ofEpochDay(it).isBefore(today) } == true else n != null && n in 1..max
    ConfirmDialog(
        title = stringResource(R.string.chapter_set_title),
        text = null,
        confirmLabel = stringResource(R.string.chapter_set),
        confirmEnabled = valid,
        onConfirm = { onSet(lengthOf(onDate, day ?: today.toEpochDay(), n ?: 1, months)) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        content = {
            ParleyListItem(
                modifier = Modifier.clickable { onDate = true },
                leadingContent = { RadioButton(onDate, { onDate = true }) },
                headlineContent = { Text(stringResource(R.string.chapter_on_date)) },
                supportingContent = if (onDate) ({
                    TextButton({ picking = true }) { Text(day?.let { dateText(LocalDate.ofEpochDay(it)) } ?: stringResource(R.string.chapter_pick_date)) }
                }) else null,
            )
            ParleyListItem(
                modifier = Modifier.clickable { onDate = false },
                leadingContent = { RadioButton(!onDate, { onDate = false }) },
                headlineContent = { Text(stringResource(R.string.chapter_after)) },
            )
            if (!onDate) AfterChoice(count, months, { count = it }, { months = it })
            if (onRemove != null) {
                TextButton(onRemove, Modifier.padding(top = Spacing.s)) { Text(stringResource(R.string.chapter_remove_end)) }
            }
        },
    )
    if (picking) {
        ChapterDatePicker(day, today, onDismiss = { picking = false }) { picked ->
            day = picked
            picking = false
        }
    }
}

/** The day a chapter ends: today or later. [onPick] gets it as an epoch day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterDatePicker(day: Long?, today: LocalDate, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val startOfToday = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = day?.let { LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= startOfToday
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                val picked = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                if (picked != null) onPick(picked) else onDismiss()
            }) {
                Text(stringResource(R.string.dc_ok))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    ) { DatePicker(state) }
}
