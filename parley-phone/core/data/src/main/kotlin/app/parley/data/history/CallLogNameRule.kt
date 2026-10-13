package app.parley.data.history

import android.Manifest
import android.content.Context
import app.parley.common.backup.CallLogRecord
import app.parley.common.history.CallLogNames
import app.parley.data.CallLogRepository
import app.parley.data.ContactsRepository
import app.parley.data.Permissions
import app.parley.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
suspend fun forgetHiddenCallLogNames(
    context: Context, callLog: CallLogRepository, contacts: ContactsRepository, numbers: Collection<String>, region: String?,
): Int {
    val canRead = Permissions.has(context, Manifest.permission.READ_CONTACTS)
    val hidden = withContext(Dispatchers.IO) { CallLogNames.hidden(numbers, canRead) { contacts.lookup(it) != null } }
    return callLog.clearCachedNames(hidden, region)
}

/**
 * Restores a backup's call-log section ([records]) into Android's call log, skipping calls it already has, with only the
 * names it may keep: archived and private people's calls go back without theirs ([CallLogNames]). Blocking: for
 * readers that stream a backup section on an IO thread. Returns how many rows were written.
 */
fun CallLogRepository.restoreNamed(records: Sequence<CallLogRecord>, contacts: ContactsRepository, vault: VaultRepository): Int {
    val existing = rowSignatures()
    val names = callLogNames(contacts, vault)
    return records.filter { CallLogRepository.signature(it.number, it.date, it.duration, it.type) !in existing }.chunked(RESTORE_CHUNK)
        .sumOf { insert(runBlocking { names.scrub(it) }) }
}

private const val RESTORE_CHUNK = 200
