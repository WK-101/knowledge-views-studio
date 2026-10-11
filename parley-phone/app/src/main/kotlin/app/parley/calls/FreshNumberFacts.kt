package app.parley.calls

import app.parley.common.catching
import app.parley.common.people.FreshNumber
import app.parley.common.people.NumberPassport
import app.parley.data.DataContainer
import app.parley.data.EmergencyNumbers
import app.parley.data.PhoneEnv
import app.parley.data.people.NumberOwners
import app.parley.data.people.NumberPassportSweep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gathers what the fresh-number warning ([NumberPassport]) decides on for a ringing call: the number's stamp in the
 * passport, your calls with it since, and the contact's other numbers. Fails safe: no contact, a private contact (only
 * Parley writes those), a passport that can't be read or hasn't started, an origin nobody knows: nothing shows.
 * Offline; nothing it reads leaves the phone.
 */
object FreshNumberFacts {
    suspend fun check(c: DataContainer, number: String, accountId: String?, now: Long = System.currentTimeMillis()): FreshNumber? =
        withContext(Dispatchers.IO) { checkNow(c, number, accountId, now) }

    private suspend fun checkNow(c: DataContainer, number: String, accountId: String?, now: Long): FreshNumber? {
        val iso = PhoneEnv.countryIso(c.appContext, accountId)
        if (EmergencyNumbers.isEmergency(c.appContext, number)) return null
        // A private contact's number (hidden or not): Parley alone writes those, so there's nothing to tell apart.
        if (catching { c.numberOwners.findIn(number, iso, NumberOwners.Use.CALL_PATH).private }.getOrNull() != null) return null
        val owner = catching { c.contacts.lookupAll(number) }.getOrDefault(emptyList()).firstOrNull() ?: return null
        val passport = c.contacts.passport
        val entry = passport.entry(number)?.takeIf { it.tracking } ?: return null
        // Changed since the last look at the address book (another app, just now): asked about here, not written down.
        val stamp = entry.stamp ?: if (entry.seen) {
            null
        } else {
            val verdict = catching { c.people.provenance.verdict(owner.contactId) }.getOrNull()
            val attribution = NumberPassportSweep.attribution(verdict)?.takeIf { it.origin.warns }
            // Many contacts changed since the last look (an account added): the sweep would say "unknown", so say nothing.
            // Unreadable counts as many: no line rather than a wrong one.
            val changed = if (attribution == null) {
                0
            } else {
                entry.looked?.let { l -> catching { c.contacts.changedSince(l, NumberPassport.MASS_CHANGE + 1) }.getOrNull() }
                    ?: (NumberPassport.MASS_CHANGE + 1)
            }
            NumberPassport.unseen(attribution, entry.looked, now, changed)
        }
        if (stamp == null || !stamp.origin.warns) return null
        val callsSince = catching { c.history.callsFor(number, since = stamp.since, region = iso) }.getOrDefault(emptyList()).size
        if (!NumberPassport.warns(stamp, now, callsSince)) return null
        val stamps = passport.state.value.stamps
        val others = catching { c.contacts.details(owner.contactId) }.getOrNull()?.phones.orEmpty()
            .map { passport.line(it.value) }.map { it to stamps[it.key] }
        return FreshNumber(stamp.origin, stamp.since, NumberPassport.olderNumber(stamp, entry.line.key, others, now))
    }
}
