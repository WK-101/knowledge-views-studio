package app.parley.common.calls

import app.parley.common.Codecs
import app.parley.common.ContactSummary
import app.parley.common.LabelRefs
import app.parley.common.PhoneIdentity
import app.parley.common.catching
import app.parley.common.people.ContactRef
import app.parley.common.who.Who
import app.parley.common.who.WhoChoice
import kotlinx.serialization.Serializable

/** Where a person in a call round stands. */
@Serializable
enum class RoundMark {
    /** Not called yet. */
    WAITING,

    /** Told: they won't be offered again. */
    REACHED,

    /** Rang, nobody answered: offered again once everyone was tried. */
    NO_ANSWER,

    /** Put off ("not now"): offered again once everyone was tried. */
    LATER,
}

/**
 * One person in a round: [key] is their Parley key (a lookup key, or a private contact's key), [number] the one to call
 * and [numbers] every number of theirs, so a call from or to any of them shows the round's note. [private] marks a
 * private contact's entry: it shows only while private contacts may.
 */
@Serializable
data class RoundPerson(
    val key: String,
    val name: String,
    val number: String,
    val numbers: List<String> = emptyList(),
    val private: Boolean = false,
    val mark: RoundMark = RoundMark.WAITING,
)

/** A call round: one thing ([message]) to tell several people, in order, with each one's [RoundPerson.mark]. */
@Serializable
data class CallRound(
    val message: String = "",
    val people: List<RoundPerson> = emptyList(),
    val startedAt: Long = 0,
) {
    val active: Boolean get() = people.isNotEmpty()
}

/** The score of a round: how many are reached, didn't answer, were put off and wait. */
data class RoundScore(val reached: Int, val noAnswer: Int, val later: Int, val waiting: Int) {
    val total: Int get() = reached + noAnswer + later + waiting

    /** Everyone was told. */
    val done: Boolean get() = total > 0 && reached == total
}

/**
 * "Tell eight people one thing": the rules of a call round. Parley shows who's next and keeps the score; it never
 * places a call itself (each call starts with a tap on the round's screen). Pure: the app keeps the round sealed and
 * decides, through the privacy view, what of it may show.
 */
object CallRounds {
    /** People in one round at most (a round is a handful of calls, not a mailing list). */
    const val MAX_PEOPLE = 50

    /** The one thing to say, at most this long (it is read on the call screen). */
    const val MAX_MESSAGE = 280

    private val json = Codecs.tolerant

    fun encode(r: CallRound): String = json.encodeToString(CallRound.serializer(), r)

    fun decode(stored: String?): CallRound =
        stored?.takeIf { it.isNotBlank() }?.let { catching { json.decodeFromString(CallRound.serializer(), it) }.getOrNull() } ?: CallRound()

    /**
     * A new round telling [people] [message]: each person once (by key, in the order given), only people with a number,
     * at most [MAX_PEOPLE]. Null when there is nothing to say or nobody to call.
     */
    fun start(message: String, people: List<RoundPerson>, now: Long): CallRound? {
        val text = message.trim().take(MAX_MESSAGE)
        val who = people.filter { it.number.isNotBlank() && it.key.isNotBlank() }
            .distinctBy { it.key }
            .take(MAX_PEOPLE)
            .map { it.copy(mark = RoundMark.WAITING, numbers = (listOf(it.number) + it.numbers).filter(String::isNotBlank).distinct()) }
        if (text.isEmpty() || who.isEmpty()) return null
        return CallRound(text, who, now)
    }

    /** [c] as a person to call in a round (their default number, else the first); null without a number. */
    fun personOf(c: ContactSummary): RoundPerson? {
        val number = c.primaryNumber?.takeIf { it.isNotBlank() } ?: return null
        val private = c.id < 0
        val key = if (private) ContactRef.privateKey(-c.id) else c.lookupKey.ifBlank { return null }
        return RoundPerson(key, c.displayName, number, c.phones.map { it.number }.filter(String::isNotBlank).distinct(), private)
    }

    /**
     * The people [who] means among [contacts] (the lists' own contacts: private ones only while they show), in list
     * order: Favourites, Your Circle ([circleKeys], lookup keys) or a label ([labelsOf] a contact id's label titles).
     * Nobody for the other choices: a round never calls everyone.
     */
    fun pick(who: Who, contacts: List<ContactSummary>, circleKeys: Set<String>, labelsOf: (Long) -> Collection<String>): List<ContactSummary> =
        when (who.choice) {
            WhoChoice.FAVOURITES -> contacts.filter { it.starred }
            WhoChoice.CIRCLE -> contacts.filter { it.lookupKey.isNotEmpty() && it.lookupKey in circleKeys }
            WhoChoice.LABEL -> who.label?.let(LabelRefs::key)?.takeIf { it.isNotEmpty() }
                ?.let { k -> contacts.filter { c -> labelsOf(c.id).any { LabelRefs.key(it) == k } } }
                .orEmpty()
            WhoChoice.EVERYONE, WhoChoice.CONTACTS -> emptyList()
        }

    /** [r] as it may show now: a private contact's entry only while private contacts may show. */
    fun shown(r: CallRound, privateShown: Boolean): CallRound = if (privateShown) r else r.copy(people = r.people.filterNot { it.private })

    /**
     * Who to call next: the first not called yet; once everyone was tried, the first put off, then the first who
     * didn't answer. Null when everyone was reached.
     */
    fun next(r: CallRound): RoundPerson? =
        r.people.firstOrNull { it.mark == RoundMark.WAITING }
            ?: r.people.firstOrNull { it.mark == RoundMark.LATER }
            ?: r.people.firstOrNull { it.mark == RoundMark.NO_ANSWER }

    /** [key]'s mark set to [mark]; a person put off or not answering goes to the end of those to try again. */
    fun mark(r: CallRound, key: String, mark: RoundMark): CallRound {
        val p = r.people.firstOrNull { it.key == key } ?: return r
        val marked = p.copy(mark = mark)
        val rest = r.people.filterNot { it.key == key }
        val people = if (mark == RoundMark.LATER || mark == RoundMark.NO_ANSWER) rest + marked else r.people.map { if (it.key == key) marked else it }
        return r.copy(people = people)
    }

    /** [key] taken out of the round (nobody left ends it). */
    fun remove(r: CallRound, key: String): CallRound = r.copy(people = r.people.filterNot { it.key == key })

    fun score(r: CallRound): RoundScore = RoundScore(
        reached = r.people.count { it.mark == RoundMark.REACHED },
        noAnswer = r.people.count { it.mark == RoundMark.NO_ANSWER },
        later = r.people.count { it.mark == RoundMark.LATER },
        waiting = r.people.count { it.mark == RoundMark.WAITING },
    )

    /**
     * The round's note for a call with [number]: its message, when the number is one of someone's in [r] who wasn't
     * reached yet. [r] must already be [shown] (a hidden private contact's number gets nothing).
     */
    fun noteFor(r: CallRound, number: String, region: String?): String? {
        if (number.isBlank() || r.message.isBlank()) return null
        val inRound = r.people.any { p -> p.mark != RoundMark.REACHED && p.numbers.ifEmpty { listOf(p.number) }.any { PhoneIdentity.same(it, number, region) } }
        return r.message.takeIf { inRound }
    }
}
