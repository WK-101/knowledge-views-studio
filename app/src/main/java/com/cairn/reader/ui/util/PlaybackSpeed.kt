package com.cairn.reader.ui.util

/**
 * Shared read-aloud (TTS) playback-speed steps and helpers, used by both the reader's mini-player
 * and the standalone listen bar so the two stay in lock-step.
 */
val ListenSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** The next speed in [ListenSpeeds] after [current], wrapping around; 1.0 if [current] is unknown. */
fun nextSpeed(current: Float): Float {
    val i = ListenSpeeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return if (i == -1) 1.0f else ListenSpeeds[(i + 1) % ListenSpeeds.size]
}

/** A compact label for a playback speed, e.g. "1×" or "1.25×". */
fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"
