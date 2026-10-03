package com.wkhan.hexis.voice.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttCapabilities
import com.wkhan.hexis.bridge.voice.SttErrorType
import com.wkhan.hexis.bridge.voice.SttFinal

import com.wkhan.hexis.voice.capture.VoiceCaptureService

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline speech-to-text with sherpa-onnx (k2-fsa), in a **record-then-transcribe** flow — the same
 * shape Scrib uses, and the reason it is accurate and predictable:
 *
 *  - Tapping the mic starts recording immediately; the whole utterance is buffered in memory.
 *  - The recognizer is warmed on a parallel thread while you speak, so "open" is instant and the model
 *    load never blocks the start of recording.
 *  - Recording continues until the core calls [stop] (manual stop — it never cuts you off on a pause).
 *  - On stop, the entire clip is decoded **once** (full-context beam search, no real-time pressure),
 *    which avoids the dropped words a live-streaming loop suffers when decode can't keep up with the mic.
 *
 * All recognition is on-device; the addon holds RECORD_AUDIO and returns only text over the bridge.
 * The model is whatever the user imported (see [ModelStore]); biasing hotwords (the user's own list /
 * tag / context names) are compiled in when a bpe.vocab is present.
 */
class SherpaSttEngine(private val context: Context) : SttEngine {

    private val modelStore = ModelStore(context)

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var captureThread: Thread? = null
    private val recording = AtomicBoolean(false)
    @Volatile private var cancelled = false

    override fun capabilities(): SttCapabilities = SttCapabilities(
        engineId = "sherpa-onnx",
        engineVersion = ENGINE_VERSION,
        supportedLanguages = listOf("en"),
        streaming = false,
        biasing = modelStore.hasBiasing(),
        modelReady = modelStore.isReady(),
    )

    @Synchronized
    private fun ensureRecognizer(): OnlineRecognizer {
        recognizer?.let { return it }
        val useBias = modelStore.hasBiasing()
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = modelStore.encoder!!.absolutePath,
                decoder = modelStore.decoder!!.absolutePath,
                joiner = modelStore.joiner!!.absolutePath,
            ),
            tokens = modelStore.tokens.absolutePath,
            numThreads = NUM_THREADS,
            modelType = "",
        ).apply {
            if (useBias) {
                modelingUnit = "bpe"
                bpeVocab = modelStore.bpeVocab.absolutePath
            }
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            // Full-utterance decode: we feed the whole clip at once and finalize ourselves, so the
            // recognizer's own endpointing is off (it must never cut the utterance short).
            enableEndpoint = false,
            decodingMethod = if (useBias) "modified_beam_search" else "greedy_search",
            hotwordsScore = HOTWORDS_SCORE,
        )
        return OnlineRecognizer(assetManager = null, config = config).also { recognizer = it }
    }

    /** One hotword phrase per line; LibriSpeech BPE is uppercase, so match it. Empty when no biasing. */
    private fun buildHotwords(request: ListenRequest): String =
        if (modelStore.hasBiasing()) {
            request.hotwords.joinToString("\n") { it.text.trim().uppercase() }.trim()
        } else {
            ""
        }

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
        val id = "sherpa-${System.nanoTime()}"
        // Warm the model while the user is already speaking, so decode at stop is instant and the load
        // never delays the start of recording.
        Thread({ runCatching { ensureRecognizer() } }, "sherpa-warm").start()
        val hotwords = buildHotwords(request)
        recording.set(true)
        startCaptureForeground()
        captureThread = Thread({ recordThenTranscribe(hotwords, listener) }, "sherpa-capture")
            .also { it.start() }
        return id
    }

    // Record the whole utterance, then decode it once. One cohesive unit; any failure surfaces as an
    // onError (never a crash), hence the broad catch.
    @SuppressLint("MissingPermission")
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun recordThenTranscribe(hotwords: String, listener: SttListener) {
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
                // Nothing meaningful captured (an accidental tap) — return empty, not an error.
                listener.onFinal(SttFinal(text = "", confidence = 0f))
                return
            }
            listener.onFinal(SttFinal(text = transcribe(chunks, total, hotwords), confidence = 1f))
        } catch (t: Throwable) {
            Log.w(TAG, "capture/transcribe failed", t)
            listener.onError(SttErrorType.INTERNAL, t.message)
        } finally {
            recording.set(false)
            record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            stopCaptureForeground()
        }
    }

    /** Decode the whole buffered utterance at once (offline-style) — no realtime pressure, no drops. */
    private fun transcribe(chunks: List<ShortArray>, total: Int, hotwords: String): String {
        val samples = FloatArray(total)
        var o = 0
        for (c in chunks) {
            for (s in c) {
                samples[o++] = s / PCM_FULL_SCALE
            }
        }
        val rec = ensureRecognizer()
        val stream = rec.createStream(hotwords)
        stream.acceptWaveform(samples, SAMPLE_RATE)
        stream.inputFinished()
        while (rec.isReady(stream)) rec.decode(stream)
        val text = rec.getResult(stream).text
        runCatching { stream.release() }
        return text.trim()
    }

    /** Manual stop: finalize and transcribe what was recorded. */
    override fun stop(sessionId: String) {
        recording.set(false)
    }

    override fun cancel(sessionId: String) {
        cancelled = true
        recording.set(false)
        captureThread?.let { runCatching { it.join(JOIN_TIMEOUT_MS) } }
    }

    // The mic FGS keeps the process foreground + shows the required microphone notification while
    // AudioRecord is live. Best-effort: if the OS refuses the start, capture still proceeds under the
    // microphone capability the core confers via BIND_INCLUDE_CAPABILITIES while it is in the foreground.
    private fun startCaptureForeground() {
        runCatching {
            context.startForegroundService(Intent(context, VoiceCaptureService::class.java))
        }.onFailure { Log.w(TAG, "mic FGS start refused", it) }
    }

    private fun stopCaptureForeground() {
        runCatching { context.stopService(Intent(context, VoiceCaptureService::class.java)) }
    }

    private companion object {
        const val TAG = "SherpaSttEngine"
        const val ENGINE_VERSION = "1.13.8"
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
        const val NUM_THREADS = 2
        const val HOTWORDS_SCORE = 2.0f
        const val PCM_FULL_SCALE = 32768f
        const val CHUNKS_PER_SECOND = 10
        const val BUFFER_CHUNKS = 4
        const val JOIN_TIMEOUT_MS = 4000L
        const val MAX_SAMPLES = SAMPLE_RATE * 60 // 60s safety cap on a single utterance
        const val MIN_SAMPLES = SAMPLE_RATE / 4 // ignore < 0.25s (accidental taps)
    }
}
