package app.parley.data

/** One editable multi-value row (phone, e-mail, website). [id] is null for rows not yet saved. */
data class DataItem(
    val id: Long? = null,
    val value: String = "",
    val type: Int = 0,
    val label: String? = null,
    val isPrimary: Boolean = false,
)

data class PostalItem(
    val id: Long? = null,
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = "",
    val type: Int = 0,
    val label: String? = null,
) {
    val formatted: String
        get() = listOf(street, listOf(postcode, city).filter { it.isNotBlank() }.joinToString(" "), region, country)
            .filter { it.isNotBlank() }.joinToString(", ")
    val isBlank: Boolean get() = listOf(street, city, region, postcode, country).all { it.isBlank() }
}

data class EventItem(
    val id: Long? = null,
    /** yyyy-MM-dd or --MM-dd (no year). */
    val date: String = "",
    val type: Int = 3,
    val label: String? = null,
)

data class AccountRef(val type: String?, val name: String?) {
    val isLocal: Boolean get() = type == null
    val displayLabel: String
        get() = when {
            type == null -> "Phone only (not synced)"
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
    val nicknameId: Long? = null,
    val nickname: String = "",
    val orgId: Long? = null,
    val company: String = "",
    val title: String = "",
    val noteId: Long? = null,
    val note: String = "",
    val phones: List<DataItem> = emptyList(),
    val emails: List<DataItem> = emptyList(),
    val websites: List<DataItem> = emptyList(),
    val addresses: List<PostalItem> = emptyList(),
    val events: List<EventItem> = emptyList(),
    val groupIds: Set<Long> = emptySet(),
    val rawContacts: List<RawContactRef> = emptyList(),
    /**
     * Raw contact the editor writes to (a writable account). Null for display-only loads, or when
     * every source is read-only (e.g. messenger apps); saving then creates a linked device entry.
     */
    val editRawId: Long? = null,
    /** All raw contacts in writable accounts (used to remove a photo everywhere). */
    val writableRawIds: List<Long> = emptyList(),
) {
    val composedName: String
        get() = listOf(prefix, given, middle, family, suffix).filter { it.isNotBlank() }.joinToString(" ").trim()
}

data class CallerInfo(
    val contactId: Long,
    val lookupKey: String?,
    val name: String,
    val photoUri: String?,
    val numberLabel: String?,
    val customRingtone: String?,
    val sendToVoicemail: Boolean,
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
)
