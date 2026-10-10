package app.parley.data.people

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.common.people.NativeName
import app.parley.common.people.SocialProfiles
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.PostalItem
import app.parley.data.RecordDetails
import app.parley.common.vcard.VCardStream

/**
 * My card is kept as a whole contact ([ContactDetails]): every field and option a contact has, edited with the same
 * form. [MeCard] is its short form for what only needs the name, numbers and the parts a signed card carries ("Send my
 * details", Introduce myself, the signed vCard, "Shared with"): [toCard]. [toDetails] reads a card kept before My
 * card held everything (and the phone's profile), so nothing typed there is lost.
 */
object MeCardDetails {
    fun toDetails(card: MeCard): ContactDetails {
        val (given, family) = MeCards.splitName(card.name)
        return ContactDetails(
            given = given,
            family = family,
            phones = card.phones.map { DataItem(value = it, type = Phone.TYPE_MOBILE) },
            emails = card.emails.map { DataItem(value = it, type = Email.TYPE_HOME) },
            company = card.company,
            title = card.title,
            // Profiles are website rows labelled with their service, as on every contact (SocialProfiles).
            websites = card.websites.map { DataItem(value = it, type = Website.TYPE_HOMEPAGE) } +
                card.profiles.map { DataItem(value = it.url, type = SocialProfiles.TYPE_CUSTOM, label = it.service.label) },
            // The card's address was one line: it goes into the street field as typed.
            addresses = card.address.takeIf { it.isNotBlank() }?.let { listOf(PostalItem(street = it, type = StructuredPostal.TYPE_HOME)) }.orEmpty(),
            note = card.note,
        )
    }

    fun toCard(d: ContactDetails): MeCard {
        val a = d.addresses.firstOrNull { !it.isBlank }
        // A row labelled with a service whose link isn't a profile Parley reads (a LinkedIn article, a link page) stays a
        // plain website: never dropped on save.
        val profiles = d.websites.mapNotNull { w -> SocialProfiles.labelled(w.type, w.label)?.let { SocialProfiles.fromWebsite(w.value, w.type, w.label) } }
        val sites = d.websites.filter { w -> SocialProfiles.labelled(w.type, w.label) == null || SocialProfiles.fromWebsite(w.value, w.type, w.label) == null }
        return MeCard(
            name = MeCards.joinName(d.prefix, d.given, d.middle, d.family, d.suffix),
            // The number marked default first, as "Send my details" uses the first.
            phones = (d.phones.filter { it.isPrimary } + d.phones.filterNot { it.isPrimary }).map { it.value },
            emails = (d.emails.filter { it.isPrimary } + d.emails.filterNot { it.isPrimary }).map { it.value },
            company = d.company,
            title = d.title,
            websites = sites.map { it.value },
            profiles = profiles,
            address = a?.let { MeCards.joinAddress(it.street, listOf(it.poBox, it.neighborhood, it.postcode, it.city, it.region, it.country)) }.orEmpty(),
            note = d.note,
        ).cleaned()
    }

    /**
     * [d] with the name and number "Send my details" uses set to [name] and [number] (its quick edit): a changed name
     * replaces the name parts, the number becomes the first one ([MeCards.withNameAndNumber]: one already on the card,
     * compared by digits, moves up; an empty one removes the first). Every other field, and each number's type, is
     * kept.
     */
    fun withNameAndNumber(d: ContactDetails, name: String, number: String): ContactDetails {
        val before = toCard(d)
        val next = MeCards.withNameAndNumber(before, name, number)
        val named = if (next.name == before.name) {
            d
        } else {
            val (given, family) = MeCards.splitName(next.name)
            d.copy(prefix = "", given = given, middle = "", family = family, suffix = "")
        }
        val rows = named.phones.filter { it.value.isNotBlank() }
        fun digits(s: String) = s.filter(Char::isDigit)
        val phones = next.phones.map { n ->
            rows.firstOrNull { it.value.trim() == n } ?: rows.firstOrNull { digits(it.value).isNotEmpty() && digits(it.value) == digits(n) }?.copy(value = n)
                ?: DataItem(value = n, type = Phone.TYPE_MOBILE)
        }.distinct()
        // The first number is the one sent, so no other is left marked default.
        return named.copy(phones = phones.mapIndexed { i, p -> p.copy(isPrimary = p.isPrimary && i == 0) })
    }

    /**
     * What My card shows: [own] completed with the phone's [profile] ("Me" in ContactsContract.Profile). What you typed
     * in Parley wins; the profile only fills in a missing name, work or address, and adds numbers, e-mails and websites
     * the card doesn't have yet (numbers compared by digits, e-mails ignoring case).
     */
    fun withProfile(own: ContactDetails, profile: MeCard?): ContactDetails {
        val p = profile?.cleaned() ?: return own
        var d = own
        if (d.composedName.isBlank() && p.name.isNotBlank()) {
            val (given, family) = MeCards.splitName(p.name)
            d = d.copy(given = given, family = family)
        }
        fun digits(s: String) = s.filter(Char::isDigit).ifEmpty { s }
        val phones = d.phones.map { digits(it.value) }.toSet()
        val emails = d.emails.map { it.value.trim().lowercase() }.toSet()
        val sites = d.websites.map { it.value.trim() }.toSet()
        d = d.copy(
            phones = d.phones + p.phones.filter { digits(it) !in phones }.map { DataItem(value = it, type = Phone.TYPE_MOBILE) },
            emails = d.emails + p.emails.filter { it.lowercase() !in emails }.map { DataItem(value = it, type = Email.TYPE_HOME) },
            websites = d.websites + p.websites.filter { it !in sites }.map { DataItem(value = it, type = Website.TYPE_HOMEPAGE) },
        )
        if (d.company.isBlank() && d.title.isBlank()) d = d.copy(company = p.company, title = p.title)
        if (d.addresses.all { it.isBlank } && p.address.isNotBlank()) {
            d = d.copy(addresses = listOf(PostalItem(street = p.address, type = StructuredPostal.TYPE_HOME)))
        }
        return d
    }

    /** The name My card goes by: its full name, else the nickname, else the company. */
    fun nameOf(d: ContactDetails): String = d.composedName.ifBlank { d.nickname.trim().ifBlank { d.company.trim() } }

    /**
     * Only what [parts] share of [d] ([MeCards.Part]): the note, relations and every other detail stay out unless
     * ticked. Ids, links and Parley's own bookkeeping never go along.
     */
    @Suppress("CyclomaticComplexMethod") // One check per part a share can include.
    fun restrict(d: ContactDetails, parts: Set<MeCards.Part>): ContactDetails {
        fun has(p: MeCards.Part) = p in parts
        val name = has(MeCards.Part.NAME)
        val details = has(MeCards.Part.NAME_DETAILS)
        val profile = { w: DataItem -> SocialProfiles.labelled(w.type, w.label) != null && SocialProfiles.fromWebsite(w.value, w.type, w.label) != null }
        return ContactDetails(
            prefix = if (name) d.prefix else "",
            given = if (name) d.given else "",
            middle = if (name) d.middle else "",
            family = if (name) d.family else "",
            suffix = if (name) d.suffix else "",
            phoneticGiven = if (details) d.phoneticGiven else "",
            phoneticMiddle = if (details) d.phoneticMiddle else "",
            phoneticFamily = if (details) d.phoneticFamily else "",
            secondSurname = if (name) d.secondSurname else "",
            generation = if (name) d.generation else "",
            nickname = if (details) d.nickname else "",
            pronouns = if (details) d.pronouns else "",
            nativeName = if (details) d.nativeName else NativeName(),
            company = if (has(MeCards.Part.WORK)) d.company else "",
            title = if (has(MeCards.Part.WORK)) d.title else "",
            department = if (has(MeCards.Part.WORK)) d.department else "",
            phones = if (has(MeCards.Part.PHONES)) d.phones.clean() else emptyList(),
            emails = if (has(MeCards.Part.EMAILS)) d.emails.clean() else emptyList(),
            websites = d.websites.clean().filter { w -> if (profile(w)) has(MeCards.Part.PROFILES) else has(MeCards.Part.WEBSITES) },
            addresses = if (has(MeCards.Part.ADDRESS)) d.addresses.filterNot { it.isBlank }.map { it.copy(id = null) } else emptyList(),
            events = if (has(MeCards.Part.DATES)) d.events.filter { it.date.isNotBlank() }.map { it.copy(id = null) } else emptyList(),
            handles = if (has(MeCards.Part.HANDLES)) d.handles.filter { it.value.isNotBlank() }.map { it.copy(id = null) } else emptyList(),
            relations = if (has(MeCards.Part.RELATIONS)) d.relations.clean() else emptyList(),
            languages = if (has(MeCards.Part.LANGUAGES)) d.languages.filter { it.isNotBlank() } else emptyList(),
            citizenships = if (has(MeCards.Part.LANGUAGES)) d.citizenships.filter { it.isNotBlank() } else emptyList(),
            customFields = if (has(MeCards.Part.OTHER)) d.customFields.filterNot { it.isBlank }.map { it.copy(id = null, mime = null) } else emptyList(),
            note = if (has(MeCards.Part.NOTE)) d.note else "",
        )
    }

    /** Whether [d] holds nothing to show or share (a photo alone counts as something). */
    fun isEmpty(d: ContactDetails): Boolean = toCard(d).isEmpty && nameOf(d).isBlank() && d.photoUri == null &&
        d.events.none { it.date.isNotBlank() } && d.relations.none { it.value.isNotBlank() } && d.handles.none { it.value.isNotBlank() } &&
        d.languages.none { it.isNotBlank() } && d.citizenships.none { it.isNotBlank() } && d.customFields.all { it.isBlank } &&
        d.pronouns.isBlank() && d.nativeName.isBlank

    /**
     * [d] with only [parts] ([restrict]) as a full vCard, the way Parley exports a contact: what a share with more than
     * a signed card carries uses ([MeCards.isSignable]). [photo]: the card's photo, put in only with [MeCards.Part.PHOTO].
     */
    fun vcard(d: ContactDetails, parts: Set<MeCards.Part>, photo: ByteArray? = null): String {
        val shared = restrict(d, parts)
        val record = RecordDetails.toRecord(shared.copy(displayName = nameOf(shared)), key = "", photo = photo?.takeIf { MeCards.Part.PHOTO in parts })
        return VCardStream.writeAll(listOf(record))
    }

    private fun List<DataItem>.clean() = filter { it.value.isNotBlank() }.map { it.copy(id = null) }
}
