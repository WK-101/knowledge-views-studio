package app.parley.data

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import androidx.annotation.VisibleForTesting
import app.parley.common.record.AccountKinds
import app.parley.common.record.NewContactAccount
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Which contacts accounts exist on this device and which Parley may write to. One place for the rules
 * so saving, importing, moving and restoring agree:
 * - the phone-only account is always writable, whether it is null (AOSP), Android 15's local account, or an OEM type
 *   such as Samsung's `vnd.sec.contact.phone` or Xiaomi's `com.android.contacts.default`;
 * - SIM and messenger accounts never are;
 * - any other account only when its contacts sync adapter `supportsUploading()`;
 * - on Android 16 and later, a new contact goes to the system's cloud default when Android refuses the phone
 *   ([newContacts], [NewContactAccount]).
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

    /**
     * Where new contacts may go on this device, read once per save or import. Every raw contact insert asks it, so
     * saving, imports, restores, Make visible and the rest agree.
     */
    class NewContacts(private val sdk: Int, private val default: NewContactAccount.SystemDefault<AccountRef>?, val device: AccountRef) {
        private val local: (AccountRef) -> Boolean = { isLocal(it, device) || AccountKinds.isSimType(it.type) }

        /** The cloud account Android puts new contacts in instead of the phone, or null when the phone takes them. */
        val cloudInstead: AccountRef? get() = NewContactAccount.cloudInstead(sdk, default)

        /** The account a new raw contact is written to; [requested] null when nobody chose one. */
        fun decide(requested: AccountRef?): NewContactAccount.Decision<AccountRef> =
            NewContactAccount.decide(sdk, default, requested, device, local)

        fun target(requested: AccountRef?): AccountRef = decide(requested).account

        fun offered(targets: List<AccountRef>): List<AccountRef> = NewContactAccount.offered(targets, sdk, default, local)
    }

    private val redirectsFlow = MutableSharedFlow<AccountRef>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Each time Android 16 took a new contact into its cloud default instead of the account asked for (a save, an
     * import, a restore, an undo), so the app can say where it went. Writes that tell the user themselves (Make visible,
     * the import report) don't report here.
     */
    val redirects: SharedFlow<AccountRef> = redirectsFlow

    internal fun noteRedirect(to: AccountRef) {
        redirectsFlow.tryEmit(to)
    }

    /** Replaces the device's answer in tests: Robolectric has no Android 16 Contacts Provider. */
    @VisibleForTesting
    @Volatile
    var newContactsOverride: ((Context) -> NewContacts)? = null

    fun newContacts(context: Context): NewContacts =
        newContactsOverride?.invoke(context) ?: NewContacts(Build.VERSION.SDK_INT, systemDefault(context), localAccount(context))

    /** Android 16's default account for new contacts; null before Android 16 or when it can't be read. */
    private fun systemDefault(context: Context): NewContactAccount.SystemDefault<AccountRef>? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            val d = RawContacts.DefaultAccount.getDefaultAccountForNewContacts(context.contentResolver)
            NewContactAccount.SystemDefault(NewContactAccount.state(d.state), d.account?.let { AccountRef(it.type, it.name) })
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            // A build that ships API 36 without this part of the provider.
            null
        }
    }

    fun isWritable(a: AccountRef, uploading: Set<String>, local: AccountRef): Boolean =
        AccountKinds.isWritable(a.type, uploading, local.type)

    /** Whether [a] is a phone-only account, including Android 15's and the OEM ones. */
    fun isLocal(a: AccountRef, local: AccountRef): Boolean = a.isLocal || (local.type != null && a.type == local.type)

    /**
     * Save, import, move and restore targets: the device account first, then every writable account signed in or
     * holding contacts. OEM phone-only types are folded into the device account (Android files null-account
     * contacts there itself). While Android 16 refuses the phone, its cloud default comes first instead.
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
        val all = AccountKinds.writableTargets(
            candidates.filter { !isLocal(it, local) }, { it.type }, uploading, device = local, platformLocalType = local.type,
        )
        return newContacts(context).offered(all)
    }
}
