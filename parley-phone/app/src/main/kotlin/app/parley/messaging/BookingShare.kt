package app.parley.messaging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.NumberText
import app.parley.common.PhoneIdentity
import app.parley.common.cases.BookingCodes
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseMode
import app.parley.common.catching
import app.parley.common.people.ContactSearch
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.ui.Bidi
import app.parley.ui.ListSectionHeader
import app.parley.ui.ParleyListItem
import app.parley.ui.Spacing
import app.parley.ui.showMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Contacts listed at once in "Find a contact" (typing narrows them). */
private const val PICK_ROWS = 20

/** Case files listed at once, the newest activity first. */
private const val CASE_ROWS = 10

/**
 * The codes in shared [text] ([BookingCodes]), or empty: offered only while case files may show (not during a duress
 * unlock, which hides them as it hides notes). The phone numbers in the text are never offered as codes.
 */
internal fun bookingCodes(c: DataContainer, text: String, region: String?): List<BookingCodes.Found> {
    if (!c.privacy.memory().notesShown) return emptyList()
    val numbers = NumberText.find(text, region).map { it.e164 ?: it.raw }
    return BookingCodes.find(text, numbers)
}

/** "Keep the codes in a case file" in the share sheet, when the shared text holds [count] codes. */
@Composable
internal fun BookingRow(count: Int, onClick: () -> Unit) {
    ParleyListItem(
        headlineContent = { Text(stringResource(R.string.booking_row)) },
        supportingContent = { Text(pluralStringResource(R.plurals.booking_row_sub, count, count)) },
        leadingContent = { Icon(Icons.Rounded.Bookmark, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Where the codes go: a case file kept already, or a contact whose case file is made (or found) by their numbers. */
private sealed interface Target {
    val name: String

    data class Case(val id: String, override val name: String) : Target

    /** A contact: [contactId] for the address book's, [privateId] for a private one. */
    data class Person(override val name: String, val contactId: Long?, val privateId: Long?) : Target
}

/** What the picker offers: case files matching a number in the text first, then the other case files, then people. */
private class Choices(val suggested: List<Target>, val cases: List<Target>, val people: List<Target.Person>)

/**
 * The booking pocket: the codes found in shared [text], each ticked or not, and the organisation they belong to,
 * which the person always chooses (one that has a number in the text comes first, already chosen, and the button
 * names it). Kept sealed in that organisation's case file, one reference per code; a contact without a case file
 * gets one. Nothing is kept unless Keep is tapped; [done] closes the sheet.
 */
@Composable
internal fun BookingPocket(c: DataContainer, text: String, done: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val region = remember { PhoneEnv.countryIso(context) }
    val codes = remember(text) { bookingCodes(c, text, region) }
    var ticked by remember(text) { mutableStateOf(codes.filter { it.named }.map { it.value }.toSet()) }
    var typed by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Target?>(null) }
    var busy by remember { mutableStateOf(false) }
    val choices by produceState(Choices(emptyList(), emptyList(), emptyList()), text) {
        value = withContext(Dispatchers.IO) { choicesFor(c, text, region) }
        if (picked == null) picked = value.suggested.singleOrNull()
    }
    val folded = ContactSearch.fold(typed.trim())
    val people = remember(choices, folded) {
        if (folded.isEmpty()) emptyList() else choices.people.filter { ContactSearch.fold(it.name).contains(folded) }.take(PICK_ROWS)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.l)) {
        Text(
            stringResource(R.string.booking_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s).semantics { heading() },
        )
        ListSectionHeader(stringResource(R.string.booking_codes))
        Text(
            stringResource(R.string.booking_codes_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        CodeRows(codes, ticked) { ticked = it }
        ListSectionHeader(stringResource(R.string.booking_which))
        (choices.suggested + choices.cases.filterNot { it in choices.suggested }).forEach { t ->
            val why = stringResource(if (t in choices.suggested) R.string.booking_suggested else R.string.booking_has_case)
            TargetRow(t, picked == t, why) { picked = t }
        }
        OutlinedTextField(
            typed, { typed = it.take(MAX_NAME) }, singleLine = true, label = { Text(stringResource(R.string.booking_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        if (people.isEmpty() && folded.isNotEmpty()) {
            Text(
                stringResource(R.string.booking_none), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl),
            )
        }
        people.forEach { t -> TargetRow(t, picked == t, null) { picked = t } }
        val chosen = codes.filter { it.value in ticked }
        KeepButton(picked, chosen.isNotEmpty() && !busy) { target ->
            busy = true
            scope.launch {
                val kept = keep(c, target, chosen.map { res.getString(kindRes(it.kind)) to it.value }, region)
                busy = false
                val said = if (kept > 0) res.getQuantityString(R.plurals.booking_kept, kept, kept, target.name) else res.getString(R.string.booking_failed)
                showMessage(context, said)
                if (kept > 0) done()
            }
        }
    }
}

/** "Keep in Sky Air's case file", naming the organisation chosen; off until one is chosen. */
@Composable
private fun KeepButton(target: Target?, ready: Boolean, onKeep: (Target) -> Unit) {
    Button(
        onClick = { target?.let(onKeep) },
        enabled = target != null && ready,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.xl, vertical = Spacing.s),
    ) {
        Text(target?.let { stringResource(R.string.booking_keep_in, it.name) } ?: stringResource(R.string.booking_pick_first))
    }
}

/** The codes found, each with a tick box. */
@Composable
private fun CodeRows(codes: List<BookingCodes.Found>, ticked: Set<String>, onTicked: (Set<String>) -> Unit) {
    codes.forEach { f ->
        val on = f.value in ticked
        ParleyListItem(
            headlineContent = { Text(Bidi.ltr(f.value)) },
            supportingContent = { Text(kindLabel(f.kind)) },
            leadingContent = { Checkbox(on, null) },
            modifier = Modifier.toggleable(on, role = Role.Checkbox) { v -> onTicked(if (v) ticked + f.value else ticked - f.value) },
        )
    }
}

@Composable
private fun TargetRow(t: Target, on: Boolean, sub: String?, onPick: () -> Unit) {
    ParleyListItem(
        headlineContent = { Text(t.name) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = { RadioButton(on, null) },
        trailingContent = {
            Icon(
                when (t) {
                    is Target.Case -> Icons.Rounded.FolderOpen
                    is Target.Person -> if (t.privateId != null) Icons.Rounded.Person else Icons.Rounded.Business
                },
                null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.toggleable(on, role = Role.RadioButton) { onPick() },
    )
}

private const val MAX_NAME = 80

/**
 * What the picker offers for [text]: the visible case files (a private contact's only while private contacts show),
 * those with a number that appears in the text first; and the people to search (private ones only while shown).
 */
private suspend fun choicesFor(c: DataContainer, text: String, region: String?): Choices {
    val numbers = NumberText.find(text, region).map { it.e164 ?: it.raw }
    c.cases.load()
    val state = catching { c.cases.shown.first() }.getOrNull() ?: return Choices(emptyList(), emptyList(), emptyList())
    val listed = CaseFiles.listed(state)
    val lines = PhoneIdentity.LineSet(numbers, region)
    val suggestedCases = listed.filter { case -> case.numbers.any { it in lines } }.map { Target.Case(it.id, it.name) }
    val cases = listed.take(CASE_ROWS).map { Target.Case(it.id, it.name) }
    val device = catching { c.contacts.contacts.value ?: c.contacts.loadNow() }.getOrDefault(emptyList())
        .map { Target.Person(it.displayName, it.id, null) }
    val private = if (c.privacy.now().privateHidden) {
        emptyList()
    } else {
        c.vault.contacts.value.filterNot { it.archived }.map { Target.Person(it.name, null, it.id) }
    }
    // A saved contact with a number in the text, when no case file has it yet.
    val owners = if (suggestedCases.isNotEmpty()) {
        emptyList()
    } else {
        numbers.mapNotNull { n -> catching { c.contacts.lookup(n) }.getOrNull()?.contactId }
            .mapNotNull { id -> device.firstOrNull { it.contactId == id } }.distinct()
    }
    return Choices(suggestedCases + owners, cases, (device + private).sortedBy { it.name.lowercase() })
}

/**
 * Keeps [codes] (label to value) in [target]'s case file, made for a contact that has none ("Keep a case file", by
 * their numbers); how many were kept. Each is sealed on its own ([app.parley.data.cases.CaseFileStore.addReference]).
 */
private suspend fun keep(c: DataContainer, target: Target, codes: List<Pair<String, String>>, region: String?): Int = withContext(Dispatchers.IO) {
    val caseId = when (target) {
        is Target.Case -> target.id
        is Target.Person -> {
            val numbers = target.contactId?.let { id -> catching { c.contacts.numbersOf(id) }.getOrDefault(emptyList()) }
                ?: c.vault.contacts.value.firstOrNull { it.id == target.privateId }?.numbers.orEmpty()
            if (numbers.isEmpty()) return@withContext 0
            val private = target.privateId != null
            val after = c.cases.update { s ->
                // A case file kept already (or stopped) for these numbers stays as it is; otherwise one is started.
                if (CaseFiles.find(s, numbers, region) != null) s
                else CaseFiles.setMode(s, target.name, numbers, private, CaseMode.ON, System.currentTimeMillis(), region, UUID.randomUUID().toString())
            } ?: return@withContext 0
            CaseFiles.find(after, numbers, region)?.takeIf { it.kept }?.id ?: return@withContext 0
        }
    }
    codes.count { (label, value) -> c.cases.addReference(caseId, label, value, typed = false) }
}

@Composable
private fun kindLabel(kind: BookingCodes.Kind?): String = stringResource(kindRes(kind))

private fun kindRes(kind: BookingCodes.Kind?): Int = when (kind) {
    null -> R.string.booking_kind_code
    BookingCodes.Kind.BOOKING -> R.string.booking_kind_booking
    BookingCodes.Kind.CONFIRMATION -> R.string.booking_kind_confirmation
    BookingCodes.Kind.RESERVATION -> R.string.booking_kind_reservation
    BookingCodes.Kind.ORDER -> R.string.booking_kind_order
    BookingCodes.Kind.TICKET -> R.string.booking_kind_ticket
    BookingCodes.Kind.POLICY -> R.string.booking_kind_policy
    BookingCodes.Kind.CLAIM -> R.string.booking_kind_claim
    BookingCodes.Kind.CUSTOMER -> R.string.booking_kind_customer
    BookingCodes.Kind.MEMBERSHIP -> R.string.booking_kind_membership
    BookingCodes.Kind.CASE -> R.string.booking_kind_case
    BookingCodes.Kind.TRACKING -> R.string.booking_kind_tracking
    BookingCodes.Kind.REFERENCE -> R.string.case_reference_unnamed
}
