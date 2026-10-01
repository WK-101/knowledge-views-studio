package app.parley.calls

import app.parley.common.PhoneIdentity
import app.parley.common.calls.ExpectedCalls
import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.ExpectedWindow
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * I7 expected-call hints: notes and promises with a day ("dentist will call Tue"), To call items for numbers nobody
 * saved, and delivery-like QR codes turn on "Expecting a call" for a window (the same exception as the tile, read by
 * screening with the rest of it). Each kind is asked about once ([offer], shown by the app's root): off until the user
 * says yes, and Settings › Blocking & spam › "Expecting a call from your notes" changes it later.
 */
object ExpectedCallHints {
    /** A window waiting for the user's first yes or no about its kind. */
    data class Offer(val window: ExpectedWindow)

    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    /** A note (or promise) about [name] was saved; [key] tells this note's window from others. */
    suspend fun noteSaved(c: DataContainer, name: String?, text: String?, key: String, now: Long = System.currentTimeMillis()) {
        val (start, end) = ExpectedCalls.fromNote(text, now, ZoneId.systemDefault()) ?: return
        consider(c, ExpectedWindow(start, end, ExpectedSource.NOTE, key, label = name?.takeIf { it.isNotBlank() }))
    }

    /** [number] went on the To call list for [at]: it may call back first. Only for numbers nobody saved (contacts ring anyway). */
    suspend fun toCallAdded(c: DataContainer, number: String, at: Long, now: Long = System.currentTimeMillis()) {
        val known = withContext(Dispatchers.IO) { runCatching { c.contacts.lookup(number) != null || c.vault.lookup(number) != null }.getOrDefault(true) }
        if (known) return
        val (start, end) = ExpectedCalls.forToCall(at, now)
        consider(c, ExpectedWindow(start, end, ExpectedSource.TO_CALL, PhoneIdentity.key(number, PhoneEnv.countryIso(c.appContext)), number = number))
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

    /** Who a call note is about: a contact's name, a private contact's outside discreet mode, else none. */
    suspend fun callerName(c: DataContainer, number: String?): String? = withContext(Dispatchers.IO) {
        if (number.isNullOrBlank()) return@withContext null
        runCatching { c.contacts.lookup(number)?.takeIf { !it.work }?.name }.getOrNull()
            ?: if (c.settings.current().hideVault) null else runCatching { c.vault.lookup(number)?.second?.name }.getOrNull()
    }
}
