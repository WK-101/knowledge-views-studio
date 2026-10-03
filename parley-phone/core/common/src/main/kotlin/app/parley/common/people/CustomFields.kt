package app.parley.common.people

import app.parley.common.record.Mime

/**
 * Custom fields ("Shoe size: 38"): a label and a value. Google Contacts syncs them as its user-defined field, so a
 * Google account gets that kind ([Mime.GOOGLE_CUSTOM_FIELD]); every other account, and private contacts, get
 * Parley's own row ([Mime.CUSTOM_FIELD]). Both keep the label in DATA1 and the value in DATA2, so moving a contact
 * between accounts only changes the kind.
 */
object CustomFields {
    const val GOOGLE_ACCOUNT = "com.google"

    fun isCustomField(mime: String): Boolean = mime == Mime.CUSTOM_FIELD || mime == Mime.GOOGLE_CUSTOM_FIELD

    /**
     * The kind the custom field [label]: [value] is written as in an account of [accountType]: Google's in a Google
     * account, so it syncs, but only with both halves (Google's user-defined field needs a key and a value, and sync
     * would drop a half one); otherwise Parley's own.
     */
    fun mimeFor(accountType: String?, label: String?, value: String?): String =
        if (accountType == GOOGLE_ACCOUNT && !label.isNullOrBlank() && !value.isNullOrBlank()) Mime.GOOGLE_CUSTOM_FIELD else Mime.CUSTOM_FIELD

    /** [mime] as written to an account of [accountType]: custom fields take that account's kind ([mimeFor]), other kinds stay. */
    fun mimeIn(mime: String, accountType: String?, label: String?, value: String?): String =
        if (isCustomField(mime)) mimeFor(accountType, label, value) else mime

    /** One field as the contact page and search show it: "Shoe size: 38", or whichever half is there. */
    fun display(label: String, value: String): String = listOf(label.trim(), value.trim()).filter { it.isNotEmpty() }.joinToString(": ")
}
