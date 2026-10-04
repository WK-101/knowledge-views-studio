package app.parley.data.security

import android.content.Context
import android.util.Log
import app.parley.common.suspendRunCatching
import app.parley.data.backup.TimeMachine
import app.parley.data.db.AppDatabase

/**
 * Seals the small records that older versions stored plain: pinned notes, call notes, screened callers' names, journal
 * payloads and photos, time-machine snapshots, and the [stores] that seal their own values (the To call list). Runs in the
 * background until everything is sealed (then it remembers that and stops, until a value has to be stored plain again
 * because the key couldn't be used: [markPending]); values stay readable throughout, since readers accept both forms.
 * Each write applies only if the value is still the plain one it read, so an edit made meanwhile is never lost.
 */
class RecordSealing(
    context: Context,
    private val db: AppDatabase,
    private val timeMachine: () -> TimeMachine,
    /** Stores kept outside the database that seal their own values (the To call list). */
    private val stores: () -> List<Resealable> = { emptyList() },
) {
    /** A store that seals its own values; [resealPlain] is false while something of it is still plain or unwritten. */
    interface Resealable {
        suspend fun resealPlain(): Boolean
    }

    private val crypto = RecordCrypto.get(context)
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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
        sealJournal(t)
        suspendRunCatching { t.sealed += timeMachine().resealOld() }.onFailure { t.left++ }
        t.left += resealStores()
        if (t.left == 0) prefs.edit().putBoolean(DONE, true).apply() else Log.w(TAG, "${t.left} records stay plain until the next run")
        return t.sealed
    }

    /**
     * Journal payloads and photos. A photo kept plain stays the one every later copy of that photo points at, so it
     * is sealed in place rather than waiting for a new copy.
     */
    private suspend fun sealJournal(t: Tally) {
        val meta = db.metaDao()
        for (id in meta.journalIds()) {
            val e = meta.journalEntry(id)
            if (e != null && !crypto.isSealed(e.payload)) {
                val sealed = crypto.sealBytes(e.payload)
                if (crypto.isSealed(sealed)) { meta.resealJournalPayload(id, e.payload, sealed); t.sealed++ } else t.left++
            }
        }
        for (hash in meta.journalPhotoHashes()) {
            val p = meta.journalPhoto(hash)
            if (p != null && !crypto.isSealed(p.blob)) {
                val sealed = crypto.sealBytes(p.blob)
                if (crypto.isSealed(sealed)) { meta.resealJournalPhoto(hash, p.blob, sealed); t.sealed++ } else t.left++
            }
        }
    }

    /** The stores that seal their own values; returns how many still hold something plain or unwritten. */
    private suspend fun resealStores(): Int = stores().count { !suspendRunCatching { it.resealPlain() }.getOrDefault(false) }

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

    companion object {
        private const val TAG = "RecordSealing"
        private const val FILE = "record_sealing"
        private const val DONE = "done_v1"

        /** A value was just stored plain as a fallback: the next run seals it. */
        fun markPending(context: Context) {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            if (prefs.getBoolean(DONE, false)) prefs.edit().putBoolean(DONE, false).apply()
        }
    }
}
