package app.parley.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.common.people.ContactRef
import app.parley.common.people.TemporaryDue
import app.parley.container
import app.parley.data.DataContainer
import app.parley.IntentRoutes
import kotlinx.coroutines.launch

/**
 * Temporary contacts whose time is up, with "Ask before deleting temporary contacts" on (the default): nothing is
 * deleted until you answer. The daily upkeep calls [check], which posts one notification ("1 temporary contact is
 * due to be deleted": no name, so it's safe on the lock screen) with Delete (only after unlocking), Keep 7 more days
 * and Keep permanently (harmless, so they work from the lock screen);
 * the Temporary contacts screen shows the same choice. Unanswered, they stay, and the question comes back every few
 * days ([TemporaryDue.shouldNotify]). Contacts are named by their Parley key (lookup key, or a private contact's key).
 */
object DueTemporaries {
    private const val PREFS = "temporary_due"
    private const val K_ASKED = "asked"
    private const val K_AT = "asked_at"
    private const val ID = 900

    /** Every due contact at [now], by Parley key. */
    suspend fun dueKeys(c: DataContainer, now: Long): Set<String> {
        val device = c.temporaries.due(now).map { it.lookupKey }
        val private = c.vault.expired(now).map { ContactRef.privateKey(it) }
        return (device + private).toSet()
    }

    /** The upkeep's step: asks once about what became due, reminds gently, and clears the question when nothing is due. */
    suspend fun check(c: DataContainer, now: Long = System.currentTimeMillis()) {
        val ctx = c.appContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = dueKeys(c, now)
        val asked = prefs.getStringSet(K_ASKED, emptySet()).orEmpty()
        if (due.isEmpty()) {
            if (asked.isNotEmpty()) prefs.edit { remove(K_ASKED); remove(K_AT) }
            cancel(ctx)
            return
        }
        if (!TemporaryDue.shouldNotify(due, asked, prefs.getLong(K_AT, 0L), now)) return
        prefs.edit { putStringSet(K_ASKED, due); putLong(K_AT, now) }
        notify(ctx, due.size)
    }

    /**
     * Applies [decision] to the due contacts in [keys] (null: the ones the notification asked about). Only those still
     * due are touched. Returns how many.
     */
    suspend fun decide(c: DataContainer, decision: TemporaryDue.Decision, keys: Set<String>? = null, now: Long = System.currentTimeMillis()): Int {
        val ctx = c.appContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val asked = prefs.getStringSet(K_ASKED, emptySet()).orEmpty()
        val targets = TemporaryDue.targets(keys ?: asked, dueKeys(c, now))
        val device = targets.filter { ContactRef.vaultIdOf(it) == null }.toSet()
        val private = targets.mapNotNull { ContactRef.vaultIdOf(it) }.toSet()
        when (decision) {
            TemporaryDue.Decision.DELETE -> MaintenanceWorker.expireTemporaries(c, now, device, private)
            TemporaryDue.Decision.KEEP_LONGER, TemporaryDue.Decision.KEEP -> {
                val at = TemporaryDue.newExpiry(decision, now)
                device.forEach { k -> if (at != null) c.temporaries.extendTo(k, at) else c.temporaries.clear(k) }
                private.forEach { id -> c.vault.setExpiry(id, at) }
            }
        }
        val waiting = TemporaryDue.stillWaiting(asked, targets)
        prefs.edit { putStringSet(K_ASKED, waiting) }
        if (waiting.isEmpty()) cancel(ctx)
        c.contacts.refresh()
        return targets.size
    }

    /**
     * Delete can't be pressed on a locked phone (it deletes private contacts too). On Android 12+ the system asks
     * to unlock before the button runs; before that, it opens the Temporary contacts screen instead (an activity, so
     * the phone is unlocked, and Parley's app lock applies), where the same choice is shown with the names.
     */
    private fun deleteAction(ctx: Context, open: PendingIntent): NotificationCompat.Action {
        val label = ctx.getString(R.string.blk_delete)
        return if (Build.VERSION.SDK_INT >= 31) {
            NotificationCompat.Action.Builder(0, label, DueActionReceiver.pending(ctx, TemporaryDue.Decision.DELETE))
                .setAuthenticationRequired(true)
                .build()
        } else {
            NotificationCompat.Action.Builder(0, label, open).build()
        }
    }

    private fun cancel(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(NotificationIds.TAG_TEMPORARY, ID)

    private fun notify(ctx: Context, count: Int) {
        val title = ctx.resources.getQuantityString(R.plurals.temp_due_title, count, count)
        val open = PrivateNotice.route(ctx, NotificationRequests.TEMPORARY_DUE, IntentRoutes.ACTION_OPEN_TEMPORARY)
        // No names anywhere in it: the same title on the lock screen and after unlocking. Answered, not tapped away.
        val b = PrivateNotice.builder(
            ctx, NotificationChannels.HOUSEKEEPING, R.drawable.ic_stat_cake, title, title, ctx.getString(R.string.temp_due_text), open,
        )
            .setAutoCancel(false)
            .addAction(deleteAction(ctx, open))
            .addAction(0, ctx.getString(R.string.temp_due_keep_longer), DueActionReceiver.pending(ctx, TemporaryDue.Decision.KEEP_LONGER))
            .addAction(0, ctx.getString(R.string.contact_keep_permanently), DueActionReceiver.pending(ctx, TemporaryDue.Decision.KEEP))
        PrivateNotice.post(ctx, NotificationIds.TAG_TEMPORARY, ID, b)
    }
}

/**
 * The due notification's buttons (no screen needed; Delete only after unlocking, see [DueTemporaries]). Not exported;
 * the pending intents are explicit and immutable.
 */
class DueActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val decision = TemporaryDue.Decision.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_DECISION) } ?: return
        val pending = goAsync()
        val c = context.container
        c.scope.launch {
            try {
                DueTemporaries.decide(c, decision)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION = "app.parley.temporary.DUE_DECISION"
        private const val EXTRA_DECISION = "decision"

        fun pending(context: Context, decision: TemporaryDue.Decision): PendingIntent = PendingIntent.getBroadcast(
            context, NotificationRequests.TEMPORARY_DUE_ACTION + decision.ordinal,
            Intent(ACTION).setClass(context, DueActionReceiver::class.java).putExtra(EXTRA_DECISION, decision.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
