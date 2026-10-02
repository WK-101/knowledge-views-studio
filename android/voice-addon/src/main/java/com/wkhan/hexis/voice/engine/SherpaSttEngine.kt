package com.wkhan.hexis.voice.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttCapabilities
import com.wkhan.hexis.bridge.voice.SttErrorType
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttMode
import com.wkhan.hexis.bridge.voice.SttPartial
import com.wkhan.hexis.voice.capture.VoiceCaptureService

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real engine: offline streaming speech-to-text with sherpa-onnx (k2-fsa), v1.13.8.
 *
 * All recognition is on-device — the addon holds RECORD_AUDIO, captures the mic in its own process,
 * and streams only text back over the bridge. The model is the one the user imported via SAF (no
 * network), located by [ModelStore]. When no model is present [capabilities] reports `modelReady =
 * false` and [startListening] fails fast with [SttErrorType.MODEL_NOT_AVAILABLE] so the core can
 * prompt the user to install it.
 *
 * Biasing: when the imported bundle includes a bpe.vocab, per-utterance hotwords (the user's own
 * list / tag / context names, sent by the core) are compiled into the stream via
 * [OnlineRecognizer.createStream] with `modified_beam_search`; otherwise it falls back to plain
 * `greedy_search`.
 */
class SherpaSttEngine(private val context: Context) : SttEngine {

    private val modelStore = ModelStore(context)

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var captureThread: Thread? = null
    private val listening = AtomicBoolean(false)

    override fun capabilities(): SttCapabilities = SttCapabilities(
        engineId = "sherpa-onnx",
        engineVersion = ENGINE_VERSION,
        supportedLanguages = listOf("en"),
        streaming = true,
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
            // Left empty so sherpa auto-detects the model type from the ONNX metadata.
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
            endpointConfig = EndpointConfig(),
            enableEndpoint = true,
            decodingMethod = if (useBias) "modified_beam_search" else "greedy_search",
            hotwordsScore = HOTWORDS_SCORE,
        )
        // assetManager = null → load every file from the filesystem, which is what lets runtime
        // per-stream hotwords (createStream(hotwords)) work.
        return OnlineRecognizer(assetManager = null, config = config).also { recognizer = it }
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
        if (listening.get()) cancel("")
        val id = "sherpa-${System.nanoTime()}"
        val rec = runCatching { ensureRecognizer() }.getOrElse {
            listener.onError(SttErrorType.INTERNAL, it.message)
            return ""
        }
        val hotwords = buildHotwords(request)
        val stream = rec.createStream(hotwords)
        listening.set(true)
        startCaptureForeground()
        captureThread = Thread({ captureLoop(rec, stream, request.mode, listener) }, "sherpa-capture")
            .also { it.start() }
        return id
    }

    /** One hotword phrase per line; LibriSpeech BPE is uppercase, so match it. Empty when no biasing. */
    private fun buildHotwords(request: ListenRequest): String =
        if (modelStore.hasBiasing()) {
            request.hotwords.joinToString("\n") { it.text.trim().uppercase() }.trim()
        } else {
            ""
        }

    // The capture + decode loop is one cohesive unit of work (mic read → features → decode → emit);
    // splitting it would only scatter the shared buffers. IPC/engine failures must surface as an
    // onError, never crash the addon, hence the broad catch.
    @SuppressLint("MissingPermission")
    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "NestedBlockDepth")
    private fun captureLoop(
        rec: OnlineRecognizer,
        stream: OnlineStream,
        mode: SttMode,
        listener: SttListener,
    ) {
        var record: AudioRecord? = null
        var lastEmitted = ""
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
            val shorts = ShortArray(bufferShorts)
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
                // The device accepted the config but the mic didn't actually start (access not granted
                // right now, or busy). Report it instead of streaming silence forever.
                listener.onError(SttErrorType.MIC_UNAVAILABLE, "The microphone didn't start. Try again.")
                return
            }
            while (listening.get()) {
                val read = mic.read(shorts, 0, shorts.size)
                if (read <= 0) continue
                val samples = FloatArray(read) { shorts[it] / PCM_FULL_SCALE }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (rec.isReady(stream)) rec.decode(stream)
                val text = rec.getResult(stream).text
                if (text.isNotBlank() && text != lastEmitted) {
                    lastEmitted = text
                    listener.onPartial(SttPartial(cumulativeText = text))
                }
                // In COMMAND mode a trailing-silence endpoint ends the single utterance; DICTATION
                // keeps going until the core calls stop().
                if (mode == SttMode.COMMAND && rec.isEndpoint(stream)) break
            }
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            val finalText = rec.getResult(stream).text.ifBlank { lastEmitted }
            if (listening.get() || mode == SttMode.COMMAND) {
                listener.onFinal(SttFinal(text = finalText, confidence = 1f))
            } else {
                listener.onError(SttErrorType.CANCELLED, null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed", t)
            listener.onError(SttErrorType.INTERNAL, t.message)
        } finally {
            listening.set(false)
            record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { stream.release() }
            stopCaptureForeground()
        }
    }

    override fun stop(sessionId: String) {
        listening.set(false)
    }

    override fun cancel(sessionId: String) {
        listening.set(false)
        captureThread?.let { runCatching { it.join(JOIN_TIMEOUT_MS) } }
    }

    // The mic FGS keeps the process foreground + shows the required microphone notification while
    // AudioRecord is live. Best-effort: if the OS refuses a background start (Android 14), capture
    // still proceeds under the foreground importance the binding core confers.
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
        const val JOIN_TIMEOUT_MS = 2000L
    }
}
