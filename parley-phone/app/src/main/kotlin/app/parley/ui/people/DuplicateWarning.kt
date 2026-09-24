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

/**
 * New-contact editor: "Anna Smith already exists · Open / Add these details to her", checked as you type
 * (debounced) against names, numbers and e-mails with the duplicate finder's keys.
 */
@Composable
fun DuplicateWarning(vm: AppViewModel, draft: ContactDetails, onOpen: (Long) -> Unit, onAddTo: (Long) -> Unit) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val lookup = remember(contacts) { contacts?.let { DuplicateLookup(it) } }
    var hit by remember { mutableStateOf<DuplicateHit?>(null) }
    var dismissed by remember { mutableStateOf<Long?>(null) }
    val name = draft.composedName
    val phones = draft.phones.map { it.value }
    val emails = draft.emails.map { it.value }
    LaunchedEffect(lookup, name, phones, emails) {
        delay(400)
        hit = lookup?.let { l -> withContext(Dispatchers.Default) { l.find(name, phones, emails) } }
    }
    val h = hit?.takeIf { it.contact.id != dismissed } ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PersonSearch, null, Modifier.padding(end = 12.dp))
                Text(
                    when (h.reason) {
                        DuplicateReason.NAME -> "${h.contact.displayName} already exists"
                        else -> DuplicateLookup.describe(h)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row {
                TextButton({ onOpen(h.contact.id) }) { Text("Open") }
                TextButton({ onAddTo(h.contact.id) }) { Text("Add these details to ${h.contact.displayName.substringBefore(' ')}") }
                TextButton({ dismissed = h.contact.id }) { Text("It's someone else") }
            }
        }
    }
}
