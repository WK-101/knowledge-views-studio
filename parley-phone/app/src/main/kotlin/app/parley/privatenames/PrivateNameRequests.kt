package app.parley.privatenames

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.ParleyApp
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.people.LookupApproval
import app.parley.work.PrivateNotice

/**
 * The notification a phone app's first Directory lookup posts ([PrivateDirectoryProvider]), and its "Don't allow"
 * answer. "Allow…" opens the approval sheet inside Parley instead.
 */
object PrivateNameRequests {
    private const val CHANNEL = NotificationChannels.PRIVATE_NAMES
    private const val NOTIFICATION_TAG = NotificationIds.TAG_PRIVATE_NAME
    const val EXTRA_PACKAGE = IntentRoutes.EXTRA_PACKAGE
    private const val LEGACY_EXTRA_ALLOW = "allow"

    internal fun askUser(ctx: Context, pkg: String) {
        // The package name, never the app's own label: any app can call itself "Phone".
        val label = pkg
        val id = notificationId(pkg)

        // "Allow…" opens the approval sheet in Parley, behind Parley's own lock (its PIN when one is set), which
        // shows the package name and the signing certificate before anything is allowed.
        val allowIntent = IntentRoutes.own(ctx).setAction(IntentRoutes.ACTION_APPROVE_PRIVATE_NAME)
            .putExtra(IntentRoutes.EXTRA_PACKAGE, pkg)
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
                    ctx, req, Intent(ctx, PrivateNameDecisionReceiver::class.java).putExtra(EXTRA_PACKAGE, pkg),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                return NotificationCompat.Action.Builder(0, title, pi).setAuthenticationRequired(true).build()
            }
            val pi = PendingIntent.getActivity(
                ctx, req,
                Intent(ctx, PrivateNameDecisionActivity::class.java).putExtra(EXTRA_PACKAGE, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Action.Builder(0, title, pi).build()
        }
        val open = PrivateNotice.open(ctx, id, Intent(ctx, MainActivity::class.java))
        val n = PrivateNotice.builder(
            ctx, CHANNEL, R.drawable.ic_tile_private,
            ctx.getString(R.string.privnames_dir_request_title, label),
            ctx.getString(R.string.privnames_channel),
            ctx.getString(R.string.privnames_dir_request_text, label), open,
        )
            .addAction(allow)
            .addAction(deny())
        PrivateNotice.post(ctx, NOTIFICATION_TAG, id, n)
    }

    /**
     * "Don't allow" from the request notification. It never allows anything: "Allow" goes through the approval sheet
     * behind Parley's lock. While a duress unlock hides things the answer is only shown ([app.parley.data.people.PrivateNameAccess]).
     */
    internal fun decide(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        // The removed lookup provider's request answers nothing (that app never asked for the Directory): it only goes.
        if (IntentRoutes.isLegacyLookupRequest(intent)) return NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, pkg.hashCode())
        // An "Allow" from a notification posted by an earlier version answers nothing; the app is asked again.
        if (intent.getBooleanExtra(LEGACY_EXTRA_ALLOW, false)) return cancel(context, pkg)
        (context.applicationContext as? ParleyApp)?.containerOrNull?.people?.privateNames?.setApproval(pkg, LookupApproval.DENIED)
        cancel(context, pkg)
    }

    // The same id the Directory's requests always had, so an earlier version's notification is replaced, not doubled.
    private fun notificationId(pkg: String) = ("directory:" + pkg).hashCode()

    fun cancel(ctx: Context, pkg: String) = NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_TAG, notificationId(pkg))
}

/** "Don't allow" from the request notification (Android 12+). Not exported: only Parley's own PendingIntents reach it. */
class PrivateNameDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = PrivateNameRequests.decide(context, intent)
}

/** The same answer on Android 10-11, where only an activity can make the lock screen ask for the unlock first. Not exported. */
class PrivateNameDecisionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrivateNameRequests.decide(applicationContext, intent)
        finish()
    }
}
