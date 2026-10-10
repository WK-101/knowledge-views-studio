// The screen lives with the words it uses.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.people.cards

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.NumberText
import app.parley.common.cards.ShareLedger
import app.parley.common.cards.ShareMethod
import app.parley.common.cards.SharedPerson
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.EmptyState
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.PersonRow
import app.parley.ui.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SharedWithText {
    fun method(res: Resources, m: ShareMethod): String = res.getString(
        when (m) {
            ShareMethod.QR_SWAP -> R.string.card_method_qr_swap
            ShareMethod.SEND_DETAILS -> R.string.card_method_send_details
            ShareMethod.INTRODUCE -> R.string.card_method_introduce
            ShareMethod.CARD_FILE -> R.string.card_method_file
            ShareMethod.NEW_NUMBER -> R.string.card_method_new_number
        },
    )

    /** "Ana" or, without a name, their number. */
    fun who(p: SharedPerson): String = p.name.ifBlank { p.number?.let { Bidi.ltr(NumberText.formatInternational(it)) }.orEmpty() }
}

/**
 * My card › Shared with. Everyone Parley saw your card go to (QR swaps, Send my details, Introduce myself, the
 * card file sent to someone), newest first, with how and when; each can be removed, or all of it. It's a private
 * record: sealed on this phone and in your encrypted backups, never sent anywhere. It also feeds "Changed my number".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedWithScreen(vm: AppViewModel, back: () -> Unit) {
    val store = vm.c.people.shareLedger
    val scope = rememberCoroutineScope()
    // Private contacts named from the vault, and hidden in discreet mode.
    val receipts by CardSharing.rememberShownReceipts(vm)
    var loaded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { loaded = store.load() }
    val people = remember(receipts) { ShareLedger.people(receipts, vm.countryIso) }
    var forget by remember { mutableStateOf<SharedPerson?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    val fingerprint by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { vm.c.people.cardIdentity.fingerprint() }.getOrNull() }
    }

    ParleyScaffold(topBar = {
        ParleyTopBar(stringResource(R.string.card_shared_title), onBack = back, actions = {
            if (people.isNotEmpty()) IconButton({ clearAll = true }) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.card_shared_clear)) }
        })
    }) { p ->
        when {
            loaded == false -> EmptyState(Icons.Rounded.People, stringResource(R.string.card_shared_unreadable), null, Modifier.padding(p))
            loaded == true && people.isEmpty() -> EmptyState(
                Icons.Rounded.People, stringResource(R.string.card_shared_empty), stringResource(R.string.card_shared_empty_body), Modifier.padding(p),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(), contentPadding = PaddingValues(top = p.calculateTopPadding(), bottom = p.calculateBottomPadding() + Spacing.l),
            ) {
                item {
                    Text(
                        stringResource(R.string.card_shared_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
                items(people, key = { it.latest.id }) { person -> SharedPersonRow(person) { forget = person } }
                fingerprint?.let { f ->
                    item {
                        Text(
                            stringResource(R.string.card_shared_key, Bidi.ltr(f)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                        )
                    }
                }
            }
        }
    }
    forget?.let { person ->
        ConfirmDialog(
            title = stringResource(R.string.card_shared_remove_title, SharedWithText.who(person)),
            text = stringResource(R.string.card_shared_remove_body),
            confirmLabel = stringResource(R.string.me_remove),
            onConfirm = { forget = null; scope.launch { store.remove(person.receipts.map { it.id }.toSet()) } },
            onDismiss = { forget = null },
            destructive = true,
        )
    }
    if (clearAll) {
        ConfirmDialog(
            title = stringResource(R.string.card_shared_clear_title),
            text = stringResource(R.string.card_shared_remove_body),
            confirmLabel = stringResource(R.string.card_shared_clear),
            onConfirm = { clearAll = false; scope.launch { store.clear() } },
            onDismiss = { clearAll = false },
            destructive = true,
        )
    }
}

/** One person: name (or number), then their number, how and when they last got your card, and how often. */
@Composable
private fun SharedPersonRow(person: SharedPerson, onRemove: () -> Unit) {
    val res = LocalResources.current
    val context = LocalContext.current
    val who = SharedWithText.who(person)
    val l = person.latest
    val whenText = DateUtils.formatDateTime(context, l.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    PersonRow(
        who.ifBlank { "?" }, null, headline = who,
        supportingContent = {
            val line = listOfNotNull(
                person.number?.takeIf { person.name.isNotBlank() }?.let { Bidi.ltr(NumberText.formatInternational(it)) },
                SharedWithText.method(res, l.method), whenText,
                person.receipts.size.takeIf { it > 1 }?.let { n -> res.getQuantityString(R.plurals.card_shared_times, n, n) },
            )
            Text(line.joinToString(stringResource(R.string.main_separator)), color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = { IconButton(onRemove) { Icon(Icons.Rounded.Delete, stringResource(R.string.card_shared_remove_who, who)) } },
    )
}
