package app.parley.ui.people

import android.content.res.Resources
import app.parley.R
import app.parley.common.people.BroadSearch

/** I8: "Matched: address" under a contact the Contacts search found by another field than the name or number. */
internal fun matchHint(res: Resources, field: BroadSearch.Field?): String {
    val name = when (field) {
        BroadSearch.Field.EMAIL -> R.string.search_field_email
        BroadSearch.Field.NICKNAME -> R.string.search_field_nickname
        BroadSearch.Field.COMPANY -> R.string.search_field_company
        BroadSearch.Field.ADDRESS -> R.string.search_field_address
        BroadSearch.Field.NOTE -> R.string.search_field_note
        BroadSearch.Field.WEBSITE -> R.string.search_field_website
        BroadSearch.Field.HANDLE -> R.string.search_field_handle
        BroadSearch.Field.NAME, BroadSearch.Field.NUMBER, null -> return ""
    }
    return res.getString(R.string.search_matched, res.getString(name))
}
