package app.parley.ui.people.archive

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.catching
import app.parley.common.people.Archive
import app.parley.common.people.ArchivedCard
import app.parley.common.people.ContactRef
import app.parley.common.people.PrivateArchive
import app.parley.data.AccountRef
import app.parley.data.archive.ArchiveStore
import app.parley.data.vault.VaultSummary
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.EmptyState
import app.parley.ui.ListSectionHeader
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.PrivateBadge
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.avatarSize
import app.parley.ui.common.AccountRefSaver
import app.parley.ui.common.Format
import app.parley.ui.people.PeopleRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Archiving from the app: one contact from its page, the members a chapter brought (ChapterUi), and the Archived list
 * with Unarchive. The work runs in the app's scope, so leaving a screen never leaves a contact half moved. A private
 * contact is archived inside the vault ([app.parley.data.vault.VaultRepository.setArchived]): it stays private.
 */
object ArchiveActions {
    /** Archives private contact [vaultId] (it stays sealed in the vault); false when it couldn't be. */
    suspend fun archivePrivate(vm: AppViewModel, vaultId: Long): Boolean = vm.c.scope.async {
        catching { vm.c.vault.setArchived(vaultId, System.currentTimeMillis()) }.getOrDefault(false)
    }.await()

    /** Lists private contact [vaultId] again, among the private contacts; false when it couldn't be. */
    suspend fun unarchivePrivate(vm: AppViewModel, vaultId: Long): Boolean = vm.c.scope.async {
        catching { vm.c.vault.setArchived(vaultId, null) }.getOrDefault(false)
    }.await()

    /** Archives the device contacts [ids] (list ids; private ones are out of other apps already and stay as they are). */
    suspend fun archive(vm: AppViewModel, ids: Collection<Long>): List<ArchiveStore.Archived> = vm.c.scope.async {
        ids.filter { ContactRef.ofNavId(it) is ContactRef.Device }.mapNotNull { id -> catching { vm.c.archive.archive(id) }.getOrNull() }
    }.await()

    /** What [archiveMany] archived: device contacts by archive id, private ones by vault id. */
    data class Batch(val archived: List<ArchiveStore.Archived>, val privates: List<Long>) {
        val count: Int get() = archived.size + privates.size
    }

    /** Archives the contacts [ids] (list ids), device and private alike. */
    suspend fun archiveMany(vm: AppViewModel, ids: Collection<Long>): Batch {
        val privates = ids.mapNotNull { (ContactRef.ofNavId(it) as? ContactRef.Private)?.vaultId }
        val device = archive(vm, ids)
        return Batch(device, privates.filter { archivePrivate(vm, it) })
    }

    /**
     * Undo: puts what [batch] archived back, device contacts into the accounts they came from. False when one of them
     * couldn't be put back (it stays in the Archived list).
     */
    suspend fun undo(vm: AppViewModel, batch: Batch): Boolean = vm.c.scope.async {
        val device = batch.archived.count { a -> catching { vm.c.archive.unarchive(a.id) }.getOrNull() is ArchiveStore.Unarchived.Done }
        val private = batch.privates.count { unarchivePrivate(vm, it) }
        device + private == batch.count
    }.await()

    /** The snackbar after archiving: what happened, with Undo. */
    fun announce(vm: AppViewModel, res: Resources, batch: Batch, text: String) {
        if (batch.count == 0) {
            vm.toast(text)
            return
        }
        vm.offerUndo(text) {
            if (!undo(vm, batch)) vm.toast(res.getString(R.string.archive_undo_failed))
        }
    }

    /** What the toast says once one contact was archived. */
    fun doneText(res: Resources, name: String, done: ArchiveStore.Archived?): String = when {
        done == null -> res.getString(R.string.archive_failed)
        done.synced || done.messengerCopies -> res.getString(R.string.archive_done_synced, name)
        else -> res.getString(R.string.archive_done, name)
    }
}

/**
 * "Archive Ana?" on a contact's page (⋮ › Privacy… › Archive). [onArchived]: the page closes, the contact is gone from
 * it. A private contact ([contactId] negative) is archived inside the vault and stays private. The snackbar after it
 * offers Undo, which puts the contact back where it was.
 */
@Composable
fun ArchiveContactDialog(vm: AppViewModel, contactId: Long, name: String, onDismiss: () -> Unit, onArchived: () -> Unit) {
    val res = LocalContext.current.resources
    val private = ContactRef.ofNavId(contactId) as? ContactRef.Private
    ConfirmDialog(
        title = stringResource(R.string.archive_title, name),
        text = stringResource(if (private != null) R.string.archive_private_body else R.string.archive_body),
        confirmLabel = stringResource(R.string.archive_action),
        icon = Icons.Rounded.Archive,
        onConfirm = {
            onDismiss()
            vm.c.scope.launch {
                if (private != null) {
                    val ok = ArchiveActions.archivePrivate(vm, private.vaultId)
                    val batch = ArchiveActions.Batch(emptyList(), if (ok) listOf(private.vaultId) else emptyList())
                    val text = if (ok) res.getString(R.string.archive_done, name) else res.getString(R.string.archive_private_failed)
                    ArchiveActions.announce(vm, res, batch, text)
                    if (ok) withContext(Dispatchers.Main) { onArchived() }
                    return@launch
                }
                val done = ArchiveActions.archive(vm, listOf(contactId)).firstOrNull()
                ArchiveActions.announce(vm, res, ArchiveActions.Batch(listOfNotNull(done), emptyList()), ArchiveActions.doneText(res, name, done))
                if (done != null) withContext(Dispatchers.Main) { onArchived() }
            }
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
    )
}

/** "Archive 3 contacts?" from the Contacts selection (⋮ › Privacy… › Archive), with Undo afterwards. */
@Composable
fun ArchiveSelectionDialog(vm: AppViewModel, ids: List<Long>, onDismiss: () -> Unit, onArchived: () -> Unit) {
    val res = LocalContext.current.resources
    ConfirmDialog(
        title = pluralStringResource(R.plurals.archive_n_title, ids.size, ids.size),
        text = stringResource(R.string.archive_n_body),
        confirmLabel = stringResource(R.string.archive_action),
        icon = Icons.Rounded.Archive,
        onConfirm = {
            onDismiss()
            onArchived()
            vm.c.scope.launch {
                val batch = ArchiveActions.archiveMany(vm, ids)
                val text = if (batch.count == 0) {
                    res.getString(R.string.archive_n_failed)
                } else {
                    res.getQuantityString(R.plurals.archive_n_done, batch.count, batch.count)
                }
                ArchiveActions.announce(vm, res, batch, text)
            }
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
    )
}

/**
 * Archived private contacts, while private contacts may show ([PrivateArchive.mayShow]: not hidden by discreet mode or a
 * duress unlock, not locked with "Lock private contacts"); none otherwise.
 */
@Composable
fun privateArchived(vm: AppViewModel): List<VaultSummary> {
    val listed by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    val hidden = privacy.privateHidden
    val duress = privacy.duress
    val locked by vm.c.vault.lock.lockedByPerson.collectAsStateWithLifecycle()
    if (!PrivateArchive.mayShow(hidden = hidden, hiding = duress.hiding, locked = locked)) return emptyList()
    return remember(listed) { listed.filter { it.archived }.sortedBy { it.name.lowercase() } }
}

/**
 * Unarchive for an archived device contact: back into the account it came from, or, when that account is gone (signed
 * out, removed), into the one the person picks ([Host] asks).
 */
internal class Unarchiving(private val vm: AppViewModel, private val res: Resources) {
    private var asking by mutableStateOf<ArchivedCard?>(null)
    private var missing by mutableStateOf("")

    private fun putBack(card: ArchivedCard, into: AccountRef?) = vm.c.scope.launch {
        when (val r = vm.c.archive.unarchive(card.id, into)) {
            is ArchiveStore.Unarchived.Done -> vm.toast(
                r.redirectedTo?.let { res.getString(R.string.archive_unarchived_in, card.name, it.displayLabel) }
                    ?: res.getString(R.string.archive_unarchived, card.name),
            )
            ArchiveStore.Unarchived.NotWritten -> vm.toast(res.getString(R.string.archive_unarchive_failed))
        }
    }

    fun start(card: ArchivedCard) = vm.c.scope.launch {
        when (val t = vm.c.archive.target(card.id)) {
            null -> Unit
            Archive.Target.Original -> putBack(card, null)
            is Archive.Target.Ask -> withContext(Dispatchers.Main) {
                missing = t.missing.joinToString { it.name ?: it.type.orEmpty() }
                asking = card
            }
        }
    }

    /** The account question, while one is open. */
    @Composable
    fun Host() {
        val card = asking ?: return
        ChooseAccountDialog(vm, card.name, missing, onDismiss = { asking = null }) { into ->
            asking = null
            putBack(card, into)
        }
    }
}

@Composable
internal fun rememberUnarchiving(vm: AppViewModel): Unarchiving {
    val res = LocalContext.current.resources
    return remember(vm) { Unarchiving(vm, res) }
}

/** Contacts › ⋮ › Archived: everyone archived, with Unarchive; a tap opens their read-only page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val cards by vm.c.archive.cards.collectAsStateWithLifecycle()
    val privates = privateArchived(vm)
    val unarchiving = rememberUnarchiving(vm)

    ParleyScaffold(topBar = { ParleyTopBar(stringResource(R.string.archive_title_screen), onBack = back) }) { p ->
        if (cards.isEmpty() && privates.isEmpty()) {
            EmptyState(Icons.Rounded.Archive, stringResource(R.string.archive_empty_title), stringResource(R.string.archive_empty_text), Modifier.padding(p))
            return@ParleyScaffold
        }
        LazyColumn(Modifier.padding(p)) {
            item {
                Text(
                    stringResource(R.string.archive_intro),
                    Modifier.padding(horizontal = Spacing.listInset, vertical = Spacing.s),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(cards, key = { it.id }) { card -> ArchivedRow(vm, card, open) { unarchiving.start(card) } }
            privateArchivedSection(vm, privates, open)
        }
    }
    unarchiving.Host()
}

/** One archived contact: name, number and when it was archived; a tap opens their read-only page. */
@Composable
private fun ArchivedRow(vm: AppViewModel, card: ArchivedCard, open: (Destination) -> Unit, onUnarchive: () -> Unit) {
    val context = LocalContext.current
    val number = card.numbers.firstOrNull()
    val line = listOfNotNull(
        number?.let { Bidi.ltr(Format.number(it, vm.countryIso)) },
        stringResource(R.string.archive_row_when, DateUtils.formatDateTime(context, card.archivedAt, DATE_FLAGS)),
    ).joinToString(stringResource(R.string.main_separator))
    val unarchiveLabel = stringResource(R.string.archive_unarchive_for, card.name)
    ParleyListItem(
        modifier = Modifier.clickable { open(PeopleRoutes.ArchivedContact(card.id)) },
        leadingContent = { Avatar(card.name, null, avatarSize()) },
        headlineContent = { Text(card.name) },
        supportingContent = { Text(line) },
        trailingContent = {
            TextButton(onUnarchive, Modifier.semantics { contentDescription = unarchiveLabel }) {
                Text(stringResource(R.string.archive_unarchive))
            }
        },
    )
}

/** The archived private contacts under their own header, each with Unarchive (back among the private contacts). */
private fun LazyListScope.privateArchivedSection(vm: AppViewModel, privates: List<VaultSummary>, open: (Destination) -> Unit) {
    if (privates.isEmpty()) return
    item(key = "private") { ListSectionHeader(stringResource(R.string.archive_private_section)) }
    items(privates, key = { "p${it.id}" }) { v ->
        val res = LocalContext.current.resources
        PrivateArchivedRow(vm, v, open) {
            vm.c.scope.launch {
                val ok = ArchiveActions.unarchivePrivate(vm, v.id)
                vm.toast(res.getString(if (ok) R.string.archive_private_unarchived else R.string.archive_private_unarchive_failed, v.name))
            }
        }
    }
}

/** One archived private contact: its photo with the private badge, number and when it was archived; a tap opens its page. */
@Composable
private fun PrivateArchivedRow(vm: AppViewModel, v: VaultSummary, open: (Destination) -> Unit, onUnarchive: () -> Unit) {
    val context = LocalContext.current
    val number = v.numbers.firstOrNull()
    val line = listOfNotNull(
        number?.let { Bidi.ltr(Format.number(it, vm.countryIso)) },
        v.archivedAt?.let { stringResource(R.string.archive_row_when, DateUtils.formatDateTime(context, it, DATE_FLAGS)) },
    ).joinToString(stringResource(R.string.main_separator))
    val unarchiveLabel = stringResource(R.string.archive_unarchive_for, v.name)
    // The photo file is looked at off the main thread.
    val photo by produceState<String?>(null, v.id) { value = withContext(Dispatchers.IO) { vm.c.vault.photoUri(v.id) } }
    ParleyListItem(
        // A private contact keeps its page while archived (its "Kept as" row says Archived, with Unarchive there too).
        modifier = Modifier.clickable { open(Routes.contact(ContactRef.Private(v.id).navId)) },
        leadingContent = {
            Box {
                Avatar(v.name, photo, avatarSize())
                PrivateBadge(Modifier.align(Alignment.BottomEnd))
            }
        },
        headlineContent = { Text(v.name) },
        supportingContent = { Text(line) },
        trailingContent = {
            TextButton(onUnarchive, Modifier.semantics { contentDescription = unarchiveLabel }) {
                Text(stringResource(R.string.archive_unarchive))
            }
        },
    )
}

/** Unarchive when the account a contact came from isn't on the phone now: which account it goes to instead. */
@Composable
private fun ChooseAccountDialog(vm: AppViewModel, name: String, missing: String, onDismiss: () -> Unit, onPick: (AccountRef) -> Unit) {
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var account by rememberSaveable(stateSaver = AccountRefSaver) { mutableStateOf<AccountRef?>(null) }
    LaunchedEffect(Unit) {
        accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() }
        val s = vm.settings.value
        if (account == null) account = accounts.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName } ?: accounts.firstOrNull()
    }
    ConfirmDialog(
        title = stringResource(R.string.archive_choose_account_title, name),
        text = null,
        confirmLabel = stringResource(R.string.archive_put_back),
        onConfirm = { account?.let(onPick) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        confirmEnabled = account != null,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.archive_choose_account_text, missing), style = MaterialTheme.typography.bodyMedium)
                accounts.forEach { a ->
                    ParleyListItem(
                        modifier = Modifier.clickable { account = a },
                        leadingContent = { RadioButton(account == a, { account = a }) },
                        headlineContent = { Text(a.displayLabel) },
                    )
                }
            }
        },
    )
}

private const val DATE_FLAGS = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
