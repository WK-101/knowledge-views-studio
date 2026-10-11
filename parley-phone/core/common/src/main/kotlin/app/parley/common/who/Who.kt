package app.parley.common.who

import app.parley.common.LabelRefs
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import kotlinx.serialization.Serializable

/**
 * The one answer to "who" that Situations, off hours, auto-answer and the drive profile all ask, with the same
 * choices in the same order: Everyone · Contacts · Favourites · Your Circle · A label…. A place offers only the
 * choices that make sense there ([WhoRules.choicesFor]); none offers fewer than Contacts, Favourites, Your Circle and
 * a label.
 */
@Serializable
enum class WhoChoice {
    EVERYONE,
    CONTACTS,
    FAVOURITES,

    /** The people in Your Circle (those with a keep-in-touch rhythm). Parley's own screening only. */
    CIRCLE,

    /** The people in [Who.label]. */
    LABEL,
}

/**
 * One "who": a [choice], and the label's title for [WhoChoice.LABEL] (matched in every account, as labels are). A
 * choice this version doesn't know (written by a newer one, read with a tolerant codec) reads as Favourites: the
 * narrowest of the choices everyone has.
 */
@Serializable
data class Who(val choice: WhoChoice = WhoChoice.FAVOURITES, val label: String? = null) {
    companion object {
        val EVERYONE = Who(WhoChoice.EVERYONE)
        val CONTACTS = Who(WhoChoice.CONTACTS)
        val FAVOURITES = Who(WhoChoice.FAVOURITES)
        val CIRCLE = Who(WhoChoice.CIRCLE)

        fun label(title: String): Who = Who(WhoChoice.LABEL, title)
    }
}

/** What the call path knows about a caller when a "who" is checked. */
data class WhoFacts(
    /** A contact or a private contact (unknown and hidden callers are neither). */
    val known: Boolean,
    val favourite: Boolean = false,
    val inCircle: Boolean = false,
    /** Titles of their labels, in every account. */
    val labels: Set<String> = emptySet(),
    /** The labels couldn't be read: a label counts as matched (screening fails open, never silencing a contact by mistake). */
    val labelsUnknown: Boolean = false,
    /** Circle membership couldn't be read: counted as in the Circle, for the same reason. */
    val circleUnknown: Boolean = false,
)

/** Where a "who" is asked, which decides the choices offered. */
enum class WhoPlace {
    /** A Situation's "Who may ring" (also "As it is", which isn't a [Who]). */
    SITUATION,

    /** Off hours' "Who may still ring": Everyone is the same as off hours being off, so it isn't offered. */
    OFF_HOURS,

    /** Auto-answer and the drive profile: an unknown caller is never answered on its own, so Everyone isn't offered. */
    ANSWER,
}

/**
 * Pure rules of [Who]: the choices, whether a caller is in, and how each place stores it. Stored forms stay readable by
 * older versions: no new value is ever added to an enum they know. Your Circle is written as Favourites plus a separate
 * `circle` flag (an older Parley reads Favourites, the nearest it knows); a new field holding a [Who] is simply skipped
 * by an older version, and read tolerantly here, so a value added later reads as the field's default.
 */
object WhoRules {
    fun choicesFor(place: WhoPlace): List<WhoChoice> = when (place) {
        WhoPlace.SITUATION -> WhoChoice.entries
        WhoPlace.OFF_HOURS, WhoPlace.ANSWER -> WhoChoice.entries - WhoChoice.EVERYONE
    }

    /** Whether a caller with [f] is one of [who]. */
    fun includes(who: Who, f: WhoFacts): Boolean = when (who.choice) {
        WhoChoice.EVERYONE -> true
        WhoChoice.CONTACTS -> f.known
        WhoChoice.FAVOURITES -> f.known && f.favourite
        WhoChoice.CIRCLE -> f.known && (f.inCircle || f.circleUnknown)
        WhoChoice.LABEL -> f.known && (f.labelsUnknown || who.label?.let(LabelRefs::key)?.let { k -> k.isNotEmpty() && f.labels.any { LabelRefs.key(it) == k } } == true)
    }

    // ------------------------------------------------------------------ off hours

    /** Who off hours lets ring, as [o] stores it. */
    fun ofOffHours(o: OffHours): Who = when (o.allow) {
        OffHoursAllow.CONTACTS -> Who.CONTACTS
        OffHoursAllow.FAVOURITES -> if (o.circle) Who.CIRCLE else Who.FAVOURITES
        OffHoursAllow.LABEL -> Who(WhoChoice.LABEL, o.labelTitle)
    }

    /**
     * [o] letting [who] ring. Only who rings changes: a label title kept from before stays with it (unused unless a
     * label is chosen), so a Situation's values come back field by field ([app.parley.common.situations.Situations.restore]).
     * Everyone isn't a choice for off hours: it reads as Contacts (off hours' own default).
     */
    fun intoOffHours(o: OffHours, who: Who): OffHours = when (who.choice) {
        WhoChoice.EVERYONE, WhoChoice.CONTACTS -> o.copy(allow = OffHoursAllow.CONTACTS, circle = false)
        WhoChoice.FAVOURITES -> o.copy(allow = OffHoursAllow.FAVOURITES, circle = false)
        // Favourites for an older version, which doesn't know the Circle.
        WhoChoice.CIRCLE -> o.copy(allow = OffHoursAllow.FAVOURITES, circle = true)
        WhoChoice.LABEL -> who.label?.let(LabelRefs::key)?.takeIf { it.isNotEmpty() }
            ?.let { o.copy(allow = OffHoursAllow.LABEL, circle = false, labelId = null, labelTitle = it) }
            ?: o.copy(allow = OffHoursAllow.FAVOURITES, circle = false)
    }

    /** Whether off hours [o], while it is on, needs the caller's Circle membership. */
    fun needsCircle(o: OffHours): Boolean = o.enabled && o.allow == OffHoursAllow.FAVOURITES && o.circle

    // ------------------------------------------------------------------ answering

    /**
     * Who auto-answer or the drive profile answers: [stored] when set, else [legacy] (what an older version kept: the
     * whole of Contacts for auto-answer with a headset, Favourites for the drive profile). Everyone reads as Contacts:
     * an unknown caller is never answered on its own.
     */
    fun answering(stored: Who?, legacy: Who): Who = (stored ?: legacy).let { if (it.choice == WhoChoice.EVERYONE) Who.CONTACTS else it }

    // ------------------------------------------------------------------ labels

    /** [who] after labels were renamed or merged ([renames]: old title → new title): a label it names follows. */
    fun renamed(who: Who?, renames: Map<String, String>): Who? {
        if (who?.choice != WhoChoice.LABEL) return who
        val m = renames.mapKeys { LabelRefs.key(it.key) }
        return who.label?.let { m[LabelRefs.key(it)] }?.let { Who.label(LabelRefs.key(it)) } ?: who
    }

    /** [who] after labels were deleted: one naming them answers Favourites instead (as a Situation lets Favourites ring). */
    fun labelsDeleted(who: Who?, titles: Set<String>): Who? =
        if (who?.choice == WhoChoice.LABEL && LabelRefs.refersTo(who.label, titles)) Who.FAVOURITES else who
}
