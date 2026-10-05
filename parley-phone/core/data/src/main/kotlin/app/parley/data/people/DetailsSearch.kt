package app.parley.data.people

import app.parley.common.people.ContactSearch
import app.parley.data.ContactDetails
import app.parley.data.messaging.Romanizer

/**
 * A contact's opened details as a Contacts-search doc: how a private contact is searched by every field while its
 * details are open. The doc is kept in memory only (never written), like the opened details it comes from.
 */
object DetailsSearch {
    /** [d] under list id [id], with its [labels] (titles) and [extraName] (the name the list shows). */
    fun doc(id: Long, d: ContactDetails, labels: Collection<String>, region: String?, extraName: String = ""): ContactSearch.Doc =
        ContactSearch.Builder(id, region, latin = Romanizer).apply {
            name(d.prefix, d.given, d.middle, d.family, d.suffix, d.secondSurname, d.generation)
            if (wholeNameOnly(d)) name(d.displayName)
            shownName(d.displayName, extraName)
            phonetic(d.phoneticGiven, d.phoneticMiddle, d.phoneticFamily)
            nickname(d.nickname)
            d.phones.forEach { number(it.value) }
            d.emails.forEach { email(it.value) }
            d.addresses.forEach { a -> address(a.street, a.poBox, a.neighborhood, a.city, a.region, a.postcode, a.country, a.parts) }
            work(d.company, d.title, d.department, d.officeLocation, d.jobDescription)
            d.websites.forEach { website(it.value, it.type, it.label) }
            d.handles.forEach { h -> handle(h.value, h.customProtocol ?: h.service.label) }
            d.relations.forEach { relation(it.value, it.type, it.label) }
            d.events.forEach { event(it.date, it.type, it.label) }
            note(d.note)
            // A private contact's "who is this" line and note for calls are its notes too.
            note(d.context)
            note(d.pinnedNote)
            d.customFields.forEach { custom(it.label, it.value) }
            pronouns(d.pronouns)
            namesAndLanguages(d)
            labels.forEach { label(it) }
        }.build()

    /** The name in their language, the languages and citizenship. */
    private fun ContactSearch.Builder.namesAndLanguages(d: ContactDetails) {
        nativeName(d.nativeName.shown, d.nativeName.given, d.nativeName.family)
        d.languages.forEach { language(it) }
        d.citizenships.forEach { citizenship(it) }
    }

    /** A name kept only as a whole (no parts) is still a name, unless it is really a number or an email. */
    private fun wholeNameOnly(d: ContactDetails): Boolean =
        listOf(d.prefix, d.given, d.middle, d.family, d.suffix).all { it.isBlank() } && d.displayName.any { it.isLetter() } && '@' !in d.displayName
}
