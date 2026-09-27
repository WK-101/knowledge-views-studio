package app.parley.data.security

import android.content.Context
import android.util.Log
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
        var n = 0
        var left = 0
        val meta = db.metaDao()
        for (m in meta.allMetaNow()) {
            val note = m.pinnedNote ?: continue
            if (crypto.isSealed(note)) continue
            val sealed = crypto.sealText(note)
            if (sealed != null && crypto.isSealed(sealed)) { meta.resealPinnedNote(m.lookupKey, note, sealed); n++ } else left++
        }
        for (c in meta.allCallNotesNow()) {
            if (c.text.isEmpty() || crypto.isSealed(c.text)) continue
            val sealed = crypto.sealText(c.text)
            if (sealed != null && crypto.isSealed(sealed)) { meta.resealCallNote(c.id, c.text, sealed); n++ } else left++
        }
        val blocks = db.blockDao()
        for (b in blocks.screenedSince(0)) {
            val name = b.callerName ?: continue
            if (name.isEmpty() || crypto.isSealed(name)) continue
            val sealed = crypto.sealText(name)
            if (sealed != null && crypto.isSealed(sealed)) { blocks.resealCallerName(b.id, name, sealed); n++ } else left++
        }
        for (id in meta.journalIds()) {
            val e = meta.journalEntry(id) ?: continue
            if (crypto.isSealed(e.payload)) continue
            val sealed = crypto.sealBytes(e.payload)
            if (crypto.isSealed(sealed)) { meta.resealJournalPayload(id, e.payload, sealed); n++ } else left++
        }
        n += runCatching { timeMachine().resealOld() }.getOrElse { left++; 0 }
        if (left == 0) prefs.edit().putBoolean(DONE, true).apply() else Log.w(TAG, "$left records stay plain until the next run")
        return n
    }

    private companion object {
        const val TAG = "RecordSealing"
        const val DONE = "done_v1"
    }
}
