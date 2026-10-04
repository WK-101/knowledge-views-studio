package app.parley.data

import app.parley.common.people.ContactText
import app.parley.common.people.Handle
import app.parley.common.people.HandleService
import app.parley.common.record.AccountKinds

/** One editable multi-value row (phone, e-mail, website). [id] is null for rows not yet saved. */
data class DataItem(
    val id: Long? = null,
    val value: String = "",
    val type: Int = 0,
    val label: String? = null,
    val isPrimary: Boolean = false,
)

/** The row marked as default, else the first (numbers and emails). */
fun List<DataItem>.primary(): DataItem? = firstOrNull { it.isPrimary } ?: firstOrNull()

data class PostalItem(
    val id: Long? = null,
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = "",
    val type: Int = 0,
    val label: String? = null,
    /** StructuredPostal.POBOX: loaded, shown and saved, never dropped. */
    val poBox: String = "",
    /** StructuredPostal.NEIGHBORHOOD. */
    val neighborhood: String = "",
    /**
     * RFC 9554's room, floor, building… ([app.parley.common.people.AddressParts], stored form): shown, not edited, and
     * written only with a new row (an edit leaves the row's column as it is).
     */
    val parts: String = "",
) {
    val formatted: String
        get() = ContactText.postal(street, poBox, neighborhood, postcode, city, region, country)
    val isBlank: Boolean get() = listOf(street, poBox, neighborhood, city, region, postcode, country, parts).all { it.isBlank() }
}

data class EventItem(
    val id: Long? = null,
    /** yyyy-MM-dd or --MM-dd (no year). */
    val date: String = "",
    val type: Int = 3,
    val label: String? = null,
    /** The calendar it recurs by ([app.parley.common.AltCalendar.key]); null for Gregorian. */
    val calendar: String? = null,
)

/**
 * A custom field ("Shoe size: 38"). [mime] is the kind it's stored as (Google's in a Google account, Parley's
 * elsewhere, [app.parley.common.people.CustomFields]); null for one not saved yet, which takes its account's kind.
 */
data class CustomFieldItem(
    val id: Long? = null,
    val label: String = "",
    val value: String = "",
    val mime: String? = null,
) {
    val isBlank: Boolean get() = label.isBlank() && value.isBlank()
}

/**
 * A messenger handle row (Im or SipAddress). [id] is null for rows not yet saved. [customProtocol] keeps an
 * unknown service's own name.
 */
data class HandleItem(
    val id: Long? = null,
    val service: HandleService = HandleService.SIGNAL,
    val value: String = "",
    val customProtocol: String? = null,
) {
    val handle: Handle get() = Handle(service, value, customProtocol)
}

data class AccountRef(val type: String?, val name: String?) {
    /** Phone-only storage: no account, or an OEM phone account such as Samsung's `vnd.sec.contact.phone`. */
    val isLocal: Boolean get() = AccountKinds.isLocalType(type)
    val displayLabel: String
        get() = when {
            type == null || isLocal -> "Phone only (not synced)"
            type == "com.google" -> "Google · $name"
            type.contains("davdroid") || type.contains("davx5") || type.contains("bitfire") -> "CardDAV · $name"
            else -> name ?: type
        }
}

data class RawContactRef(val id: Long, val account: AccountRef)

data class GroupInfo(val id: Long, val title: String, val account: AccountRef)

data class ContactDetails(
    val id: Long = 0,
    val lookupKey: String = "",
    val displayName: String = "",
    val photoUri: String? = null,
    val starred: Boolean = false,
    val customRingtone: String? = null,
    val sendToVoicemail: Boolean = false,
    val nameId: Long? = null,
    val prefix: String = "",
    val given: String = "",
    val middle: String = "",
    val family: String = "",
    val suffix: String = "",
    val phoneticGiven: String = "",
    val phoneticFamily: String = "",
    val phoneticMiddle: String = "",
    /** Parley's row for RFC 9554's secondary surname and generation ([app.parley.common.record.Mime.NAME_PARTS]). */
    val namePartsId: Long? = null,
    val secondSurname: String = "",
    val generation: String = "",
    val nicknameId: Long? = null,
    val nickname: String = "",
    /** Parley's pronouns row ([app.parley.common.record.Mime.PRONOUNS]): "she/her", shown beside the name. */
    val pronounsId: Long? = null,
    val pronouns: String = "",
    val orgId: Long? = null,
    val company: String = "",
    val title: String = "",
    /** The work row's department (Organization.DEPARTMENT), edited with company and title. */
    val department: String = "",
    /**
     * The work row's office location and job description: shown on the contact page (Other fields) but not edited,
     * and kept by every save.
     */
    val officeLocation: String = "",
    val jobDescription: String = "",
    val noteId: Long? = null,
    val note: String = "",
    val phones: List<DataItem> = emptyList(),
    val emails: List<DataItem> = emptyList(),
    val websites: List<DataItem> = emptyList(),
    /** Relations (spouse, manager, …): value = the related person's name. */
    val relations: List<DataItem> = emptyList(),
    /**
     * Device contacts only: relations kept in Parley only ([app.parley.common.people.ParleyRelations]), never written
     * to the address book. Loaded by the editor and the contact page from Parley's contact metadata; id is always null.
     */
    val parleyRelations: List<DataItem> = emptyList(),
    val addresses: List<PostalItem> = emptyList(),
    val events: List<EventItem> = emptyList(),
    val groupIds: Set<Long> = emptySet(),
    val rawContacts: List<RawContactRef> = emptyList(),
    /**
     * Raw contact the editor writes to (a writable account). Null for display-only loads, or when
     * every source is read-only (e.g. messenger apps); saving then creates a linked device entry.
     */
    val editRawId: Long? = null,
    /**
     * RawContacts.VERSION of [editRawId] when the editor loaded it. The save asserts it is unchanged, so an edit made
     * meanwhile by a sync adapter or another app is never overwritten silently.
     */
    val editRawVersion: Long? = null,
    /** All raw contacts in writable accounts (used to remove a photo everywhere). */
    val writableRawIds: List<Long> = emptyList(),
    /**
     * Data rows the provider marks read-only (Data.IS_READ_ONLY, set by some sync adapters). The editor shows them
     * locked and saving never changes or deletes them.
     */
    val readOnlyDataIds: Set<Long> = emptySet(),
    /** Messenger handles (Im and SIP rows). */
    val handles: List<HandleItem> = emptyList(),
    /**
     * Private contacts only (kept in their encrypted record): a "who is this" line and a note shown when they
     * call. Regular contacts keep their note for calls in Parley's contact metadata instead.
     */
    val context: String = "",
    val pinnedNote: String = "",
    /** Private contacts only: their [app.parley.common.people.MessengerPrefs], encoded. */
    val messengerPrefs: String = "",
    /** The language to use with them ([app.parley.common.record.Mime.LANGUAGE]): a BCP 47 tag, or the name as typed. */
    val languageId: Long? = null,
    val language: String = "",
    val customFields: List<CustomFieldItem> = emptyList(),
) {
    val composedName: String
        get() = listOf(prefix, given, middle, family, suffix).filter { it.isNotBlank() }.joinToString(" ").trim()
}

/**
 * The raw contact being saved was changed elsewhere (a sync, another app) after the editor loaded it, or it is gone.
 * Nothing was written.
 */
class ContactChangedElsewhereException(val contactId: Long) : IllegalStateException("The contact changed elsewhere since it was opened")

data class CallerInfo(
    val contactId: Long,
    val lookupKey: String?,
    val name: String,
    val photoUri: String?,
    val numberLabel: String?,
    val customRingtone: String?,
    val sendToVoicemail: Boolean,
    /** Found in the work profile (through the enterprise lookup); it can't be opened or edited from here. */
    val work: Boolean = false,
    /** A favourite (the address book's star, or Parley's own for a private contact): the drive profile may answer it. */
    val starred: Boolean = false,
    /** The "Family, Given" form, when known without another read (a private contact's caller-ID copy). */
    val alternativeName: String? = null,
)

/** A birthday / anniversary / other date of a contact, for the timeline and reminders. */
data class ContactEvent(
    val contactId: Long,
    val lookupKey: String,
    val name: String,
    val photoUri: String?,
    val date: String,
    val type: Int,
    val label: String?,
    val phone: String?,
    /** The calendar it recurs by ([app.parley.common.AltCalendar.key]); null for Gregorian. */
    val calendar: String? = null,
)
