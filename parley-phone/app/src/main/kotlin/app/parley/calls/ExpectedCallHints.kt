package app.parley.calls

import app.parley.common.calls.ExpectedCalls
import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.ExpectedWindow
import app.parley.common.catching
import app.parley.common.people.ContactRef
import app.parley.data.DataContainer
import app.parley.data.circle.CircleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Expected-call hints: notes and promises with a day ("dentist will call Tue"), To call items for numbers nobody
 * saved, and delivery-like QR codes turn on "Expecting a call" for a window (the same exception as the tile, read by
 * screening with the rest of it). Each kind is asked about once ([offer], shown by the app's root): off until the user
 * says yes, and Settings › Blocking & spam › "Expecting a call from your notes" changes it later.
 */
object ExpectedCallHints {
    /** A window waiting for the user's first yes or no about its kind. */
    data class Offer(val window: ExpectedWindow)

    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    /**
     * A note (or promise) about [name] was saved, or changed ([key]: that note, see [loggedKey] and [callNoteKey]). A
     * note that no longer promises a call (edited, ticked off, emptied) withdraws the window it opened. [number] limits
     * the window to the line the note is about (a call note); [privateName] marks a private contact's name, which
     * discreet mode hides.
     */
    suspend fun noteSaved(
        c: DataContainer, name: String?, text: String?, key: String, number: String? = null, privateName: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        val (start, end) = ExpectedCalls.fromNote(text, now, ZoneId.systemDefault()) ?: return noteGone(c, key)
        val label = name?.takeIf { it.isNotBlank() }
        val line = number?.takeIf { it.isNotBlank() }
        consider(c, ExpectedWindow(start, end, ExpectedSource.NOTE, key, label = label, number = line, privateName = privateName && label != null))
    }

    /** The note [key] was deleted (or no longer promises a call): its window goes, and so does an offer made from it. */
    suspend fun noteGone(c: DataContainer, key: String) {
        _offer.value?.takeIf { it.window.source == ExpectedSource.NOTE && it.window.key == key }?.let { _offer.value = null }
        val store = c.familySafety
        if (store.load() && store.summary.value.windows.any { it.source == ExpectedSource.NOTE && it.key == key }) {
            store.removeWindow(ExpectedSource.NOTE, key)
        }
    }

    /** The window key of a logged interaction's note: one window per note, never one per person. */
    fun loggedKey(id: Long): String = "note:i$id"

    /** The window key of a call note. */
    fun callNoteKey(id: Long): String = "call:n$id"

    /**
     * A promise was ticked off (or back on) in [note] about [lookupKey]: its window follows the note as it is now (gone
     * when nothing in it promises a call any more). The window keeps its name and line.
     */
    suspend fun promiseTicked(c: DataContainer, lookupKey: String, note: CircleRepository.PersonNote) {
        val key = when (note.source) {
            CircleRepository.NoteSource.LOGGED -> loggedKey(note.id)
            CircleRepository.NoteSource.CALL -> callNoteKey(note.id)
            // The pinned note never opens a window.
            CircleRepository.NoteSource.PINNED -> return
        }
        val text = withContext(Dispatchers.IO) {
            runCatching {
                if (note.source == CircleRepository.NoteSource.LOGGED) c.circle.interactions.noteOf(note.id) else c.meta.callNote(note.id)?.text
            }.getOrNull()
        }
        if (!c.familySafety.load()) return
        val w = c.familySafety.summary.value.windows.firstOrNull { it.source == ExpectedSource.NOTE && it.key == key }
        // A call note's window is for its own line, which only the window remembers: without one, it opens none.
        if (w == null && note.source == CircleRepository.NoteSource.CALL) return
        noteSaved(c, w?.label, text, key, w?.number, w?.privateName ?: ContactRef.isPrivateKey(lookupKey))
    }

    /** [number] went on the To call list for [at]: it may call back first. Only for numbers nobody saved (contacts ring anyway). */
    suspend fun toCallAdded(c: DataContainer, number: String, key: String, at: Long, now: Long = System.currentTimeMillis()) {
        // Archived contacts are saved contacts too; a lookup that failed counts as saved (no window for a contact).
        val known = withContext(Dispatchers.IO) { catching { c.numberOwners.find(number, null).let { it.saved || it.unsure } }.getOrDefault(true) }
        if (known) return
        val (start, end) = ExpectedCalls.forToCall(at, now)
        consider(c, ExpectedWindow(start, end, ExpectedSource.TO_CALL, key, number = number))
    }

    /** The To call lines [keys] left the list (done, removed, or settled): their windows go. */
    suspend fun toCallGone(c: DataContainer, keys: Set<String>) {
        val store = c.familySafety
        if (!store.load()) return
        store.summary.value.windows.filter { it.source == ExpectedSource.TO_CALL && it.key in keys }.forEach { store.removeWindow(it.source, it.key) }
    }

    /** A QR code was scanned: a parcel's tracking code lets the courier's call ring. */
    suspend fun qrScanned(c: DataContainer, text: String?, now: Long = System.currentTimeMillis()) {
        if (!ExpectedCalls.isDelivery(text)) return
        val (start, end) = ExpectedCalls.forDelivery(now, ZoneId.systemDefault())
        consider(c, ExpectedWindow(start, end, ExpectedSource.DELIVERY_QR, "delivery"))
    }

    private suspend fun consider(c: DataContainer, w: ExpectedWindow) {
        val store = c.familySafety
        if (!store.load()) return
        when (store.summary.value.consents[w.source]) {
            true -> store.putWindow(w)
            // Never asked: once, with this window as the example.
            null -> _offer.value = Offer(w)
            false -> Unit
        }
    }

    /** "Turn on": this kind is on from now, starting with the offered window. */
    suspend fun accept(c: DataContainer, offer: Offer) {
        _offer.value = null
        c.familySafety.setConsent(offer.window.source, true)
        c.familySafety.putWindow(offer.window)
    }

    /** "No thanks" (or the question dismissed): asked once, so it stays off until turned on in Settings. */
    suspend fun decline(c: DataContainer, offer: Offer) {
        _offer.value = null
        c.familySafety.setConsent(offer.window.source, false)
    }

    /**
     * Who a call note is about ([app.parley.data.people.NumberOwners]): a contact's name, a private contact's (with true:
     * discreet mode hides it wherever the window shows), an archived contact's, else none.
     */
    suspend fun caller(c: DataContainer, number: String?): Pair<String?, Boolean> = withContext(Dispatchers.IO) {
        if (number.isNullOrBlank()) return@withContext null to false
        val found = catching { c.numberOwners.find(number, null) }.getOrNull() ?: return@withContext null to false
        found.contact?.takeIf { !it.work }?.name?.let { return@withContext it to false }
        found.private?.second?.name?.let { return@withContext it to true }
        found.archived?.name to false
    }
}
