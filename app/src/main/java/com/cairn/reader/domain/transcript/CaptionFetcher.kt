package com.cairn.reader.domain.transcript

import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches existing captions/transcripts for a piece of media over the network, then parses them
 * on-device via [CaptionParsers]. This is the "captions" transcription source — it never sends any
 * audio anywhere; it only downloads a text track a publisher already produced:
 *
 *  • YouTube — reads the watch page, extracts the caption-track URL from `ytInitialPlayerResponse`,
 *    and downloads the timedtext track (no key, no Data API).
 *  • Podcasts / anything — downloads a `<podcast:transcript>` URL or a plain .vtt/.srt/.json.
 *
 * Blocking by design; callers run it on [kotlinx.coroutines.Dispatchers.IO].
 */
@Singleton
class CaptionFetcher @Inject constructor(private val client: OkHttpClient) {

    /** Download and parse a transcript from a direct URL (a `<podcast:transcript>`, .vtt, .srt, JSON). */
    fun fetchFromUrl(url: String, mime: String? = null, kind: TranscriptSourceKind = TranscriptSourceKind.CAPTION_FILE): Transcript? {
        val body = get(url) ?: return null
        val cues = CaptionParsers.parse(body, mime ?: url)
        return if (cues.isEmpty()) null else Transcript(cues, source = kind)
    }

    /** Fetch YouTube captions for a video URL or bare id. Prefers a manually-authored English track,
     *  then any English, then auto-generated, then the first available. */
    fun fetchYouTube(urlOrId: String, preferLang: String = "en"): Transcript? {
        val id = youtubeVideoId(urlOrId) ?: urlOrId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return null
        val watch = get("https://www.youtube.com/watch?v=$id", youtube = true) ?: return null
        val tracks = extractCaptionTracks(watch)
        if (tracks.isEmpty()) return null
        val chosen = tracks.firstOrNull { it.lang == preferLang && !it.asr }
            ?: tracks.firstOrNull { it.lang == preferLang }
            ?: tracks.firstOrNull { it.lang.startsWith(preferLang) }
            ?: tracks.firstOrNull { !it.asr }
            ?: tracks.first()
        val xml = get(chosen.baseUrl, youtube = true) ?: return null
        val cues = CaptionParsers.parseTimedTextXml(xml)
        return if (cues.isEmpty()) null else Transcript(cues, language = chosen.lang, source = TranscriptSourceKind.YOUTUBE_CAPTIONS)
    }

    /** True if [url] looks like a YouTube video we can fetch captions for. */
    fun isYouTube(url: String?): Boolean = url != null && youtubeVideoId(url) != null

    private data class CaptionTrack(val baseUrl: String, val lang: String, val asr: Boolean)

    /** Pull captionTracks out of the watch page's embedded player response JSON blob. */
    private fun extractCaptionTracks(html: String): List<CaptionTrack> {
        val arr = Regex("\"captionTracks\":(\\[.*?\\])").find(html)?.groupValues?.get(1) ?: return emptyList()
        val out = ArrayList<CaptionTrack>()
        // Each track object carries a baseUrl, a languageCode, and (for auto-captions) kind:"asr".
        Regex("\\{[^{}]*\"baseUrl\"[^{}]*\\}").findAll(arr).forEach { m ->
            val obj = m.value
            val base = Regex("\"baseUrl\":\"(.*?)\"").find(obj)?.groupValues?.get(1)
                ?.replace("\\u0026", "&")?.replace("\\/", "/") ?: return@forEach
            val lang = Regex("\"languageCode\":\"(.*?)\"").find(obj)?.groupValues?.get(1) ?: ""
            val asr = obj.contains("\"kind\":\"asr\"")
            out.add(CaptionTrack(base, lang, asr))
        }
        return out
    }

    /** Extract an 11-char YouTube video id from the usual URL shapes, or null. */
    fun youtubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/embed/([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/shorts/([A-Za-z0-9_-]{11})"),
        )
        for (p in patterns) p.find(url)?.let { return it.groupValues[1] }
        return null
    }

    private fun get(url: String, youtube: Boolean = false): String? = try {
        val req = Request.Builder().url(url).apply {
            // A desktop UA + language hint makes YouTube serve the player response we parse.
            if (youtube) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                header("Accept-Language", "en-US,en;q=0.9")
            }
        }.build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }
}
