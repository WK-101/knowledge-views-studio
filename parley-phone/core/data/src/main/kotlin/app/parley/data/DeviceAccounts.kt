package app.parley.data

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import app.parley.common.record.AccountKinds

/**
 * Which contacts accounts exist on this device and which Parley may write to (F3, F10). One place for the rules
 * so saving, importing, moving and restoring agree:
 * - the phone-only account is always writable, whether it is null (AOSP), Android 15's local account, or an OEM type
 *   such as Samsung's `vnd.sec.contact.phone` or Xiaomi's `com.android.contacts.default`;
 * - SIM and messenger accounts never are;
 * - any other account only when its contacts sync adapter `supportsUploading()`.
 */
object DeviceAccounts {
    /** Account types whose contacts sync adapter uploads changes (so a raw contact there can be edited). */
    fun uploadingTypes(): Set<String> = try {
        ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /** Where Android puts phone-only contacts: Android 15's local account, else no account. */
    fun localAccount(context: Context): AccountRef {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val type = RawContacts.getLocalAccountType(context)
                if (type != null) return AccountRef(type, RawContacts.getLocalAccountName(context))
            } catch (_: Exception) {
            }
        }
        return AccountRef(null, null)
    }

    fun isWritable(a: AccountRef, uploading: Set<String>, local: AccountRef): Boolean =
        AccountKinds.isWritable(a.type, uploading, local.type)

    /** Whether [a] is a phone-only account, including Android 15's and the OEM ones. */
    fun isLocal(a: AccountRef, local: AccountRef): Boolean = a.isLocal || (local.type != null && a.type == local.type)

    /**
     * Save, import, move and restore targets: the device account first, then every writable account signed in or
     * holding contacts. OEM phone-only types are folded into the device account (Android files null-account
     * contacts there itself).
     */
    fun targets(context: Context): List<AccountRef> {
        val uploading = uploadingTypes()
        val local = localAccount(context)
        val candidates = LinkedHashSet<AccountRef>()
        try {
            AccountManager.get(context).accounts.filter { it.type in uploading }.forEach { candidates += AccountRef(it.type, it.name) }
        } catch (_: Exception) {
        }
        context.contentResolver.safeQuery(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED}=0")?.use { c ->
            while (c.moveToNext()) {
                val t = c.getString(0) ?: continue
                candidates += AccountRef(t, c.getString(1))
            }
        }
        return AccountKinds.writableTargets(
            candidates.filter { !isLocal(it, local) }, { it.type }, uploading, device = local, platformLocalType = local.type,
        )
    }
}
