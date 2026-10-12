package app.parley.ui.cases

import app.parley.common.CallEntry
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseMode
import app.parley.common.cases.CaseReference
import app.parley.common.cases.CaseState
import app.parley.common.cases.CaseStatus
import app.parley.data.cases.CaseFileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * What the case-file screens may do (the list, one case, its card, the contact page's "Keep a case file"), as intents
 * rather than edits of the store: screens call these instead of reaching into the data container. The stores are
 * passed as getters so nothing is built before a screen needs it.
 */
class CasesUi(
    private val store: () -> CaseFileStore,
    private val calls: () -> StateFlow<List<CallEntry>?>,
    private val callsWithPrivate: () -> StateFlow<List<CallEntry>?>,
) {
    /** The case files as the screens show them ([CaseFileStore.shown]: nothing during a duress unlock). */
    val shown: Flow<CaseState> get() = store().shown

    suspend fun load(): CaseState = store().load()

    /** The calls a case's timeline is drawn from: a private contact's include their private calls. */
    fun calls(private: Boolean): StateFlow<List<CallEntry>?> = if (private) callsWithPrivate() else calls()

    suspend fun openReference(r: CaseReference): String? = store().openReference(r)

    suspend fun addReference(caseId: String, label: String, value: String): Boolean = store().addReference(caseId, label, value, typed = false)

    suspend fun removeReference(caseId: String, referenceId: String): Boolean =
        store().update { CaseFiles.removeReference(it, caseId, referenceId) } != null

    suspend fun setStatus(caseId: String, status: CaseStatus): Boolean =
        store().update { CaseFiles.setStatus(it, caseId, status, System.currentTimeMillis()) } != null

    /** "Stop keeping this case file"; false when the store can't be written now. */
    suspend fun stop(caseId: String): Boolean = store().update { CaseFiles.stop(it, caseId) } != null

    /** "Stop keeping case files for all". */
    suspend fun stopAll(): Boolean = store().update(CaseFiles::stopAll) != null

    /** Case files start again for organisations (after "Stop keeping case files for all"). */
    suspend fun startAgain(): Boolean = store().update(CaseFiles::startAgain) != null

    /** "Keep a case file" for [owner], by their numbers (a private contact's hides with it). */
    suspend fun keep(owner: CaseOwner, iso: String): Boolean = store().update {
        CaseFiles.setMode(it, owner.name, owner.numbers, owner.private, CaseMode.ON, System.currentTimeMillis(), iso, UUID.randomUUID().toString())
    } != null

    /** Makes the case file of [owner] when there is none yet (an organisation's card was opened); its id, or null. */
    suspend fun ensure(owner: CaseOwner, iso: String): String? {
        val after = store().update {
            CaseFiles.ensure(it, owner.name, owner.numbers, owner.private, System.currentTimeMillis(), iso, UUID.randomUUID().toString())
        } ?: return null
        return CaseFiles.find(after, owner.numbers, iso)?.id
    }
}
