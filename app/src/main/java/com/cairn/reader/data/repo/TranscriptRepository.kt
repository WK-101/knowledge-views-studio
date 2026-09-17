package com.cairn.reader.data.repo

import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.TranscriptDao
import com.cairn.reader.data.db.TranscriptEntity
import com.cairn.reader.domain.transcript.CaptionFetcher
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
    private val speechEngine: SpeechToTextEngine,
    private val highlightRepository: HighlightRepository,
) {
    fun observeSaved(itemId: String): Flow<Boolean> = transcriptDao.observeSaved(itemId)

    /** Resolve a transcript: prefer the saved copy, then fetch captions, else report unavailability. */
    suspend fun load(itemId: String): TranscriptResult = withContext(Dispatchers.IO) {
        transcriptDao.get(itemId)?.let { return@withContext TranscriptResult.Ready(deserialize(it), saved = true) }
        val item = itemDao.getItem(itemId) ?: return@withContext TranscriptResult.Failed("Item not found")
        val fetched = when {
            captionFetcher.isYouTube(item.url) -> captionFetcher.fetchYouTube(item.url)
            !item.transcriptUrl.isNullOrBlank() ->
                captionFetcher.fetchFromUrl(item.transcriptUrl!!, kind = TranscriptSourceKind.PODCAST_TRANSCRIPT)
            // Any other video/web media: try to discover a caption <track> or sidecar .vtt/.srt on the
            // page itself before giving up to on-device transcription.
            else -> item.url.takeIf { it.isNotBlank() }?.let { captionFetcher.fetchFromUrl(it) }
        }
        if (fetched != null && !fetched.isEmpty) TranscriptResult.Ready(fetched, saved = false)
        else TranscriptResult.Unavailable(speechEngine.isSupported(), speechEngine.isModelReady())
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
    }

    /** Forget the permanently-saved transcript (annotations already made are untouched). */
    suspend fun forget(itemId: String) = withContext(Dispatchers.IO) { transcriptDao.delete(itemId) }

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
