package com.wkhan.hexis.voice.engine

import kotlin.math.sqrt

/**
 * A lightweight, dependency-free energy (RMS) voice-activity detector. It finds the span of a recorded
 * clip that actually contains speech so the engine can:
 *  - trim leading/trailing silence before transcription (faster, and whisper is less prone to tacking on
 *    hallucinated words for empty audio), and
 *  - skip transcription entirely for an essentially silent clip (an accidental tap, a cancelled thought).
 *
 * This is deliberately simple (no model, no native code): 20 ms RMS frames, a noise floor from the
 * quietest frames, and a relative threshold, with generous padding so word onsets/offsets aren't clipped.
 * It is conservative — when energy is present but diffuse it returns the whole clip rather than risk
 * dropping real (quiet) speech.
 */
object EnergyVad {

    private const val FRAME = 320 // 20 ms at 16 kHz mono
    private const val PAD_FRAMES = 8 // ~160 ms kept on each side of detected speech
    private const val ABS_FLOOR = 0.006f // below this peak the whole clip is treated as silence
    private const val REL_FACTOR = 3.0f // speech must exceed noise floor by this factor
    private const val NOISE_PCTL = 0.1f // noise floor = this percentile of frame energies

    /**
     * The sample range containing speech (with padding), or null if the clip is essentially silent and
     * should not be transcribed at all.
     */
    fun speechRange(samples: FloatArray): IntRange? {
        if (samples.isEmpty()) return null
        val nFrames = samples.size / FRAME
        if (nFrames < 2) return 0..samples.lastIndex

        val energies = FloatArray(nFrames)
        for (f in 0 until nFrames) {
            val base = f * FRAME
            var sum = 0.0
            for (i in 0 until FRAME) {
                val s = samples[base + i]
                sum += s.toDouble() * s
            }
            energies[f] = sqrt(sum / FRAME).toFloat()
        }

        val peak = energies.max()
        if (peak < ABS_FLOOR) return null // essentially silent → don't transcribe

        val sorted = energies.sortedArray()
        val noiseFloor = sorted[(sorted.size * NOISE_PCTL).toInt().coerceIn(0, sorted.lastIndex)]
        val threshold = maxOf(ABS_FLOOR, noiseFloor * REL_FACTOR)

        var first = -1
        var last = -1
        for (f in 0 until nFrames) {
            if (energies[f] >= threshold) {
                if (first < 0) first = f
                last = f
            }
        }
        if (first < 0) return 0..samples.lastIndex // energy present but diffuse → keep the whole clip

        val startF = (first - PAD_FRAMES).coerceAtLeast(0)
        val endF = (last + PAD_FRAMES).coerceAtMost(nFrames - 1)
        val start = startF * FRAME
        val end = ((endF + 1) * FRAME - 1).coerceAtMost(samples.lastIndex)
        return start..end
    }
}
