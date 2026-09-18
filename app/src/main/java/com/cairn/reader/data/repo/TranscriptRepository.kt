package com.cairn.reader.data.repo

import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.TranscriptDao
import com.cairn.reader.data.db.TranscriptEntity
import com.cairn.reader.domain.transcript.CaptionFetcher
import com.cairn.reader.domain.transcript.CaptionParsers
import com.cairn.reader.domain.transcript.SpeechToTextEngine
import com.cairn.reader.domain.transcript.Transcript
import com.cairn.reader.domain.transcript.TranscriptCue
import com.cairn.reader.domain.transcript.TranscriptSourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** How much transcript text to put in the full-text index — generous, since a kept transcript is the
 *  item's main searchable content. */
private const val TRANSCRIPT_FTS_CHARS = 200_000

/** The outcome of resolving a transcript for an item. */
sealed interface TranscriptResult {
    /** A transcript is available; [saved] is true when it's the permanently-kept copy. */
    data class Ready(val transcript: Transcript, val saved: Boolean) : TranscriptResult
    /** No captions could be found; whether on-device speech-to-text could step in is reported so the
     *  UI can offer it honestly (or explain that the speech pack isn't in this build). */
    data class Unavailable(val onDeviceSupported: Boolean, val onDeviceModelReady: Boolean) : TranscriptResult
    data class Failed(val message: String) : TranscriptResult
}

/**
 * Orchestrates transcripts for media items: returns a saved copy when one exists, otherwise fetches
 * captions from the best available source (YouTube for video links, a `<podcast:transcript>` for
 * episodes), and persists/forgets the whole transcript on request. Highlighted cues are saved as
 * timestamped annotations through [HighlightRepository] — independent of whether the whole transcript
 * is kept, so "save only my highlights" and "keep the whole transcript" are both first-class.
 */
@Singleton
class TranscriptRepository @Inject constructor(
    private val transcriptDao: TranscriptDao,
    private val itemDao: ItemDao,
    private val captionFetcher: CaptionFetcher,
    private val youtubeProxy: com.cairn.reader.domain.transcript.YouTubeProxyResolver,
    private val youtubeCaptions: com.cairn.reader.domain.transcript.YouTubeCaptionWebView,
    private val speechEngine: SpeechToTextEngine,
    private val highlightRepository: HighlightRepository,
) {
    fun observeSaved(itemId: String): Flow<Boolean> = transcriptDao.observeSaved(itemId)

    /** Resolve a transcript: prefer the saved copy, then fetch captions, else report unavailability. */
    suspend fun load(itemId: String): TranscriptResult = withContext(Dispatchers.IO) {
        transcriptDao.get(itemId)?.let { return@withContext TranscriptResult.Ready(deserialize(it), saved = true) }
        val item = itemDao.getItem(itemId) ?: return@withContext TranscriptResult.Failed("Item not found")
        val fetched = when {
            captionFetcher.isYouTube(item.url) -> fetchYouTube(item.url)
            !item.transcriptUrl.isNullOrBlank() ->
                captionFetcher.fetchFromUrl(item.transcriptUrl!!, kind = TranscriptSourceKind.PODCAST_TRANSCRIPT)
            // Any other video/web media: try to discover a caption <track> or sidecar .vtt/.srt on the
            // page itself before giving up to on-device transcription.
            else -> item.url.takeIf { it.isNotBlank() }?.let { captionFetcher.fetchFromUrl(it) }
        }
        if (fetched != null && !fetched.isEmpty) TranscriptResult.Ready(fetched, saved = false)
        else TranscriptResult.Unavailable(speechEngine.isSupported(), speechEngine.isModelReady())
    }

    /** YouTube captions, most-reliable source first:
     *   1. the InnerTube VISIONOS Apple client (inside [captionFetcher]) — self-contained, not
     *      subject to YouTube's bot gate, and its caption URLs need no PoToken. This is the path that
     *      works now (it's what NewPipe uses), so it goes first;
     *   2. a public Piped/Invidious instance (server-side extraction) — fallback if the Apple client
     *      is ever closed;
     *   3. an in-session WebView fetch.
     *  We try them in that order and take the first that yields cues. */
    private suspend fun fetchYouTube(url: String): Transcript? {
        captionFetcher.fetchYouTube(url)?.takeIf { !it.isEmpty }?.let { return it }
        val id = captionFetcher.youtubeVideoId(url) ?: return null
        youtubeProxy.captions(id, "en")?.takeIf { !it.isEmpty }?.let { return it }
        val json3 = youtubeCaptions.fetchJson3(id, "en")
        val cues = json3?.let { CaptionParsers.parseJson3(it) }.orEmpty()
        return if (cues.isNotEmpty()) Transcript(cues, source = TranscriptSourceKind.YOUTUBE_CAPTIONS) else null
    }

    /** Persist the whole transcript so it stays available offline without re-fetching. */
    suspend fun saveWhole(itemId: String, transcript: Transcript) = withContext(Dispatchers.IO) {
        transcriptDao.upsert(
            TranscriptEntity(
                itemId = itemId,
                cuesJson = serialize(transcript.cues),
                language = transcript.language,
                source = transcript.source.name,
                cueCount = transcript.cues.size,
                durationMs = transcript.durationMs,
                savedAt = System.currentTimeMillis(),
            ),
        )
        // Make the kept transcript findable in the app's search: index its text into the item's
        // full-text row (media items rarely have article body text, so this is their content).
        itemDao.getItem(itemId)?.let { item ->
            itemDao.indexItem(
                com.cairn.reader.data.db.ItemFtsEntity(
                    itemId = itemId, title = item.title, author = item.author,
                    body = transcript.plainText.take(TRANSCRIPT_FTS_CHARS),
                ),
            )
        }
    }

    /** Forget the permanently-saved transcript (annotations already made are untouched). The item's
     *  FTS body is reset so its transcript text no longer turns up in search. */
    suspend fun forget(itemId: String) = withContext(Dispatchers.IO) {
        transcriptDao.delete(itemId)
        itemDao.getItem(itemId)?.let { item ->
            itemDao.indexItem(
                com.cairn.reader.data.db.ItemFtsEntity(itemId = itemId, title = item.title, author = item.author, body = null),
            )
        }
    }

    /** Whether the on-device engine can run here (native ready) and has a model installed. */
    suspend fun onDeviceStatus(): Pair<Boolean, Boolean> =
        speechEngine.isSupported() to speechEngine.isModelReady()

    /**
     * Transcribe the item's audio entirely on-device (offline ASR), for media with no captions. Finds
     * the audio: a podcast enclosure, a YouTube audio stream, or a direct media URL — decodes and
     * transcribes it locally. Returns the transcript (not yet saved), or null if it can't run.
     */
    suspend fun transcribeOnDevice(itemId: String, onProgress: (Float) -> Unit): Transcript? =
        withContext(Dispatchers.IO) {
            if (!speechEngine.isSupported() || !speechEngine.isModelReady()) {
                com.cairn.reader.util.AppLog.w("transcript/ondevice: engine not ready (supported=${speechEngine.isSupported()}, model=${speechEngine.isModelReady()})")
                return@withContext null
            }
            val item = itemDao.getItem(itemId) ?: return@withContext null
            val audioUrl = when {
                !item.enclosureUrl.isNullOrBlank() -> item.enclosureUrl
                // YouTube audio is ciphered on the WEB client, but the VISIONOS Apple client returns
                // direct, un-ciphered stream URLs (via captionFetcher); the Piped proxy is the fallback.
                captionFetcher.isYouTube(item.url) ->
                    captionFetcher.youtubeAudioUrl(item.url)
                        ?: captionFetcher.youtubeVideoId(item.url)?.let { youtubeProxy.audioUrl(it) }
                else -> item.url.takeIf { it.isNotBlank() }
            }
            if (audioUrl.isNullOrBlank()) {
                com.cairn.reader.util.AppLog.w("transcript/ondevice: no audio URL for item ${item.type} url=${item.url}")
                return@withContext null
            }
            com.cairn.reader.util.AppLog.diag("ondevice: transcribing audio=${audioUrl.take(80)}")
            speechEngine.transcribe(audioUrl, null, onProgress)?.takeIf { !it.isEmpty }
        }

    private fun serialize(cues: List<TranscriptCue>): String {
        val arr = JSONArray()
        cues.forEach { arr.put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) }
        return arr.toString()
    }

    private fun deserialize(entity: TranscriptEntity): Transcript {
        val arr = runCatching { JSONArray(entity.cuesJson) }.getOrNull() ?: JSONArray()
        val cues = ArrayList<TranscriptCue>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            cues.add(TranscriptCue(o.optLong("s"), o.optLong("e"), o.optString("t")))
        }
        val kind = runCatching { TranscriptSourceKind.valueOf(entity.source) }.getOrDefault(TranscriptSourceKind.UNKNOWN)
        return Transcript(cues, entity.language, kind)
    }
}
