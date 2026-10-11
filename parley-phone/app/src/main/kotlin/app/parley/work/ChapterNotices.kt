package app.parley.work

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.common.catching
import app.parley.common.people.Chapters
import app.parley.data.DataContainer

/**
 * Chapters (labels given an end) that ended: the daily upkeep asks once, with one notification for all of them that
 * opens the label (or Labels, for several), where the card asks what to do. Only the phone that decides about a label
 * asks (its owner's, for a shared label). The lock screen shows "A chapter has ended", never the label's name.
 */
object ChapterNotices {
    suspend fun check(c: DataContainer, now: Long = System.currentTimeMillis()) {
        val ended = Chapters.toAnnounce(c.extras.chapters.value, now)
        if (ended.isEmpty()) return
        val titles = c.people.labels.labels().map { it.title }.toSet()
        catching { c.sharedLabels.load() }
        val ask = ArrayList<String>()
        val settled = HashSet<String>()
        // A label gone since has nothing to ask.
        for (t in ended.filter { it in titles }) {
            val shared = c.sharedLabels.forTitle(t) != null
            val owner = c.sharedLabels.isOwner(t)
            if (Chapters.decisionKnown(shared, owner)) {
                settled += t
                if (Chapters.decidesHere(shared, owner)) ask += t
            }
        }
        // Each ending is told once, by the phone that decides; one whose owner can't be told yet waits for the next check.
        c.extras.updateChapters { m -> m.mapValues { (t, ch) -> if (t in settled) ch.copy(askedAt = now) else ch } }
        if (ask.isNotEmpty()) notify(c.appContext, ask)
    }

    /** The card on the label answered it: the notice goes. */
    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NotificationIds.TAG_CHAPTERS, NotificationIds.CHAPTERS_ID)

    private fun notify(ctx: Context, titles: List<String>) {
        val one = titles.singleOrNull()
        val title = if (one != null) ctx.getString(R.string.chapter_ended_title, one)
        else ctx.resources.getQuantityString(R.plurals.chapter_notice_title, titles.size, titles.size)
        val intent = IntentRoutes.own(ctx).setAction(IntentRoutes.ACTION_OPEN_LABEL).apply { one?.let { putExtra(IntentRoutes.EXTRA_LABEL, it) } }
        val open = PrivateNotice.open(ctx, NotificationRequests.CHAPTER_ENDED, intent, update = true)
        val b = PrivateNotice.builder(
            ctx, NotificationChannels.HOUSEKEEPING, R.drawable.ic_stat_cake, title, ctx.getString(R.string.chapter_notice_public),
            ctx.getString(R.string.chapter_notice_text), open,
        )
        PrivateNotice.post(ctx, NotificationIds.TAG_CHAPTERS, NotificationIds.CHAPTERS_ID, b)
    }
}
