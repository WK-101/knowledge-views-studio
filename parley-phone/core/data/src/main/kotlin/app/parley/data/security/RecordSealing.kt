package app.parley.data.security

import android.content.Context
import android.util.Log
import app.parley.common.suspendRunCatching
import app.parley.data.backup.TimeMachine
import app.parley.data.db.AppDatabase

/**
 * Seals the small records that older versions stored plain: pinned notes, call notes, screened callers' names, journal
 * payloads and time-machine snapshots. Runs in the background until everything is sealed (then it remembers that and
 * stops); values stay readable throughout, since readers accept both forms. Each write applies only if the value is
 * still the plain one it read, so an edit made meanwhile is never lost.
 */
class RecordSealing(context: Context, private val db: AppDatabase, private val timeMachine: () -> TimeMachine) {
    private val crypto = RecordCrypto.get(context)
    private val prefs = context.getSharedPreferences("record_sealing", Context.MODE_PRIVATE)

    val done: Boolean get() = prefs.getBoolean(DONE, false)

    /** Re-seals what is still plain; returns how many values it sealed. */
    suspend fun runIfNeeded(): Int {
        if (done) return 0
        val t = Tally()
        val meta = db.metaDao()
        for (m in meta.allMetaNow()) {
            val note = m.pinnedNote
            if (!note.isNullOrEmpty()) sealText(note, t) { meta.resealPinnedNote(m.lookupKey, note, it) }
        }
        for (c in meta.allCallNotesNow()) {
            if (c.text.isNotEmpty()) sealText(c.text, t) { meta.resealCallNote(c.id, c.text, it) }
        }
        val blocks = db.blockDao()
        for (b in blocks.screenedSince(0)) {
            val name = b.callerName
            if (!name.isNullOrEmpty()) sealText(name, t) { blocks.resealCallerName(b.id, name, it) }
        }
        for (id in meta.journalIds()) {
            val e = meta.journalEntry(id)
            if (e != null && !crypto.isSealed(e.payload)) {
                val sealed = crypto.sealBytes(e.payload)
                if (crypto.isSealed(sealed)) { meta.resealJournalPayload(id, e.payload, sealed); t.sealed++ } else t.left++
            }
        }
        suspendRunCatching { t.sealed += timeMachine().resealOld() }.onFailure { t.left++ }
        if (t.left == 0) prefs.edit().putBoolean(DONE, true).apply() else Log.w(TAG, "${t.left} records stay plain until the next run")
        return t.sealed
    }

    private class Tally(var sealed: Int = 0, var left: Int = 0)

    /** Seals one plain [text] and stores it with [write]; sealed values are skipped. */
    private suspend fun sealText(text: String, t: Tally, write: suspend (String) -> Unit) {
        if (crypto.isSealed(text)) return
        val sealed = crypto.sealText(text)
        if (sealed != null && crypto.isSealed(sealed)) {
            write(sealed)
            t.sealed++
        } else {
            t.left++
        }
    }

    private companion object {
        const val TAG = "RecordSealing"
        const val DONE = "done_v1"
    }
}
