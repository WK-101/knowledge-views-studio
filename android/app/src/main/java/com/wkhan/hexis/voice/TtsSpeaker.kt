package com.wkhan.hexis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * A tiny wrapper over the platform Text-To-Speech engine for spoken output (daily briefing, spoken
 * answers). Platform TTS needs no permission and runs on-device. Initialization is async, so a speak()
 * that arrives before the engine is ready is queued and flushed on init.
 */
class TtsSpeaker(context: Context) {

    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            runCatching { tts.language = Locale.US }
            ready = true
            pending?.let { pending = null; speak(it) }
        }
    }

    fun speak(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (!ready) { pending = t; return }
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "hexis-tts")
    }

    fun stop() {
        pending = null
        runCatching { tts.stop() }
    }

    fun shutdown() {
        pending = null
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
