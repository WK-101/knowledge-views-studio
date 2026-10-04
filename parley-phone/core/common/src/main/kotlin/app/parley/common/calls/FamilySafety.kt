package app.parley.common.calls

import app.parley.common.Codecs
import app.parley.common.LabelRefs
import app.parley.common.PhoneIdentity
import kotlinx.serialization.Serializable

/** I4: a label's private question and the answer only family knows ("What's our word?" · "Blue heron"). */
@Serializable
data class SafeWord(val question: String, val answer: String)

/** I5: a trusted person who can be added to a call. [private] is a private contact (named only outside discreet mode). */
@Serializable
data class Helper(val name: String, val number: String, val private: Boolean = false)

/**
 * Family safety on this phone: the safe words by label title, up to [Helpers.MAX] helpers, which expected-call
 * sources the user said yes or no to (absent: not asked yet, so off), and the expected-call windows they made.
 * Kept as one sealed document (see the app's FamilySafetyStore); the answers never leave it except to be shown.
 */
@Serializable
data class FamilySafetyState(
    val safeWords: Map<String, SafeWord> = emptyMap(),
    val helpers: List<Helper> = emptyList(),
    val consents: Map<ExpectedSource, Boolean> = emptyMap(),
    val windows: List<ExpectedWindow> = emptyList(),
) {
    /** The source is on: the user accepted it once (or turned it on in Settings). */
    fun accepted(source: ExpectedSource): Boolean = consents[source] == true

    /** The source was never asked about: the first hint from it asks before doing anything. */
    fun undecided(source: ExpectedSource): Boolean = source !in consents

    /**
     * This phone's family safety with a backup's put in (a restore): wherever both have something, this phone's own
     * wins (a safe word for the same label, a helper on the same line, an answer about the same source, a window
     * from the same note or item). Helpers stay within [Helpers.MAX]; windows that have ended are left out.
     */
    fun restoredFrom(backup: FamilySafetyState, now: Long, region: String?): FamilySafetyState {
        val labels = safeWords.keys.map(LabelRefs::key).toSet()
        val words = safeWords + backup.safeWords.filterKeys { LabelRefs.key(it) !in labels }
        val people = backup.helpers.fold(helpers) { list, h -> Helpers.add(list, h, region) }
        val mine = windows.map { it.source to it.key }.toSet()
        val times = backup.windows.filter { (it.source to it.key) !in mine }.fold(ExpectedCalls.prune(windows, now)) { list, w ->
            if (w.end > now) ExpectedCalls.put(list, w, now) else list
        }
        return FamilySafetyState(words, people, backup.consents + consents, times)
    }

    companion object {
        private val json = Codecs.stored

        fun encode(s: FamilySafetyState): String = json.encodeToString(serializer(), s)

        /** A stored document; empty for none. Anything unreadable throws (the store keeps it rather than lose it). */
        fun decode(text: String?): FamilySafetyState = if (text.isNullOrBlank()) FamilySafetyState() else json.decodeFromString(serializer(), text)
    }
}

/** I4's rules: when the in-call card "Claims to be family? Ask: …" shows, and what the label page may store. */
object SafeWords {
    /** A call from an unknown number shows the card once it has lasted this long. */
    const val AFTER_SECONDS = 20L

    const val MAX_QUESTION = 120
    const val MAX_ANSWER = 80

    /** What the call screen knows when deciding. */
    data class Facts(
        /** At least one label has a safe word, and the caller isn't a saved member of it. */
        val hasSafeWord: Boolean,
        /** Not a contact or private contact (or a hidden number). */
        val unknownCaller: Boolean,
        val emergency: Boolean,
        /** Connected (talking or on hold). */
        val connected: Boolean,
        val connectedSeconds: Long,
        /** The user tapped "Says they're family" in More. */
        val claimedFamily: Boolean,
        /** The user closed the card for this call. */
        val dismissed: Boolean,
    )

    /** Whether the card shows: never for an emergency call, never once closed, only while connected. */
    fun cardShows(f: Facts): Boolean =
        f.hasSafeWord && !f.emergency && !f.dismissed && f.connected &&
            (f.claimedFamily || (f.unknownCaller && f.connectedSeconds > AFTER_SECONDS))

    /** More › "Says they're family": offered while the card isn't up yet. */
    fun claimOffered(f: Facts): Boolean = f.hasSafeWord && !f.emergency && f.connected && !cardShows(f)

    /**
     * The labels whose safe word the card offers for this caller: every label with one, except those the caller is a
     * saved member of (family calling from their saved number needs no check).
     */
    fun offeredFor(safeWords: Map<String, SafeWord>, callerLabels: Set<String>): List<String> {
        val mine = callerLabels.map(LabelRefs::key).toSet()
        return safeWords.keys.filter { LabelRefs.key(it) !in mine }.sortedBy { it.lowercase() }
    }

    /** A question and answer as they are stored: trimmed, one line, within their lengths; null when either is empty. */
    fun clean(question: String, answer: String): SafeWord? {
        fun one(s: String, max: Int) = s.replace(Regex("""[\r\n\t]+"""), " ").replace(Regex("""\p{Cntrl}"""), "").trim().take(max)
        val q = one(question, MAX_QUESTION)
        val a = one(answer, MAX_ANSWER)
        return if (q.isEmpty() || a.isEmpty()) null else SafeWord(q, a)
    }

    /** A label renamed or merged ([renames]: old → new): its safe word follows; a target's own one wins. */
    fun renamed(words: Map<String, SafeWord>, renames: Map<String, String>): Map<String, SafeWord> {
        val m = renames.mapKeys { LabelRefs.key(it.key) }.mapValues { LabelRefs.key(it.value) }
        val out = LinkedHashMap<String, SafeWord>()
        words.forEach { (t, w) -> if (LabelRefs.key(t) !in m) out[t] = w }
        words.forEach { (t, w) -> m[LabelRefs.key(t)]?.let { to -> out.putIfAbsent(to, w) } }
        return out
    }

    /** Labels deleted: their safe words go with them. */
    fun deleted(words: Map<String, SafeWord>, titles: Set<String>): Map<String, SafeWord> = words.filterKeys { !LabelRefs.refersTo(it, titles) }
}

/** I5's rules: who can be a helper and when "Add my helper" is offered. */
object Helpers {
    const val MAX = 3

    /** [list] with [h] added at the end, unless full or that line is already a helper. */
    fun add(list: List<Helper>, h: Helper, region: String?): List<Helper> =
        if (list.size >= MAX || h.number.none { it.isDigit() } || list.any { PhoneIdentity.same(it.number, h.number, region) }) list else list + h

    /**
     * "Add my helper" is offered on a connected call that isn't an emergency call, while it's the only call (Telecom
     * holds it and places one more; a third would be refused) and no helper is being brought in already.
     */
    fun offered(helpers: Int, connected: Boolean, emergency: Boolean, otherCalls: Int, joining: Boolean): Boolean =
        helpers > 0 && connected && !emergency && otherCalls == 0 && !joining

    /** The helpers to offer during a call with [caller]: never the caller themselves. */
    fun forCall(list: List<Helper>, caller: String?, region: String?): List<Helper> =
        list.filterNot { caller != null && PhoneIdentity.same(it.number, caller, region) }
}

/** Where bringing in a helper is: "Calling Sam to join…", then "Merge now", then joined (or gone). */
enum class HelperStage { CALLING, ANSWERED, JOINED, GONE }

object HelperJoin {
    /**
     * [found]: the helper's own call as the call screen sees it (null while Telecom hasn't reported it or once it
     * ended); [merged]: the helper is in a conference with the call; [seen]: their call was seen at some point.
     */
    fun stage(found: LiveCallState?, merged: Boolean, seen: Boolean): HelperStage = when {
        merged -> HelperStage.JOINED
        found == null -> if (seen) HelperStage.GONE else HelperStage.CALLING
        found == LiveCallState.ACTIVE || found == LiveCallState.HOLDING -> HelperStage.ANSWERED
        else -> HelperStage.CALLING
    }

    /**
     * A helper's call cancelled before Telecom reported it is ended when it shows up within this long (placing a call
     * takes a moment; after that it was never placed).
     */
    const val CANCEL_WAIT_MS = 15_000L

    /**
     * What "Cancel" does at [stage]: end the helper's call now when it is known ([callKnown]); when it hasn't shown up
     * yet ([seen] false) end it as soon as Telecom reports it; otherwise (answered, joined, gone) only forget the card.
     */
    fun onCancel(stage: HelperStage, callKnown: Boolean, seen: Boolean): HelperCancel = when {
        stage == HelperStage.CALLING && callKnown -> HelperCancel.HANG_UP
        stage == HelperStage.CALLING && !seen -> HelperCancel.WHEN_REPORTED
        else -> HelperCancel.FORGET
    }

    /** Whether a call to the helper that showed up [sinceCancel] ms after Cancel is the cancelled one, to end. */
    fun endsCancelled(sinceCancel: Long): Boolean = sinceCancel in 0..CANCEL_WAIT_MS
}

/** What "Cancel" on the helper card does with the helper's call (see [HelperJoin.onCancel]). */
enum class HelperCancel { HANG_UP, WHEN_REPORTED, FORGET }
