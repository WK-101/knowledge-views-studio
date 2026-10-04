package app.parley.ui.contact

import android.provider.ContactsContract
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.EventDate
import app.parley.common.circle.YearlyEvents
import app.parley.common.people.ContactCapability
import app.parley.common.people.ContactPage
import app.parley.common.people.ContactSection
import app.parley.common.people.LifeEvents
import app.parley.common.people.RelationTypes
import app.parley.common.people.SocialProfiles
import app.parley.ui.Clipboard
import app.parley.ui.OnGroupSurface
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.circle.ContactTimeline
import app.parley.ui.circle.PromisesCard
import app.parley.ui.circle.StayInTouchCard
import app.parley.ui.circle.timelineEntries
import app.parley.ui.common.Intents
import app.parley.ui.history.CallInsightsSection
import app.parley.ui.people.RelationText
import app.parley.ui.people.describeLifeEvent
import app.parley.ui.people.eventLabel
import java.time.ZoneId

/**
 * Stay in touch first when there's something to say: the rhythm (Circle), a good time to call, promises. Outside the
 * Circle, "Add to your Circle" waits in the settings group and the next date is in the header.
 */
@Composable
internal fun StayInTouchSection(sections: PageSections, ctx: ContactPageContext) {
    val d = ctx.d
    val memory = ctx.ui.memory
    val stayHasNews = ctx.inCircle || ctx.goodTime != null || memory.promises.isNotEmpty()
    if (d.lookupKey.isEmpty() || !stayHasNews) return
    sections.add(ContactSection.STAY, sectionTitle(LocalResources.current, ContactSection.STAY), ctx.lastTalked) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            StayInTouchCard(ctx.ui.meta, d, ctx.ui.history, ctx.ui.interactions, goodTime = ctx.goodTime, title = null, showNext = false, invite = false) {
                ctx.show(ContactDialog.Rhythm)
            }
            if (memory.promises.isNotEmpty()) PromisesCard(ctx.vm, d.lookupKey, memory)
        }
    }
}

/** The "About" family: dates, websites, relations and the contact's own note, then other fields and the note for calls. */
@Composable
internal fun AboutSections(sections: PageSections, ctx: ContactPageContext) {
    DatesSection(sections, ctx)
    AboutSection(sections, ctx)
    // Custom fields, the language, RFC 9554's name and address parts.
    val d = ctx.d
    val resources = LocalResources.current
    val more = remember(d) { moreFacts(resources, d) }
    if (more.isNotEmpty()) {
        val summary = resources.getQuantityString(R.plurals.contact_page_count_items, more.size, more.size)
        sections.addRows(ContactSection.MORE, sectionTitle(resources, ContactSection.MORE), summary) {
            more.forEachIndexed { i, f -> item { GroupDataRow(Icons.Rounded.Info, i == 0, f.value, f.label, onClick = null) } }
        }
    }
    NoteForCallsSection(sections, ctx)
}

@Composable
@Suppress("CyclomaticComplexMethod") // A row per date, each with its own yearly reminder.
private fun DatesSection(sections: PageSections, ctx: ContactPageContext) {
    val d = ctx.d
    val quickDates = ctx.can(ContactCapability.QUICK_DATES) && hasMissingDates(d)
    if (d.events.isEmpty() && !quickDates) return
    val resources = LocalResources.current
    val sep = stringResource(R.string.main_separator)
    val today = ctx.today
    val next = ContactPage.nextDate(ctx.dated.map { it.second }, today)?.let { (j, days) -> ctx.dated[j].first to days }
    val summary = next?.let { (i, days) -> ctx.dateText(i, days) }
        ?: if (d.events.isNotEmpty()) resources.getQuantityString(R.plurals.contact_page_count_dates, d.events.size, d.events.size) else ""
    sections.addRows(ContactSection.DATES, sectionTitle(resources, ContactSection.DATES), summary) {
        val yearly = YearlyEvents.decode(ctx.ui.meta?.yearlyEvents)
        d.events.forEachIndexed { i, ev ->
            item {
                // A life event (new job, moved…) can be remembered yearly in the digest.
                val date = EventDate.parse(ev.date)
                val canYearly = date != null && d.lookupKey.isNotEmpty() && YearlyEvents.eligible(ev.type) && !LifeEvents.isDeath(ev.type, ev.label)
                val key = if (canYearly) YearlyEvents.key(ev.type, ev.label, date!!) else null
                val on = key != null && key in yearly
                GroupDataRow(
                    Icons.Rounded.Cake, i == 0, describeCalendarEvent(resources, ev.date, ev.calendar, today) ?: describeLifeEvent(resources, d, ev),
                    eventLabel(resources, ev) + (if (on) sep + resources.getString(R.string.circle_yearly_label) else ""),
                    onClick = {},
                    trailing = if (key == null) {
                        null
                    } else {
                        {
                            IconButton({ ctx.page.setYearly(key, !on) }) {
                                Icon(
                                    Icons.Rounded.EventRepeat,
                                    stringResource(if (on) R.string.circle_yearly_stop else R.string.circle_yearly_remember),
                                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            }
        }
        // Empty birthday / anniversary slots, saved straight to the system contact.
        if (quickDates) {
            item {
                MissingDateChips(
                    ctx.vm, d, onSaved = ctx.page::reload, modifier = Modifier.padding(vertical = Spacing.xs), save = { e -> ctx.page.addDate(d, e) },
                )
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod") // Websites, three kinds of relation and the note, each in its own rows.
private fun AboutSection(sections: PageSections, ctx: ContactPageContext) {
    val d = ctx.d
    val resources = LocalResources.current
    val context = LocalContext.current
    // An address's map link opens from the address itself, so it isn't listed again as a website.
    // Profiles have their own group above.
    val mapLinks = remember(d.addresses, d.websites) { AddressMapLinks.matches(d) }
    val sites = d.websites.filterIndexed { i, w -> i !in mapLinks.values && SocialProfiles.fromWebsite(w.value, w.type, w.label)?.handle.isNullOrBlank() }
    val parleyRelations = ctx.parleyRelations
    val relationsFromOthers = ctx.relationsFromOthers
    val relationCount = d.relations.size + parleyRelations.size + relationsFromOthers.size
    if (sites.isEmpty() && d.note.isBlank() && relationCount == 0) return
    val n = sites.size + relationCount + (if (d.note.isNotBlank()) 1 else 0)
    val title = resources.getString(R.string.detail_about, d.given.ifBlank { d.displayName })
    sections.addRows(ContactSection.ABOUT, title, resources.getQuantityString(R.plurals.contact_page_count_items, n, n)) {
        sites.forEachIndexed { i, w ->
            item {
                val label = resources.getString(R.string.detail_website)
                GroupDataRow(Icons.Rounded.Language, i == 0, w.value, label, onClick = { Intents.web(context, w.value) })
            }
        }
        d.relations.forEachIndexed { i, rel ->
            item {
                val type = RelationTypes.fromAndroid(rel.type, rel.label)
                val label = relationLabel(resources, type?.key, type?.let { RelationText.label(resources, it) })
                    ?: ContactsContract.CommonDataKinds.Relation.getTypeLabel(resources, rel.type, rel.label).toString()
                GroupDataRow(Icons.Rounded.People, i == 0, rel.value, label, onClick = { ctx.openRelation(rel.value) })
            }
        }
        // Relations kept in Parley only: the same rows, saying where they live.
        parleyRelations.forEachIndexed { i, rel ->
            item {
                val type = RelationTypes.fromAndroid(rel.type, rel.label)
                val label = relationLabel(resources, type?.key, type?.let { RelationText.label(resources, it) })
                    ?: ContactsContract.CommonDataKinds.Relation.getTypeLabel(resources, rel.type, rel.label).toString()
                GroupDataRow(
                    Icons.Rounded.People, d.relations.isEmpty() && i == 0, rel.value, resources.getString(R.string.detail_relation_parley_only, label),
                    onClick = { ctx.openRelation(rel.value) },
                )
            }
        }
        // A private contact's relation to this one (or this private contact's from another): shown, never written
        // where other apps could read it (RelationsFromOthers).
        relationsFromOthers.forEachIndexed { i, other ->
            item {
                val known = other.row.typeKey?.let(RelationTypes::byKey)?.let { RelationText.label(resources, it) }
                val type = relationLabel(resources, other.row.typeKey, known) ?: other.row.label.orEmpty()
                GroupDataRow(
                    Icons.Rounded.People, d.relations.isEmpty() && parleyRelations.isEmpty() && i == 0, other.row.name,
                    resources.getString(R.string.detail_relation_from_them, type),
                    onClick = { ctx.open(Routes.contact(other.navId)) },
                )
            }
        }
        if (d.note.isNotBlank()) {
            item {
                GroupDataRow(
                    Icons.AutoMirrored.Rounded.Notes, true, d.note, resources.getString(R.string.detail_note), onClick = {},
                    headline = { LinkifiedText(d.note) },
                )
            }
        }
    }
}

@Composable
private fun NoteForCallsSection(sections: PageSections, ctx: ContactPageContext) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val note = ctx.ui.meta?.pinnedNote
    val summary = note?.lineSequence()?.firstOrNull().orEmpty().ifBlank { resources.getString(R.string.contact_page_no_note) }
    sections.addRows(ContactSection.NOTE, sectionTitle(resources, ContactSection.NOTE), summary) {
        item {
            InfoRow(
                // Tap edits it; press and hold copies it, like the page's other facts.
                modifier = Modifier.combinedClickable(
                    onClick = { ctx.show(ContactDialog.EditNote) },
                    onLongClick = note?.let { n -> { Clipboard.copy(context, n) } },
                    onLongClickLabel = note?.let { stringResource(R.string.main_copy) },
                ),
                leading = {
                    val cs = MaterialTheme.colorScheme
                    Icon(Icons.Rounded.PushPin, null, tint = if (note != null) cs.primary else cs.onSurfaceVariant)
                },
                headline = { Text(note ?: stringResource(R.string.detail_add_note)) },
                supporting = { Text(stringResource(if (note != null) R.string.detail_note_shown else R.string.detail_note_hint)) },
            )
        }
    }
}

/** Calls, logged interactions, call notes and dates (the latest few; "Show all" opens the rest), then call insights and other fields. */
@Composable
internal fun HistorySections(sections: PageSections, ctx: ContactPageContext) {
    val d = ctx.d
    val ui = ctx.ui
    val history = ui.history
    val resources = LocalResources.current
    val timelineCount = remember(history, ui.interactions, ui.notes, d.events) {
        timelineEntries(d, history, ui.interactions, ui.notes, ZoneId.systemDefault()).size
    }
    val entries = resources.getQuantityString(R.plurals.contact_page_entries, timelineCount, timelineCount)
    sections.add(ContactSection.TIMELINE, sectionTitle(resources, ContactSection.TIMELINE), entries) {
        ContactTimeline(
            ctx.vm, d, history, ui.interactions, ui.notes, onEdit = { ctx.show(ContactDialog.EditInteraction(it.id)) },
            // A number's own history screen lists the phone's call history; a private contact's calls are all here.
            onAllCalls = ctx.primary?.takeIf { history.size > 5 && !ctx.isPrivate }?.let { p -> { ctx.open(Routes.history(p.value)) } },
            limit = TIMELINE_PREVIEW, onShowAll = { ctx.open(ContactPageRoutes.timeline(ctx.contactId)) }, showTitle = false,
        )
    }
    if (history.isNotEmpty()) {
        val calls = resources.getQuantityString(R.plurals.contact_page_count_calls, history.size, history.size)
        sections.add(ContactSection.INSIGHTS, sectionTitle(resources, ContactSection.INSIGHTS), calls) {
            OnGroupSurface { CallInsightsSection(ctx.vm, d.phones.map { it.value }, showTitle = false, index = ui.privateIndex) }
        }
    }
    val otherFields = ui.otherFields
    if (otherFields.isNotEmpty()) {
        sections.addRows(
            ContactSection.OTHER, sectionTitle(resources, ContactSection.OTHER),
            resources.getQuantityString(R.plurals.contact_page_count_items, otherFields.size, otherFields.size),
            after = { GroupNote(stringResource(R.string.detail_other_fields_note)) },
        ) {
            otherFields.forEachIndexed { i, f -> item { GroupDataRow(Icons.Rounded.Info, i == 0, f.value, f.label, onClick = null) } }
        }
    }
}

/** Timeline entries shown on the page before "Show all" (the full timeline has search and filters). */
private const val TIMELINE_PREVIEW = 3
