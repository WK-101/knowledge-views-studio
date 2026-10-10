package app.parley.data.people

import app.parley.common.catching
import app.parley.common.people.CallerCard
import app.parley.data.DataContainer

/** The caller-card line other surfaces reuse (the missed-call notification). */
object CallerCards {
    /**
     * The extra line for a missed call from [number] (read as [region] reads it): a contact's job/company, or a private
     * contact's "who is this" line (never while [privateHidden]). Who owns the number is [NumberOwners]'s answer. For
     * the notification's private version only; its public version has no names.
     */
    suspend fun missedCallLine(c: DataContainer, number: String, privateHidden: Boolean, region: String? = null): String? = catching {
        val found = c.numberOwners.findIn(number, region ?: c.numberOwners.region(null), NumberOwners.Use.NOTIFICATION)
        val contact = found.contact
        if (contact != null) {
            if (contact.work) return@catching null
            val org = c.contacts.organization(contact.contactId)
            return@catching CallerCard.missedCallLine(
                isPrivate = false, hideVault = privateHidden, subtitle = CallerCard.subtitle(org?.second, org?.first), context = null,
            )
        }
        if (privateHidden) return@catching null
        val (id, _) = found.private ?: return@catching null
        val card = c.vault.callerCard(id) ?: return@catching null
        CallerCard.missedCallLine(isPrivate = true, hideVault = privateHidden, subtitle = card.subtitle, context = card.context)
    }.getOrNull()
}
