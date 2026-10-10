package app.parley.messaging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.catching
import app.parley.common.people.ContactSearch
import app.parley.data.DataContainer
import app.parley.data.circle.AgendaTarget
import app.parley.ui.ParleyListItem
import app.parley.ui.circle.AgendaAddDialog
import app.parley.ui.circle.addToAgenda
import app.parley.ui.showMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Names listed at once in "Talk about this with…" (typing narrows them). */
private const val AGENDA_PICK_ROWS = 30

/**
 * Text shared to Parley as something to talk about: [text] (the rest of it, beside [number]) for whoever [number] is
 * (or the number itself). [done] closes the sheet once it is added.
 */
@Composable
internal fun AgendaNumberRow(c: DataContainer, text: String, number: String, name: String?, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    var adding by remember { mutableStateOf(false) }
    ParleyListItem(
        headlineContent = { Text(stringResource(R.string.agenda_share_number_row)) },
        supportingContent = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Rounded.Checklist, null) },
        modifier = Modifier.clickable { adding = true },
    )
    if (!adding) return
    AgendaAddDialog(
        name, initial = text, keepOnRotation = false,
        onAdd = { item ->
            adding = false
            scope.launch {
                val target = c.agenda.targetFor(number)
                showMessage(context, res.getString(target?.let { addToAgenda(c, it, item) } ?: R.string.agenda_add_failed))
                if (target != null) done()
            }
        },
        onDismiss = { adding = false },
    )
}

/**
 * "Talk about this with…": a name field and the contacts it matches (private contacts too, unless they are hidden),
 * then the item to add, filled with the shared [text]. Nothing is kept unless Add is tapped; [done] closes the sheet.
 */
@Composable
internal fun AgendaPick(c: DataContainer, text: String, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    var typed by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<String, AgendaTarget>?>(null) }
    val people by produceState(emptyList<Pair<String, AgendaTarget>>()) {
        value = withContext(Dispatchers.IO) {
            val device = catching { c.contacts.contacts.value ?: c.contacts.loadNow() }.getOrDefault(emptyList())
                .filter { it.lookupKey.isNotEmpty() }
                .map { it.displayName to (AgendaTarget.Contact(it.lookupKey, it.id) as AgendaTarget) }
            val hidden = c.privacy.now().privateHidden
            val private = if (hidden) {
                emptyList()
            } else {
                c.vault.contacts.value.filterNot { it.archived }.map { it.name to (AgendaTarget.Private(it.id) as AgendaTarget) }
            }
            (device + private).sortedBy { it.first.lowercase() }
        }
    }
    val folded = ContactSearch.fold(typed.trim())
    val shown = remember(people, folded) {
        people.filter { folded.isEmpty() || ContactSearch.fold(it.first).contains(folded) }.take(AGENDA_PICK_ROWS)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.agenda_share_pick_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
        )
        OutlinedTextField(
            typed, { typed = it.take(80) }, singleLine = true,
            label = { Text(stringResource(R.string.agenda_share_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (shown.isEmpty() && folded.isNotEmpty()) {
            Text(
                stringResource(R.string.agenda_share_none), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        shown.forEach { (name, target) ->
            ParleyListItem(headlineContent = { Text(name) }, modifier = Modifier.clickable { picked = name to target })
        }
    }
    picked?.let { (name, target) ->
        AgendaAddDialog(
            name, initial = text, keepOnRotation = false,
            onAdd = { item ->
                picked = null
                scope.launch {
                    showMessage(context, res.getString(addToAgenda(c, target, item)))
                    done()
                }
            },
            onDismiss = { picked = null },
        )
    }
}
