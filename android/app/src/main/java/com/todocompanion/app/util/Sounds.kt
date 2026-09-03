package com.todocompanion.app.util

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri

/**
 * R81 — in-app audio cues for the focus timer / stopwatch (start & completion). A "sound spec" is a
 * single string: a built-in preset id (played with [ToneGenerator] — no bundled assets, no extra
 * permission), "none"/"default", or a content:// URI the user picked from the system ringtone picker
 * (played with [RingtoneManager]). This keeps sound choices fully offline and portable in backups.
 */
object Sounds {
    /** Built-in preset ids offered for in-app cues, in display order. */
    val PRESETS = listOf("none", "beep", "double", "chime", "ascending", "descending")

    fun label(spec: String): String = when {
        spec.isBlank() || spec == "none" -> "None"
        spec == "default" -> "Default"
        spec == "silent" -> "Silent"
        spec == "beep" -> "Beep"
        spec == "double" -> "Double beep"
        spec == "chime" -> "Chime"
        spec == "ascending" -> "Rising"
        spec == "descending" -> "Falling"
        isUri(spec) -> "Custom sound"
        else -> "Sound"
    }

    fun isUri(spec: String): Boolean =
        spec.startsWith("content://") || spec.startsWith("android.resource") || spec.startsWith("file://")

    /** Play a sound spec as a one-shot in-app cue. No-op for blank / "none" / "silent". */
    fun play(context: Context, spec: String) {
        when {
            spec.isBlank() || spec == "none" || spec == "silent" -> return
            spec == "default" -> playUri(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            isUri(spec) -> playUri(context, runCatching { Uri.parse(spec) }.getOrNull())
            else -> playTone(spec)
        }
    }

    private fun playUri(context: Context, uri: Uri?) {
        if (uri == null) return
        runCatching { RingtoneManager.getRingtone(context.applicationContext, uri)?.play() }
    }

    /** Play a short built-in tone pattern on a throwaway thread, releasing the generator afterwards. */
    private fun playTone(preset: String) {
        val seq: List<Pair<Int, Int>> = when (preset) {
            "beep" -> listOf(ToneGenerator.TONE_PROP_BEEP to 150)
            "double" -> listOf(ToneGenerator.TONE_PROP_BEEP2 to 300)
            "chime" -> listOf(ToneGenerator.TONE_PROP_ACK to 300)
            "ascending" -> listOf(ToneGenerator.TONE_DTMF_1 to 110, ToneGenerator.TONE_DTMF_5 to 110, ToneGenerator.TONE_DTMF_9 to 150)
            "descending" -> listOf(ToneGenerator.TONE_DTMF_9 to 110, ToneGenerator.TONE_DTMF_5 to 110, ToneGenerator.TONE_DTMF_1 to 150)
            else -> listOf(ToneGenerator.TONE_PROP_BEEP to 150)
        }
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            Thread {
                runCatching {
                    for ((tone, ms) in seq) { tg.startTone(tone, ms); Thread.sleep((ms + 40).toLong()) }
                    Thread.sleep(120)
                }
                runCatching { tg.release() }
            }.start()
        }
    }
}
