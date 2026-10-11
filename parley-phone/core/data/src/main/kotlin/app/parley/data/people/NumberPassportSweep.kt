package app.parley.data.people

import app.parley.common.people.Attribution
import app.parley.common.people.ChangeSource
import app.parley.common.people.NumberOrigin
import app.parley.common.people.ProvenanceVerdict
import app.parley.data.DataContainer

/**
 * Brings the number passport up to date with the address book: after contacts settle (not in a process started for a
 * call) and in the daily maintenance run. A number nobody accounted for is attributed with "Why did this change?".
 */
object NumberPassportSweep {
    suspend fun run(c: DataContainer) {
        // The shared list when it's loaded; a worker's process doesn't load it, so it reads the provider itself.
        val contacts = c.contacts.contacts.value ?: c.contacts.loadNow()
        c.contacts.passport.sweep(contacts, { id -> attribution(c.people.provenance.verdict(id)) })
    }

    /** "Why did this change?" as the passport's origin: Parley's own save is yours. */
    fun attribution(v: ProvenanceVerdict?): Attribution? = v?.let {
        val origin = when (it.source) {
            ChangeSource.PARLEY -> NumberOrigin.YOU
            ChangeSource.ANOTHER_APP -> NumberOrigin.OTHER_APP
            ChangeSource.SYNC -> NumberOrigin.SYNC
            ChangeSource.UNKNOWN -> NumberOrigin.UNKNOWN
        }
        Attribution(origin, it.time)
    }
}
