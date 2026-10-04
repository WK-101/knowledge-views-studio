package app.parley.common.calls

import app.parley.common.Codecs
import app.parley.common.PhoneIdentity
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/** One key the user pressed during a call: [tone] (0–9, * or #) and when, counted from the moment the call connected. */
data class MenuPress(val tone: Char, val sinceConnectMs: Long)

/** One step of a phone-menu path: wait [waitMs] (after the call connected, or after the previous step), then send [tone]. */
@Serializable
data class MenuStep(val tone: Char, val waitMs: Long)

/** The keys sent in the last call to [number], with their timing, and when that call was ([at], epoch ms). */
@Serializable
data class MenuPath(val steps: List<MenuStep>, val at: Long, val number: String = "")

/** A saved path ("Bank › lost card") that dials [number] and then sends [steps] with pauses. */
@Serializable
data class MenuShortcut(val id: String, val name: String, val number: String, val steps: List<MenuStep>, val created: Long)

/**
 * Everything menu memory keeps: the last path per number (by line key, see [PhoneIdentity.key]), the saved shortcuts,
 * and the numbers whose keys are never remembered ([optOut], line keys too).
 */
@Serializable
data class MenuState(
    val paths: Map<String, MenuPath> = emptyMap(),
    val shortcuts: List<MenuShortcut> = emptyList(),
    val optOut: Set<String> = emptySet(),
)

/**
 * I6 menu memory, an offline "Direct My Call": Parley sends every DTMF tone itself, so it can remember which keys were
 * pressed in a call to a number and when, offer them again next time ("Last time: 2 › 1 › 4", one tap replays them
 * with the same pauses), and save them as a shortcut that dials `number,,2,1,4`.
 *
 * Phone menus also ask for PINs, card and account numbers, passcodes and their "2nd and 5th digit". Parley can't hear
 * the prompt and doesn't trust typing speed (TalkBack users and slow typists pause between every digit), so the guard
 * is conservative ([secretStart]): nothing from the first run of [SECRET_RUN] or more digits on is kept, however
 * slowly it was typed, nor from digits ended by # or * that add up to [SECRET_AROUND_MARKS]; at most [MAX_STEPS] keys
 * are kept. Menu choices are single keys, so real menu paths still fit. Users can turn memory off for everything
 * (Settings) or per number ([setOptOut]). Never for emergency numbers or service codes ([remembers]).
 */
object MenuMemory {
    /** This many digits in a row, at any speed, are never kept (a PIN, a card or account number, a passcode). */
    const val SECRET_RUN = 4

    /** Digits followed by # or * ("2#", "5#", "12*") that add up to this many look like a PIN or a passcode's digits. */
    const val SECRET_AROUND_MARKS = 4

    /** At most this many keys are remembered per number. */
    const val MAX_STEPS = 6

    /** At most this many numbers are remembered (the oldest go first). */
    const val MAX_NUMBERS = 200

    /** At most this many shortcuts. */
    const val MAX_SHORTCUTS = 50

    /** A shortcut's name is cut to this length. */
    const val MAX_NAME = 40

    /** How long Android waits for one `,` in a dial string (GSM and IMS calls). */
    const val PAUSE_MS = 3_000L

    /** At most this many `,` before one key of a shortcut (30 s). */
    const val MAX_PAUSES = 10

    /** Replay never waits less than this between two keys, nor longer than [MAX_REPLAY_GAP_MS]. */
    const val MIN_REPLAY_GAP_MS = 400L
    const val MAX_REPLAY_GAP_MS = 30_000L

    /** How long each replayed tone plays. */
    const val REPLAY_TONE_MS = 180L

    private const val SEPARATOR = " › "

    /** The number part of a dial string: everything before the first pause or wait (`,` or `;`). */
    fun dialled(raw: String): String {
        val cut = raw.indexOfFirst { it == ',' || it == ';' }
        return (if (cut >= 0) raw.substring(0, cut) else raw).trim()
    }

    /** Whether keys sent to [number] may be remembered at all: a real number, not an emergency number or service code. */
    fun remembers(number: String?, emergency: Boolean): Boolean {
        if (emergency || number.isNullOrBlank()) return false
        val n = dialled(number)
        if (n.isEmpty() || PhoneIdentity.isServiceCode(n)) return false
        // Codes typed with * or # (call forwarding, USSD) are settings, not a menu.
        if (n.first() == '*' || n.first() == '#') return false
        if (EmergencyPolicy.isFallbackEmergencyNumber(n)) return false
        return n.count { it.isDigit() } >= 3
    }

    /** The line key menu memory keeps [number] under ("" when it has none). */
    fun key(number: String, region: String?): String = PhoneIdentity.key(dialled(number), region)

    /** The remembered menu of [number]: under [key], or the older form a line was stored under before 5.4. */
    fun pathFor(state: MenuState, number: String, region: String?): MenuPath? {
        val forms = PhoneIdentity.keyForms(dialled(number), region)
        // "Stop remembering" under any form wins over a path kept under another.
        if (forms.any { it in state.optOut }) return null
        return forms.firstNotNullOfOrNull { state.paths[it] }
    }

    /**
     * The path to remember from the keys pressed in one call, or null when there's nothing to keep. Keys before the
     * call connected (negative times) are dropped; everything from the first secret-looking run on is dropped
     * ([secretStart]); at most [MAX_STEPS] keys are kept.
     */
    fun record(presses: List<MenuPress>, at: Long, number: String = ""): MenuPath? {
        val valid = presses.filter { it.sinceConnectMs >= 0 && it.tone in TONES }.sortedBy { it.sinceConnectMs }
        val keep = valid.take(secretStart(valid) ?: valid.size).take(MAX_STEPS)
        if (keep.isEmpty()) return null
        var before = 0L
        val steps = keep.map { p -> MenuStep(p.tone, p.sinceConnectMs - before).also { before = p.sinceConnectMs } }
        return MenuPath(steps, at, dialled(number))
    }

    /** Where the first secret-looking key is in [presses] (sorted by time), or null; see [secretStartOf]. */
    fun secretStart(presses: List<MenuPress>): Int? = secretStartOf(presses.map { it.tone })

    /**
     * Where the first secret-looking key is in [tones], or null: the start of the first run of [SECRET_RUN] or more
     * digits (timing plays no part), or the first digit followed by # or * once such digits add up to
     * [SECRET_AROUND_MARKS] ("2# 5# 7# 9#", "12*34#").
     */
    fun secretStartOf(tones: List<Char>): Int? {
        var run: Int? = null
        var marked: Int? = null
        var firstMarked: Int? = null
        var markedDigits = 0
        var i = 0
        while (i < tones.size) {
            if (!tones[i].isDigit()) {
                i++
                continue
            }
            var end = i + 1
            while (end < tones.size && tones[end].isDigit()) end++
            if (run == null && end - i >= SECRET_RUN) run = i
            if (end < tones.size && tones[end] in MARKS && marked == null) {
                if (firstMarked == null) firstMarked = i
                markedDigits += end - i
                if (markedDigits >= SECRET_AROUND_MARKS) marked = firstMarked
            }
            i = end
        }
        return listOfNotNull(run, marked).minOrNull()
    }

    /**
     * The marks part of [secretStartOf] on its own: where digit groups shorter than [SECRET_RUN], each followed by #
     * or *, first add up to [SECRET_AROUND_MARKS] ("2# 5# 7# 9#", "12*34#"), or null. Case files use it to tell a call
     * where a PIN was read out key by key from one where only a long reference was typed (which the run rule flags too).
     */
    fun markedSecretStartOf(tones: List<Char>): Int? {
        var firstMarked: Int? = null
        var markedDigits = 0
        var i = 0
        while (i < tones.size) {
            if (!tones[i].isDigit()) {
                i++
                continue
            }
            var end = i + 1
            while (end < tones.size && tones[end].isDigit()) end++
            if (end - i < SECRET_RUN && end < tones.size && tones[end] in MARKS) {
                if (firstMarked == null) firstMarked = i
                markedDigits += end - i
                if (markedDigits >= SECRET_AROUND_MARKS) return firstMarked
            }
            i = end
        }
        return null
    }

    /** [path] as the guard keeps it today (paths stored by an older, looser guard are cut on load); null when nothing is left. */
    fun sanitize(path: MenuPath): MenuPath? {
        val cut = secretStartOf(path.steps.map { it.tone }) ?: path.steps.size
        val steps = path.steps.take(cut).take(MAX_STEPS)
        return if (steps.isEmpty()) null else if (steps == path.steps) path else path.copy(steps = steps)
    }

    /** [state] with every remembered path through [sanitize] (shortcuts were saved on purpose and stay as they are). */
    fun sanitize(state: MenuState): MenuState {
        val paths = state.paths.mapNotNull { (k, p) -> sanitize(p)?.let { k to it } }.toMap()
        return if (paths == state.paths) state else state.copy(paths = paths)
    }

    /** Settings › "Remember menu keys" turned off: every remembered path goes (shortcuts and opt-outs stay). */
    fun forgetPaths(state: MenuState): MenuState = if (state.paths.isEmpty()) state else state.copy(paths = emptyMap())

    /**
     * Who a suggested shortcut name starts with: the caller's [name], but only the [number] for a private contact, whose
     * name must not end up in a launcher's shortcut store or on a page that discreet mode or the vault's lock hides.
     */
    fun shortcutWho(name: String?, number: String, private: Boolean): String = name?.takeIf { it.isNotBlank() && !private } ?: number

    /** "2 › 1 › 4". */
    fun label(steps: List<MenuStep>): String = steps.joinToString(SEPARATOR) { it.tone.toString() }

    /**
     * The dial string of a shortcut: the number, then each key after as many `,` as its recorded wait needs (at least
     * one, at most [MAX_PAUSES]), e.g. `0800123456,,2,1,4`. Android sends the keys itself once the call connects.
     */
    fun dialString(number: String, steps: List<MenuStep>): String = buildString {
        append(dialled(number))
        steps.forEach { s ->
            repeat(pauses(s.waitMs)) { append(',') }
            append(s.tone)
        }
    }

    /** How many `,` stand for a wait of [waitMs]. */
    fun pauses(waitMs: Long): Int = (waitMs.toDouble() / PAUSE_MS).roundToLong().toInt().coerceIn(1, MAX_PAUSES)

    /**
     * How long to wait before each key when replaying [steps] in a call that connected [sinceConnectMs] ago: the first
     * key waits for what is left of its recorded wait (the menu may still be talking), every other key as long as last
     * time; each between [MIN_REPLAY_GAP_MS] and [MAX_REPLAY_GAP_MS] (the first may go at once when its time has come).
     */
    fun replayDelays(steps: List<MenuStep>, sinceConnectMs: Long): List<Long> = steps.mapIndexed { i, s ->
        if (i == 0) (s.waitMs - sinceConnectMs).coerceIn(0, MAX_REPLAY_GAP_MS) else s.waitMs.coerceIn(MIN_REPLAY_GAP_MS, MAX_REPLAY_GAP_MS)
    }

    /**
     * L3: whether a replay may send its next key: the call is still the active one, on its own. Merged into a
     * conference the keys would reach everyone in it; with another call active they would go to a held call's menu.
     */
    fun replayGoesOn(active: Boolean, inConference: Boolean, otherActive: Boolean): Boolean = active && !inConference && !otherActive

    // ---------------------------------------------------------------- The state

    /** The path remembered for [key], unless memory is off for it. */
    fun pathFor(state: MenuState, key: String): MenuPath? = if (key.isEmpty() || key in state.optOut) null else state.paths[key]

    /** Keeps [path] as the last one for [key] (unless it's opted out); the oldest numbers go past [MAX_NUMBERS]. */
    fun remember(state: MenuState, key: String, path: MenuPath): MenuState {
        if (key.isEmpty() || key in state.optOut) return state
        val clean = sanitize(path) ?: return state
        val paths = state.paths + (key to clean)
        val kept = if (paths.size <= MAX_NUMBERS) paths else paths.entries.sortedByDescending { it.value.at }.take(MAX_NUMBERS).associate { it.key to it.value }
        return state.copy(paths = kept)
    }

    /** "Don't remember keys for this number" ([on]), which also forgets what was kept; or remember them again. */
    fun setOptOut(state: MenuState, key: String, on: Boolean): MenuState {
        if (key.isEmpty()) return state
        return if (on) state.copy(optOut = state.optOut + key, paths = state.paths - key) else state.copy(optOut = state.optOut - key)
    }

    /** Forgets the last path for [key] (the opt-out stays as it is). */
    fun forget(state: MenuState, key: String): MenuState = state.copy(paths = state.paths - key)

    /** A shortcut's name made safe and short; null when nothing is left. */
    fun cleanName(raw: String): String? = CallSubject.clean(raw)?.let { if (it.length > MAX_NAME) it.take(MAX_NAME).trimEnd() else it }

    /** The name suggested when saving: "Ana › 2 › 1 › 4" (or the number in place of a name). */
    fun suggestedName(who: String, steps: List<MenuStep>): String = (listOf(who) + steps.map { it.tone.toString() }).joinToString(SEPARATOR)

    /** Adds [shortcut] (its name cleaned) unless one with the same number and keys exists; the newest first. */
    fun addShortcut(state: MenuState, shortcut: MenuShortcut, region: String?): MenuState {
        val name = cleanName(shortcut.name) ?: return state
        if (shortcut.steps.isEmpty() || !remembers(shortcut.number, emergency = false)) return state
        val number = dialled(shortcut.number)
        val same = state.shortcuts.firstOrNull { it.steps == shortcut.steps && PhoneIdentity.same(it.number, number, region) }
        val others = state.shortcuts.filter { it.id != shortcut.id && it != same }
        val kept = (listOf(shortcut.copy(name = name, number = number, id = same?.id ?: shortcut.id)) + others).take(MAX_SHORTCUTS)
        return state.copy(shortcuts = kept)
    }

    fun renameShortcut(state: MenuState, id: String, name: String): MenuState {
        val clean = cleanName(name) ?: return state
        return state.copy(shortcuts = state.shortcuts.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun removeShortcut(state: MenuState, id: String): MenuState = state.copy(shortcuts = state.shortcuts.filterNot { it.id == id })

    /** The shortcuts for any of [numbers] (a contact's), in their saved order. */
    fun shortcutsFor(state: MenuState, numbers: List<String>, region: String?): List<MenuShortcut> {
        if (numbers.isEmpty()) return emptyList()
        val lines = PhoneIdentity.LineSet(numbers, region)
        return state.shortcuts.filter { it.number in lines }
    }

    /**
     * The backup's copy of [state]: shortcuts and opt-outs, without anything about the numbers [leaveOut] says (private
     * contacts' numbers). Remembered paths stay on this phone: they are typed keys, the closest thing to a secret here.
     */
    fun forBackup(state: MenuState, leaveOut: (String) -> Boolean): MenuState = without(state, leaveOut).copy(paths = emptyMap())

    /** [state] without anything about the numbers [leaveOut] says (private contacts' numbers stay out of a backup). */
    fun without(state: MenuState, leaveOut: (String) -> Boolean): MenuState = MenuState(
        paths = state.paths.filter { (k, p) -> !leaveOut(p.number.ifEmpty { k }) },
        shortcuts = state.shortcuts.filterNot { leaveOut(it.number) },
        optOut = state.optOut,
    )

    /**
     * A restored state merged into this phone's: the newer path per number, every shortcut (by id), and every opt-out
     * (which wins over a path from either side).
     */
    fun merge(mine: MenuState, restored: MenuState): MenuState {
        val optOut = mine.optOut + restored.optOut
        val paths = (restored.paths.keys + mine.paths.keys).associateWith { k ->
            listOfNotNull(mine.paths[k], restored.paths[k]).maxBy { it.at }
        }.filterKeys { it !in optOut }
        val ids = mine.shortcuts.map { it.id }.toSet()
        val shortcuts = (mine.shortcuts + restored.shortcuts.filter { it.id !in ids }).take(MAX_SHORTCUTS)
        return MenuState(paths, shortcuts, optOut)
    }

    private val json = Codecs.stored

    fun encode(state: MenuState): String = json.encodeToString(MenuState.serializer(), state)

    fun decode(stored: String?): MenuState =
        stored?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(MenuState.serializer(), it) }.getOrNull() } ?: MenuState()

    private val TONES = "0123456789*#".toSet()
    private val MARKS = setOf('#', '*')
}
