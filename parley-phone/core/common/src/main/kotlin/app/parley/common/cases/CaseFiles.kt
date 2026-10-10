package app.parley.common.cases

import app.parley.common.Codecs
import app.parley.common.PhoneIdentity
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuPress
import kotlinx.serialization.Serializable

/** Whether Parley keeps a case file for an organisation. */
@Serializable
enum class CaseMode {
    /** Kept because the contact looks like an organisation (a bank, a clinic, a council); see `NeverCallsYou`. */
    AUTO,

    /** Kept because you chose "Keep a case file" for the contact. */
    ON,

    /** You stopped it: nothing more is kept, even for an organisation. */
    OFF,
}

/** Where a case stands, set with one tap on its page and printed in its PDF. */
@Serializable
enum class CaseStatus { OPEN, WAITING, RESOLVED }

/**
 * A reference number given by an organisation ("Claim", "CR-48213"). [value] is sealed with this phone's small-records
 * key wherever it is stored; it is plain only inside the encrypted backup, and on screen once you ask to see it.
 * [typed]: kept from the keys typed during a call.
 */
@Serializable
data class CaseReference(val id: String, val label: String = "", val value: String, val at: Long, val typed: Boolean = false)

/** One call with the organisation as the case file kept it: its length, the time spent on hold and the menu keys sent. */
@Serializable
data class CaseCall(
    val at: Long,
    val incoming: Boolean,
    val durationSec: Long = 0,
    val holdSec: Long = 0,
    val connected: Boolean = false,
    /** The phone-menu keys sent ("214"), already without anything secret-looking ([MenuMemory.record]). */
    val menu: String = "",
)

/**
 * One organisation's case file: who ([name], its [numbers]), why it is kept ([mode]), the reference numbers and the
 * calls. [private]: the contact is a private one, so the case hides with private contacts.
 */
@Serializable
data class CaseFile(
    val id: String,
    val name: String,
    val numbers: List<String>,
    val mode: CaseMode = CaseMode.ON,
    val private: Boolean = false,
    val references: List<CaseReference> = emptyList(),
    val calls: List<CaseCall> = emptyList(),
    val created: Long = 0,
    val status: CaseStatus = CaseStatus.OPEN,
    /** When [status] was last set (0: never, the case is open as it started). */
    val statusAt: Long = 0,
) {
    val kept: Boolean get() = mode != CaseMode.OFF
}

/**
 * [autoStart]: whether an organisation's calls start a case file on their own. "Stop keeping case files for all"
 * turns it off (and stops every case); "Keep a case file" on a contact still works.
 */
@Serializable
data class CaseState(val cases: List<CaseFile> = emptyList(), val autoStart: Boolean = true)

/**
 * Case files: for a service organisation (a bank, an insurer, a council, a utility, a clinic) Parley keeps the calls
 * with their hold time and menu keys, and the reference numbers you were given, so you have them before you call and
 * can hand them over for a complaint. Kept by line (a case is found from any of its numbers), offline and sealed.
 * Pure: the store seals, reads and writes; this decides.
 */
object CaseFiles {
    /** At most this many case files kept (the ones with the oldest last call go first). */
    const val MAX_CASES = 100

    /** At most this many stopped case files remembered as stopped; they don't count towards [MAX_CASES]. */
    const val MAX_STOPPED = 500

    /** At most this many calls per case (the oldest go first). */
    const val MAX_CALLS = 300

    /** At most this many reference numbers per case. */
    const val MAX_REFERENCES = 30

    /** A reference number and its label are cut to these lengths. */
    const val MAX_REFERENCE = 40
    const val MAX_LABEL = 40

    /** Typed keys offered as a reference: a run of at least this many digits. */
    const val MIN_TYPED_DIGITS = 4

    /** The case file kept for any of [numbers] (same line), or null. */
    fun find(state: CaseState, numbers: List<String>, region: String?): CaseFile? {
        if (numbers.isEmpty()) return null
        val lines = PhoneIdentity.LineSet(numbers, region)
        return state.cases.firstOrNull { c -> c.numbers.any { it in lines } }
    }

    fun byId(state: CaseState, id: String): CaseFile? = state.cases.firstOrNull { it.id == id }

    /**
     * "Keep a case file" ([mode] ON) for a contact named [name] with [numbers], or turning it back to [CaseMode.AUTO]:
     * the one found by number changes (its name and numbers follow the contact's), else a new one is made with [newId].
     */
    @Suppress("LongParameterList") // Each value is a separate fact of the case; a holder would only rename them.
    fun setMode(state: CaseState, name: String, numbers: List<String>, private: Boolean, mode: CaseMode, now: Long, region: String?, newId: String): CaseState {
        val found = find(state, numbers, region)
        val next = found?.let { withNumbers(it, numbers, region).copy(name = name.ifBlank { it.name }, mode = mode, private = private) }
            ?: CaseFile(newId, name, numbers.distinct(), mode, private, created = now)
        return put(state, next)
    }

    /**
     * The case file of an organisation that has none yet (its card was opened): made with [CaseMode.AUTO] and nothing in it.
     * The found one when there is one.
     */
    @Suppress("LongParameterList") // Each value is a separate fact of the case; a holder would only rename them.
    fun ensure(state: CaseState, name: String, numbers: List<String>, private: Boolean, now: Long, region: String?, newId: String): CaseState =
        if (find(state, numbers, region) != null || !state.autoStart) {
            state
        } else {
            put(state, CaseFile(newId, name, numbers.distinct(), CaseMode.AUTO, private, created = now))
        }

    /**
     * Stops the case file [id]: what it kept (calls, hold times, references) goes, and it stays off so an organisation's
     * calls don't start a new one. Your calls and notes themselves stay where they are.
     */
    fun stop(state: CaseState, id: String): CaseState =
        state.copy(cases = state.cases.map { if (it.id == id) it.copy(mode = CaseMode.OFF, references = emptyList(), calls = emptyList()) else it })

    /**
     * A call with [number] ended: kept in its case file. A number without one starts an [CaseMode.AUTO] case when it is
     * an [organisation]'s ([name] then names the contact); one that was stopped keeps nothing. [private]: the number is
     * a private contact's now, which the case takes on either way.
     */
    @Suppress("LongParameterList") // Each value is a separate fact of the call; a holder would only rename them.
    fun recordCall(
        state: CaseState,
        number: String,
        call: CaseCall,
        organisation: Boolean,
        name: String,
        private: Boolean,
        region: String?,
        newId: String,
    ): CaseState {
        val found = find(state, listOf(number), region)
        val case = when {
            found != null && !found.kept -> return state
            // Whether the contact is a private one now (it may have moved since the case was made).
            found != null -> found.copy(private = private)
            organisation && name.isNotBlank() && state.autoStart -> CaseFile(newId, name, listOf(number), CaseMode.AUTO, private, created = call.at)
            else -> return state
        }
        val calls = (listOf(call) + case.calls.filterNot { it.at == call.at }).sortedByDescending { it.at }.take(MAX_CALLS)
        return put(state, case.copy(calls = calls))
    }

    /** Sets where case [id] stands; a stopped case has no status to set. */
    fun setStatus(state: CaseState, id: String, status: CaseStatus, now: Long): CaseState = state.copy(
        cases = state.cases.map { if (it.id == id && it.kept && it.status != status) it.copy(status = status, statusAt = now) else it },
    )

    /**
     * The Case files list: the kept cases, the newest activity first; resolved ones after the rest, as they need
     * nothing more from you.
     */
    fun listed(state: CaseState): List<CaseFile> =
        state.cases.filter { it.kept }.sortedWith(compareBy<CaseFile> { it.status == CaseStatus.RESOLVED }.thenByDescending(::lastActivity))

    /**
     * "Stop keeping case files for all": every case is stopped as [stop] does, and organisations' calls no longer
     * start one ([CaseState.autoStart]). A contact you then choose "Keep a case file" for still gets one.
     */
    fun stopAll(state: CaseState): CaseState =
        state.cases.filter { it.kept }.fold(state.copy(autoStart = false)) { s, c -> stop(s, c.id) }

    /** Organisations' calls start case files again (after [stopAll]); the stopped ones stay stopped. */
    fun startAgain(state: CaseState): CaseState = state.copy(autoStart = true)

    /** The menu keys of a call to keep ("214"), from its [presses]: nothing when memory is off or keeps nothing for it. */
    fun menuOf(presses: List<MenuPress>, remember: Boolean): String =
        if (!remember) "" else MenuMemory.record(presses, 0)?.steps?.joinToString("") { it.tone.toString() }.orEmpty()

    /** Adds [ref] to case [id] (its label and value cleaned by the caller); unchanged at [MAX_REFERENCES]. */
    fun addReference(state: CaseState, id: String, ref: CaseReference): CaseState = state.copy(
        cases = state.cases.map { c ->
            if (c.id != id || !c.kept || c.references.size >= MAX_REFERENCES) c else c.copy(references = c.references + ref)
        },
    )

    fun removeReference(state: CaseState, id: String, refId: String): CaseState =
        state.copy(cases = state.cases.map { c -> if (c.id == id) c.copy(references = c.references.filterNot { it.id == refId }) else c })

    /** A reference number as typed: spaces collapsed, cut to [MAX_REFERENCE]; null when nothing is left. */
    fun cleanReference(raw: String): String? = clean(raw, MAX_REFERENCE)

    /** A reference's label ("Claim"): as [cleanReference], "" when none. */
    fun cleanLabel(raw: String): String = clean(raw, MAX_LABEL).orEmpty()

    private fun clean(raw: String, max: Int): String? =
        raw.filterNot { it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(max).trimEnd().ifEmpty { null }

    /**
     * What the keys typed in a call ([typed]: "2#41234567#") offer as a reference, or null. Menus ask for PINs and
     * card numbers on exactly the lines that have case files, so the guard prefers offering nothing:
     * - nothing typed from the first secret-looking entry on is offered: a 13–19 digit run that passes the Luhn check
     *   (a card number), a [PIN_DIGITS] run typed right after another long run with only # or * between them (an
     *   account number then its PIN), or keys read out one by one around # and * ([MenuMemory.markedSecretStartOf]);
     * - in a call where any of those was seen, no run of up to [PIN_SHAPED_MAX] digits is offered at all;
     * - of what is left, the first run of at least [MIN_TYPED_DIGITS] digits (menu choices are single keys).
     */
    fun typedReference(typed: String): String? {
        val runs = Regex("[0-9]+").findAll(typed).toList()
        val card = runs.firstOrNull { looksLikeCard(it.value) }?.range?.first
        val pin = runs.zipWithNext().firstOrNull { (a, b) ->
            a.value.length >= MIN_TYPED_DIGITS && b.value.length in PIN_DIGITS &&
                typed.substring(a.range.last + 1, b.range.first).let { gap -> gap.isNotEmpty() && gap.all { it == '#' || it == '*' } }
        }?.second?.range?.first
        val marked = MenuMemory.markedSecretStartOf(typed.toList())
        val secretAt = listOfNotNull(card, pin, marked).minOrNull()
        return runs.asSequence()
            .filter { it.value.length >= MIN_TYPED_DIGITS && (secretAt == null || it.range.last < secretAt) }
            .filter { secretAt == null || it.value.length > PIN_SHAPED_MAX }
            .firstOrNull()?.value?.take(MAX_REFERENCE)
    }

    /** A run that may be a payment card number: 13–19 digits passing the Luhn check. */
    internal fun looksLikeCard(digits: String): Boolean {
        if (digits.length !in CARD_DIGITS || !digits.all { it in '0'..'9' }) return false
        val sum = digits.reversed().mapIndexed { i, c ->
            val d = c - '0'
            if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
        }.sum()
        return sum % 10 == 0
    }

    private val CARD_DIGITS = 13..19
    private val PIN_DIGITS = 4..6
    private const val PIN_SHAPED_MAX = 8

    /**
     * What may show now: nothing while a duress unlock hides notes ([notesHidden]; case files are notes about whom you
     * deal with), and no private contact's case while private contacts are hidden ([privateHidden]): one made for a
     * private contact, or one with a number that is a private contact's now ([hidden]; the contact may have been made
     * private after its case was made).
     */
    fun visible(state: CaseState, notesHidden: Boolean, privateHidden: Boolean, hidden: (String) -> Boolean = { false }): CaseState = when {
        notesHidden -> CaseState(autoStart = state.autoStart)
        privateHidden -> state.copy(cases = state.cases.filterNot { it.private || it.numbers.any(hidden) })
        else -> state
    }

    /** A reference shown hidden: "•••• 4821" (the last four), or "••••" for a short one. */
    fun masked(value: String): String = if (value.length > MASK_SHOWN) "•••• " + value.takeLast(MASK_SHOWN) else "••••"

    /**
     * The backup's copy of [state]: cases of private contacts ([leaveOut]) stay out, and reference numbers are opened
     * ([open]) so the next phone can seal them with its own key; one that can't be opened now is left out.
     */
    fun forBackup(state: CaseState, leaveOut: (CaseFile) -> Boolean, open: (String) -> String?): CaseState = state.copy(
        cases = state.cases.filterNot(leaveOut).map { c ->
            // A stopped case travels as what it is: only "stay off", nothing it once kept.
            if (!c.kept) c.copy(references = emptyList(), calls = emptyList())
            else c.copy(references = c.references.mapNotNull { r -> open(r.value)?.let { r.copy(value = it) } })
        },
    )

    /** A restored backup's references sealed for this phone ([seal]); one that can't be sealed now is left out. */
    fun sealed(state: CaseState, seal: (String) -> String?): CaseState = state.copy(
        cases = state.cases.map { c -> c.copy(references = c.references.mapNotNull { r -> seal(r.value)?.let { r.copy(value = it) } }) },
    )

    /**
     * A restored state merged into this phone's: a case found on both (same line) keeps this phone's mode and name and
     * gains the other's numbers, references (by id) and calls (by time); one only in the backup is added. A case
     * stopped here stays as it is: what "Stop keeping" deleted never comes back from an older backup. Case files stay
     * off for organisations when either side turned them off. Where a case stands is this phone's, unless only the
     * backup's was ever set.
     */
    fun merge(mine: CaseState, restored: CaseState, region: String?): CaseState {
        var out = mine.copy(autoStart = mine.autoStart && restored.autoStart)
        restored.cases.forEach { r ->
            val here = find(out, r.numbers, region)
            out = if (here == null) {
                put(out, if (r.kept) r else r.copy(references = emptyList(), calls = emptyList()))
            } else if (!here.kept) {
                out
            } else {
                val refIds = here.references.map { it.id }.toSet()
                val callTimes = here.calls.map { it.at }.toSet()
                put(
                    out,
                    withNumbers(here, r.numbers, region).copy(
                        status = if (here.statusAt == 0L && r.kept) r.status else here.status,
                        statusAt = if (here.statusAt == 0L && r.kept) r.statusAt else here.statusAt,
                        references = (here.references + r.references.filter { it.id !in refIds }).take(MAX_REFERENCES),
                        calls = (here.calls + r.calls.filter { it.at !in callTimes }).sortedByDescending { it.at }.take(MAX_CALLS),
                    ),
                )
            }
        }
        return out
    }

    /** [case] with those of [numbers] it doesn't have yet (by line). */
    private fun withNumbers(case: CaseFile, numbers: List<String>, region: String?): CaseFile {
        val lines = PhoneIdentity.LineSet(case.numbers, region)
        val added = numbers.filter { it.isNotBlank() && it !in lines }.distinct()
        return if (added.isEmpty()) case else case.copy(numbers = case.numbers + added)
    }

    /**
     * [case] in place of the one with its id (or added). Past [MAX_CASES] kept cases, the ones with the oldest activity
     * go. Stopped ones don't count towards that (they hold nothing but "stay off", and dropping one would let the
     * organisation's next call start a case again); only past [MAX_STOPPED] of them do the oldest stopped ones go.
     */
    private fun put(state: CaseState, case: CaseFile): CaseState {
        val all = listOf(case) + state.cases.filterNot { it.id == case.id }
        val (kept, stopped) = all.partition { it.kept }
        val keep = (newest(kept, MAX_CASES) + newest(stopped, MAX_STOPPED)).toSet()
        return state.copy(cases = all.filter { it in keep })
    }

    private fun newest(cases: List<CaseFile>, max: Int): List<CaseFile> =
        if (cases.size <= max) cases else cases.sortedByDescending { lastActivity(it) }.take(max)

    /** When anything last happened in [c]: made, a call, a reference number, or its status set. */
    fun lastActivity(c: CaseFile): Long =
        maxOf(maxOf(c.created, c.statusAt), c.calls.maxOfOrNull { it.at } ?: 0, c.references.maxOfOrNull { it.at } ?: 0)

    private const val MASK_SHOWN = 4

    private val json = Codecs.stored

    fun encode(state: CaseState): String = json.encodeToString(CaseState.serializer(), state)

    fun decode(stored: String?): CaseState =
        stored?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(CaseState.serializer(), it) }.getOrNull() } ?: CaseState()
}
