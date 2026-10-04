package app.parley.ui.people

import android.content.Context
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.people.Reports
import app.parley.ui.Clipboard
import app.parley.ui.common.Format

/**
 * "Copy as text" for selected contacts: names, numbers (with their type) and e-mail addresses as plain text.
 * The clip is marked sensitive, so Android 13+ keeps it out of the clipboard preview and keyboard suggestions.
 */
fun copyAsText(context: Context, chosen: List<ContactSummary>) {
    val text = Reports.contactsAsText(
        chosen.map { c ->
            Reports.TextContact(c.displayName, c.phones.map { it.number to Format.phoneType(context.resources, it.type, it.label) }, c.emails)
        },
    )
    Clipboard.copy(context, text, confirm = context.resources.getQuantityString(R.plurals.ppl_copied, chosen.size, chosen.size))
}
