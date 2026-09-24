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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.MainActivity
import app.parley.common.people.LookupApproval
import app.parley.common.people.LookupOutcome
import app.parley.common.people.LookupPolicy
import app.parley.data.DataContainer
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
        val c = container(ctx) ?: return result
        val access = c.people.privateNames
        val segments = uri.pathSegments
        val number = if (segments.size == 2 && segments[0] == PATH && selection.isNullOrEmpty() && selectionArgs.isNullOrEmpty()) LookupPolicy.parseNumber(segments[1]) else null
        val now = System.currentTimeMillis()
        val approval = access.approval(caller)
        var outcome = LookupPolicy.decide(access.state.value.enabled, approval, number != null, access.recentQueries(caller, now), now)
        when (outcome) {
            LookupOutcome.ASKED -> if (approval == null) {
                access.setApproval(caller, LookupApproval.PENDING)
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

    /** Providers can be queried while the app is still starting: wait briefly for the container. */
    private fun container(ctx: Context): DataContainer? {
        val app = ctx.applicationContext as? ParleyApp ?: return null
        repeat(40) {
            try {
                return app.container
            } catch (_: UninitializedPropertyAccessException) {
                Thread.sleep(50)
            }
        }
        return null
    }

    companion object {
        const val PATH = "lookup"
        private val COLUMNS = arrayOf("display_name", "photo_uri")
        private const val CHANNEL = "private_names_v1"
        private const val NOTIFICATION_TAG = "private-name-request"

        fun authority(context: Context) = context.packageName + ".privatenames"

        /** The permission guarding the provider, as declared in the manifest (it differs in debug builds). */
        @Suppress("DEPRECATION")
        fun permission(context: Context): String =
            runCatching { context.packageManager.resolveContentProvider(authority(context), 0)?.readPermission }
                .getOrNull() ?: "app.parley.permission.LOOKUP_PRIVATE_NAME"

        private fun askUser(ctx: Context, pkg: String) {
            val pm = ctx.packageManager
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Private name requests", NotificationManager.IMPORTANCE_DEFAULT))
            val id = pkg.hashCode()
            fun decide(allow: Boolean) = PendingIntent.getBroadcast(
                ctx, id * 2 + if (allow) 1 else 0,
                Intent(ctx, PrivateNameDecisionReceiver::class.java).putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_ALLOW, allow),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(app.parley.R.drawable.ic_tile_private)
                .setContentTitle("$label wants to show private names")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$label asked Parley for the name of a phone number that may belong to one of your private contacts. " +
                            "If you allow it, $label can ask for one number's name at a time; it never gets a list.",
                    ),
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Allow", decide(true))
                .addAction(0, "Don't allow", decide(false))
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(NOTIFICATION_TAG, id, n)
            } catch (_: SecurityException) {
            }
        }

        const val EXTRA_PACKAGE = "package"
        const val EXTRA_ALLOW = "allow"

        fun cancel(ctx: Context, pkg: String) = NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_TAG, pkg.hashCode())
    }
}

/** "Allow" / "Don't allow" from the request notification. Not exported: only Parley's own PendingIntents reach it. */
class PrivateNameDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(PrivateNameProvider.EXTRA_PACKAGE) ?: return
        val allow = intent.getBooleanExtra(PrivateNameProvider.EXTRA_ALLOW, false)
        (context.applicationContext as? ParleyApp)?.container?.people?.privateNames?.setApproval(pkg, if (allow) LookupApproval.ALLOWED else LookupApproval.DENIED)
        PrivateNameProvider.cancel(context, pkg)
    }
}
