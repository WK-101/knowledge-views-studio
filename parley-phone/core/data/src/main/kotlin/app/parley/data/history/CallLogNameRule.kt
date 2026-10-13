package app.parley.data.history

import app.parley.common.backup.CallLogRecord
import app.parley.common.history.CallLogNames
import app.parley.common.catching
import app.parley.data.CallLogRepository
import app.parley.data.ContactsRepository
import app.parley.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [CallLogNames] with this phone's rule: a name goes back into Android's call log only for a number an address-book
 * contact owns (archived contacts have left the address book) and no private contact does.
 */
fun callLogNames(contacts: ContactsRepository, vault: VaultRepository): CallLogNames = CallLogNames { n ->
    withContext(Dispatchers.IO) { contacts.lookup(n) != null } && vault.lookup(n) == null
}

/** [records] with only the names [names] lets Android's call log keep. */
suspend fun CallLogNames.scrub(records: List<CallLogRecord>): List<CallLogRecord> =
    records.map { r -> keep(r.number, r.name).let { if (it == r.name) r else r.copy(name = it) } }

/**
 * Clears Android's call-log names for archived and private people's [numbers] that no address-book contact owns now
 * ([CallLogRepository.clearCachedNames]): at archive time, and in the daily upkeep for what an older version, a contact
 * made private or a call the phone named meanwhile left.
 */
suspend fun forgetHiddenCallLogNames(callLog: CallLogRepository, contacts: ContactsRepository, numbers: Collection<String>, region: String?): Int {
    val hidden = withContext(Dispatchers.IO) { numbers.filter { it.isNotBlank() }.distinct().filter { catching { contacts.lookup(it) }.getOrNull() == null } }
    return callLog.clearCachedNames(hidden, region)
}
