package app.parley.calls

import android.provider.ContactsContract.CommonDataKinds.Phone
import app.parley.common.CallEntry
import app.parley.common.PhoneIdentity
import app.parley.common.calls.NeverCallsYou
import app.parley.data.DataContainer
import app.parley.data.EmergencyNumbers
import app.parley.data.PhoneEnv
import app.parley.data.history.CallHistory
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
        val saved = savedFor(c, number, iso)
        // The history is read only for an organisation: most callers stop at the contact lookup.
        if (saved.owners.isEmpty() || !saved.owners.all(NeverCallsYou::organisation)) return@withContext false
        NeverCallsYou.shows(number, line, saved.owners, pastCalls(c, number, saved.vaultId).map { it.type }, emergency = false)
    }

    /**
     * For the number's history: the date of the first call that came from a saved organisation's line after you had
     * only ever called it ("First call from them to you"), or null.
     */
    suspend fun firstFromThem(c: DataContainer, number: String, iso: String): Long? = withContext(Dispatchers.IO) {
        val saved = savedFor(c, number, iso)
        if (saved.owners.isEmpty() || !saved.owners.all(NeverCallsYou::organisation)) return@withContext null
        NeverCallsYou.firstFromThem(pastCalls(c, number, saved.vaultId), CallEntry::date, CallEntry::type)?.date
    }

    /** Everyone [number] is saved for, and the private contact among them (its id) when there is one. */
    private class Saved(val owners: List<NeverCallsYou.SavedAs>, val vaultId: Long?)

    private suspend fun savedFor(c: DataContainer, number: String, iso: String): Saved {
        val contacts = runCatching { c.contacts.lookupAll(number) }.getOrDefault(emptyList()).map { o ->
            NeverCallsYou.SavedAs(
                name = o.name,
                company = runCatching { c.contacts.organization(o.contactId)?.first }.getOrNull().orEmpty(),
                labels = runCatching { c.contacts.labelTitlesOf(o.contactId) }.getOrDefault(emptySet()),
                companyLine = o.phoneType == Phone.TYPE_COMPANY_MAIN,
            )
        }
        // Discreet mode: a private contact is a plain number everywhere, so it is no organisation here either.
        val private = if (c.settings.current().hideVault) null else runCatching { c.vault.lookup(number, iso) }.getOrNull()
        val privateOwner = private?.let { (id, info) ->
            NeverCallsYou.SavedAs(
                name = info.name,
                company = runCatching { c.vault.summary(id)?.company }.getOrNull().orEmpty(),
                labels = runCatching { c.privateLabels.titlesOf(id) }.getOrDefault(emptySet()),
            )
        }
        return Saved(contacts + listOfNotNull(privateOwner), private?.first)
    }

    /** Every call with the line Parley can read: the call log and the archive, and a private contact's sealed calls. */
    private suspend fun pastCalls(c: DataContainer, number: String, vaultId: Long?): List<CallEntry> {
        val shared = c.history.callsFor(number)
        val private = vaultId?.let { id -> runCatching { c.vault.privateCallsOf(id) }.getOrDefault(emptyList()).map { CallHistory.privateEntry(it) } }
        return shared + private.orEmpty()
    }
}
