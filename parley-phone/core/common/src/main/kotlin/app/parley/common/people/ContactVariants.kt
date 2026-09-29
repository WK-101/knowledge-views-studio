package app.parley.common.people

/**
 * Where a contact is kept: its only difference that other apps can notice. Inside Parley both have the same page,
 * editor and features, except the few in [ContactCapabilities] that need the address book itself.
 */
enum class ContactStorage { DEVICE, PRIVATE }

val ContactRef.storage: ContactStorage get() = if (this is ContactRef.Private) ContactStorage.PRIVATE else ContactStorage.DEVICE

/**
 * The variants of one contact: where it is kept, and whether (and when) it deletes itself. Variants combine: a
 * private contact can be temporary, and so can a device contact. More can follow (work profile, SIM: read-only).
 */
data class ContactVariants(val storage: ContactStorage, val expiresAt: Long? = null) {
    val isPrivate: Boolean get() = storage == ContactStorage.PRIVATE
    val isTemporary: Boolean get() = expiresAt != null

    /** The small status chips the page header shows, in this order; an ordinary device contact shows none. */
    val chips: List<VariantChip> get() = listOfNotNull(
        VariantChip.Private.takeIf { isPrivate },
        expiresAt?.let { VariantChip.Temporary(it) },
    )

    /** The conversions offered from "Settings for this contact", in this order. */
    val conversions: List<ContactConversion> get() = listOf(
        if (isPrivate) ContactConversion.MAKE_VISIBLE else ContactConversion.MAKE_PRIVATE,
        if (isTemporary) ContactConversion.KEEP_PERMANENTLY else ContactConversion.MAKE_TEMPORARY,
    ) + listOfNotNull(ContactConversion.CHANGE_EXPIRY.takeIf { isTemporary })
}

/** A status chip in the contact page's header. */
sealed interface VariantChip {
    /** "Private · hidden from other apps". */
    data object Private : VariantChip

    /** "Temporary · deletes itself on …". */
    data class Temporary(val expiresAt: Long) : VariantChip
}

/** Moving between variants without losing anything (docs/CONTACT_MODEL.md, "Conversions"). */
enum class ContactConversion {
    /** Device → private: out of the address book, into the vault. */
    MAKE_PRIVATE,

    /** Private → device: back into the address book, where other apps can see it. */
    MAKE_VISIBLE,

    /** Give it a date to delete itself (either storage). */
    MAKE_TEMPORARY,

    /** Clear that date (either storage). */
    KEEP_PERMANENTLY,

    /** Another date. */
    CHANGE_EXPIRY,
}

/**
 * The few things only one storage can do, because they are the address book's own features. Everything else on a
 * contact's page and in its editor is the same for both (the pure list, so the page and the docs can't drift).
 */
enum class ContactCapability(
    /** Why a private contact can't have it; null when both storages have it. */
    val deviceOnlyReason: String? = null,
) {
    // ---- Both storages
    CALL_AND_MESSAGE, REACH_VIA_APPS, ABOUT, DATES, MAP_LINKS, RELATIONS, NOTE_FOR_CALLS, TIMELINE, CALL_INSIGHTS,
    CIRCLE, PROMISES, CALL_SCREEN_PICTURE, FAVOURITE, DEFAULT_NUMBER, QR_CODE, SECURE_QR, BLOCK_NUMBERS, TEMPORARY, DELETE,

    // Kept by Parley for a private contact, sealed in its vault entry (the address book keeps them for a device one):
    // label membership (the labels themselves stay the address book's), the ringtone and "send to voicemail" (applied
    // by Parley's own call screening and ringer), call time limits (by its Parley key), the page's date chips, and
    // "Recently deleted" (a sealed copy for 30 days instead of History & undo's plain one).
    LABELS, RINGTONE, SEND_TO_VOICEMAIL, CALL_TIME, QUICK_DATES, RECENTLY_DELETED,

    // ---- Device contacts only
    SHARE_VCARD_FILE("A vCard file is handed to another app, which could keep it"),
    VERSION_HISTORY("Snapshots are copies of the address book; private contacts are never copied out of the vault"),
    ACCOUNTS("Accounts (Google, phone, SIM) are where the address book keeps a contact"),
    LINKED_COPIES("Only the address book links copies from several accounts"),
    OTHER_FIELDS("Rows written by other apps exist only in the address book"),
    COPY_TO_SIM("A SIM card is readable by any phone it is put in"),
    HOME_SCREEN_SHORTCUT("The launcher (another app) would store the name and number"),
    ;

    val deviceOnly: Boolean get() = deviceOnlyReason != null
}

object ContactCapabilities {
    /** What a contact kept in [storage] can do. */
    fun of(storage: ContactStorage): Set<ContactCapability> = when (storage) {
        ContactStorage.DEVICE -> ContactCapability.entries.toSet()
        ContactStorage.PRIVATE -> ContactCapability.entries.filterNot { it.deviceOnly }.toSet()
    }

    fun has(storage: ContactStorage, capability: ContactCapability): Boolean = capability in of(storage)
}
