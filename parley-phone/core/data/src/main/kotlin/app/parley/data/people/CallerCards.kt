package app.parley.data.people

import app.parley.common.people.CallerCard
import app.parley.data.DataContainer

/** I6: the caller-card line other surfaces reuse (the missed-call notification). */
object CallerCards {
    /**
     * The extra line for a missed call from [number]: a contact's job/company, or a private contact's "who is this"
     * line (never in discreet mode). For the notification's private version only; its public version has no names.
     */
    suspend fun missedCallLine(c: DataContainer, number: String, hideVault: Boolean): String? = runCatching {
        val contact = c.contacts.lookup(number)
        if (contact != null) {
            if (contact.work) return@runCatching null
            val org = c.contacts.organization(contact.contactId)
            return@runCatching CallerCard.missedCallLine(isPrivate = false, hideVault = hideVault, subtitle = CallerCard.subtitle(org?.second, org?.first), context = null)
        }
        if (hideVault) return@runCatching null
        val (id, _) = c.vault.lookup(number) ?: return@runCatching null
        val card = c.vault.callerCard(id) ?: return@runCatching null
        CallerCard.missedCallLine(isPrivate = true, hideVault = hideVault, subtitle = card.subtitle, context = card.context)
    }.getOrNull()
}
