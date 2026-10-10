package app.parley.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.parley.R
import app.parley.common.people.ContactsFooter
import app.parley.ui.Spacing

/** The quiet count at the end of the Contacts list ("120 contacts", "12 contacts in Family", "4 results"). */
@Composable
fun ContactsCountFooter(line: ContactsFooter.Line, modifier: Modifier = Modifier) {
    val n = line.count
    val text = when (line) {
        is ContactsFooter.Line.All -> {
            val all = pluralStringResource(R.plurals.contacts_footer_count, n, n)
            val private = pluralStringResource(R.plurals.contacts_footer_private, line.private, line.private)
            if (line.private > 0) stringResource(R.string.archive_page_work, all, private) else all
        }
        is ContactsFooter.Line.Results -> pluralStringResource(R.plurals.contacts_footer_results, n, n)
        is ContactsFooter.Line.In -> pluralStringResource(R.plurals.contacts_footer_in, n, n, line.name)
        is ContactsFooter.Line.Unlabelled -> pluralStringResource(R.plurals.contacts_footer_unlabelled, n, n)
        is ContactsFooter.Line.Filtered -> pluralStringResource(R.plurals.contacts_footer_filtered, n, n)
        is ContactsFooter.Line.Private -> pluralStringResource(R.plurals.contacts_footer_private_list, n, n)
    }
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.l),
    )
}
