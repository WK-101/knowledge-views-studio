package app.parley.ui.people

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.common.people.SocialProfiles
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.PostalItem

/**
 * My card in the contact editor: the card as the editor's fields (so it's edited with the same form as every
 * contact), and back. The card keeps one name, one address line and plain lists, so only those fields are offered
 * ([app.parley.common.people.EditorForm.meCardKinds]).
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
            // The card's address is one line: it goes into the street field as typed.
            addresses = card.address.takeIf { it.isNotBlank() }?.let { listOf(PostalItem(street = it, type = StructuredPostal.TYPE_HOME)) }.orEmpty(),
            note = card.note,
        )
    }

    fun toCard(d: ContactDetails): MeCard {
        val a = d.addresses.firstOrNull()
        val (profiles, sites) = d.websites.partition { SocialProfiles.labelled(it.type, it.label) != null }
        return MeCard(
            name = MeCards.joinName(d.prefix, d.given, d.middle, d.family, d.suffix),
            phones = d.phones.map { it.value },
            emails = d.emails.map { it.value },
            company = d.company,
            title = d.title,
            websites = sites.map { it.value },
            profiles = profiles.mapNotNull { SocialProfiles.fromWebsite(it.value, it.type, it.label) },
            address = a?.let { MeCards.joinAddress(it.street, listOf(it.poBox, it.neighborhood, it.postcode, it.city, it.region, it.country)) }.orEmpty(),
            note = d.note,
        ).cleaned()
    }
}
