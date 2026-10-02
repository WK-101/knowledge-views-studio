package app.parley.common.calls

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.Random

/**
 * Sonic caller ID: a short, pleasant tune made from a name, so a ring says who is calling without a look at the
 * screen. Pure and deterministic: the same name and variant give the same notes and the same samples on every phone
 * (only [StrictMath] and a seeded [Random], both specified to the bit), so a regenerated or restored tune never
 * changes.
 *
 * How a tune is made:
 * - **Notes** come from the name's letters (and digits): each letter moves the melody up or down the major pentatonic scale (no
 *   two notes in it clash, so any walk sounds pleasant), by at most two steps so it stays singable.
 * - **The rest** (key, tempo, rhythm, timbre) comes from the name and [compose]'s variant, so "Try another" keeps the
 *   name's shape but changes how it is played.
 * - **Form**: a four-beat phrase, the same phrase again ending on the home note (it sounds finished), then one beat
 *   of quiet so the repeating ring reads as tune · pause · tune. 9 beats at 120–150 bpm: 3.6 to 4.5 s.
 * - **Sound**: simple additive synthesis (a few sine partials per instrument) under an ADSR envelope, mixed and
 *   scaled so the loudest sample is exactly [PEAK] of full scale: never clipped, always as loud as the others.
 */
object CallerTune {
    /** Samples per second of the rendered tune: enough for the highest partial, small enough to stay ~200 KB. */
    const val SAMPLE_RATE = 22_050

    /** The loudest sample, as a share of 16-bit full scale (headroom against clipping in any resampler). */
    const val PEAK = 0.85

    const val MIN_BPM = 120
    const val MAX_BPM = 150

    /** Beats in a phrase (sung twice) and the quiet beat after it. */
    const val PHRASE_BEATS = 4
    const val TOTAL_BEATS = PHRASE_BEATS * 2 + 1

    /** Major pentatonic steps in semitones, over two octaves plus the top home note. */
    private val SCALE = intArrayOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24)

    /** Rhythms of one phrase, in eighth notes (each sums to [PHRASE_BEATS] beats). */
    private val RHYTHMS = arrayOf(
        intArrayOf(2, 2, 2, 2),
        intArrayOf(1, 1, 2, 2, 2),
        intArrayOf(2, 1, 1, 2, 2),
        intArrayOf(1, 1, 1, 1, 2, 2),
        intArrayOf(3, 1, 2, 2),
        intArrayOf(2, 2, 1, 1, 2),
        intArrayOf(1, 1, 2, 1, 1, 2),
    )

    /**
     * Timbres: partials (frequency ratio to amplitude) and an ADSR envelope. Decaying instruments (bell, marimba,
     * music box) never hold, which keeps fast notes apart; the soft flute holds a little.
     */
    enum class Instrument(
        val partials: List<Pair<Double, Double>>,
        val attackS: Double,
        val decayS: Double,
        val sustain: Double,
        val releaseS: Double,
    ) {
        BELL(listOf(1.0 to 1.0, 2.0 to 0.45, 3.0 to 0.2, 4.2 to 0.12), 0.006, 0.55, 0.0, 0.25),
        MARIMBA(listOf(1.0 to 1.0, 4.0 to 0.3, 9.2 to 0.06), 0.004, 0.32, 0.0, 0.12),
        MUSIC_BOX(listOf(1.0 to 1.0, 2.0 to 0.3, 5.0 to 0.1), 0.004, 0.4, 0.0, 0.2),
        FLUTE(listOf(1.0 to 1.0, 2.0 to 0.2, 3.0 to 0.08), 0.03, 0.12, 0.7, 0.12),
    }

    /** One note: when it starts and how long it is held, in beats, and its MIDI pitch. */
    data class Note(val startBeat: Double, val beats: Double, val midi: Int)

    data class Tune(val notes: List<Note>, val bpm: Int, val instrument: Instrument, val rootMidi: Int) {
        val lengthMs: Long get() = Math.round(TOTAL_BEATS * 60_000.0 / bpm)
    }

    /**
     * The tune for [name]; [variant] 0 is the first one offered, 1, 2… are "Try another". A name without letters
     * (digits, symbols, empty) still gets a tune from its characters.
     */
    fun compose(name: String, variant: Int = 0): Tune {
        val letters = letters(name)
        val r = Random(seed(name).toLong() * 1_000_003L + variant)
        val root = 60 + r.nextInt(8) // C4…G4: bright enough to cut through a room, never shrill
        val bpm = MIN_BPM + r.nextInt(MAX_BPM - MIN_BPM + 1)
        val instrument = Instrument.entries[r.nextInt(Instrument.entries.size)]
        val rhythm = RHYTHMS[r.nextInt(RHYTHMS.size)]
        // The walk: start in the middle of the range, each letter a step of -2…+2, bounced off the edges.
        var at = 3 + r.nextInt(3)
        val steps = IntArray(rhythm.size) { i ->
            if (i > 0) {
                val c = letters[(i - 1) % letters.length].code
                at = bounce(at + Math.floorMod(c + variant, 5) - 2)
            }
            at
        }
        val notes = ArrayList<Note>(rhythm.size * 2)
        var beat = 0.0
        for (phrase in 0..1) {
            rhythm.forEachIndexed { i, eighths ->
                val last = phrase == 1 && i == rhythm.lastIndex
                // The second phrase ends on the home note nearest the melody, so the tune sounds finished.
                val step = if (last) nearestHome(steps[i]) else steps[i]
                notes += Note(beat, eighths / 2.0, root + SCALE[step])
                beat += eighths / 2.0
            }
        }
        return Tune(notes, bpm, instrument, root)
    }

    /** 16-bit mono samples of [tune], exactly [Tune.lengthMs] long, peaking at [PEAK], silent at both ends. */
    fun render(tune: Tune, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val total = (tune.lengthMs * sampleRate / 1000).toInt()
        val mix = DoubleArray(total)
        val beatS = 60.0 / tune.bpm
        for (n in tune.notes) addNote(mix, n, beatS, tune.instrument, sampleRate)
        // 10 ms fades at both ends: no click when the ring starts or loops.
        val fade = sampleRate / 100
        for (i in 0 until minOf(fade, total)) {
            val g = i.toDouble() / fade
            mix[i] *= g
            mix[total - 1 - i] *= g
        }
        val peak = mix.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
        val scale = if (peak > 0.0) PEAK * Short.MAX_VALUE / peak else 0.0
        val max = Short.MAX_VALUE.toInt()
        return ShortArray(total) { i -> Math.round(mix[i] * scale).toInt().coerceIn(-max, max).toShort() }
    }

    /** Adds note [n] to [mix]: its partials under the instrument's envelope, cut at the end of the buffer. */
    private fun addNote(mix: DoubleArray, n: Note, beatS: Double, inst: Instrument, sampleRate: Int) {
        val start = (n.startBeat * beatS * sampleRate).toInt()
        val holdS = n.beats * beatS
        val f = 440.0 * StrictMath.pow(2.0, (n.midi - 69) / 12.0)
        // Partials above the Nyquist frequency would fold back as noise; they're left out.
        val partials = inst.partials.filter { f * it.first < sampleRate / 2.0 }
        val weight = inst.partials.sumOf { it.second }
        val len = minOf(((holdS + inst.releaseS) * sampleRate).toInt(), mix.size - start)
        for (k in 0 until len) {
            val t = k.toDouble() / sampleRate
            val env = envelope(inst, t, holdS)
            if (env > 0.0) mix[start + k] += env * partialsAt(partials, f, t) / weight
        }
    }

    /** The partials' sum at [t] seconds; higher partials fade faster, as on real struck instruments. */
    private fun partialsAt(partials: List<Pair<Double, Double>>, f: Double, t: Double): Double =
        partials.sumOf { (ratio, amp) -> amp * StrictMath.sin(2.0 * Math.PI * f * ratio * t) * StrictMath.exp(-t * (ratio - 1.0) * 1.5) }

    /** A plain PCM WAV file (RIFF, 16-bit mono) of [samples]: every ringer and player on Android reads it. */
    fun wav(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArray(44 + dataBytes)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun le32(at: Int, v: Int) { for (i in 0..3) out[at + i] = (v ushr (8 * i)).toByte() }
        fun le16(at: Int, v: Int) { for (i in 0..1) out[at + i] = (v ushr (8 * i)).toByte() }
        ascii(0, "RIFF"); le32(4, 36 + dataBytes); ascii(8, "WAVE")
        ascii(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, 1)
        le32(24, sampleRate); le32(28, sampleRate * 2); le16(32, 2); le16(34, 16)
        ascii(36, "data"); le32(40, dataBytes)
        samples.forEachIndexed { i, s -> le16(44 + i * 2, s.toInt()) }
        return out
    }

    /**
     * The file a tune is kept in. Made from a hash, never the name itself, so the file name (which other apps may
     * show as the ringtone's title) doesn't say who it is for. L8: 64 bits of SHA-256 over the name's letters and the
     * variant, so two people's tunes never share a file (a 32-bit hash could, and `save` would hand out the other's).
     */
    fun fileName(name: String, variant: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((letters(name) + "\u0000" + variant).toByteArray(Charsets.UTF_8))
        return "parley-tune-" + digest.take(FILE_HASH_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) } + ".wav"
    }

    /** Whether [fileName] is a tune file's name (this form, or the older 32-bit one), for clearing out unused ones. */
    fun isTuneFile(fileName: String): Boolean = TUNE_FILE.matches(fileName)

    /**
     * The tune files among [files] that no ringtone uses any more: [inUse] are the ringtones set anywhere (content URIs
     * or paths; a tune is in use when one ends with its file name).
     */
    fun unused(files: List<String>, inUse: Collection<String>): List<String> {
        val used = inUse.mapNotNullTo(HashSet()) { u -> u.substringAfterLast('/').takeIf(::isTuneFile) }
        return files.filter { isTuneFile(it) && it !in used }
    }

    private const val FILE_HASH_BYTES = 8
    private val TUNE_FILE = Regex("parley-tune-([0-9a-f]{8}|[0-9a-f]{16})\\.wav")

    /** The name's letters and digits ("Flat 2" isn't "Flat 3"), accents dropped, lower case; else its other characters. */
    internal fun letters(name: String): String {
        val plain = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        return plain.ifEmpty { name.trim().ifEmpty { "?" } }
    }

    private fun seed(name: String): Int = letters(name).hashCode()

    private fun bounce(i: Int): Int = when {
        i < 0 -> -i
        i > SCALE.lastIndex -> 2 * SCALE.lastIndex - i
        else -> i
    }

    /** The home note (0, 12 or 24 semitones up) closest to scale index [i]. */
    private fun nearestHome(i: Int): Int = intArrayOf(0, 5, 10).minBy { kotlin.math.abs(it - i) }

    /** ADSR at [t] seconds into a note held for [holdS] seconds; 0 once released. */
    internal fun envelope(inst: Instrument, t: Double, holdS: Double): Double {
        val level = when {
            t < inst.attackS -> t / inst.attackS
            inst.sustain == 0.0 -> StrictMath.exp(-(t - inst.attackS) / inst.decayS * 3.0)
            t < inst.attackS + inst.decayS -> 1.0 - (1.0 - inst.sustain) * (t - inst.attackS) / inst.decayS
            else -> inst.sustain
        }
        if (t <= holdS) return level
        // Released: fade from the level at release time to silence over releaseS.
        val atRelease = envelope(inst, holdS, holdS)
        val r = 1.0 - (t - holdS) / inst.releaseS
        return if (r <= 0.0) 0.0 else atRelease * r
    }
}
