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
    /** The "Family, Given" form, for sorting by last name and showing names last name first ([app.parley.common.people.NameOrder]). */
    val displayNameAlt: String = displayName,
    /** How the name is pronounced, when the contact has one (furigana, pinyin…): searched on the keypad too. */
    val phoneticName: String? = null,
    /** The name the list is sorted, sectioned and indexed by ("Sort by"), which may differ from the shown one. */
    val sortName: String = displayName,
) {
    /** The number to call: the one marked as default, else the first. */
    val primaryPhone: PhoneEntry? get() = phones.firstOrNull { it.isPrimary } ?: phones.firstOrNull()
    val primaryNumber: String? get() = primaryPhone?.number
}

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
    /** Android logged it as a video call (`CallLog.Calls.FEATURES_VIDEO`), even if Parley answered it as voice. */
    val video: Boolean = false,
)

data class SimAccount(
    val id: String,
    val label: String,
    val subtitle: String?,
    val color: Int,
    val slotIndex: Int,
)
