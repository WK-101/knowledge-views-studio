package app.parley.privatenames

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.work.PrivateNotice
import app.parley.common.people.LookupApproval
import app.parley.common.people.LookupOutcome
import app.parley.common.people.LookupPolicy
import app.parley.ParleyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Protected private-name lookup ("Let apps show private names"). Another app holding the custom permission
 * (declared in the manifest, requested by that app and granted by the user) can ask for the name of ONE phone
 * number: `content://<package>.privatenames/lookup/<number>` → zero or one row (display_name, photo_uri).
 *
 * - Never a list: no other paths, no selection, no wildcards, at least [LookupPolicy.MIN_DIGITS] digits, exact
 *   match only (the vault matches the E.164 form by keyed hash, never the last digits), and a per-app hourly limit.
 * - Off by default; each app must also be approved in Parley (first query → a notification asking the user).
 * - Every query is logged (app, time, outcome; never the number) and shown in Privacy.
 * - Discreet mode ("Hide private contacts") answers nothing.
 */
class PrivateNameProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(COLUMNS)
        val ctx = context ?: return result
        val caller = callingPackage ?: return result
        if (caller == ctx.packageName) return result
        val c = (ctx.applicationContext as? ParleyApp)?.containerOrNull ?: return result
        val access = c.people.privateNames
        val segments = uri.pathSegments
        val number = if (segments.size == 2 && segments[0] == PATH && selection.isNullOrEmpty() && selectionArgs.isNullOrEmpty()) LookupPolicy.parseNumber(segments[1]) else null
        val now = System.currentTimeMillis()
        val approval = access.approval(caller)
        var outcome = LookupPolicy.decide(access.stored.enabled, approval, number != null, access.recentQueries(caller, now), now)
        when (outcome) {
            LookupOutcome.ASKED -> if (access.takePrompt(caller, directory = false, now = now)) {
                if (approval == null) access.markPending(caller, directory = false)
                askUser(ctx, caller)
            }
            LookupOutcome.ANSWERED -> {
                // M8: read from storage, not the settings flow (its first value in a cold process is the defaults); fails closed.
                val hidden = runBlocking(Dispatchers.IO) { c.settings.hidesPrivateNames() }
                val hit = if (hidden) null else runBlocking(Dispatchers.IO) { withTimeoutOrNull(2_000) { c.vault.lookup(number!!, exact = true) } }
                if (hit == null) outcome = if (hidden) LookupOutcome.OFF else LookupOutcome.NOT_FOUND
                else result.addRow(arrayOf<Any?>(hit.second.name, null))
            }
            else -> Unit
        }
        access.log(caller, outcome, now)
        return result
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.parley.privatename"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val PATH = "lookup"
        private val COLUMNS = arrayOf("display_name", "photo_uri")
        private const val CHANNEL = NotificationChannels.PRIVATE_NAMES
        private const val NOTIFICATION_TAG = NotificationIds.TAG_PRIVATE_NAME

        fun authority(context: Context) = context.packageName + ".privatenames"

        /** The permission guarding the provider, as declared in the manifest (it differs in debug builds). */
        @Suppress("DEPRECATION")
        fun permission(context: Context): String =
            runCatching { context.packageManager.resolveContentProvider(authority(context), 0)?.readPermission }
                .getOrNull() ?: "app.parley.permission.LOOKUP_PRIVATE_NAME"

        /** [directory]: the request is for the contacts Directory, which has its own approvals and text. */
        internal fun askUser(ctx: Context, pkg: String, directory: Boolean = false) {
            // The package name, never the app's own label: any app can call itself "Phone".
            val label = pkg
            val id = notificationId(pkg, directory)

            // "Allow…" opens the approval sheet in Parley, behind Parley's own lock (its PIN when one is set), which
            // shows the package name and the signing certificate before anything is allowed.
            val allowIntent = IntentRoutes.own(ctx).setAction(IntentRoutes.ACTION_APPROVE_PRIVATE_NAME)
                .putExtra(IntentRoutes.EXTRA_PACKAGE, pkg).putExtra(IntentRoutes.EXTRA_DIRECTORY, directory)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val allow = NotificationCompat.Action.Builder(
                0, ctx.getString(R.string.privnames_allow_ask),
                PendingIntent.getActivity(ctx, id * 2 + 1, allowIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build()

            // "Don't allow" takes nothing away from anyone, so it acts at once, but still not from the lock screen:
            // Android 12+ asks for the unlock before sending the broadcast; before that, the action opens an invisible
            // activity, which the lock screen only starts after unlocking.
            fun deny(): NotificationCompat.Action {
                val title = ctx.getString(R.string.privnames_deny)
                val req = id * 2
                if (Build.VERSION.SDK_INT >= 31) {
                    val pi = PendingIntent.getBroadcast(
                        ctx, req, decisionIntent(Intent(ctx, PrivateNameDecisionReceiver::class.java), pkg, directory),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    return NotificationCompat.Action.Builder(0, title, pi).setAuthenticationRequired(true).build()
                }
                val pi = PendingIntent.getActivity(
                    ctx, req,
                    decisionIntent(Intent(ctx, PrivateNameDecisionActivity::class.java), pkg, directory)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                return NotificationCompat.Action.Builder(0, title, pi).build()
            }
            val open = PrivateNotice.open(ctx, id, Intent(ctx, MainActivity::class.java))
            val n = PrivateNotice.builder(
                ctx, CHANNEL, R.drawable.ic_tile_private,
                ctx.getString(if (directory) R.string.privnames_dir_request_title else R.string.privnames_request_title, label),
                ctx.getString(R.string.privnames_channel),
                ctx.getString(if (directory) R.string.privnames_dir_request_text else R.string.privnames_request_text, label), open,
            )
                .addAction(allow)
                .addAction(deny())
            PrivateNotice.post(ctx, NOTIFICATION_TAG, id, n)
        }

        const val EXTRA_PACKAGE = IntentRoutes.EXTRA_PACKAGE
        const val EXTRA_DIRECTORY = IntentRoutes.EXTRA_DIRECTORY
        private const val LEGACY_EXTRA_ALLOW = "allow"

        private fun decisionIntent(i: Intent, pkg: String, directory: Boolean) =
            i.putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_DIRECTORY, directory)

        /**
         * "Don't allow" from the request notification. It never allows anything: "Allow" goes through the approval sheet
         * behind Parley's lock. While a duress unlock hides things the answer is only shown ([PrivateNameAccess]).
         */
        internal fun decide(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
            val directory = intent.getBooleanExtra(EXTRA_DIRECTORY, false)
            // An "Allow" from a notification posted by an earlier version answers nothing; the app is asked again.
            if (intent.getBooleanExtra(LEGACY_EXTRA_ALLOW, false)) return cancel(context, pkg, directory)
            (context.applicationContext as? ParleyApp)?.containerOrNull?.people?.privateNames?.setApproval(pkg, LookupApproval.DENIED, directory)
            cancel(context, pkg, directory)
        }

        private fun notificationId(pkg: String, directory: Boolean) = if (directory) ("directory:" + pkg).hashCode() else pkg.hashCode()

        fun cancel(ctx: Context, pkg: String, directory: Boolean = false) = NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_TAG, notificationId(pkg, directory))
    }
}

/** "Don't allow" from the request notification (Android 12+). Not exported: only Parley's own PendingIntents reach it. */
class PrivateNameDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = PrivateNameProvider.decide(context, intent)
}

/** The same answer on Android 10-11, where only an activity can make the lock screen ask for the unlock first. Not exported. */
class PrivateNameDecisionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrivateNameProvider.decide(applicationContext, intent)
        finish()
    }
}
