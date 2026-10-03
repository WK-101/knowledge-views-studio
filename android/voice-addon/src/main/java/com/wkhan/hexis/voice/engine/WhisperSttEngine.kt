package com.wkhan.hexis.voice.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttCapabilities
import com.wkhan.hexis.bridge.voice.SttErrorType
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.voice.capture.VoiceCaptureService

import com.whispercpp.whisper.WhisperContext

import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device speech-to-text with **whisper.cpp** (built from vendored source in the `:whisper` module),
 * in a record-then-transcribe flow — the same engine and shape Scrib uses, which is why it is accurate
 * at a small footprint (GGML-quantized models: ~31 MB tiny.en-q5_1 / ~57 MB base.en-q5_1).
 *
 *  - Tapping the mic starts recording immediately; the whole utterance is buffered.
 *  - The model is loaded on a parallel thread while the user speaks, so "open" is instant.
 *  - Recording runs until the core calls [stop] (manual stop; never cut off on a pause).
 *  - On stop the whole clip is transcribed once, biased toward the user's own vocabulary (the core's
 *    list / tag / context names, passed as Whisper's initial prompt).
 */
class WhisperSttEngine(private val context: Context) : SttEngine {

    private val modelStore = ModelStore(context)

    @Volatile private var whisper: WhisperContext? = null
    @Volatile private var captureThread: Thread? = null
    private val recording = AtomicBoolean(false)
    @Volatile private var cancelled = false

    override fun capabilities(): SttCapabilities = SttCapabilities(
        engineId = "whisper.cpp",
        engineVersion = "v1.7.5",
        supportedLanguages = listOf("en"),
        streaming = false,
        biasing = true,
        modelReady = modelStore.isReady(),
    )

    @Synchronized
    private fun ensureContext(): WhisperContext {
        whisper?.let { return it }
        val model = modelStore.model ?: throw IllegalStateException("no model")
        return WhisperContext.createContextFromFile(model.absolutePath).also { whisper = it }
    }

    /** Whisper initial prompt: the user's own vocabulary so domain words are recognized. "" = none. */
    private fun buildPrompt(request: ListenRequest): String =
        request.hotwords.asSequence()
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .take(MAX_PROMPT_CHARS)

    @SuppressLint("MissingPermission") // RECORD_AUDIO is the addon's own permission, granted via consent.
    override fun startListening(request: ListenRequest, listener: SttListener): String {
        if (!modelStore.isReady()) {
            listener.onError(
                SttErrorType.MODEL_NOT_AVAILABLE,
                "Voice model not installed. Install it from Hexis → Settings → Addon bridges.",
            )
            return ""
        }
        if (recording.get()) cancel("")
        cancelled = false
        val id = "whisper-${System.nanoTime()}"
        // Load the model while the user is already speaking → instant open, transcribe is ready at stop.
        Thread({ runCatching { ensureContext() } }, "whisper-warm").start()
        val prompt = buildPrompt(request)
        recording.set(true)
        startCaptureForeground()
        captureThread = Thread({ recordThenTranscribe(prompt, listener) }, "whisper-capture")
            .also { it.start() }
        return id
    }

    @SuppressLint("MissingPermission")
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun recordThenTranscribe(prompt: String, listener: SttListener) {
        var record: AudioRecord? = null
        val chunks = ArrayList<ShortArray>()
        var total = 0
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferShorts = maxOf(minBuffer / 2, SAMPLE_RATE / CHUNKS_PER_SECOND)
            val mic = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferShorts * 2 * BUFFER_CHUNKS,
            )
            record = mic
            if (mic.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError(SttErrorType.MIC_UNAVAILABLE, "Couldn't open the microphone.")
                return
            }
            try {
                mic.startRecording()
            } catch (se: SecurityException) {
                listener.onError(SttErrorType.PERMISSION_DENIED, "Microphone access was denied.")
                return
            }
            if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                listener.onError(SttErrorType.MIC_UNAVAILABLE, "The microphone didn't start. Try again.")
                return
            }
            val buf = ShortArray(bufferShorts)
            while (recording.get() && total < MAX_SAMPLES) {
                val n = mic.read(buf, 0, buf.size)
                if (n > 0) {
                    chunks.add(buf.copyOf(n))
                    total += n
                }
            }
            if (cancelled) {
                listener.onError(SttErrorType.CANCELLED, null)
                return
            }
            if (total < MIN_SAMPLES) {
                listener.onFinal(SttFinal(text = "", confidence = 0f))
                return
            }
            listener.onFinal(SttFinal(text = transcribe(chunks, total, prompt), confidence = 1f))
        } catch (t: Throwable) {
            Log.w(TAG, "capture/transcribe failed", t)
            listener.onError(SttErrorType.INTERNAL, t.message)
        } finally {
            recording.set(false)
            record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            stopCaptureForeground()
        }
    }

    private fun transcribe(chunks: List<ShortArray>, total: Int, prompt: String): String {
        val samples = FloatArray(total)
        var o = 0
        for (c in chunks) {
            for (s in c) {
                samples[o++] = s / PCM_FULL_SCALE
            }
        }
        return ensureContext().transcribeBlocking(samples, prompt)
    }

    override fun stop(sessionId: String) {
        recording.set(false)
    }

    override fun cancel(sessionId: String) {
        cancelled = true
        recording.set(false)
        captureThread?.let { runCatching { it.join(JOIN_TIMEOUT_MS) } }
    }

    private fun startCaptureForeground() {
        runCatching {
            context.startForegroundService(Intent(context, VoiceCaptureService::class.java))
        }.onFailure { Log.w(TAG, "mic FGS start refused", it) }
    }

    private fun stopCaptureForeground() {
        runCatching { context.stopService(Intent(context, VoiceCaptureService::class.java)) }
    }

    private companion object {
        const val TAG = "WhisperSttEngine"
        const val SAMPLE_RATE = 16000
        const val PCM_FULL_SCALE = 32768f
        const val CHUNKS_PER_SECOND = 10
        const val BUFFER_CHUNKS = 4
        const val JOIN_TIMEOUT_MS = 8000L
        const val MAX_SAMPLES = SAMPLE_RATE * 60 // 60s safety cap on a single utterance
        const val MIN_SAMPLES = SAMPLE_RATE / 4 // ignore < 0.25s (accidental taps)
        const val MAX_PROMPT_CHARS = 600
    }
}
