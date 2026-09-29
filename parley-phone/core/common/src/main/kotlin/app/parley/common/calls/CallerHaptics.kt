package app.parley.common.calls

import java.util.Locale
import java.util.Random

/**
 * Haptic caller ID: a vibration of their own for a person or a label, so the phone in a pocket (or on silent vibrate)
 * says who is calling. Stored as a short spec ([encode]/[decode]): one of the presets, or a generated rhythm with the
 * seed it was made from, so the rhythm never changes when the contact is renamed or converted.
 *
 * Every rhythm fits in one second and is followed by a pause, so a repeating ring reads as "rhythm · pause · rhythm".
 * Timings are Android waveform timings: off, on, off, on… in milliseconds, starting with the delay before the first
 * pulse.
 */
object CallerHaptics {
    enum class Preset {
        /** A rhythm of its own, made from a seed (see [generated]). */
        GENERATED,

        /** Lub-dub. */
        HEARTBEAT,

        /** Two even pulses. */
        DOUBLE,

        /** One long pulse. */
        LONG,

        /** The first letter of the name in Morse code. */
        MORSE,
    }

    /** A chosen pattern: the preset and, for [Preset.GENERATED], the seed of its rhythm. */
    data class Pattern(val preset: Preset, val seed: Int = 0)

    /** One rhythm lasts at most this long… */
    const val RHYTHM_MS = 1000L

    /** …and is followed by this pause before it repeats. */
    const val PAUSE_MS = 900L

    /** Shortest pulse and gap a hand can still feel apart. */
    const val MIN_PULSE_MS = 60L

    /** The Morse unit (a dot); a dash is three. Four symbols of the longest letters still fit in [RHYTHM_MS]. */
    const val MORSE_UNIT_MS = 70L

    private const val SEP = ':'

    fun encode(p: Pattern): String {
        val name = p.preset.name.lowercase(Locale.ROOT)
        return if (p.preset == Preset.GENERATED) "$name$SEP${p.seed}" else name
    }

    /** Null for a blank or unknown spec (then the phone's own vibration is used). */
    fun decode(spec: String?): Pattern? {
        val s = spec?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = s.substringBefore(SEP).uppercase(Locale.ROOT)
        val preset = Preset.entries.firstOrNull { it.name == name } ?: return null
        val seed = if (preset == Preset.GENERATED) s.substringAfter(SEP, "").toIntOrNull() ?: return null else 0
        return Pattern(preset, seed)
    }

    /** A new generated pattern for [key] (a contact's key or a label's title): the same key gives the same rhythm. */
    fun generatedFor(key: String): Pattern = Pattern(Preset.GENERATED, key.trim().lowercase(Locale.ROOT).hashCode())

    /**
     * The waveform of [p] (without the trailing pause; see [repeating]). [name] is who it is for: [Preset.MORSE] uses
     * its first Latin letter (accents dropped), and falls back to a generated rhythm for names without one.
     */
    fun timings(p: Pattern, name: String? = null): LongArray = when (p.preset) {
        Preset.GENERATED -> generated(p.seed)
        Preset.HEARTBEAT -> longArrayOf(0, 110, 130, 190)
        Preset.DOUBLE -> longArrayOf(0, 220, 160, 220)
        Preset.LONG -> longArrayOf(0, 800)
        Preset.MORSE -> morse(name) ?: generated((name ?: "").hashCode())
    }

    /** [timings] followed by the pause, ready to repeat from index 0 for as long as the call rings. */
    fun repeating(p: Pattern, name: String? = null): LongArray = timings(p, name) + longArrayOf(PAUSE_MS)

    /**
     * A rhythm of 2 to 4 pulses made from [seed]: pulse lengths from short taps to long buzzes, gaps long enough to
     * feel apart, the whole thing within [RHYTHM_MS]. Deterministic ([Random] with a seed is specified to give the
     * same numbers on every JVM), so the same seed rings the same on every phone and after a restore.
     */
    fun generated(seed: Int): LongArray {
        val r = Random(seed.toLong())
        val pulses = 2 + r.nextInt(3)
        val lengths = LongArray(pulses) { PULSES[r.nextInt(PULSES.size)] }
        val gaps = LongArray(pulses - 1) { GAPS[r.nextInt(GAPS.size)] }
        // Too long: shorten the longest pulse, then the longest gap, until it fits (never below twice the minimum).
        var shortened = true
        while (lengths.sum() + gaps.sum() > RHYTHM_MS && shortened) {
            val i = lengths.indices.maxBy { lengths[it] }
            val j = gaps.indices.maxBy { gaps[it] }
            shortened = when {
                lengths[i] > MIN_PULSE_MS * 2 -> { lengths[i] -= 40; true }
                gaps[j] > MIN_PULSE_MS * 2 -> { gaps[j] -= 20; true }
                else -> false
            }
        }
        val out = ArrayList<Long>(pulses * 2)
        out += 0L
        lengths.forEachIndexed { i, l ->
            out += l
            if (i < gaps.size) out += gaps[i]
        }
        return out.toLongArray()
    }

    /** The first letter of [name] in Morse code, or null when it doesn't start with a Latin letter. */
    fun morse(name: String?): LongArray? {
        val c = name?.trim()?.firstOrNull { it.isLetterOrDigit() }?.let { stripAccent(it) }?.uppercaseChar() ?: return null
        val code = MORSE[c] ?: return null
        val out = ArrayList<Long>()
        out += 0L
        code.forEachIndexed { i, sym ->
            if (i > 0) out += MORSE_UNIT_MS
            out += if (sym == '-') MORSE_UNIT_MS * 3 else MORSE_UNIT_MS
        }
        return out.toLongArray()
    }

    /** The letter [name] would be tapped with in Morse, for the row's description ("S"), or null. */
    fun morseLetter(name: String?): Char? =
        name?.trim()?.firstOrNull { it.isLetterOrDigit() }?.let { stripAccent(it) }?.uppercaseChar()?.takeIf { it in MORSE }

    /** Total length of one rhythm (pulses and gaps, no pause). */
    fun length(timings: LongArray): Long = timings.sum()

    private fun stripAccent(c: Char): Char =
        java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).firstOrNull() ?: c

    private val PULSES = longArrayOf(70, 120, 180, 260, 380)
    private val GAPS = longArrayOf(90, 140, 200)

    private val MORSE: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....",
        'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.",
        'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        // Letters only: digits have five symbols and wouldn't fit in one second.
        'Y' to "-.--", 'Z' to "--..",
    )

    /**
     * Which pattern rings for a caller: their own first, else the first of their labels (alphabetically, like label
     * ringtones) that has one. Null: the phone's usual vibration.
     */
    fun resolve(own: String?, labels: Set<String>, labelPatterns: Map<String, String>): String? =
        own?.takeIf { decode(it) != null }
            ?: labels.sortedBy { it.lowercase(Locale.ROOT) }.firstNotNullOfOrNull { l -> labelPatterns[l]?.takeIf { decode(it) != null } }
}
