package app.parley.calls

import android.provider.ContactsContract.CommonDataKinds.Phone
import app.parley.common.PhoneIdentity
import app.parley.common.calls.ExpectedCalls
import app.parley.common.calls.ExpectedWindow
import app.parley.common.calls.NeverCallsYou
import app.parley.common.catching
import app.parley.data.DataContainer
import app.parley.data.EmergencyNumbers
import app.parley.data.PhoneEnv
import app.parley.data.history.CallHistory
import app.parley.data.people.NumberOwners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gathers what [NeverCallsYou] decides on: who a number is saved for (contacts and, unless discreet mode hides them,
 * private contacts) and your calls with its line (the call log, the archive and a private contact's own calls). For
 * the ringing call and for the number's history page. Offline; nothing it reads leaves the phone.
 */
object NeverCallsYouFacts {
    /** Whether a call from [number] (on the SIM [accountId]) shows "This number never calls you". */
    suspend fun shows(c: DataContainer, number: String, accountId: String?): Boolean = withContext(Dispatchers.IO) {
        val iso = PhoneEnv.countryIso(c.appContext, accountId)
        val emergency = EmergencyNumbers.isEmergency(c.appContext, number) ||
            c.settings.current().screening.emergencyExtras.any { PhoneIdentity.same(it, number, iso) }
        val line = PhoneIdentity.e164(number, iso)
        if (emergency || line == null) return@withContext false
        val saved = savedFor(c, number, iso, NumberOwners.Use.CALL_PATH)
        if (saved.owners.isEmpty()) return@withContext false
        // "They'll call me back": the call you're waiting for isn't flagged, and isn't counted as them calling you.
        expectedCallback(c, number, iso, saved.vaultId)?.let { w ->
            catching { c.disownedCalls.expect(number, w.start, w.end) }
            return@withContext false
        }
        // "They never call me" on the contact: no history needed.
        if (NeverCallsYou.shows(number, line, saved.owners, emptyList(), null, emergency = false)) return@withContext true
        // The history is read only for an organisation: most callers stop at the contact lookup.
        if (!saved.owners.all(NeverCallsYou::organisation)) return@withContext false
        val keptSince = keptSince(c, number) ?: return@withContext false
        val past = pastCalls(c, number, saved.vaultId, iso)
        NeverCallsYou.shows(number, line, saved.owners, past, keptSince, emergency = false)
    }

    /**
     * For the number's history: the date of the first call that came from a saved organisation's line after you had
     * only ever called it ("First call from them to you"), or null.
     */
    suspend fun firstFromThem(c: DataContainer, number: String, iso: String): Long? = withContext(Dispatchers.IO) {
        val saved = savedFor(c, number, iso)
        if (saved.owners.isEmpty() || !saved.owners.all(NeverCallsYou::organisation)) return@withContext null
        val keptSince = keptSince(c, number) ?: return@withContext null
        NeverCallsYou.firstFromThem(pastCalls(c, number, saved.vaultId, iso), keptSince, NeverCallsYou.PastCall::date, NeverCallsYou.PastCall::type)?.date
    }

    /**
     * For the number's history: whether a call faking [number] would be warned about, and if not, why
     * ([NeverCallsYou.watch]); null when there is nothing to say about it.
     */
    suspend fun watch(c: DataContainer, number: String, iso: String): NeverCallsYou.Watch? = withContext(Dispatchers.IO) {
        val saved = savedFor(c, number, iso)
        if (saved.owners.isEmpty()) return@withContext null
        if (saved.owners.any { it.neverCalls }) return@withContext NeverCallsYou.watch(saved.owners, emptyList(), null)
        if (!saved.owners.all(NeverCallsYou::organisation)) return@withContext null
        NeverCallsYou.watch(saved.owners, pastCalls(c, number, saved.vaultId, iso), keptSince(c, number))
    }

    /**
     * The "They'll call me back" window open now for whoever [number] is saved for: one for this line, or for another
     * of the same contact's numbers (organisations call back from any of their lines). Null when none (the usual case,
     * read from memory without looking anything up).
     */
    private suspend fun expectedCallback(c: DataContainer, number: String, iso: String, vaultId: Long?): ExpectedWindow? {
        val open = ExpectedCalls.callbacksAt(c.familySafety.windowsNow().orEmpty(), System.currentTimeMillis())
        if (open.isEmpty()) return null
        open.firstOrNull { w -> w.number != null && PhoneIdentity.same(w.number, number, iso) }?.let { return it }
        val ids = catching { c.contacts.lookupAll(number) }.getOrDefault(emptyList()).map { it.contactId }.toSet()
        return open.firstOrNull { w ->
            val other = w.number ?: return@firstOrNull false
            val theirs = catching { c.contacts.lookupAll(other) }.getOrDefault(emptyList()).map { it.contactId }
            theirs.any { it in ids } ||
                (vaultId != null && catching { c.numberOwners.findIn(other, iso, NumberOwners.Use.CALL_PATH).private?.first }.getOrNull() == vaultId)
        }
    }

    /** Who a number is saved for when that is an organisation: the name to show, and whether it is a private contact's. */
    data class Organisation(val name: String, val private: Boolean)

    /**
     * Case files: the organisation [number] is saved for (every contact it is saved for looks like one, as for the
     * notice), or null. Private contacts count only while they aren't hidden.
     */
    suspend fun organisation(c: DataContainer, number: String, iso: String): Organisation? = withContext(Dispatchers.IO) {
        val saved = savedFor(c, number, iso)
        val first = saved.owners.firstOrNull() ?: return@withContext null
        if (!saved.owners.all(NeverCallsYou::organisation)) return@withContext null
        Organisation(first.name, private = saved.vaultId != null)
    }

    /**
     * From when Parley's own copy of your calls holds every call with the line (null when it's off or can't be read):
     * Android's log alone trims itself, so it can't vouch that a line never called.
     */
    private suspend fun keptSince(c: DataContainer, number: String): Long? =
        catching { c.history.keptSince(number, c.settings.current().callLogRetentionDays) }.getOrNull()

    /** Everyone [number] is saved for, and the private contact among them (its id) when there is one. */
    private class Saved(val owners: List<NeverCallsYou.SavedAs>, val vaultId: Long?)

    private suspend fun savedFor(c: DataContainer, number: String, iso: String, use: NumberOwners.Use = NumberOwners.Use.SCREEN): Saved {
        // "They never call me" is read from memory, and a contact's key looked up only when someone chose it at all.
        val neverCallKeys = c.extras.callerChoices.value.filterValues { it.neverCalls }.keys
        val contacts = catching { c.contacts.lookupAll(number) }.getOrDefault(emptyList()).map { o ->
            NeverCallsYou.SavedAs(
                name = o.name,
                company = catching { c.contacts.organization(o.contactId)?.first }.getOrNull().orEmpty(),
                labels = catching { c.contacts.labelTitlesOf(o.contactId) }.getOrDefault(emptySet()),
                companyLine = o.phoneType == Phone.TYPE_COMPANY_MAIN,
                neverCalls = neverCallKeys.isNotEmpty() && catching { c.contacts.lookupKeyOf(o.contactId) }.getOrNull() in neverCallKeys,
            )
        }
        // Discreet mode: a private contact is a plain number everywhere, so it is no organisation here either.
        val private = if (c.privacy.now().privateHidden) null else catching { c.numberOwners.findIn(number, iso, use).private }.getOrNull()
        val privateOwner = private?.let { (id, info) ->
            val summary = catching { c.vault.summary(id) }.getOrNull()
            NeverCallsYou.SavedAs(
                name = info.name,
                company = summary?.company.orEmpty(),
                labels = catching { c.privateLabels.titlesOf(id) }.getOrDefault(emptySet()),
                neverCalls = summary?.neverCalls == true,
            )
        }
        return Saved(contacts + listOfNotNull(privateOwner), private?.first)
    }

    /**
     * Every call with the line Parley can read: the call log and the archive, and a private contact's sealed calls,
     * without the calls you said weren't them and those that rang in a "They'll call me back" window
     * ([app.parley.data.calls.DisownedCalls]). [region] is the call's SIM's, so
     * rows logged on that SIM in its own national format are matched too.
     */
    private suspend fun pastCalls(c: DataContainer, number: String, vaultId: Long?, region: String): List<NeverCallsYou.PastCall> {
        val shared = c.history.callsFor(number, region = region)
        val private = vaultId?.let { id -> catching { c.vault.privateCallsOf(id) }.getOrDefault(emptyList()).map { CallHistory.privateEntry(it) } }
        val past = (shared + private.orEmpty()).map { NeverCallsYou.PastCall(it.type, it.date) }
        val kept = NeverCallsYou.withoutExpected(past, catching { c.disownedCalls.expectedFor(number) }.getOrDefault(emptyList()))
        return NeverCallsYou.withoutDisowned(kept, catching { c.disownedCalls.forNumber(number) }.getOrDefault(emptyList()))
    }

    /** "It wasn't them": [number]'s latest call is left out of the history "This number never calls you" reads. */
    suspend fun disown(c: DataContainer, number: String) {
        withContext(Dispatchers.IO) { catching { c.disownedCalls.disown(number) } }
    }
}
