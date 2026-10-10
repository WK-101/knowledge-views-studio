package app.parley.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.parley.common.ux.LockButton
import app.parley.security.AppLock
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
    val unlocked by vm.c.vault.lock.unlocked.collectAsStateWithLifecycle()
    val listed by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val hidden = vm.privacy.collectAsStateWithLifecycle().value.privateHidden
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

/**
 * The Contacts top bar's one lock button ([LockButton]): Lock private contacts while they're unlocked, Lock now while
 * the app lock is on, and a small menu with both when both apply, instead of two padlocks side by side.
 */
@Composable
fun ContactsLockButton(vm: AppViewModel, appLock: Boolean) {
    var menu by remember { mutableStateOf(false) }
    when (LockButton.of(privateContactsUnlocked(vm), appLock)) {
        LockButton.NONE -> Unit
        LockButton.PRIVATE_CONTACTS -> IconButton({ lockPrivateContacts(vm) }) { Icon(Icons.Rounded.Lock, stringResource(R.string.pl_lock)) }
        LockButton.PARLEY -> IconButton({ AppLock.lockNowByUser() }) { Icon(Icons.Rounded.Lock, stringResource(R.string.home_lock_now)) }
        LockButton.BOTH -> Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.Lock, stringResource(R.string.home_lock_menu)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    { Text(stringResource(R.string.pl_lock)) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                    onClick = { menu = false; lockPrivateContacts(vm) },
                )
                DropdownMenuItem(
                    { Text(stringResource(R.string.home_lock_parley)) }, leadingIcon = { Icon(Icons.Rounded.PhonelinkLock, null) },
                    onClick = { menu = false; AppLock.lockNowByUser() },
                )
            }
        }
    }
}

/** The same action as a row, on a private contact's page while private contacts are unlocked. */
@Composable
fun LockPrivateRow(vm: AppViewModel, modifier: Modifier = Modifier) {
    if (!privateContactsUnlocked(vm)) return
    ParleyListItem(
        headlineContent = { Text(stringResource(R.string.pl_lock)) },
        supportingContent = { Text(stringResource(R.string.pl_unlocked_sub)) },
        leadingContent = { Icon(Icons.Rounded.Lock, null) },
        modifier = modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.pl_lock)) { lockPrivateContacts(vm) },
    )
}
