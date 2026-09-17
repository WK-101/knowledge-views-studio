package com.cairn.reader.domain.transcript

import com.cairn.reader.audio.AudioDecoder
import com.cairn.reader.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device speech-to-text using Vosk (Kaldi + prebuilt native libs, offline, no NDK). Decodes the
 * media to 16 kHz mono PCM via [AudioDecoder], streams it through a word-timestamped recognizer, and
 * groups the words into readable timed cues. Nothing leaves the device.
 */
@Singleton
class VoskEngine @Inject constructor(
    private val modelManager: SpeechModelManager,
) : SpeechToTextEngine {

    private val nativeReady: Boolean by lazy {
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }.isSuccess
    }

    override fun isSupported(): Boolean = nativeReady
    override suspend fun isModelReady(): Boolean = modelManager.anyInstalled()

    override suspend fun transcribe(
        audioPath: String,
        language: String?,
        onProgress: (Float) -> Unit,
    ): Transcript? = withContext(Dispatchers.IO) {
        if (!nativeReady) { AppLog.w("transcript/vosk: native lib not loaded"); return@withContext null }
        val modelPath = modelManager.firstInstalledPath(language)
        if (modelPath == null) { AppLog.w("transcript/vosk: no model installed"); return@withContext null }
        AppLog.diag("vosk: model=$modelPath source=${audioPath.take(80)}")
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            model = Model(modelPath)
            recognizer = Recognizer(model, SAMPLE_RATE).apply { setWords(true) }
            val words = ArrayList<Word>()
            val produced = AudioDecoder.decodeTo16kMonoPcm(
                source = audioPath,
                onPcm = { bytes, len ->
                    if (recognizer!!.acceptWaveForm(bytes, len)) collectWords(recognizer!!.result, words)
                },
                onProgress = { onProgress(it.coerceIn(0f, 0.99f)) },
            )
            if (!produced) { AppLog.w("transcript/vosk: audio decode produced no PCM for $audioPath"); return@withContext null }
            collectWords(recognizer.finalResult, words)
            onProgress(1f)
            val cues = groupIntoCues(words)
            AppLog.diag("vosk: words=${words.size} cues=${cues.size}")
            if (cues.isEmpty()) null else Transcript(cues, language = language, source = TranscriptSourceKind.ON_DEVICE)
        } catch (t: Throwable) {
            AppLog.e("transcript/vosk: transcription failed", t)
            null
        } finally {
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
        }
    }

    private data class Word(val startMs: Long, val endMs: Long, val text: String)

    /** Pull the word list (with start/end seconds) out of a Vosk result JSON. */
    private fun collectWords(resultJson: String, into: MutableList<Word>) {
        val arr = runCatching { JSONObject(resultJson).optJSONArray("result") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            val text = w.optString("word").trim()
            if (text.isEmpty()) continue
            into.add(Word((w.optDouble("start") * 1000).toLong(), (w.optDouble("end") * 1000).toLong(), text))
        }
    }

    /** Break the flat word stream into caption-like lines on a speech pause, a length cap, or a time
     *  cap — so the transcript reads in sentences rather than one endless paragraph. */
    private fun groupIntoCues(words: List<Word>): List<TranscriptCue> {
        if (words.isEmpty()) return emptyList()
        val cues = ArrayList<TranscriptCue>()
        var start = words.first().startMs
        var lastEnd = words.first().endMs
        val buf = StringBuilder()
        var count = 0
        fun flush() {
            if (buf.isNotEmpty()) cues.add(TranscriptCue(start, lastEnd, buf.toString().trim()))
            buf.setLength(0); count = 0
        }
        for ((idx, w) in words.withIndex()) {
            val gap = w.startMs - lastEnd
            if (count > 0 && (gap > PAUSE_MS || count >= MAX_WORDS || (w.endMs - start) > MAX_SPAN_MS)) {
                flush(); start = w.startMs
            }
            if (count == 0) start = w.startMs
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(w.text)
            lastEnd = w.endMs
            count++
            if (idx == words.lastIndex) flush()
        }
        return cues
    }

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val PAUSE_MS = 700L
        const val MAX_WORDS = 14
        const val MAX_SPAN_MS = 12_000L
    }
}
