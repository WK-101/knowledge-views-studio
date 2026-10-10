package app.parley.ui.people

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.catching
import app.parley.common.people.ContactRef
import app.parley.common.people.RelationLinks
import app.parley.common.people.RelationTypes
import app.parley.common.people.SocialProfiles
import app.parley.data.ContactDetails
import app.parley.data.people.RelationMirrors
import app.parley.ui.Clipboard
import app.parley.ui.DataL10n
import app.parley.ui.Destination
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.contact.AddressDetailRow
import app.parley.ui.contact.AddressMapLinks
import app.parley.ui.contact.GroupDataRow
import app.parley.ui.contact.LinkifiedText
import app.parley.ui.contact.describeCalendarEvent
import app.parley.ui.contact.handleRows
import app.parley.ui.contact.moreFacts
import app.parley.ui.contact.profileRows
import app.parley.ui.contact.relationLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * My card's details, drawn with the same rows as a contact's page: numbers, e-mails, addresses and messaging apps as
 * Contact info; work; dates; websites, profiles and relations (a tap opens the contact a relation names); the other
 * fields (languages, citizenship, custom fields…) and the note. A tap on a fact copies it, as on a contact's page.
 * [links]: which contact each relation names; [hidePrivate]: discreet mode, where relations naming a private contact
 * stay out of sight.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // One group per kind of field, as on a contact's page.
@Composable
internal fun MeCardDetailsSections(
    vm: AppViewModel,
    d: ContactDetails,
    links: Map<String, RelationLinks.Link>,
    hidePrivate: Boolean,
    open: (Destination) -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val copy = { text: String -> Clipboard.copy(context, text, sensitive = false) }
    val phones = d.phones.filter { it.value.isNotBlank() }
    val emails = d.emails.filter { it.value.isNotBlank() }
    val addresses = d.addresses.filterNot { it.isBlank }
    val handles = d.handles.filter { it.value.isNotBlank() }
    Column {
        if (listOf(phones, emails, addresses, handles).any { it.isNotEmpty() }) {
            SegmentedGroup(stringResource(R.string.contact_page_info)) {
                phones.forEachIndexed { i, p ->
                    item {
                        GroupDataRow(
                            Icons.Rounded.Call, i == 0, p.value, Format.phoneType(res, p.type, p.label), onClick = { copy(p.value) },
                            headline = { Text(DataL10n.ltr(p.value)) },
                        )
                    }
                }
                emails.forEachIndexed { i, e ->
                    item {
                        GroupDataRow(Icons.Rounded.Email, i == 0, e.value, Email.getTypeLabel(res, e.type, e.label).toString(), onClick = { copy(e.value) })
                    }
                }
                val mapLinks = AddressMapLinks.matches(d)
                d.addresses.forEachIndexed { i, a ->
                    if (a.isBlank) return@forEachIndexed
                    val link = mapLinks[i]?.let { d.websites.getOrNull(it)?.value }
                    item { AddressDetailRow(a, a == addresses.first(), StructuredPostal.getTypeLabel(res, a.type, a.label).toString(), link) }
                }
                handleRows(handles, Icons.AutoMirrored.Rounded.Chat, onWeb = { Intents.web(context, it.uri) })
            }
        }
        val work = listOf(d.title, d.department, d.company).filter { it.isNotBlank() }.joinToString(", ")
        if (work.isNotEmpty()) {
            SegmentedGroup(stringResource(R.string.me_part_work)) {
                item { GroupDataRow(Icons.Rounded.Business, true, work, null, onClick = { copy(work) }) }
            }
        }
        val events = d.events.filter { it.date.isNotBlank() }
        if (events.isNotEmpty()) {
            val today = LocalDate.now()
            SegmentedGroup(stringResource(R.string.contact_page_sec_dates)) {
                events.forEachIndexed { i, ev ->
                    item {
                        val text = describeCalendarEvent(res, ev.date, ev.calendar, today) ?: describeLifeEvent(res, d, ev)
                        GroupDataRow(Icons.Rounded.Cake, i == 0, text, eventLabel(res, ev), onClick = { copy(text) })
                    }
                }
            }
        }
        // Profiles (Instagram, LinkedIn…) open in their app; an address's map link opens from the address.
        val mapLinks = AddressMapLinks.matches(d).values.toSet()
        val profiles = d.websites.mapNotNull { w -> SocialProfiles.fromWebsite(w.value, w.type, w.label)?.takeIf { it.handle.isNotBlank() } }
        val sites = d.websites.filterIndexed { i, w ->
            w.value.isNotBlank() && i !in mapLinks && SocialProfiles.fromWebsite(w.value, w.type, w.label)?.handle.isNullOrBlank()
        }
        if (profiles.isNotEmpty() || sites.isNotEmpty()) {
            SegmentedGroup(stringResource(R.string.me_websites)) {
                profileRows(profiles)
                sites.forEachIndexed { i, w ->
                    item { GroupDataRow(Icons.Rounded.Language, i == 0 && profiles.isEmpty(), w.value, null, onClick = { Intents.web(context, w.value) }) }
                }
            }
        }
        val relations = d.relations.filter { r ->
            r.value.isNotBlank() && !(hidePrivate && links[RelationLinks.nameKey(r.value)]?.lookupKey?.let(ContactRef::isPrivateKey) == true)
        }
        if (relations.isNotEmpty()) {
            SegmentedGroup(stringResource(R.string.me_part_relations)) {
                relations.forEachIndexed { i, rel ->
                    item {
                        val row = RelationMirrors.rowOf(rel)
                        val known = row.typeKey?.let(RelationTypes::byKey)?.let { RelationText.label(res, it) }
                        val label = relationLabel(res, row.typeKey, known) ?: rel.label.orEmpty()
                        val link = links[RelationLinks.nameKey(rel.value)]
                        GroupDataRow(
                            Icons.Rounded.People, i == 0, rel.value, label,
                            onClick = if (link == null) null else {
                                {
                                    scope.launch {
                                        val id = ContactRef.vaultIdOf(link.lookupKey)?.let { ContactRef.Private(it).navId }
                                            ?: withContext(Dispatchers.IO) {
                                                catching { vm.c.contacts.currentOf(link.lookupKey, link.contactId)?.first }.getOrNull()
                                            }
                                        if (id != null) open(Routes.contact(id)) else copy(rel.value)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        val more = moreFacts(res, d)
        if (more.isNotEmpty()) {
            SegmentedGroup(stringResource(R.string.contact_page_sec_other)) {
                more.forEachIndexed { i, f -> item { GroupDataRow(Icons.Rounded.Info, i == 0, f.value, f.label, onClick = { copy(f.value) }) } }
            }
        }
        if (d.note.isNotBlank()) {
            SegmentedGroup(stringResource(R.string.blk_col_note)) {
                item {
                    GroupDataRow(
                        Icons.AutoMirrored.Rounded.Notes, true, d.note, stringResource(R.string.me_note_hint), onClick = { copy(d.note) },
                        headline = { LinkifiedText(d.note) },
                    )
                }
            }
        }
    }
}
