package app.parley.common.people

/** An account as (type, name); type null = phone-only storage. */
data class AccountKey(val type: String?, val name: String?)

enum class AccountFindingKind { ORPHANED, NO_CONTACTS, SYNC_OFF, MASTER_SYNC_OFF, LOCAL_MISSING }

data class AccountFinding(val kind: AccountFindingKind, val account: AccountKey?, val count: Int = 0)

/**
 * Account diagnostics for the health check: compares the accounts Android reports (signed in, with a contacts
 * sync adapter) with the accounts that actually own contacts, and flags accounts whose contacts sync is off.
 */
object AccountCheck {
    fun check(
        signedIn: Set<AccountKey>,
        owning: Map<AccountKey, Int>,
        syncOff: Set<AccountKey>,
        masterSyncOn: Boolean,
        /** Whether the provider knows a phone-only (local) account: a local raw contact exists or was created. */
        localAccountPresent: Boolean,
        /** Account types that never sync (phone-only storage, SIM, messenger apps). */
        unsyncedTypes: Set<String?> = emptySet(),
    ): List<AccountFinding> {
        val out = ArrayList<AccountFinding>()
        if (!masterSyncOn && signedIn.isNotEmpty()) out += AccountFinding(AccountFindingKind.MASTER_SYNC_OFF, null)
        // Contacts in accounts that are no longer on the phone: they stay, but nothing syncs them any more.
        owning.filter { (a, n) -> n > 0 && a.type != null && a.type !in unsyncedTypes && a !in signedIn }
            .forEach { (a, n) -> out += AccountFinding(AccountFindingKind.ORPHANED, a, n) }
        signedIn.filter { it in syncOff }.forEach { out += AccountFinding(AccountFindingKind.SYNC_OFF, it, owning[it] ?: 0) }
        signedIn.filter { (owning[it] ?: 0) == 0 && it !in syncOff }.forEach { out += AccountFinding(AccountFindingKind.NO_CONTACTS, it) }
        if (!localAccountPresent) out += AccountFinding(AccountFindingKind.LOCAL_MISSING, AccountKey(null, null))
        return out
    }
}
