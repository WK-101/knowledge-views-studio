package app.parley.common.people

import app.parley.common.Codecs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A chapter: a label given an end, for a period of life (a house move, a hospital stay, a trip, a project). When it
 * ends Parley asks once what to do with it ([Chapters.Outcome]); nothing happens by itself.
 *
 * [beforeKeys] and [beforeIds] are who was in the label when the chapter began (Parley keys and list ids), so the end
 * can tell who joined for the chapter. [membersKnown] is false for a chapter restored from a backup: those keys mean
 * nothing on another phone, so nobody counts as having joined for it. [askedAt]: when the end was announced (0: not yet).
 */
@Serializable
data class Chapter(
    val startedAt: Long,
    val endsAt: Long,
    val beforeKeys: Set<String> = emptySet(),
    val beforeIds: Set<Long> = emptySet(),
    val membersKnown: Boolean = true,
    val askedAt: Long = 0,
)

/** The rules of chapters: when they end, what is left of them, who joined for them and what may be done at the end. */
object Chapters {
    /** How the end is chosen: a date, or a number of weeks or months from now. */
    sealed interface Length {
        /** Ends after the day [day] (it is still in the chapter). */
        data class OnDate(val day: LocalDate) : Length

        data class After(val count: Int, val span: Span) : Length
    }

    enum class Span { WEEKS, MONTHS }

    /** What the end offers. Nothing is deleted without being chosen here, and a delete goes through Recently deleted. */
    enum class Outcome {
        /** The label stays as it is, without an end. */
        KEEP,

        /** The members who joined the label during the chapter are archived (out of lists, still named on calls). */
        ARCHIVE_ADDED,

        /** The label goes; its members stay. */
        REMOVE_LABEL,

        /** Temporary contacts that joined during the chapter are deleted, with Undo. */
        DELETE_TEMPORARY,
    }

    /** One member of the label now: its list id, Parley key, and whether it is temporary or private. */
    data class Member(val id: Long, val key: String, val temporary: Boolean = false, val private: Boolean = false)

    /** What is left of a chapter, as the label shows it. */
    sealed interface Remaining {
        data object Ended : Remaining

        data object LastDay : Remaining

        data class Days(val n: Int) : Remaining

        data class Weeks(val n: Int) : Remaining

        data class Months(val n: Int) : Remaining
    }

    const val MAX_WEEKS = 52
    const val MAX_MONTHS = 24
    private const val DAYS_SHOWN = 13
    private const val WEEKS_SHOWN = 8

    /** When a chapter chosen as [length] at [now] ends: the start of the day after its last day, in [zone]. */
    fun endsAt(length: Length, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val last = when (length) {
            is Length.OnDate -> length.day
            is Length.After -> when (length.span) {
                Span.WEEKS -> today.plusWeeks(length.count.coerceIn(1, MAX_WEEKS).toLong())
                Span.MONTHS -> today.plusMonths(length.count.coerceIn(1, MAX_MONTHS).toLong())
            }
        }
        return last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Whether a date may end a chapter: today or later. */
    fun validEnd(day: LocalDate, now: Long, zone: ZoneId): Boolean = !day.isBefore(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())

    /** A new chapter for a label whose members are [members] now. */
    fun begin(members: List<Member>, endsAt: Long, now: Long): Chapter =
        Chapter(now, endsAt, members.map { it.key }.filter { it.isNotEmpty() }.toSet(), members.map { it.id }.toSet())

    /** A chapter that was asked about keeps its question: a new end only moves the date. */
    fun withEnd(chapter: Chapter, endsAt: Long, now: Long): Chapter = chapter.copy(endsAt = endsAt, askedAt = if (endsAt > now) 0 else chapter.askedAt)

    fun isOver(chapter: Chapter, now: Long): Boolean = now >= chapter.endsAt

    /** The last day of [chapter] (the day before [Chapter.endsAt]). */
    fun lastDay(chapter: Chapter, zone: ZoneId): LocalDate = Instant.ofEpochMilli(chapter.endsAt).atZone(zone).toLocalDate().minusDays(1)

    fun remaining(chapter: Chapter, now: Long, zone: ZoneId): Remaining {
        if (isOver(chapter, now)) return Remaining.Ended
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, lastDay(chapter, zone)).toInt()
        return when {
            days <= 0 -> Remaining.LastDay
            days <= DAYS_SHOWN -> Remaining.Days(days)
            days < WEEKS_SHOWN * 7 -> Remaining.Weeks((days + 3) / 7)
            else -> Remaining.Months(maxOf(2, ChronoUnit.MONTHS.between(today, lastDay(chapter, zone)).toInt()))
        }
    }

    /** The chapters to announce at [now]: over, and not announced yet. Each is announced once. */
    fun toAnnounce(chapters: Map<String, Chapter>, now: Long): Set<String> =
        chapters.filterValues { isOver(it, now) && it.askedAt == 0L }.keys

    /** Whether this phone decides about a label's chapter: any label of its own, and a shared one only on its owner's phone. */
    fun decidesHere(shared: Boolean, owner: Boolean?): Boolean = !shared || owner == true

    /**
     * Whether it is known now which phone decides ([owner] null: a shared label's owner can't be told right now). Only
     * then is the ending marked as told; otherwise the next check asks again, so the owner's phone isn't left silent.
     */
    fun decisionKnown(shared: Boolean, owner: Boolean?): Boolean = !shared || owner != null

    /** The members who joined during [chapter]: in the label now, and neither their key nor their id was there at its start. */
    fun joinedDuring(chapter: Chapter, members: List<Member>): List<Member> =
        if (!chapter.membersKnown) emptyList() else members.filter { it.key !in chapter.beforeKeys && it.id !in chapter.beforeIds }

    /**
     * Who [Outcome.ARCHIVE_ADDED] archives: those who joined during the chapter, except private ones (already hidden
     * from other apps) and temporary ones ([Outcome.DELETE_TEMPORARY] is their choice).
     */
    fun toArchive(chapter: Chapter, members: List<Member>): List<Member> = joinedDuring(chapter, members).filter { !it.private && !it.temporary }

    /** Who [Outcome.DELETE_TEMPORARY] deletes: the temporary contacts that joined during the chapter. */
    fun toDelete(chapter: Chapter, members: List<Member>): List<Member> = joinedDuring(chapter, members).filter { it.temporary }

    /** The choices the end card and the notification offer, in this order: only those that would do something. */
    fun outcomes(chapter: Chapter, members: List<Member>): List<Outcome> = listOfNotNull(
        Outcome.KEEP,
        Outcome.ARCHIVE_ADDED.takeIf { toArchive(chapter, members).isNotEmpty() },
        Outcome.REMOVE_LABEL,
        Outcome.DELETE_TEMPORARY.takeIf { toDelete(chapter, members).isNotEmpty() },
    )

    /** Labels were renamed or merged (old title → new title); a merge keeps the target's own chapter. */
    fun renamed(map: Map<String, Chapter>, renames: Map<String, String>): Map<String, Chapter> {
        val out = LinkedHashMap<String, Chapter>()
        map.forEach { (k, v) -> if (k !in renames) out[k] = v }
        map.forEach { (k, v) -> renames[k]?.let { to -> if (to !in out) out[to] = v } }
        return out
    }

    fun deleted(map: Map<String, Chapter>, titles: Set<String>): Map<String, Chapter> = map.filterKeys { it !in titles }

    /** Every key a chapter remembers from its start, for the key sweep to follow. */
    fun keys(map: Map<String, Chapter>): Set<String> = map.values.flatMapTo(HashSet()) { it.beforeKeys }

    /**
     * A contact's key moved ([from] → [to], now contact [toId]): someone who was in the label at a chapter's start is
     * still known as such after a re-link, a sync or a move, so the end never offers to archive them as new.
     */
    fun rekeyed(map: Map<String, Chapter>, from: String, to: String, toId: Long?): Map<String, Chapter> =
        map.mapValues { (_, c) ->
            if (from !in c.beforeKeys) c else c.copy(beforeKeys = c.beforeKeys - from + to, beforeIds = toId?.let { c.beforeIds + it } ?: c.beforeIds)
        }

    /** For a backup: dates only. Who was in the label is this phone's keys, which mean nothing on another one. */
    fun forBackup(map: Map<String, Chapter>): Map<String, Chapter> =
        map.mapValues { (_, v) -> v.copy(beforeKeys = emptySet(), beforeIds = emptySet(), membersKnown = false) }

    /** A restored map joins the one here; what is here wins. */
    fun merge(here: Map<String, Chapter>, restored: Map<String, Chapter>): Map<String, Chapter> =
        restored.keys.fold(here) { acc, k -> if (k in acc) acc else acc + (k to restored.getValue(k)) }

    private val json = Codecs.stored
    private val serializer = MapSerializer(String.serializer(), Chapter.serializer())

    fun decode(text: String?): Map<String, Chapter> =
        if (text.isNullOrBlank()) emptyMap() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyMap())

    fun encode(map: Map<String, Chapter>): String = json.encodeToString(serializer, map)
}
