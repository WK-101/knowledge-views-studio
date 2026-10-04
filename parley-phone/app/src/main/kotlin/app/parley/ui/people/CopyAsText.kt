package app.parley.ui.people

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import app.parley.common.ContactSummary
import app.parley.common.people.Reports
import app.parley.ui.Clipboard
import app.parley.ui.common.Format
import androidx.compose.ui.res.stringResource
import app.parley.R

/**
 * "Copy as text" for selected contacts: names, numbers (with their type) and e-mail addresses as plain text.
 * The clip is marked sensitive, so Android 13+ keeps it out of the clipboard preview and keyboard suggestions.
 */
@Composable
fun CopyAsTextMenuItem(chosen: List<ContactSummary>, close: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    DropdownMenuItem({ Text(stringResource(R.string.ppl_copy_as_text)) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = {
        close()
        val text = Reports.contactsAsText(
            chosen.map { c ->
                Reports.TextContact(c.displayName, c.phones.map { it.number to Format.phoneType(context.resources, it.type, it.label) }, c.emails)
            },
        )
        Clipboard.copy(context, text, confirm = res.getQuantityString(R.plurals.ppl_copied, chosen.size, chosen.size))
    })
}
