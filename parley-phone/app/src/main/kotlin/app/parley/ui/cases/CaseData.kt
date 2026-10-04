package app.parley.ui.cases

import app.parley.common.catching
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.calls.NeverCallsYouFacts
import app.parley.common.CallEntry
import app.parley.common.PhoneIdentity
import app.parley.common.cases.CaseFile
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseNote
import app.parley.common.cases.CaseState
import app.parley.common.cases.CaseTimeline
import app.parley.common.cases.CaseTimelines
import app.parley.common.circle.Promises
import app.parley.common.people.ContactRef
import app.parley.common.security.Concealed
import app.parley.data.DataContainer
import app.parley.data.circle.CircleRepository
import app.parley.data.security.Concealment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Who a case file is about, as the page showing it knows them: the name, every number, and their Parley key if saved. */
data class CaseOwner(val name: String, val numbers: List<String>, val private: Boolean = false, val ownerKey: String? = null)

/**
 * The case file a page shows for [CaseOwner]: the one kept ([case], null while none was made yet) and whether to show
 * one at all: one kept, or a saved organisation's that wasn't stopped (it starts with its first call, or when opened).
 */
data class CaseShown(val case: CaseFile?, val shown: Boolean)

/** Reading what a case file shows, from Parley's own stores. Off the main thread. */
object CaseData {
    /**
     * The timeline of [case] (null: an organisation without one yet) over [numbers]: the calls with them among [calls],
     * notes written after those calls, and the promises of those notes and of the contact's own ([ownerKey]).
     */
    suspend fun timeline(c: DataContainer, case: CaseFile?, numbers: List<String>, ownerKey: String?, calls: List<CallEntry>, iso: String): CaseTimeline =
        withContext(Dispatchers.IO) {
            val all = (numbers + case?.numbers.orEmpty()).filter { it.isNotBlank() }.distinct()
            val lines = PhoneIdentity.LineSet(all, iso)
            val history = calls.filter { it.number in lines }
            val keys = all.flatMap { PhoneIdentity.lookupKeys(it, iso) }.distinct()
            val callNotes = if (keys.isEmpty()) emptyList() else catching { c.meta.callNotesNow(keys) }.getOrDefault(emptyList())
            // The contact's own notes (pinned, logged chats and visits) hold promises too; call notes are read above.
            val own = ownerKey?.takeIf { it.isNotEmpty() }?.let { k ->
                catching { c.circle.notesFor(k, emptyList()) }.getOrDefault(emptyList()).filter { it.source != CircleRepository.NoteSource.CALL }
            }.orEmpty()
            val promises = (callNotes.map { it.text } + own.map { it.text }).flatMap { Promises.parse(it) }.distinctBy { it.text to it.done }
            CaseTimelines.assemble(case, history, callNotes.map { CaseNote(it.callDate, it.text) }, promises)
        }

    /**
     * The Parley key of whoever [numbers] are saved for: a listed contact's lookup key, else a private contact's key
     * (only while private contacts may show); null for a number nobody has.
     */
    suspend fun ownerKey(vm: AppViewModel, numbers: List<String>): String? = withContext(Dispatchers.IO) {
        numbers.firstNotNullOfOrNull { vm.numberIndex.value[it]?.lookupKey?.takeIf { k -> k.isNotEmpty() } }
            ?: if (vm.c.settings.current().hideVault) {
                null
            } else {
                numbers.firstNotNullOfOrNull { n -> catching { vm.c.vault.lookup(n, vm.countryIso) }.getOrNull()?.first }?.let(ContactRef::privateKey)
            }
    }

    /** Makes the case file of [owner] when there is none yet (an organisation's card was opened); its id, or null. */
    suspend fun ensure(c: DataContainer, owner: CaseOwner, iso: String): String? {
        val after = c.cases.update {
            CaseFiles.ensure(it, owner.name, owner.numbers, owner.private, System.currentTimeMillis(), iso, java.util.UUID.randomUUID().toString())
        } ?: return null
        return CaseFiles.find(after, owner.numbers, iso)?.id
    }
}

/** The case file [owner] shows now, re-read as the store changes; nothing during a duress unlock (as notes). */
@Composable
fun rememberCaseShown(vm: AppViewModel, owner: CaseOwner): CaseShown {
    val store = vm.c.cases
    LaunchedEffect(Unit) { catching { store.load() } }
    val state by store.shown.collectAsStateWithLifecycle(CaseState())
    val case = remember(state, owner.numbers) { CaseFiles.find(state, owner.numbers, vm.countryIso) }
    // A saved organisation shows its case file before anything was kept: its calls are already there.
    val organisation by produceState(false, owner.numbers, case == null) {
        value = case == null && owner.numbers.isNotEmpty() && !Concealment.hides(Concealed.NOTES) &&
            withContext(Dispatchers.IO) {
                owner.numbers.any { n -> catching { NeverCallsYouFacts.organisation(vm.c, n, vm.countryIso) }.getOrNull() != null }
            }
    }
    return CaseShown(case, case?.kept == true || (case == null && organisation))
}

/** The timeline of [case] for [owner], re-read as calls come in and the case changes; null while it is read. */
@Composable
fun rememberCaseTimeline(vm: AppViewModel, case: CaseFile?, owner: CaseOwner): CaseTimeline? {
    val calls by (if (owner.private || case?.private == true) vm.c.history.callsWithPrivate else vm.c.history.calls).collectAsStateWithLifecycle()
    val timeline by produceState<CaseTimeline?>(null, case, owner, calls?.size) {
        value = catching { CaseData.timeline(vm.c, case, owner.numbers, owner.ownerKey, calls.orEmpty(), vm.countryIso) }.getOrNull()
    }
    return timeline
}
