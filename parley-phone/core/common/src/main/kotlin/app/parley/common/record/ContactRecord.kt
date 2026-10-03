package app.parley.common.record

import app.parley.common.MessengerCatalog

/**
 * Lossless, platform-independent image of an Android contact, mirroring ContactsContract:
 * Contact (aggregate) -> RawContact (one per account) -> Data rows (generic DATA1..DATA15).
 *
 * Every data row is kept generically (mimetype + columns) so nothing is ever dropped, even kinds
 * Parley doesn't understand (e.g. messenger rows). Shared by backup, vCard import/export and the
 * change journal.
 */
data class ContactRecord(
    /** Stable key for matching across devices: the contact's lookup key when known. */
    val key: String,
    val displayName: String,
    val starred: Boolean = false,
    val customRingtone: String? = null,
    val sendToVoicemail: Boolean = false,
    val raws: List<RawRecord>,
)

data class RawRecord(
    val accountType: String?,
    val accountName: String?,
    val dataSet: String? = null,
    val sourceId: String? = null,
    val rows: List<DataRow>,
    /**
     * RawContacts._ID on this device when read from the provider, else null. Device-local: never written to
     * backups or exports (RecordJson leaves it out), only used to act on this exact copy.
     */
    val rawId: Long? = null,
)

/**
 * One ContactsContract.Data row. [values] maps "data1".."data14" (and "data_version" is not kept);
 * [blob] is DATA15 (photo thumbnail) when present.
 */
data class DataRow(
    val mimeType: String,
    val values: Map<String, String?>,
    val blob: ByteArray? = null,
    val isPrimary: Boolean = false,
    val isSuperPrimary: Boolean = false,
) {
    operator fun get(col: String): String? = values[col]

    /** Identity used to de-duplicate rows (ignores flags and blob). */
    val canonicalKey: String
        get() = mimeType + "|" + (1..14).joinToString("|") { values["data$it"].orEmpty().trim() }

    override fun equals(other: Any?): Boolean =
        other is DataRow && mimeType == other.mimeType && values == other.values &&
            isPrimary == other.isPrimary && isSuperPrimary == other.isSuperPrimary &&
            (blob?.contentEquals(other.blob) ?: (other.blob == null))

    override fun hashCode(): Int = mimeType.hashCode() * 31 + values.hashCode()
}

/** ContactsContract MIME types and column meanings, duplicated here to keep this module pure JVM. */
object Mime {
    const val NAME = "vnd.android.cursor.item/name"
    const val PHONE = "vnd.android.cursor.item/phone_v2"
    const val EMAIL = "vnd.android.cursor.item/email_v2"
    const val POSTAL = "vnd.android.cursor.item/postal-address_v2"
    const val ORG = "vnd.android.cursor.item/organization"
    const val NICKNAME = "vnd.android.cursor.item/nickname"
    const val NOTE = "vnd.android.cursor.item/note"
    const val WEBSITE = "vnd.android.cursor.item/website"
    const val EVENT = "vnd.android.cursor.item/contact_event"
    const val IM = "vnd.android.cursor.item/im"
    const val RELATION = "vnd.android.cursor.item/relation"
    const val SIP = "vnd.android.cursor.item/sip_address"
    const val PHOTO = "vnd.android.cursor.item/photo"
    const val GROUP = "vnd.android.cursor.item/group_membership"
    const val IDENTITY = "vnd.android.cursor.item/identity"

    /**
     * A person's pronouns ("she/her"), in DATA1. Android has no kind for them, so Parley writes its own row (other apps
     * keep it, and vCard carries it as `PRONOUNS`, RFC 9554).
     */
    const val PRONOUNS = "vnd.android.cursor.item/vnd.parley.pronouns"

    /**
     * A custom field ("Shoe size: 38"): DATA1 its label, DATA2 its value, like Google's user-defined field
     * ([GOOGLE_CUSTOM_FIELD]), which Parley writes instead in Google accounts so the field syncs
     * ([app.parley.common.people.CustomFields]). vCard carries it as a grouped `X-PARLEY-CUSTOM` with an `X-ABLabel`.
     */
    const val CUSTOM_FIELD = "vnd.android.cursor.item/vnd.parley.custom_field"

    /** Google Contacts' user-defined field: DATA1 the label, DATA2 the value. */
    const val GOOGLE_CUSTOM_FIELD = "vnd.com.google.cursor.item/contact_user_defined_field"

    /**
     * The name parts RFC 9554 added to vCard's N and Android has no columns for: DATA1 the secondary surname
     * (Spanish and Portuguese double surnames), DATA2 the generation ("Jr.", "III").
     */
    const val NAME_PARTS = "vnd.android.cursor.item/vnd.parley.name_parts"

    /**
     * A language to use with the person: DATA1 a BCP 47 tag ("es-MX") or the name as typed, DATA2 vCard's TYPE
     * ("work"), DATA3 "card" when it came from RFC 9554's `LANGUAGE` (the card's own language) rather than `LANG`.
     */
    const val LANGUAGE = "vnd.android.cursor.item/vnd.parley.language"

    /** Kinds Parley shows/edits; everything else is preserved but read-only. */
    val CORE = setOf(
        NAME, PHONE, EMAIL, POSTAL, ORG, NICKNAME, NOTE, WEBSITE, EVENT, IM, RELATION, SIP, PHOTO, GROUP, PRONOUNS,
        CUSTOM_FIELD, GOOGLE_CUSTOM_FIELD, NAME_PARTS, LANGUAGE,
    )
}

/** Column names for readability: Data.DATA1..DATA15. */
object Col {
    const val D1 = "data1"; const val D2 = "data2"; const val D3 = "data3"; const val D4 = "data4"; const val D5 = "data5"
    const val D6 = "data6"; const val D7 = "data7"; const val D8 = "data8"; const val D9 = "data9"; const val D10 = "data10"
    const val D11 = "data11"; const val D12 = "data12"; const val D13 = "data13"; const val D14 = "data14"
    val ALL = listOf(D1, D2, D3, D4, D5, D6, D7, D8, D9, D10, D11, D12, D13, D14)

    /**
     * Pseudo-column on [Mime.GROUP] rows: the group's title. DATA1 (the group row id) only means something
     * on the device it came from, so the title is what travels in vCards and backups; the store resolves
     * it back to a row id in the target account on insert.
     */
    const val GROUP_TITLE = "parley_group_title"
}

/** Messenger apps whose raw contacts and data rows are owned by their sync adapters (read-only for us). */
object Messengers {
    /** Every messenger account type, from [app.parley.common.MessengerCatalog]. */
    val PACKAGES: List<String> = MessengerCatalog.ACCOUNT_TYPES.sorted()

    fun isMessengerAccount(accountType: String?): Boolean = accountType != null && accountType in PACKAGES

    /** Messenger rows use mimetypes like `vnd.android.cursor.item/vnd.com.whatsapp.profile`. */
    fun isMessengerMime(mimeType: String): Boolean = PACKAGES.any { mimeType.contains(it) }
}

/**
 * This contact without messenger raw contacts and messenger data rows. Those belong to the messenger's sync
 * adapter, which recreates them itself; copying them elsewhere only makes stale, duplicate entries.
 */
fun ContactRecord.withoutMessengers(): ContactRecord = copy(
    raws = raws.filter { !Messengers.isMessengerAccount(it.accountType) }
        .map { raw -> raw.copy(rows = raw.rows.filter { !Messengers.isMessengerMime(it.mimeType) }) },
)
