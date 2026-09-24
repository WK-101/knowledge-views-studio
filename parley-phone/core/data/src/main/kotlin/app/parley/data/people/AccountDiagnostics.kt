package app.parley.data.people

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import app.parley.common.people.AccountCheck
import app.parley.common.people.AccountFinding
import app.parley.common.people.AccountKey
import app.parley.common.record.Messengers
import app.parley.data.AccountRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AccountReport(
    /** Accounts Android reports that can hold contacts, with how many contacts each holds. */
    val signedIn: List<Pair<AccountRef, Int>>,
    /** Accounts that own contacts (including ones no longer signed in). */
    val owning: List<Pair<AccountRef, Int>>,
    val findings: List<AccountFinding>,
    /** False when Android didn't let Parley read sync settings (shown as "unknown", never as "off"). */
    val syncKnown: Boolean,
)

/**
 * Account diagnostics for the health check: accounts Android reports vs accounts that own contacts, sync turned
 * off for an account (READ_SYNC_SETTINGS, a normal install-time permission), and a missing phone-only account.
 */
class AccountDiagnostics(private val context: Context) {
    private val cr = context.contentResolver

    suspend fun report(): AccountReport = withContext(Dispatchers.IO) {
        val contactTypes = try {
            ContentResolver.getSyncAdapterTypes().filter { it.authority == ContactsContract.AUTHORITY }.map { it.accountType }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val accounts: List<Account> = try {
            AccountManager.get(context).accounts.filter { it.type in contactTypes && !Messengers.isMessengerAccount(it.type) }
        } catch (_: Exception) {
            emptyList()
        }
        val owning = HashMap<AccountKey, MutableSet<Long>>()
        try {
            cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.CONTACT_ID), "${RawContacts.DELETED}=0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(0)
                    if (Messengers.isMessengerAccount(type)) continue
                    owning.getOrPut(AccountKey(type, c.getString(1))) { HashSet() } += c.getLong(2)
                }
            }
        } catch (_: Exception) {
        }
        var syncKnown = true
        val syncOff = HashSet<AccountKey>()
        for (a in accounts) {
            try {
                if (ContentResolver.getIsSyncable(a, ContactsContract.AUTHORITY) > 0 && !ContentResolver.getSyncAutomatically(a, ContactsContract.AUTHORITY)) {
                    syncOff += AccountKey(a.type, a.name)
                }
            } catch (_: SecurityException) {
                syncKnown = false
            }
        }
        val master = try {
            ContentResolver.getMasterSyncAutomatically()
        } catch (_: SecurityException) {
            syncKnown = false
            true
        }
        val local = localAccount()
        val counts = owning.mapValues { it.value.size }
        // Samsung/Xiaomi (before Android 15) and other OEMs keep phone-only contacts under their own type (F10).
        val localPresent = fixed() || (counts[AccountKey(null, null)] ?: 0) > 0 || (local.type != null && (counts[AccountKey(local.type, local.name)] ?: 0) > 0) ||
            counts.any { (k, n) -> n > 0 && app.parley.common.record.AccountKinds.isLocalType(k.type) }
        val signedInKeys = accounts.map { AccountKey(it.type, it.name) }.toSet()
        val findings = AccountCheck.check(
            signedIn = signedInKeys,
            owning = counts,
            syncOff = syncOff,
            masterSyncOn = master,
            localAccountPresent = localPresent,
            // Phone-only storage, SIM and OEM device accounts don't sync, so they are never "orphaned".
            unsyncedTypes = setOf(null, local.type) + app.parley.common.record.AccountKinds.OEM_LOCAL_TYPES +
                counts.keys.map { it.type }.filter { t -> t != null && t !in contactTypes },
        )
        AccountReport(
            signedIn = signedInKeys.map { AccountRef(it.type, it.name) to (counts[it] ?: 0) },
            owning = counts.entries.sortedByDescending { it.value }.map { AccountRef(it.key.type, it.key.name) to it.value },
            findings = findings,
            syncKnown = syncKnown,
        )
    }

    /**
     * Makes Android create its phone-only account: some apps only offer "Phone" as a place to save once a
     * phone-only raw contact exists. Adds an empty one and removes it again at once. Returns true on success.
     */
    suspend fun createLocalAccount(): Boolean = withContext(Dispatchers.IO) {
        val local = localAccount()
        try {
            val uri = cr.insert(
                RawContacts.CONTENT_URI,
                android.content.ContentValues().apply {
                    put(RawContacts.ACCOUNT_TYPE, local.type)
                    put(RawContacts.ACCOUNT_NAME, local.name)
                },
            ) ?: return@withContext false
            val id = ContentUris.parseId(uri)
            val purge = ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
            cr.delete(purge, null, null)
            prefs.edit().putBoolean(KEY_FIXED, true).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private val prefs = context.getSharedPreferences("account_diagnostics", Context.MODE_PRIVATE)

    /** The provider keeps its phone-only account once created, even with no phone-only contacts left. */
    private fun fixed() = prefs.getBoolean(KEY_FIXED, false)

    private companion object {
        const val KEY_FIXED = "local_account_created"
    }

    private fun localAccount(): AccountRef = app.parley.data.DeviceAccounts.localAccount(context)
}
