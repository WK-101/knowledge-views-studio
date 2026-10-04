package app.parley.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.ui.ParleyListItem

/**
 * Whether "Lock private contacts" applies now: private contacts are listed (not hidden), there are some, and their
 * details are unlocked ([app.parley.data.vault.VaultRepository.unlocked]).
 */
@Composable
fun privateContactsUnlocked(vm: AppViewModel): Boolean {
    val unlocked by vm.c.vault.unlocked.collectAsStateWithLifecycle()
    val listed by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val hidden = vm.settings.collectAsStateWithLifecycle().value.hideVault
    return unlocked && !hidden && listed.isNotEmpty()
}

/**
 * Locks private contacts again, all at once: their details close (pages show their locked state, the search forgets
 * what it opened) until the next unlock. Names and numbers stay listed; "Hide private contacts" is what hides them.
 * Not the app lock: Parley stays open.
 */
fun lockPrivateContacts(vm: AppViewModel) {
    vm.c.vault.lockAll()
    vm.toast(vm.getApplication<android.app.Application>().getString(R.string.pl_locked))
}

/** The Contacts top bar's open lock, while private contacts are unlocked: one tap locks them all again. */
@Composable
fun LockPrivateButton(vm: AppViewModel) {
    if (!privateContactsUnlocked(vm)) return
    IconButton({ lockPrivateContacts(vm) }) { Icon(Icons.Rounded.LockOpen, stringResource(R.string.pl_lock)) }
}

/** The same action as a row, on a private contact's page while private contacts are unlocked. */
@Composable
fun LockPrivateRow(vm: AppViewModel, modifier: Modifier = Modifier) {
    if (!privateContactsUnlocked(vm)) return
    ParleyListItem(
        headlineContent = { Text(stringResource(R.string.pl_lock)) },
        supportingContent = { Text(stringResource(R.string.pl_unlocked_sub)) },
        leadingContent = { Icon(Icons.Rounded.LockOpen, null) },
        modifier = modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.pl_lock)) { lockPrivateContacts(vm) },
    )
}
