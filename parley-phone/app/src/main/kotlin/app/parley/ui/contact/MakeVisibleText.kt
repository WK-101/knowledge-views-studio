package app.parley.ui.contact

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Make visible" question, for one contact or several. While Android 16 puts new contacts in a cloud account
 * rather than on the phone, it says so before anything moves: a private contact that was only on the phone would go
 * to that account and be synced to the cloud.
 */
@Composable
internal fun makeVisibleBody(contacts: ContactsRepository): String {
    val cloud by produceState<AccountRef?>(null, contacts) {
        value = withContext(Dispatchers.IO) { runCatching { contacts.systemDefaultAccount() }.getOrNull() }
    }
    val body = stringResource(R.string.contact_make_visible_body)
    return cloud?.let { body + "\n\n" + stringResource(R.string.contact_make_visible_cloud, it.displayLabel) } ?: body
}

/** After one "Make visible": done, and the cloud account Android 16 put it in when it did. */
internal fun madeVisibleText(res: Resources, redirectedTo: AccountRef?): String =
    redirectedTo?.let { res.getString(R.string.contact_made_visible_in, it.displayLabel) } ?: res.getString(R.string.contact_made_visible)

/** After a bulk "Make visible": how many, and the cloud accounts Android 16 put some of them in. */
internal fun madeVisibleText(res: Resources, made: Int, redirectedTo: Set<AccountRef>): String {
    val done = res.getQuantityString(R.plurals.sel_made_visible, made, made)
    if (redirectedTo.isEmpty() || made == 0) return done
    return res.getString(R.string.contact_made_visible_count_in, done, redirectedTo.joinToString(", ") { it.displayLabel })
}
