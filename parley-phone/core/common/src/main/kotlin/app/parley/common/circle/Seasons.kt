package app.parley.common.circle

import app.parley.common.extras.LabelPolicy
import app.parley.common.people.Chapter

/**
 * A label that keeps everyone in it in the Circle ("Keep in touch with everyone here"): its title, the gap it asks for
 * and, for a label that is a Chapter, when the Chapter ends. A Chapter's feed is a season for its members: it counts
 * only while the Chapter runs.
 */
data class LabelFeed(val label: String, val days: Int, val until: Long? = null) {
    fun runs(now: Long): Boolean = until == null || now < until
}

/**
 * The gap that counts for one person now, and why: [via] the label that set it (null: their own rhythm), [until] the
 * end of the season it belongs to (their own "For a while…", or a Chapter's end; null: no end).
 */
data class CircleRhythm(val days: Int, val via: String? = null, val until: Long? = null)

/**
 * Who is in the Circle and at which gap, from a person's own rhythm and the labels that feed the Circle. Membership is
 * worked out, never stored: own ∪ label-fed, each person once.
 *
 * - A person's own season ("For a while…") wins over everything while it runs.
 * - Otherwise their own rhythm wins over a label's, except a running Chapter's shorter one (a season for the group).
 * - Someone without a rhythm of their own is in the Circle through the label with the shortest gap.
 */
object CircleFeeds {
    /** The labels that feed the Circle at [now]: switched on with a rhythm, and, for a Chapter, still running. */
    fun of(policies: Map<String, LabelPolicy>, chapters: Map<String, Chapter>, now: Long): List<LabelFeed> =
        policies.mapNotNull { (title, p) ->
            val days = p.rhythmDays?.takeIf { p.circleFromLabel } ?: return@mapNotNull null
            LabelFeed(title, days, chapters[title]?.endsAt).takeIf { it.runs(now) }
        }.sortedBy { it.label.lowercase() }

    private fun shortest(feeds: List<LabelFeed>): LabelFeed? = feeds.minWithOrNull(compareBy<LabelFeed> { it.days }.thenBy { it.label.lowercase() })

    /**
     * The rhythm for someone whose own "every N days" is [everyDays] (null: none of their own) and stored [rhythm],
     * in the labels [feeds]. Null when they aren't in the Circle.
     */
    fun effective(everyDays: Int?, rhythm: KeepRhythm, feeds: Collection<LabelFeed>, now: Long): CircleRhythm? {
        val running = feeds.filter { it.runs(now) }
        if (everyDays != null) {
            if (rhythm.inSeason(now)) return CircleRhythm(rhythm.seasonDays!!, until = rhythm.seasonUntil)
            val own = rhythm.days(everyDays)
            val chapter = shortest(running.filter { it.until != null })
            return if (chapter != null && chapter.days < own) CircleRhythm(chapter.days, chapter.label, chapter.until) else CircleRhythm(own)
        }
        val f = shortest(running) ?: return null
        return CircleRhythm(f.days, f.label, f.until)
    }

    /** Lookup key → the feeds they're in, from each feeding label's members ([members]: label → keys). */
    fun byPerson(feeds: List<LabelFeed>, members: (String) -> Collection<String>): Map<String, List<LabelFeed>> {
        val out = LinkedHashMap<String, MutableList<LabelFeed>>()
        feeds.forEach { f -> members(f.label).filter { it.isNotEmpty() }.distinct().forEach { k -> out.getOrPut(k) { ArrayList() } += f } }
        return out
    }
}

/** Seasons: "For a while…", a rhythm with an end, on top of someone's usual one. */
object Seasons {
    /** The gaps a season offers (in days): closer than the usual rhythms, for a hard time or a new baby. */
    val CHOICES = listOf(3, 7, 14)

    /** [r] with a season of [days] until [until]; null [days] ends it now. */
    fun set(r: KeepRhythm, days: Int?, until: Long?): KeepRhythm =
        if (days == null || until == null) r.endSeason() else r.copy(seasonDays = days.coerceIn(1, NaturalRhythm.MAX_DAYS), seasonUntil = until)
}
