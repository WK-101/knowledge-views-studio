package app.parley.ui.cases

import app.parley.common.CallEntry
import app.parley.common.cases.CaseFile
import app.parley.common.cases.CaseReference
import app.parley.common.cases.CaseReport
import app.parley.common.cases.CaseTimeline
import app.parley.data.DataContainer
import app.parley.ui.common.Format

/**
 * Builds the lines of a case file's PDF report ([CaseReport]) for the export job, in one pass. The timeline is reused
 * from the screen when it is ready ([timeline]); otherwise it is read here and now, so a single tap on "Export as PDF"
 * always builds the report. The report used to be gated on the screen's reactive timeline (`produceState`, which starts
 * null), so the first tap, before that timeline had arrived, did nothing and the person had to tap again.
 */
object CaseExport {
    /**
     * The report lines for [case]. When [timeline] is null it is read now over [calls] (a private contact's include
     * their private ones) and [ownerKey] (so the contact's own notes' promises are counted). Reference numbers are
     * opened with [openReference] and included only when [withReferences]; one that can't be opened is left out and
     * counted, never shown.
     */
    @Suppress("LongParameterList") // One argument per part of the report build; the suppliers read the timeline only when needed.
    suspend fun lines(
        c: DataContainer,
        case: CaseFile,
        timeline: CaseTimeline?,
        withReferences: Boolean,
        openReference: suspend (CaseReference) -> String?,
        words: CaseReport.Words,
        iso: String,
        now: Long = System.currentTimeMillis(),
        ownerKey: suspend () -> String? = { null },
        calls: suspend () -> List<CallEntry> = { emptyList() },
    ): List<CaseReport.Line> {
        val tl = timeline ?: CaseData.timeline(c, case, case.numbers, ownerKey(), calls(), iso)
        val refs = if (withReferences) {
            case.references.mapNotNull { r -> openReference(r)?.let { CaseReport.OpenReference(r.label, it, r.at) } }
        } else {
            null
        }
        val numbers = case.numbers.map { Format.number(it, iso) }
        return CaseReport.build(case.name, numbers, tl, refs, case.references.size, now, words, case.status, case.statusAt)
    }
}
