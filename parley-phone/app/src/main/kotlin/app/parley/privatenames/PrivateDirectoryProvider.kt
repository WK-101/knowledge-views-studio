package app.parley.privatenames

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Directory
import android.provider.ContactsContract.PhoneLookup
import app.parley.ParleyApp
import app.parley.common.people.DirectoryPolicy
import app.parley.data.DataContainer
import app.parley.common.people.LookupApproval
import app.parley.common.people.LookupOutcome
import app.parley.common.people.LookupPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * I7: an opt-in contacts Directory (`android.content.ContactDirectory`) so a phone app you approve (Google Phone, also
 * when it runs in the car) can show a private contact's name when they call.
 *
 * How Android uses it (verified against AOSP ContactsProvider2 / ContactDirectoryManager): the Contacts Provider
 * finds enabled providers carrying the meta-data, asks `content://<authority>/directories` once (with its own
 * identity) and lists the directory. When an app then looks a number up in that directory
 * (`PhoneLookup.CONTENT_FILTER_URI` or `ENTERPRISE_CONTENT_FILTER_URI` with `?directory=<id>`), the Contacts Provider
 * forwards the query here, again as itself, adding the app's package as `callerPackage`
 * (Directory.CALLER_PACKAGE_PARAM_KEY). That parameter is trusted only when the Contacts Provider is the caller.
 *
 * Rules (the same as "Let apps show private names", C15): off by default (the component itself is disabled until
 * you turn it on, so no directory exists), one exact number per query (E.164 by keyed hash, never the last digits),
 * only for apps you approved (a notification asks the first time), 60 lookups per app per hour, every request
 * logged without the number, nothing in discreet mode, and no other query is ever answered: no lists, no filters,
 * no photos, no lookup by key.
 *
 * Limits: only apps that look callers up in directories see the name (Google Phone does; others may look only in local
 * contacts). A phone app inside a work profile can't reach it: Android lets the personal side look into the work profile, not
 * the other way round.
 */
class PrivateDirectoryProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        return when (DirectoryPolicy.request(uri.pathSegments)) {
            DirectoryPolicy.Request.DIRECTORIES -> directories(ctx, projection)
            DirectoryPolicy.Request.PHONE_LOOKUP -> phoneLookup(ctx, uri, projection)
            DirectoryPolicy.Request.OTHER -> MatrixCursor(projection ?: arrayOf(ContactsContract.Contacts._ID))
        }
    }

    private fun directories(ctx: Context, projection: Array<out String>?): Cursor {
        val cols = projection?.map { it }?.toTypedArray() ?: DIRECTORY_COLUMNS
        val cursor = MatrixCursor(cols)
        val row = cols.map { col ->
            when (col) {
                Directory.ACCOUNT_NAME -> "Parley"
                Directory.ACCOUNT_TYPE -> ctx.packageName
                Directory.DISPLAY_NAME -> ctx.getString(app.parley.R.string.privnames_directory)
                Directory.EXPORT_SUPPORT -> Directory.EXPORT_SUPPORT_NONE
                Directory.SHORTCUT_SUPPORT -> Directory.SHORTCUT_SUPPORT_NONE
                Directory.PHOTO_SUPPORT -> Directory.PHOTO_SUPPORT_NONE
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    private fun phoneLookup(ctx: Context, uri: Uri, projection: Array<out String>?): Cursor {
        val cols = projection?.map { it }?.toTypedArray() ?: LOOKUP_COLUMNS
        val result = MatrixCursor(cols)
        val caller = DirectoryPolicy.effectiveCaller(callingPackage, contactsProviderPackage(ctx), uri.getQueryParameter(DirectoryPolicy.CALLER_PACKAGE_PARAM))
            ?: return result
        if (caller == ctx.packageName) return result
        // Never blocks the binder thread: nothing while the app is still starting.
        val c = (ctx.applicationContext as? ParleyApp)?.containerOrNull ?: return result
        val access = c.people.privateNames
        val number = LookupPolicy.parseNumber(uri.pathSegments.getOrNull(1))
        val now = System.currentTimeMillis()
        // The Directory has its own approvals: allowing an app to use the lookup provider doesn't allow this.
        val approval = access.approval(caller, directory = true)
        var outcome = LookupPolicy.decide(access.state.value.directory, approval, number != null, access.recentQueries(caller, now), now)
        when (outcome) {
            LookupOutcome.ASKED -> if (access.takePrompt(caller, directory = true, now = now)) {
                if (approval == null) access.setApproval(caller, LookupApproval.PENDING, directory = true)
                PrivateNameProvider.askUser(ctx, caller, directory = true)
            }
            LookupOutcome.ANSWERED -> {
                val hidden = c.settings.settings.value.hideVault
                val hit = if (hidden) null else runBlocking(Dispatchers.IO) { withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { c.vault.lookup(number!!, exact = true) } }
                if (hit == null) {
                    outcome = if (hidden) LookupOutcome.OFF else LookupOutcome.NOT_FOUND
                } else {
                    result.addRow(
                        cols.map { col ->
                            when (col) {
                                // Never a real contact's id (see DirectoryPolicy.rowId); no lookup key either.
                                PhoneLookup._ID, PhoneLookup.CONTACT_ID -> DirectoryPolicy.rowId(hit.first)
                                PhoneLookup.DISPLAY_NAME, ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE -> hit.second.name
                                PhoneLookup.NUMBER, PhoneLookup.NORMALIZED_NUMBER -> number
                                PhoneLookup.TYPE -> 0
                                PhoneLookup.HAS_PHONE_NUMBER -> 1
                                PhoneLookup.STARRED, PhoneLookup.IN_VISIBLE_GROUP -> 0
                                else -> null
                            }
                        },
                    )
                }
            }
            else -> Unit
        }
        access.log(caller, outcome, now, viaDirectory = true)
        return result
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** The longest a lookup may hold the Contacts Provider's binder thread; slower answers are "not found". */
        private const val LOOKUP_TIMEOUT_MS = 200L

        private val DIRECTORY_COLUMNS = arrayOf(
            Directory.ACCOUNT_NAME, Directory.ACCOUNT_TYPE, Directory.DISPLAY_NAME, Directory.TYPE_RESOURCE_ID,
            Directory.EXPORT_SUPPORT, Directory.SHORTCUT_SUPPORT, Directory.PHOTO_SUPPORT,
        )
        private val LOOKUP_COLUMNS = arrayOf(PhoneLookup._ID, PhoneLookup.DISPLAY_NAME, PhoneLookup.NUMBER, PhoneLookup.TYPE, PhoneLookup.LABEL)

        @Volatile private var cp2: String? = null

        /** The package of the Contacts Provider ("com.android.providers.contacts" on AOSP; OEMs may differ). */
        @Suppress("DEPRECATION")
        private fun contactsProviderPackage(ctx: Context): String? = cp2 ?: runCatching {
            ctx.packageManager.resolveContentProvider(ContactsContract.AUTHORITY, 0)?.packageName
        }.getOrNull().also { cp2 = it }

        /**
         * Turns the directory on or off: the flag, the provider component (disabled means Android lists no directory
         * at all), and a nudge so the Contacts Provider rescans Parley now rather than at the next reboot.
         */
        fun setEnabled(ctx: Context, c: DataContainer, on: Boolean) {
            c.people.privateNames.setDirectoryEnabled(on)
            runCatching {
                ctx.packageManager.setComponentEnabledSetting(
                    ComponentName(ctx, PrivateDirectoryProvider::class.java),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            runCatching { Directory.notifyDirectoryChange(ctx.contentResolver) }
        }
    }
}
