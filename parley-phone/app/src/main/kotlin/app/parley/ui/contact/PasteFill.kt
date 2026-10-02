package app.parley.ui.contact

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import app.parley.common.PhoneIdentity
import app.parley.common.people.MapLinks
import app.parley.common.people.PasteParser
import app.parley.common.people.PasteParser.Kind
import app.parley.common.people.PasteParser.Label
import app.parley.common.people.SocialProfiles
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.PostalItem
import java.util.Locale

/**
 * "Paste details": the ticked fields as contact rows. [details] makes them a draft of their own (handed to an
 * existing contact's editor, which appends it); [into] fills a new contact being typed, where nothing typed is ever
 * replaced: empty fields are filled, empty rows used first, and values it already has aren't added twice.
 */
object PasteFill {
    @Suppress("CyclomaticComplexMethod") // One row kind per field kind.
    fun details(fields: List<PasteParser.Field>): ContactDetails {
        var d = ContactDetails()
        fields.firstOrNull { it.kind == Kind.NAME }?.name?.let { n ->
            d = d.copy(prefix = n.prefix, given = n.given, middle = n.middle, family = n.family, suffix = n.suffix)
        }
        val notes = ArrayList<String>()
        val phones = ArrayList<DataItem>()
        val emails = ArrayList<DataItem>()
        val sites = ArrayList<DataItem>()
        val addresses = ArrayList<PostalItem>()
        val events = ArrayList<EventItem>()
        for (f in fields) {
            when (f.kind) {
                Kind.NAME -> Unit
                Kind.ORGANISATION -> d = d.copy(company = d.company.ifBlank { f.value })
                Kind.JOB_TITLE -> d = d.copy(title = d.title.ifBlank { f.value })
                Kind.PHONE -> phones += DataItem(value = f.value, type = phoneType(f.label))
                Kind.EMAIL -> emails += DataItem(value = f.value, type = emailType(f.label))
                Kind.ADDRESS -> f.address?.let { a ->
                    addresses += PostalItem(
                        street = a.street, city = a.city, region = a.region, postcode = a.postcode, country = a.country,
                        type = if (f.label == Label.HOME) StructuredPostal.TYPE_HOME else StructuredPostal.TYPE_WORK,
                    )
                }
                Kind.WEBSITE -> sites += DataItem(value = f.value, type = if (f.label == Label.WORK) Website.TYPE_WORK else Website.TYPE_HOMEPAGE)
                // A profile is a website row labelled with its service, as the editor writes them.
                Kind.PROFILE -> f.profile?.let { p -> sites += DataItem(value = p.url, type = SocialProfiles.TYPE_CUSTOM, label = p.service.label) }
                Kind.BIRTHDAY -> events += EventItem(date = f.value, type = Event.TYPE_BIRTHDAY)
                Kind.NOTE -> notes += f.value
                Kind.MAP_LINK -> Unit
            }
        }
        d = d.copy(phones = phones, emails = emails, websites = sites, addresses = addresses, events = events, note = notes.joinToString("\n"))
        // Map links go with their address ("Map (Work)"), which they fill when the text gave none.
        for (f in fields.filter { it.kind == Kind.MAP_LINK }) {
            val place = f.place ?: continue
            val type = if (f.label == Label.HOME) StructuredPostal.TYPE_HOME else StructuredPostal.TYPE_WORK
            val index = d.addresses.indexOfFirst { it.type == type && AddressMapLinks.linkOf(d, d.addresses.indexOf(it)) == null }
            d = when {
                index >= 0 -> AddressMapLinks.withLink(d, index, place)
                place.name != null || place.hasCoordinates -> {
                    val added = d.copy(addresses = d.addresses + PostalItem(type = type))
                    AddressMapLinks.withLink(added, added.addresses.lastIndex, place)
                }
                else -> d.copy(websites = d.websites + DataItem(value = MapLinks.storedLink(place), type = Website.TYPE_CUSTOM, label = MapLinks.LABEL))
            }
        }
        return d
    }

    /** Fills a new contact's [draft] with [fields] (see the class documentation). */
    fun into(draft: ContactDetails, fields: List<PasteParser.Field>): ContactDetails {
        val add = details(fields)
        val noName = listOf(draft.prefix, draft.given, draft.middle, draft.family, draft.suffix).all { it.isBlank() }
        return draft.copy(
            prefix = if (noName) add.prefix else draft.prefix,
            given = if (noName) add.given else draft.given,
            middle = if (noName) add.middle else draft.middle,
            family = if (noName) add.family else draft.family,
            suffix = if (noName) add.suffix else draft.suffix,
            company = draft.company.ifBlank { add.company },
            title = draft.title.ifBlank { add.title },
            phones = rows(draft.phones, add.phones, { it.value.isBlank() }) { a, b -> PhoneIdentity.same(a.value, b.value, null) },
            emails = rows(draft.emails, add.emails, { it.value.isBlank() }) { a, b -> a.value.equals(b.value, ignoreCase = true) },
            websites = rows(draft.websites, add.websites, { it.value.isBlank() }) { a, b -> siteKey(a.value) == siteKey(b.value) },
            addresses = rows(draft.addresses, add.addresses, { it.isBlank }) { a, b -> a.formatted.equals(b.formatted, ignoreCase = true) },
            events = if (draft.events.any { it.type == Event.TYPE_BIRTHDAY && it.date.isNotBlank() }) draft.events
            else rows(draft.events, add.events, { it.date.isBlank() }) { a, b -> a.date == b.date },
            note = listOf(draft.note, add.note).filter { it.isNotBlank() }.joinToString("\n"),
        )
    }

    /** [existing] rows, its empty ones filled first (they keep their place), then the rest of [add] not already there. */
    internal fun <T> rows(existing: List<T>, add: List<T>, blank: (T) -> Boolean, same: (T, T) -> Boolean): List<T> {
        val fresh = add.filter { a -> existing.none { !blank(it) && same(it, a) } }
        val out = existing.toMutableList()
        var k = 0
        for (i in out.indices) if (blank(out[i]) && k < fresh.size) out[i] = fresh[k++]
        return out + fresh.drop(k)
    }

    private fun siteKey(url: String) = url.lowercase(Locale.ROOT).substringAfter("://").removePrefix("www.").trimEnd('/')

    private fun phoneType(l: Label): Int = when (l) {
        Label.WORK -> Phone.TYPE_WORK
        Label.HOME -> Phone.TYPE_HOME
        Label.MAIN -> Phone.TYPE_MAIN
        Label.FAX -> Phone.TYPE_FAX_WORK
        Label.OTHER -> Phone.TYPE_OTHER
        Label.MOBILE, Label.NONE -> Phone.TYPE_MOBILE
    }

    private fun emailType(l: Label): Int = when (l) {
        Label.WORK -> Email.TYPE_WORK
        Label.HOME -> Email.TYPE_HOME
        else -> Email.TYPE_OTHER
    }
}
