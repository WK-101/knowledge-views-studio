package app.parley.ui.people

import android.content.res.Resources
import app.parley.R
import app.parley.common.people.ContactSearch

/** "Matched: address" under a contact the Contacts search found by another field than the name or number. */
internal fun matchHint(res: Resources, field: ContactSearch.Field?): String {
    val name = field?.let { HINTS[it] } ?: return ""
    return res.getString(R.string.search_matched, res.getString(name))
}

/** The word each field is named by; the name and number have none (the row shows its usual second line). */
private val HINTS: Map<ContactSearch.Field, Int> = mapOf(
    ContactSearch.Field.PHONETIC to R.string.search_field_phonetic,
    ContactSearch.Field.EMAIL to R.string.search_field_email,
    ContactSearch.Field.NICKNAME to R.string.search_field_nickname,
    ContactSearch.Field.COMPANY to R.string.search_field_company,
    ContactSearch.Field.ADDRESS to R.string.search_field_address,
    ContactSearch.Field.RELATION to R.string.search_field_relation,
    ContactSearch.Field.DATE to R.string.search_field_date,
    ContactSearch.Field.NOTE to R.string.search_field_note,
    ContactSearch.Field.WEBSITE to R.string.search_field_website,
    ContactSearch.Field.HANDLE to R.string.search_field_handle,
    ContactSearch.Field.PROFILE to R.string.search_field_profile,
    ContactSearch.Field.CUSTOM to R.string.search_field_custom,
    ContactSearch.Field.LABEL to R.string.blk_who_label,
    ContactSearch.Field.PRONOUNS to R.string.search_field_pronouns,
    ContactSearch.Field.LANGUAGE to R.string.search_field_language,
    ContactSearch.Field.CITIZENSHIP to R.string.search_field_citizenship,
    ContactSearch.Field.NATIVE_NAME to R.string.search_field_native_name,
    ContactSearch.Field.ACCOUNT to R.string.search_field_account,
)
