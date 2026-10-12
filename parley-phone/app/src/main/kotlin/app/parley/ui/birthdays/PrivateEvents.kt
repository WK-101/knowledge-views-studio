package app.parley.ui.birthdays

import app.parley.common.people.ContactRef
import app.parley.common.people.PrivateDates
import app.parley.common.security.PrivacyView
import app.parley.data.ContactEvent
import app.parley.data.vault.VaultSummary

/**
 * Private contacts' dates as Birthdays & dates lists them, beside the address book's: from each one's caller-ID copy
 * ([VaultSummary.dates], kept there since 6.8 and seeded for older entries on the next unlock), under its navigation
 * id, so a tap opens its page. Only while private contacts may show ([PrivateDates.mayShow]): hidden, locked with "Lock
 * private contacts" or under a duress unlock there are none. Never read for the lock screen, widgets or notifications,
 * which keep to the address book's dates.
 */
object PrivateEvents {
    fun of(privates: List<VaultSummary>, view: PrivacyView, lockedByPerson: Boolean, photoOf: (Long) -> String? = { null }): List<ContactEvent> {
        if (!PrivateDates.mayShow(view, lockedByPerson)) return emptyList()
        // Archived private contacts are out of the lists, and out of this one too.
        return privates.filterNot { it.archived }.flatMap { v ->
            val ref = ContactRef.Private(v.id)
            val photo by lazy { photoOf(v.id) }
            v.dates.filter { it.date.isNotBlank() }.map { e ->
                ContactEvent(ref.navId, ref.parleyKey(""), v.name, photo, e.date, e.type, e.label, v.numbers.firstOrNull(), e.calendar)
            }
        }
    }
}
