package app.parley.ui.people

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.people.DuplicateHit
import app.parley.common.people.DuplicateLookup
import app.parley.common.people.DuplicateReason
import app.parley.data.ContactDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.DataL10n

/**
 * New-contact editor: "Anna Smith already exists · Open / Add these details to her", checked as you type
 * (debounced) against names, numbers and e-mails with the duplicate finder's keys.
 */
@Composable
fun DuplicateWarning(vm: AppViewModel, draft: ContactDetails, onOpen: (Long) -> Unit, onAddTo: (Long) -> Unit) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val lookup = remember(contacts) { contacts?.let { DuplicateLookup(it) } }
    // F15: private contacts count too (not in discreet mode, where the vault stays out of sight). Their ids are
    // negative so they never clash with a contact id.
    val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val vaultLookup = remember(vault, settings.hideVault) {
        if (settings.hideVault) null else DuplicateLookup(vault.map { v -> app.parley.common.ContactSummary(-v.id, "", v.name, null, false, v.numbers.map { app.parley.common.PhoneEntry(it, 2, null) }) })
    }
    var hit by remember { mutableStateOf<DuplicateHit?>(null) }
    var dismissed by remember { mutableStateOf<Long?>(null) }
    val name = draft.composedName
    val phones = draft.phones.map { it.value }
    val emails = draft.emails.map { it.value }
    LaunchedEffect(lookup, name, phones, emails) {
        delay(400)
        hit = withContext(Dispatchers.Default) { lookup?.find(name, phones, emails) ?: vaultLookup?.find(name, phones, emails) }
    }
    val h = hit?.takeIf { it.contact.id != dismissed } ?: return
    val private = h.contact.id < 0
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PersonSearch, null, Modifier.padding(end = 12.dp))
                Text(
                    when {
                        private && h.reason == DuplicateReason.NAME -> stringResource(R.string.dup_private_name, h.contact.displayName)
                        private -> stringResource(R.string.dup_private_has, h.contact.displayName, DataL10n.ltr(h.matched))
                        h.reason == DuplicateReason.NAME -> stringResource(R.string.dup_exists, h.contact.displayName)
                        else -> stringResource(R.string.dup_has, h.contact.displayName, DataL10n.ltr(h.matched))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row {
                if (private) {
                    TextButton({ vm.navigate(app.parley.NavEvent.Vault(-h.contact.id)) }) { Text(stringResource(R.string.dup_open)) }
                } else {
                    TextButton({ onOpen(h.contact.id) }) { Text(stringResource(R.string.dup_open)) }
                    TextButton({ onAddTo(h.contact.id) }) { Text(stringResource(R.string.dup_add_to, h.contact.displayName.substringBefore(' '))) }
                }
                TextButton({ dismissed = h.contact.id }) { Text(stringResource(R.string.dup_someone_else)) }
            }
        }
    }
}
