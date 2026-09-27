package app.parley.privatenames

import android.app.NotificationChannel
import android.app.NotificationManager
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.MainActivity
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
        var outcome = LookupPolicy.decide(access.state.value.enabled, approval, number != null, access.recentQueries(caller, now), now)
        when (outcome) {
            LookupOutcome.ASKED -> if (access.takePrompt(caller, directory = false, now = now)) {
                if (approval == null) access.setApproval(caller, LookupApproval.PENDING)
                askUser(ctx, caller)
            }
            LookupOutcome.ANSWERED -> {
                val hidden = c.settings.settings.value.hideVault
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
        private const val CHANNEL = app.parley.common.NotificationChannels.PRIVATE_NAMES
        private const val NOTIFICATION_TAG = app.parley.common.NotificationIds.TAG_PRIVATE_NAME

        fun authority(context: Context) = context.packageName + ".privatenames"

        /** The permission guarding the provider, as declared in the manifest (it differs in debug builds). */
        @Suppress("DEPRECATION")
        fun permission(context: Context): String =
            runCatching { context.packageManager.resolveContentProvider(authority(context), 0)?.readPermission }
                .getOrNull() ?: "app.parley.permission.LOOKUP_PRIVATE_NAME"

        /** [directory]: the request is for the contacts Directory (I7), which has its own approvals and text. */
        internal fun askUser(ctx: Context, pkg: String, directory: Boolean = false) {
            val pm = ctx.packageManager
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(app.parley.R.string.privnames_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val id = notificationId(pkg, directory)
            // Granting lasting access to private names must not work from the lock screen: Android 12+ asks for the
            // unlock before sending the broadcast; before that, the action opens an invisible activity, which the lock
            // screen only starts after unlocking.
            fun decide(allow: Boolean): NotificationCompat.Action {
                val title = ctx.getString(if (allow) app.parley.R.string.privnames_allow else app.parley.R.string.privnames_deny)
                val req = id * 2 + if (allow) 1 else 0
                if (Build.VERSION.SDK_INT >= 31) {
                    val pi = PendingIntent.getBroadcast(
                        ctx, req, decisionIntent(Intent(ctx, PrivateNameDecisionReceiver::class.java), pkg, allow, directory),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    return NotificationCompat.Action.Builder(0, title, pi).setAuthenticationRequired(true).build()
                }
                val pi = PendingIntent.getActivity(
                    ctx, req,
                    decisionIntent(Intent(ctx, PrivateNameDecisionActivity::class.java), pkg, allow, directory)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                return NotificationCompat.Action.Builder(0, title, pi).build()
            }
            val public = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(app.parley.R.drawable.ic_tile_private)
                .setContentTitle(ctx.getString(app.parley.R.string.privnames_channel))
                .build()
            val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(app.parley.R.drawable.ic_tile_private)
                .setContentTitle(ctx.getString(if (directory) app.parley.R.string.privnames_dir_request_title else app.parley.R.string.privnames_request_title, label))
                .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(if (directory) app.parley.R.string.privnames_dir_request_text else app.parley.R.string.privnames_request_text, label)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setLocalOnly(true)
                .addAction(decide(true))
                .addAction(decide(false))
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(NOTIFICATION_TAG, id, n)
            } catch (_: SecurityException) {
            }
        }

        const val EXTRA_PACKAGE = "package"
        const val EXTRA_ALLOW = "allow"
        const val EXTRA_DIRECTORY = "directory"

        private fun decisionIntent(i: Intent, pkg: String, allow: Boolean, directory: Boolean) =
            i.putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_ALLOW, allow).putExtra(EXTRA_DIRECTORY, directory)

        /** Records the user's answer from the request notification. */
        internal fun decide(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
            val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
            val directory = intent.getBooleanExtra(EXTRA_DIRECTORY, false)
            (context.applicationContext as? ParleyApp)?.containerOrNull?.people?.privateNames
                ?.setApproval(pkg, if (allow) LookupApproval.ALLOWED else LookupApproval.DENIED, directory)
            cancel(context, pkg, directory)
        }

        private fun notificationId(pkg: String, directory: Boolean) = if (directory) ("directory:" + pkg).hashCode() else pkg.hashCode()

        fun cancel(ctx: Context, pkg: String, directory: Boolean = false) = NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_TAG, notificationId(pkg, directory))
    }
}

/** "Allow" / "Don't allow" from the request notification (Android 12+). Not exported: only Parley's own PendingIntents reach it. */
class PrivateNameDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = PrivateNameProvider.decide(context, intent)
}

/** The same answer on Android 10-11, where only an activity can make the lock screen ask for the unlock first. Not exported. */
class PrivateNameDecisionActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        PrivateNameProvider.decide(applicationContext, intent)
        finish()
    }
}
