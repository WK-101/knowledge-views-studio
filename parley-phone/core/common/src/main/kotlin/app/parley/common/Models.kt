package app.parley.common

/** A phone number attached to a contact. */
data class PhoneEntry(
    val number: String,
    val type: Int,
    val label: String?,
    val isPrimary: Boolean = false,
)

/** Light-weight projection of a contact used by lists and search. */
data class ContactSummary(
    val id: Long,
    val lookupKey: String,
    val displayName: String,
    val photoUri: String?,
    val starred: Boolean,
    val phones: List<PhoneEntry>,
    val emails: List<String> = emptyList(),
    /** "Family, Given" form used when the user sorts by last name. */
    val displayNameAlt: String = displayName,
)

enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, VOICEMAIL, ANSWERED_EXTERNALLY, UNKNOWN }

data class CallEntry(
    val id: Long,
    val number: String,
    val cachedName: String?,
    val type: CallType,
    val date: Long,
    val durationSec: Long,
    val accountId: String?,
    val isNew: Boolean,
    val presentationHidden: Boolean,
)

data class SimAccount(
    val id: String,
    val label: String,
    val subtitle: String?,
    val color: Int,
    val slotIndex: Int,
)
